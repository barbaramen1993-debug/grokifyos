/**
 * Companion VRM stage — offline Three.js + @pixiv/three-vrm + OrbitControls.
 * Host bridge: window.GrokifyCompanion.{onReady,onModelLoaded,onError,openVrm,readVrmBase64,closeVrm}
 * Stage API:   window.CompanionStage.{loadModel,setState,setMouth,playMotion,
 *              playGesture,setHands,setLook,resetBody}
 *
 * Avatar is driven like a VRChat body: virtual HMD + left/right hand "controllers"
 * with spring-damper + gravity. AI tools animate those targets (gestures / limbs).
 *
 * Android WebView cannot reliably fetch() file:// VRMs ("Failed to fetch").
 * Bytes are streamed from Kotlin via openVrm/readVrmBase64, then GLTFLoader.parse.
 *
 * Canvas gestures are orbit only (rotate / pan / pinch-zoom). Chat & voice stay on host UI buttons.
 *
 * Vendored libs: window.CompanionVrmLibs = {
 *   THREE, GLTFLoader, OrbitControls, VRMLoaderPlugin, VRMUtils, VRMExpressionPresetName
 * }
 */
(function () {
  "use strict";

  var STATE_IDLE = "idle";
  var STATE_LISTENING = "listening";
  var STATE_THINKING = "thinking";
  var STATE_SPEAKING = "speaking";

  // Chunk size for bridge base64 reads (~256KB raw → ~350KB b64).
  var BRIDGE_CHUNK = 256 * 1024;

  var libs = null;
  var renderer = null;
  var scene = null;
  var camera = null;
  var controls = null;
  var clock = null;
  /** Only place VRM roots live — clear this on every load to prevent stacking. */
  var modelRoot = null;
  var vrm = null;
  var currentState = STATE_IDLE;
  var mouthValue = 0;
  var targetMouth = 0;
  var mouthVelocity = 0;
  var speechPhase = 0;
  var visemeSmooth = { aa: 0, ih: 0, ou: 0, ee: 0, oh: 0 };
  var usingFallback = false;
  var readyNotified = false;
  var animFrame = 0;
  var idleTime = 0;
  var lookTarget = { x: 0, y: 0 };
  var lookSmooth = { x: 0, y: 0 };
  var lookWanderT = 0;
  var lookHoldT = 0;
  var blinkNext = 2.5;
  var blinkT = -1;
  var blinkDouble = false;
  var exprPulse = { happy: 0, relaxed: 0, surprised: 0 };
  var exprPulseT = 0;
  /** Monotonic load id — only the latest load may install into the scene. */
  var loadToken = 0;
  var orbitTarget = null;
  var restBones = null;
  /** User has framed the camera — never auto-reset after this unless double-tap. */
  var userFramed = false;
  /** Active pointers (multi-touch must not count as double-tap). */
  var activePointers = 0;
  var pointerMoved = false;
  var pointerDownX = 0;
  var pointerDownY = 0;
  /** Blended pose layer for life-like idle / state postures. */
  var posePhase = 0;
  var poseVariant = 0;
  var poseVariantT = 0;
  var poseBlend = 0;
  var gestureBurstT = 0;
  var gestureBurstNext = 1.6;
  var gestureBurst = 0;
  /** Sparse agreement-nod pulse (listening only) — not a continuous head bob. */
  var nodPulse = 0;
  var nodNextT = 2.8;
  var statePose = null;
  var statePoseTarget = null;
  /** Pending orbit restore from host prefs (applied after model install). */
  var pendingOrbit = null;
  var orbitSaveTimer = 0;

  /**
   * VRChat-style trackers (avatar-local, hips origin): head + L/R hands.
   * pos in meters-ish; free hands spring to rest under gravity.
   */
  var vr = {
    head: { x: 0, y: 1.45, z: 0.05, locked: false },
    left: {
      x: -0.18,
      y: 0.72,
      z: 0.1,
      vx: 0,
      vy: 0,
      vz: 0,
      locked: false,
      holdUntil: 0,
    },
    right: {
      x: 0.18,
      y: 0.72,
      z: 0.1,
      vx: 0,
      vy: 0,
      vz: 0,
      locked: false,
      holdUntil: 0,
    },
    restLeft: { x: -0.18, y: 0.72, z: 0.1 },
    restRight: { x: 0.18, y: 0.72, z: 0.1 },
    restHead: { x: 0, y: 1.45, z: 0.05 },
    gravity: 4.2,
    spring: 14,
    damp: 7.5,
  };
  /** Active scripted gesture (null when idle VR physics owns hands). */
  var activeGesture = null;
  var floorMesh = null;
  var floorRing = null;

  function hostCall(method) {
    try {
      var bridge = window.GrokifyCompanion;
      if (!bridge || typeof bridge[method] !== "function") return;
      var args = Array.prototype.slice.call(arguments, 1);
      bridge[method].apply(bridge, args);
    } catch (e) {
      // Host may not be ready yet.
    }
  }

  function hostBridge() {
    return window.GrokifyCompanion || null;
  }

  function notifyReady() {
    if (readyNotified) return;
    readyNotified = true;
    hostCall("onReady");
  }

  function notifyError(msg) {
    hostCall("onError", String(msg || "unknown error"));
  }

  function notifyModelLoaded(info) {
    hostCall("onModelLoaded", info || "");
  }

  function getLibs() {
    if (libs) return libs;
    libs = window.CompanionVrmLibs || null;
    return libs;
  }

  function showFallback(show) {
    usingFallback = !!show;
    var el = document.getElementById("fallback");
    if (!el) return;
    if (show) el.classList.add("visible");
    else el.classList.remove("visible");
  }

  function setFallbackState(state) {
    var el = document.getElementById("fallback");
    if (!el) return;
    el.classList.remove(
      "state-idle",
      "state-listening",
      "state-thinking",
      "state-speaking"
    );
    el.classList.add("state-" + state);
  }

  function setFallbackMouth(v) {
    var mouth = document.getElementById("fallback-mouth");
    if (!mouth) return;
    var open = Math.max(0, Math.min(1, Number(v) || 0));
    mouth.style.transform = "scaleY(" + (0.15 + open * 0.85).toFixed(3) + ")";
  }

  function setHud(text) {
    var el = document.getElementById("stage-hud");
    if (!el) return;
    if (!text) {
      el.textContent = "";
      el.classList.remove("visible");
      return;
    }
    el.textContent = text;
    el.classList.add("visible");
  }

  /**
   * Hard-remove every avatar from the scene (tracked + orphaned stacks).
   */
  function destroyVrm() {
    var L = getLibs();
    if (modelRoot) {
      while (modelRoot.children.length > 0) {
        var child = modelRoot.children[0];
        modelRoot.remove(child);
        try {
          if (L && L.VRMUtils && typeof L.VRMUtils.deepDispose === "function") {
            L.VRMUtils.deepDispose(child);
          }
        } catch (_) {}
      }
    }
    if (vrm && vrm.scene && (!modelRoot || vrm.scene.parent !== modelRoot)) {
      try {
        if (scene) scene.remove(vrm.scene);
      } catch (_) {}
      try {
        if (L && L.VRMUtils && typeof L.VRMUtils.deepDispose === "function") {
          L.VRMUtils.deepDispose(vrm.scene);
        }
      } catch (_) {}
    }
    // Sweep any leftover skinned groups accidentally added to the scene root.
    if (scene) {
      var orphans = [];
      scene.children.forEach(function (obj) {
        if (obj === modelRoot) return;
        if (obj.isLight || obj.isCamera || obj.isBone) return;
        var hasSkin = false;
        try {
          obj.traverse(function (n) {
            if (n.isSkinnedMesh) hasSkin = true;
          });
        } catch (_) {}
        if (hasSkin) orphans.push(obj);
      });
      orphans.forEach(function (obj) {
        try {
          scene.remove(obj);
        } catch (_) {}
        try {
          if (L && L.VRMUtils && typeof L.VRMUtils.deepDispose === "function") {
            L.VRMUtils.deepDispose(obj);
          }
        } catch (_) {}
      });
    }
    vrm = null;
    restBones = null;
    statePose = null;
    statePoseTarget = null;
    poseVariant = 0;
    poseVariantT = 0;
    poseBlend = 0;
    activeGesture = null;
  }

  function bone(name) {
    if (!vrm || !vrm.humanoid) return null;
    try {
      if (typeof vrm.humanoid.getNormalizedBoneNode === "function") {
        return vrm.humanoid.getNormalizedBoneNode(name) || null;
      }
    } catch (_) {}
    return null;
  }

  /**
   * Soft hang rest (not T, not Y). Hands slightly forward/out so skirts clear;
   * Z≈1.25–1.3 from T-bind (full hang ~1.45) — earlier 1.0 read as Y-pose.
   * Capture bind first, apply hang *deltas* for VRM0/1 normalized bones.
   */
  function captureRestPose() {
    restBones = {
      hips: bone("hips"),
      spine: bone("spine"),
      chest: bone("chest"),
      upperChest: bone("upperChest"),
      neck: bone("neck"),
      head: bone("head"),
      jaw: bone("jaw"),
      leftUpperArm: bone("leftUpperArm"),
      rightUpperArm: bone("rightUpperArm"),
      leftLowerArm: bone("leftLowerArm"),
      rightLowerArm: bone("rightLowerArm"),
      leftHand: bone("leftHand"),
      rightHand: bone("rightHand"),
      leftUpperLeg: bone("leftUpperLeg"),
      rightUpperLeg: bone("rightUpperLeg"),
      leftLowerLeg: bone("leftLowerLeg"),
      rightLowerLeg: bone("rightLowerLeg"),
    };

    // Snapshot bind (T-pose / author rest) before we rewrite.
    var bind = {};
    Object.keys(restBones).forEach(function (k) {
      var n = restBones[k];
      if (!n || !n.rotation) return;
      bind[k] = { x: n.rotation.x, y: n.rotation.y, z: n.rotation.z };
    });

    function hang(key, dx, dy, dz) {
      var n = restBones[key];
      var b = bind[key];
      if (!n || !b) return;
      setEuler(n, b.x + dx, b.y + dy, b.z + dz);
    }

    // Soft hang (~72° from T): relaxed sides, slight elbow bend, hands forward.
    hang("leftUpperArm", 0.12, 0.08, 1.26);
    hang("rightUpperArm", 0.12, -0.08, -1.26);
    hang("leftLowerArm", 0.32, 0.06, 0.08);
    hang("rightLowerArm", 0.32, -0.06, -0.08);
    hang("leftHand", 0.06, 0.04, 0.08);
    hang("rightHand", 0.06, -0.04, -0.08);
    hang("hips", 0.01, 0.015, 0.008);
    hang("spine", 0.018, -0.01, -0.006);
    hang("chest", 0.012, 0.005, 0);
    // Neutral head — no constant pitch bias (was reading as a permanent nod).
    hang("neck", 0, 0, 0);
    hang("head", 0, 0, 0);
    hang("leftUpperLeg", 0.01, 0, 0.012);
    hang("rightUpperLeg", -0.006, 0, -0.01);
    hang("leftLowerLeg", -0.012, 0, 0);
    hang("rightLowerLeg", -0.008, 0, 0);

    // Stash base rotations for idle overlays / VR mapping.
    restBones.base = {};
    Object.keys(restBones).forEach(function (k) {
      if (k === "base") return;
      var n = restBones[k];
      if (!n || !n.rotation) return;
      restBones.base[k] = {
        x: n.rotation.x,
        y: n.rotation.y,
        z: n.rotation.z,
      };
    });

    // Seed VR controller rest targets from a typical humanoid proportions.
    calibrateVrRestsFromBones();

    statePose = emptyPose();
    statePoseTarget = emptyPose();
    poseVariant = 0;
    poseVariantT = 0;
    poseBlend = 0;
    nodPulse = 0;
    nodNextT = 2.5 + Math.random() * 2;
    activeGesture = null;
    pickStatePoseTarget(currentState || STATE_IDLE);
    placeFloorUnderAvatar();
  }

  /** Estimate head/hand rest targets from current bone world positions (hips-local). */
  function calibrateVrRestsFromBones() {
    var L = getLibs();
    if (!L || !L.THREE || !restBones) return;
    var THREE = L.THREE;
    var hips = restBones.hips;
    if (!hips) return;
    try {
      hips.updateWorldMatrix(true, true);
    } catch (_) {}
    var hipsWorld = new THREE.Vector3();
    try {
      hips.getWorldPosition(hipsWorld);
    } catch (_) {
      return;
    }
    var inv = new THREE.Matrix4();
    try {
      inv.copy(hips.matrixWorld).invert();
    } catch (_) {
      try {
        inv.getInverse(hips.matrixWorld);
      } catch (_2) {
        return;
      }
    }

    function localOf(node, fallback) {
      if (!node) return fallback;
      try {
        node.updateWorldMatrix(true, false);
        var w = new THREE.Vector3();
        node.getWorldPosition(w);
        w.applyMatrix4(inv);
        return { x: w.x, y: w.y, z: w.z };
      } catch (_) {
        return fallback;
      }
    }

    var headL = localOf(restBones.head, vr.restHead);
    var leftL = localOf(restBones.leftHand, vr.restLeft);
    var rightL = localOf(restBones.rightHand, vr.restRight);
    // Nudge hands slightly forward/out from pure bone tip so skirts stay clear.
    // Keep y lower than chest height so rest is hang, not Y-pose.
    leftL.x = Math.min(leftL.x, -0.14);
    rightL.x = Math.max(rightL.x, 0.14);
    leftL.z = Math.max(leftL.z, 0.06);
    rightL.z = Math.max(rightL.z, 0.06);
    leftL.y = Math.min(Math.max(leftL.y, 0.55), 0.92);
    rightL.y = Math.min(Math.max(rightL.y, 0.55), 0.92);

    vr.restHead = headL;
    vr.restLeft = leftL;
    vr.restRight = rightL;
    vr.head.x = headL.x;
    vr.head.y = headL.y;
    vr.head.z = headL.z;
    vr.left.x = leftL.x;
    vr.left.y = leftL.y;
    vr.left.z = leftL.z;
    vr.left.vx = vr.left.vy = vr.left.vz = 0;
    vr.right.x = rightL.x;
    vr.right.y = rightL.y;
    vr.right.z = rightL.z;
    vr.right.vx = vr.right.vy = vr.right.vz = 0;
  }

  function emptyPose() {
    return {
      hips: [0, 0, 0],
      spine: [0, 0, 0],
      chest: [0, 0, 0],
      upperChest: [0, 0, 0],
      neck: [0, 0, 0],
      head: [0, 0, 0],
      leftUpperArm: [0, 0, 0],
      rightUpperArm: [0, 0, 0],
      leftLowerArm: [0, 0, 0],
      rightLowerArm: [0, 0, 0],
      leftHand: [0, 0, 0],
      rightHand: [0, 0, 0],
      leftUpperLeg: [0, 0, 0],
      rightUpperLeg: [0, 0, 0],
      leftLowerLeg: [0, 0, 0],
      rightLowerLeg: [0, 0, 0],
    };
  }

  function setEuler(node, x, y, z) {
    if (!node || !node.rotation) return;
    node.rotation.x = x;
    node.rotation.y = y;
    node.rotation.z = z;
  }

  function addEuler(node, key, dx, dy, dz) {
    if (!node || !node.rotation || !restBones || !restBones.base || !restBones.base[key]) return;
    var b = restBones.base[key];
    var p = statePose && statePose[key] ? statePose[key] : [0, 0, 0];
    node.rotation.x = b.x + dx + p[0];
    node.rotation.y = b.y + dy + p[1];
    node.rotation.z = b.z + dz + p[2];
  }

  /**
   * State posture is mostly body lean — arms are owned by the VR hand targets
   * so tool gestures and physics stay consistent.
   */
  function pickStatePoseTarget(state) {
    var p = emptyPose();
    if (state === STATE_LISTENING) {
      p.spine = [0.02, 0, 0];
      p.chest = [0.015, 0, 0];
      // Tiny lean only — head motion comes from sparse nods / lookAt eyes.
      p.neck = [0.004, 0, 0];
      p.head = [0.002, 0, 0];
    } else if (state === STATE_THINKING) {
      p.hips = [0.008, -0.02, 0.012];
      p.spine = [0.01, 0.025, 0.01];
      p.chest = [0.01, 0.015, 0.006];
      p.neck = [0.01, 0.03, 0.01];
      p.head = [0.006, 0.04, 0.012];
    } else if (state === STATE_SPEAKING) {
      p.hips = [0.008, 0, 0];
      p.spine = [0.014, 0, 0];
      p.chest = [0.02, 0, 0];
      p.neck = [0.002, 0, 0];
      p.head = [0.001, 0, 0];
    } else {
      p.spine = [0.008, 0, 0];
    }
    statePoseTarget = p;

    // Default VR hand intents per state (overridden by tools / gestures).
    if (!activeGesture) {
      if (state === STATE_THINKING) {
        // Light chin-near pose — not a high Y-pose raise.
        setHandTarget("right", 0.1, 1.05, 0.18, 2.0, true);
      } else if (state === STATE_LISTENING) {
        setHandTarget("left", vr.restLeft.x, vr.restLeft.y, vr.restLeft.z + 0.02, 0, false);
        setHandTarget("right", vr.restRight.x, vr.restRight.y, vr.restRight.z + 0.02, 0, false);
      } else if (state === STATE_SPEAKING) {
        // Tiny ready raise; gravity returns to hang rest.
        setHandTarget(
          "right",
          vr.restRight.x + 0.015,
          vr.restRight.y + 0.03,
          vr.restRight.z + 0.03,
          0.6,
          false
        );
      }
    }
  }

  function setHandTarget(side, x, y, z, holdSec, locked) {
    var h = side === "left" ? vr.left : vr.right;
    if (!h) return;
    h.x = x;
    h.y = y;
    h.z = z;
    h.vx = 0;
    h.vy = 0;
    h.vz = 0;
    h.locked = !!locked;
    h.holdUntil = holdSec > 0 ? idleTime + holdSec : 0;
  }

  function setHeadTarget(x, y, z) {
    vr.head.x = x;
    vr.head.y = y;
    vr.head.z = z;
    vr.head.locked = true;
  }

  function blendStatePose(dt) {
    if (!statePose || !statePoseTarget) return;
    var rate = Math.min(1, dt * 2.4);
    Object.keys(statePose).forEach(function (k) {
      var a = statePose[k];
      var b = statePoseTarget[k] || [0, 0, 0];
      a[0] += (b[0] - a[0]) * rate;
      a[1] += (b[1] - a[1]) * rate;
      a[2] += (b[2] - a[2]) * rate;
    });
  }

  function clamp(v, lo, hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  /**
   * Spring + gravity on free VR hands (controller dropped → falls back to rest).
   * Locked hands hold until holdUntil, then unlock and fall under gravity.
   */
  function updateVrPhysics(dt) {
    function stepHand(h, rest) {
      if (h.holdUntil > 0 && idleTime >= h.holdUntil) {
        h.holdUntil = 0;
        h.locked = false;
      }
      if (h.locked) {
        h.vx = h.vy = h.vz = 0;
        return;
      }
      // Spring toward rest + gravity on Y (game-world feel).
      var ax = (rest.x - h.x) * vr.spring - h.vx * vr.damp;
      var ay = (rest.y - h.y) * vr.spring - h.vy * vr.damp - vr.gravity * 0.35;
      var az = (rest.z - h.z) * vr.spring - h.vz * vr.damp;
      h.vx += ax * dt;
      h.vy += ay * dt;
      h.vz += az * dt;
      h.x += h.vx * dt;
      h.y += h.vy * dt;
      h.z += h.vz * dt;
      // Soft bounds (reachable workspace; rest hang ~0.7–0.9, gestures up to chest).
      h.x = clamp(h.x, -0.55, 0.55);
      h.y = clamp(h.y, 0.45, 1.45);
      h.z = clamp(h.z, -0.12, 0.5);
    }
    stepHand(vr.left, vr.restLeft);
    stepHand(vr.right, vr.restRight);
    if (!vr.head.locked) {
      vr.head.x += (vr.restHead.x - vr.head.x) * Math.min(1, dt * 4);
      vr.head.y += (vr.restHead.y - vr.head.y) * Math.min(1, dt * 4);
      vr.head.z += (vr.restHead.z - vr.head.z) * Math.min(1, dt * 4);
    }
  }

  /**
   * Map chest-relative hand targets → arm eulers (fast approximate VR IK).
   * Keeps arms clear of the torso: never pure hang through skirts.
   */
  function applyHandIk(side) {
    var h = side === "left" ? vr.left : vr.right;
    var rest = side === "left" ? vr.restLeft : vr.restRight;
    var sign = side === "left" ? 1 : -1;
    var upperKey = side === "left" ? "leftUpperArm" : "rightUpperArm";
    var lowerKey = side === "left" ? "leftLowerArm" : "rightLowerArm";
    var handKey = side === "left" ? "leftHand" : "rightHand";

    var dx = h.x - rest.x;
    var dy = h.y - rest.y;
    var dz = h.z - rest.z;

    // Raise / reach / open relative to soft hang rest (small deltas only at rest).
    var raise = clamp(dy * 1.8, -0.25, 1.2);
    var reach = clamp(dz * 1.4, -0.2, 0.95);
    var open = clamp(dx * sign * -1.2, -0.4, 0.7);
    // Tiny minimum open only when not raised — avoid Y-pose flare at idle.
    if (open < 0.02 && raise < 0.2) open = 0.02;

    var upperX = -raise * 0.7 - reach * 0.3;
    var upperY = open * 0.45 * sign + reach * 0.06 * sign;
    var upperZ = (raise * 0.1 - open * 0.15) * sign;
    var lowerX = raise * 0.5 + reach * 0.4 + Math.abs(open) * 0.15;
    var lowerY = open * 0.1 * sign;
    var lowerZ = -reach * 0.06 * sign;
    var handX = raise * 0.06 + reach * 0.04;
    var handY = open * 0.08 * sign;
    var handZ = -open * 0.06 * sign;

    // Speaking micro-wiggle on free hands (controllers jitter).
    if (currentState === STATE_SPEAKING && !h.locked && mouthValue > 0.04) {
      var w = mouthValue * 0.04;
      upperX += Math.sin(idleTime * 2.2 + (side === "left" ? 0 : 1)) * w;
      lowerX += Math.abs(Math.sin(idleTime * 3.1)) * w * 0.6;
    }

    addEuler(restBones[upperKey], upperKey, upperX, upperY, upperZ);
    addEuler(restBones[lowerKey], lowerKey, lowerX, lowerY, lowerZ);
    addEuler(restBones[handKey], handKey, handX, handY, handZ);
  }

  /** Advance active tool gesture; returns true if a gesture owns the hands. */
  function updateActiveGesture(dt) {
    if (!activeGesture) return false;
    activeGesture.t += dt;
    var g = activeGesture;
    var u = g.duration > 0 ? clamp(g.t / g.duration, 0, 1) : 1;
    var ease = u < 0.5 ? 2 * u * u : 1 - Math.pow(-2 * u + 2, 2) / 2;
    var inten = typeof g.intensity === "number" ? g.intensity : 1;

    if (g.kind === "wave") {
      var side = g.side === "left" ? "left" : "right";
      var rest = side === "left" ? vr.restLeft : vr.restRight;
      var sx = side === "left" ? -1 : 1;
      var wx = rest.x + sx * 0.05;
      var wy = rest.y + 0.42 * inten;
      var wz = rest.z + 0.18;
      var osc = Math.sin(g.t * 10) * 0.12 * inten;
      setHandTarget(side, wx + osc, wy, wz, 0, true);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "nod") {
      var np = Math.sin(g.t * Math.PI * 2.2) * 0.07 * inten * (1 - u);
      nodPulse = Math.max(nodPulse, Math.abs(np) * 12);
      if (g.t > g.duration) activeGesture = null;
      return false;
    }
    if (g.kind === "shake_head") {
      lookTarget.x = Math.sin(g.t * 9) * 0.55 * inten * (1 - u * 0.5);
      lookTarget.y = 0.02;
      if (g.t > g.duration) activeGesture = null;
      return false;
    }
    if (g.kind === "point") {
      var ps = g.side === "left" ? "left" : "right";
      var pr = ps === "left" ? vr.restLeft : vr.restRight;
      var pSign = ps === "left" ? -1 : 1;
      setHandTarget(
        ps,
        pr.x + pSign * 0.08,
        pr.y + 0.28 * inten,
        pr.z + 0.32 * inten,
        0,
        true
      );
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "shrug") {
      setHandTarget("left", vr.restLeft.x - 0.06, vr.restLeft.y + 0.28 * inten, vr.restLeft.z + 0.06, 0, true);
      setHandTarget("right", vr.restRight.x + 0.06, vr.restRight.y + 0.28 * inten, vr.restRight.z + 0.06, 0, true);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "think") {
      setHandTarget("right", 0.1, 1.2, 0.24, 0, true);
      lookTarget.x = 0.2;
      lookTarget.y = 0.12;
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "clap") {
      var c = Math.abs(Math.sin(g.t * 12));
      setHandTarget("left", -0.08 - c * 0.02, 1.05, 0.28, 0, true);
      setHandTarget("right", 0.08 + c * 0.02, 1.05, 0.28, 0, true);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "cheer") {
      setHandTarget("left", -0.18, 1.45 * (0.7 + 0.3 * ease) * inten, 0.1, 0, true);
      setHandTarget("right", 0.18, 1.45 * (0.7 + 0.3 * ease) * inten, 0.1, 0, true);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "bow") {
      if (statePose) {
        statePose.spine[0] = 0.35 * ease * inten;
        statePose.chest[0] = 0.2 * ease * inten;
        statePose.head[0] = 0.15 * ease * inten;
      }
      if (g.t > g.duration) activeGesture = null;
      return false;
    }
    if (g.kind === "lean_in") {
      if (statePose) {
        statePose.spine[0] = 0.12 * ease * inten;
        statePose.chest[0] = 0.1 * ease * inten;
      }
      if (g.t > g.duration) activeGesture = null;
      return false;
    }
    if (g.kind === "hands_on_hips") {
      setHandTarget("left", -0.22, 0.9, 0.06, 0, true);
      setHandTarget("right", 0.22, 0.9, 0.06, 0, true);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "crossed_arms") {
      setHandTarget("left", 0.08, 1.05, 0.16, 0, true);
      setHandTarget("right", -0.08, 1.0, 0.14, 0, true);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "reset") {
      resetBodyInternal();
      activeGesture = null;
      return false;
    }
    // Unknown — drop.
    activeGesture = null;
    return false;
  }

  function resetBodyInternal() {
    activeGesture = null;
    vr.left.locked = false;
    vr.right.locked = false;
    vr.head.locked = false;
    vr.left.holdUntil = 0;
    vr.right.holdUntil = 0;
    vr.left.x = vr.restLeft.x;
    vr.left.y = vr.restLeft.y;
    vr.left.z = vr.restLeft.z;
    vr.right.x = vr.restRight.x;
    vr.right.y = vr.restRight.y;
    vr.right.z = vr.restRight.z;
    vr.left.vx = vr.left.vy = vr.left.vz = 0;
    vr.right.vx = vr.right.vy = vr.right.vz = 0;
    vr.head.x = vr.restHead.x;
    vr.head.y = vr.restHead.y;
    vr.head.z = vr.restHead.z;
    nodPulse = 0;
  }

  /**
   * VRChat-style body: physics on hand controllers → arm IK, head look,
   * breath/sway on torso. Arms never use the old full-hang sine (skirt clip).
   */
  function applyBodyMotion(dt) {
    if (!vrm || !restBones || !restBones.base) return;
    var t = idleTime;
    posePhase += dt;
    poseVariantT += dt;
    gestureBurstT += dt;
    nodNextT -= dt;

    if (poseVariantT > 4.2 + (poseVariant % 4) * 0.85) {
      poseVariantT = 0;
      poseVariant = (poseVariant + 1) % 4;
    }
    var targetBlend =
      poseVariant === 0 ? 0 : poseVariant === 1 ? 1 : poseVariant === 2 ? 0.35 : 0.7;
    poseBlend += (targetBlend - poseBlend) * Math.min(1, dt * 0.6);

    if (currentState === STATE_SPEAKING) {
      if (gestureBurstT > gestureBurstNext) {
        gestureBurstT = 0;
        gestureBurst = 0.4 + Math.random() * 0.35;
        gestureBurstNext = 1.6 + Math.random() * 2.4;
      }
      gestureBurst += (0 - gestureBurst) * Math.min(1, dt * 1.1);
    } else {
      gestureBurst *= Math.max(0, 1 - dt * 3);
    }

    // Rare soft agreement nod while listening (not a continuous bob).
    if (currentState === STATE_LISTENING && nodNextT <= 0 && nodPulse < 0.02 && !activeGesture) {
      if (Math.random() < 0.35) nodPulse = 0.85;
      nodNextT = 6 + Math.random() * 8;
    }
    if (nodPulse > 0) {
      nodPulse = Math.max(0, nodPulse - dt * 2.0);
    }

    blendStatePose(dt);
    updateActiveGesture(dt);
    updateVrPhysics(dt);

    var breath = Math.sin(t * 1.2) * 0.012 + Math.sin(t * 0.32) * 0.004;
    var sway = Math.sin(t * 0.42) * 0.016 + Math.sin(t * 0.85) * 0.005;
    var weight = (poseBlend - 0.5) * 0.03 + Math.sin(t * 0.28) * 0.008;
    var shoulder = Math.sin(t * 0.55) * 0.012 + Math.sin(t * 1.4) * 0.003;
    // Single clean nod arc — small amplitude (was stacking with lookAt → weird bob).
    var nodAmt = nodPulse > 0 ? Math.sin((1 - nodPulse) * Math.PI) * 0.028 : 0;

    if (currentState === STATE_THINKING) {
      sway = Math.sin(t * 0.35) * 0.01;
      breath = Math.sin(t * 1.0) * 0.01;
    } else if (currentState === STATE_SPEAKING) {
      sway = Math.sin(t * 0.65) * 0.014 + Math.sin(t * 1.15) * 0.004;
      breath = Math.sin(t * 1.5) * 0.014 + mouthValue * 0.006;
    } else if (currentState === STATE_LISTENING) {
      sway = Math.sin(t * 0.38) * 0.012;
    }

    // Arms from VR controllers (IK mapping).
    applyHandIk("left");
    applyHandIk("right");

    addEuler(restBones.hips, "hips", 0.008 + weight * 0.35, sway * 0.3 + weight * 0.55, weight * 0.2);
    addEuler(
      restBones.spine,
      "spine",
      0.01 + breath * 0.55,
      sway * 0.32 - weight * 0.22,
      sway * 0.08 + shoulder * 0.18
    );
    if (restBones.chest) {
      addEuler(restBones.chest, "chest", breath * 0.7, sway * 0.2, shoulder * 0.28);
    }
    if (restBones.upperChest) {
      addEuler(restBones.upperChest, "upperChest", breath * 0.35, sway * 0.1, shoulder * 0.08);
    }

    // Neck/head: yaw only from look + tiny nod pulse. Pitch wander is NOT applied
    // on bones (vrm.lookAt drives eyes) — dual pitch was the weird continuous nod.
    var lookYaw = lookSmooth.x * 0.18;
    var lookRoll = lookSmooth.x * 0.03;
    var headDx = (vr.head.x - vr.restHead.x) * 0.2;
    addEuler(
      restBones.neck,
      "neck",
      nodAmt * 0.4 + breath * 0.04,
      lookYaw * 0.45 + sway * 0.06 + headDx * 0.35,
      lookRoll * 0.3 + shoulder * 0.04
    );
    addEuler(
      restBones.head,
      "head",
      nodAmt * 0.6,
      lookYaw * 0.55 + sway * 0.05 + headDx * 0.45,
      lookRoll * 0.4
    );

    if (restBones.leftUpperLeg) {
      addEuler(restBones.leftUpperLeg, "leftUpperLeg", weight * 0.5, 0, weight * 0.22);
    }
    if (restBones.rightUpperLeg) {
      addEuler(restBones.rightUpperLeg, "rightUpperLeg", -weight * 0.5, 0, -weight * 0.22);
    }
    if (restBones.leftLowerLeg) {
      addEuler(restBones.leftLowerLeg, "leftLowerLeg", -weight * 0.28, 0, 0);
    }
    if (restBones.rightLowerLeg) {
      addEuler(restBones.rightLowerLeg, "rightLowerLeg", weight * 0.28, 0, 0);
    }
  }

  function playGesture(name, opts) {
    opts = opts || {};
    var n = String(name || "")
      .toLowerCase()
      .replace(/[\s-]+/g, "_");
    var intensity =
      typeof opts.intensity === "number" ? clamp(opts.intensity, 0.2, 1.5) : 1;
    var side = String(opts.side || "right").toLowerCase();
    if (side !== "left" && side !== "right" && side !== "both") side = "right";

    if (n === "reset" || n === "reset_body" || n === "idle") {
      resetBodyInternal();
      return true;
    }

    var table = {
      wave: { kind: "wave", duration: 1.8, side: side === "both" ? "right" : side },
      nod: { kind: "nod", duration: 0.9 },
      shake_head: { kind: "shake_head", duration: 1.1 },
      no: { kind: "shake_head", duration: 1.1 },
      point: { kind: "point", duration: 1.6, side: side === "both" ? "right" : side },
      shrug: { kind: "shrug", duration: 1.4 },
      think: { kind: "think", duration: 2.2 },
      clap: { kind: "clap", duration: 1.2 },
      cheer: { kind: "cheer", duration: 1.5 },
      bow: { kind: "bow", duration: 1.6 },
      lean_in: { kind: "lean_in", duration: 1.4 },
      hands_on_hips: { kind: "hands_on_hips", duration: 2.0 },
      crossed_arms: { kind: "crossed_arms", duration: 2.2 },
      celebrate: { kind: "cheer", duration: 1.5 },
      hello: { kind: "wave", duration: 1.8, side: "right" },
      yes: { kind: "nod", duration: 0.9 },
    };
    var spec = table[n];
    if (!spec) {
      try {
        console.warn("[CompanionStage] unknown gesture", n);
      } catch (_) {}
      return false;
    }
    activeGesture = {
      kind: spec.kind,
      t: 0,
      duration: spec.duration,
      intensity: intensity,
      side: spec.side || side,
    };
    return true;
  }

  function setHands(jsonOrObj) {
    var o = jsonOrObj;
    if (typeof o === "string") {
      try {
        o = JSON.parse(o);
      } catch (_) {
        return false;
      }
    }
    if (!o || typeof o !== "object") return false;
    var hold =
      typeof o.hold_sec === "number"
        ? o.hold_sec
        : typeof o.holdSec === "number"
          ? o.holdSec
          : 2.5;
    var locked = o.locked !== false;
    function num(v, fallback) {
      return typeof v === "number" && isFinite(v) ? v : fallback;
    }
    if (o.left && typeof o.left === "object") {
      setHandTarget(
        "left",
        num(o.left.x, vr.restLeft.x),
        num(o.left.y, vr.restLeft.y),
        num(o.left.z, vr.restLeft.z),
        hold,
        locked
      );
    }
    if (o.right && typeof o.right === "object") {
      setHandTarget(
        "right",
        num(o.right.x, vr.restRight.x),
        num(o.right.y, vr.restRight.y),
        num(o.right.z, vr.restRight.z),
        hold,
        locked
      );
    }
    // Single-hand form: { hand, x, y, z }
    if (o.hand === "left" || o.hand === "right") {
      var restH = o.hand === "left" ? vr.restLeft : vr.restRight;
      setHandTarget(
        o.hand,
        num(o.x, restH.x),
        num(o.y, restH.y),
        num(o.z, restH.z),
        hold,
        locked
      );
    }
    activeGesture = null;
    return true;
  }

  function setLook(jsonOrObj) {
    var o = jsonOrObj;
    if (typeof o === "string") {
      try {
        o = JSON.parse(o);
      } catch (_) {
        return false;
      }
    }
    if (!o || typeof o !== "object") return false;
    if (typeof o.x === "number") lookTarget.x = clamp(o.x, -1, 1);
    if (typeof o.y === "number") lookTarget.y = clamp(o.y, -1, 1);
    return true;
  }

  function resetBody() {
    resetBodyInternal();
    return true;
  }

  function ensureFloor(THREE) {
    if (floorMesh) return;
    try {
      var geo = new THREE.CircleGeometry(2.2, 48);
      var mat = new THREE.MeshStandardMaterial({
        color: 0x141a28,
        roughness: 0.92,
        metalness: 0.04,
        transparent: true,
        opacity: 0.88,
      });
      floorMesh = new THREE.Mesh(geo, mat);
      floorMesh.rotation.x = -Math.PI / 2;
      floorMesh.position.y = 0;
      floorMesh.name = "companion-floor";
      scene.add(floorMesh);

      var ringGeo = new THREE.RingGeometry(0.35, 0.42, 48);
      var ringMat = new THREE.MeshBasicMaterial({
        color: 0x6ea8ff,
        transparent: true,
        opacity: 0.35,
        side: THREE.DoubleSide,
      });
      floorRing = new THREE.Mesh(ringGeo, ringMat);
      floorRing.rotation.x = -Math.PI / 2;
      floorRing.position.y = 0.005;
      scene.add(floorRing);
    } catch (_) {}
  }

  function placeFloorUnderAvatar() {
    if (!floorMesh || !vrm || !vrm.scene) return;
    try {
      var L = getLibs();
      if (!L || !L.THREE) return;
      var box = new L.THREE.Box3().setFromObject(vrm.scene);
      if (!isFinite(box.min.y)) return;
      var y = box.min.y;
      floorMesh.position.y = y;
      if (floorRing) floorRing.position.y = y + 0.006;
    } catch (_) {}
  }

  /** Idle gaze wander + state-based look targets (mostly horizontal; tiny pitch). */
  function updateLookWander(dt) {
    lookWanderT -= dt;
    lookHoldT -= dt;
    if (lookWanderT > 0) return;
    lookWanderT = 2.4 + Math.random() * 3.6;
    var baseX = 0;
    var baseY = 0;
    if (currentState === STATE_THINKING) {
      baseX = 0.2 + Math.random() * 0.18;
      baseY = 0.04 + Math.random() * 0.06;
    } else if (currentState === STATE_LISTENING) {
      baseX = (Math.random() - 0.5) * 0.12;
      baseY = Math.random() * 0.04;
    } else if (currentState === STATE_SPEAKING) {
      baseX = (Math.random() - 0.5) * 0.08;
      baseY = Math.random() * 0.03;
    } else {
      // Idle: occasional side glance (eyes via lookAt; keep pitch near zero).
      if (Math.random() < 0.4) {
        baseX = (Math.random() - 0.5) * 0.4;
        baseY = (Math.random() - 0.5) * 0.08;
        lookWanderT = 1.2 + Math.random() * 1.6;
      } else {
        baseX = (Math.random() - 0.5) * 0.08;
        baseY = (Math.random() - 0.5) * 0.03;
      }
    }
    lookTarget.x = baseX;
    lookTarget.y = baseY;
  }

  /** Subtle mood expression drift on top of state baselines. */
  function updateMicroExpressions(dt) {
    exprPulseT -= dt;
    if (exprPulseT <= 0) {
      exprPulseT = 2.5 + Math.random() * 4.5;
      if (currentState === STATE_SPEAKING) {
        exprPulse.happy = 0.15 + Math.random() * 0.25;
        exprPulse.relaxed = 0.05 + Math.random() * 0.1;
        exprPulse.surprised = Math.random() < 0.12 ? 0.08 + Math.random() * 0.1 : 0;
      } else if (currentState === STATE_LISTENING) {
        exprPulse.happy = 0.1 + Math.random() * 0.2;
        exprPulse.relaxed = 0.08 + Math.random() * 0.12;
        exprPulse.surprised = 0;
      } else if (currentState === STATE_THINKING) {
        exprPulse.happy = 0;
        exprPulse.relaxed = 0.1 + Math.random() * 0.15;
        exprPulse.surprised = 0;
      } else {
        exprPulse.happy = Math.random() < 0.35 ? 0.08 + Math.random() * 0.12 : 0;
        exprPulse.relaxed = 0.08 + Math.random() * 0.1;
        exprPulse.surprised = 0;
      }
    }
    // Ease toward pulse targets (state baseline reapplied slowly underneath).
    var rate = Math.min(1, dt * 1.8);
    var baseHappy =
      currentState === STATE_LISTENING
        ? 0.32
        : currentState === STATE_SPEAKING
          ? 0.22
          : 0;
    var baseRelaxed =
      currentState === STATE_THINKING
        ? 0.14
        : currentState === STATE_IDLE
          ? 0.12
          : currentState === STATE_LISTENING
            ? 0.18
            : 0.05;
    setExpression("happy", baseHappy + exprPulse.happy * 0.85);
    setExpression("relaxed", baseRelaxed + exprPulse.relaxed * 0.7);
    if (exprPulse.surprised > 0.02) {
      setExpression("surprised", exprPulse.surprised);
      exprPulse.surprised *= Math.max(0, 1 - dt * 2.2);
    }
  }

  function captureOrbit() {
    if (!camera) return null;
    var t = controls && controls.target
      ? { x: controls.target.x, y: controls.target.y, z: controls.target.z }
      : orbitTarget
        ? { x: orbitTarget.x, y: orbitTarget.y, z: orbitTarget.z }
        : { x: 0, y: 1.2, z: 0 };
    return {
      px: camera.position.x,
      py: camera.position.y,
      pz: camera.position.z,
      tx: t.x,
      ty: t.y,
      tz: t.z,
      userFramed: !!userFramed,
    };
  }

  function applyOrbit(o) {
    if (!o || !camera) return false;
    var px = Number(o.px);
    var py = Number(o.py);
    var pz = Number(o.pz);
    var tx = Number(o.tx);
    var ty = Number(o.ty);
    var tz = Number(o.tz);
    if (
      !isFinite(px) ||
      !isFinite(py) ||
      !isFinite(pz) ||
      !isFinite(tx) ||
      !isFinite(ty) ||
      !isFinite(tz)
    ) {
      return false;
    }
    // Sanity: reject absurd restores (corrupt prefs / wrong model scale).
    var dist = Math.sqrt(
      (px - tx) * (px - tx) + (py - ty) * (py - ty) + (pz - tz) * (pz - tz)
    );
    if (dist < 0.2 || dist > 20) return false;
    camera.position.set(px, py, pz);
    if (controls) {
      controls.target.set(tx, ty, tz);
      controls.update();
      try {
        if (typeof controls.saveState === "function") controls.saveState();
      } catch (_) {}
    } else {
      camera.lookAt(tx, ty, tz);
    }
    orbitTarget = { x: tx, y: ty, z: tz, dist: dist };
    userFramed = o.userFramed !== false;
    return true;
  }

  function parseOrbitJson(raw) {
    if (!raw) return null;
    try {
      var o = typeof raw === "string" ? JSON.parse(raw) : raw;
      if (!o || typeof o !== "object") return null;
      return o;
    } catch (_) {
      return null;
    }
  }

  function loadSavedOrbitFromHost() {
    try {
      var bridge = hostBridge();
      if (!bridge || typeof bridge.getSavedOrbit !== "function") return null;
      return parseOrbitJson(bridge.getSavedOrbit());
    } catch (_) {
      return null;
    }
  }

  function persistOrbitNow() {
    if (!userFramed || !camera) return;
    var o = captureOrbit();
    if (!o) return;
    try {
      hostCall("saveOrbit", JSON.stringify(o));
    } catch (_) {}
  }

  function schedulePersistOrbit() {
    if (!userFramed) return;
    if (orbitSaveTimer) {
      try {
        clearTimeout(orbitSaveTimer);
      } catch (_) {}
    }
    orbitSaveTimer = setTimeout(function () {
      orbitSaveTimer = 0;
      persistOrbitNow();
    }, 280);
  }

  function fitCamera(forceReset) {
    if (!camera || !renderer) return;
    var w = window.innerWidth || 1;
    var h = window.innerHeight || 1;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h, false);

    // Never steal framing after the user orbits — only resize the viewport.
    if (!forceReset && (userFramed || orbitTarget) && controls) {
      controls.update();
      return;
    }
    if (!forceReset && !controls && orbitTarget) {
      return;
    }

    var L = getLibs();
    if (vrm && vrm.scene && L && L.THREE) {
      try {
        var THREE = L.THREE;
        var box = new THREE.Box3().setFromObject(vrm.scene);
        if (isFinite(box.min.x) && isFinite(box.max.y)) {
          var size = new THREE.Vector3();
          var center = new THREE.Vector3();
          box.getSize(size);
          box.getCenter(center);
          if (size.y > 0.01) {
            var lookY = center.y + size.y * 0.18;
            var dist = Math.max(size.y * 0.95, size.x * 1.4, 1.1);
            if (w >= h) dist *= 1.15;
            camera.position.set(center.x, lookY, center.z + dist);
            if (controls) {
              controls.target.set(center.x, lookY, center.z);
              controls.minDistance = Math.max(0.45, dist * 0.35);
              controls.maxDistance = dist * 3.2;
              controls.update();
              try {
                if (typeof controls.saveState === "function") controls.saveState();
              } catch (_) {}
            } else {
              camera.lookAt(center.x, lookY, center.z);
            }
            orbitTarget = { x: center.x, y: lookY, z: center.z, dist: dist };
            userFramed = false;
            return;
          }
        }
      } catch (_) {}
    }

    var ty = w < h ? 1.25 : 1.2;
    var tz = w < h ? 1.55 : 1.85;
    camera.position.set(0, ty + 0.1, tz);
    if (controls) {
      controls.target.set(0, ty, 0);
      controls.minDistance = 0.6;
      controls.maxDistance = 6;
      controls.update();
      try {
        if (typeof controls.saveState === "function") controls.saveState();
      } catch (_) {}
    } else {
      camera.lookAt(0, ty, 0);
    }
    orbitTarget = { x: 0, y: ty, z: 0, dist: tz };
    userFramed = false;
  }

  function setExpression(name, value) {
    if (!vrm || !vrm.expressionManager) return;
    try {
      vrm.expressionManager.setValue(name, Math.max(0, Math.min(1, value)));
    } catch (_) {}
  }

  function clearTalkExpressions() {
    ["aa", "ih", "ou", "ee", "oh", "jawOpen"].forEach(function (n) {
      setExpression(n, 0);
    });
    visemeSmooth.aa = 0;
    visemeSmooth.ih = 0;
    visemeSmooth.ou = 0;
    visemeSmooth.ee = 0;
    visemeSmooth.oh = 0;
    if (restBones && restBones.jaw && restBones.base && restBones.base.jaw) {
      var b = restBones.base.jaw;
      setEuler(restBones.jaw, b.x, b.y, b.z);
    }
  }

  /**
   * Lip-sync from host amplitude envelope.
   * Primary: aa + jawOpen track open tightly. Light secondary only.
   */
  function applyMouth(v) {
    var open = Math.max(0, Math.min(1, Number(v) || 0));
    mouthValue = open;
    if (usingFallback) {
      setFallbackMouth(open);
      return;
    }
    if (!vrm) return;

    if (open < 0.01) {
      clearTalkExpressions();
      return;
    }

    var vel = Math.max(0, Math.min(1, Math.abs(mouthVelocity) * 2.2));
    // Boost mid-range so quiet speech still opens lips visibly.
    var drive = Math.min(1, open * 1.15 + vel * 0.08);
    var wAa = drive * (0.85 + vel * 0.1);
    var wOh = drive * (0.12 + (drive > 0.5 ? 0.1 : 0));
    var wOu = drive < 0.3 ? drive * 0.22 : drive * 0.08;
    var wIh = drive * 0.08 * (0.3 + vel);
    var wEe = drive * 0.05 * vel;

    // Near-instant follow on attack; quick close on release.
    var s = drive > visemeSmooth.aa ? 0.88 : 0.7;
    visemeSmooth.aa += (wAa - visemeSmooth.aa) * s;
    visemeSmooth.ih += (wIh - visemeSmooth.ih) * s;
    visemeSmooth.ou += (wOu - visemeSmooth.ou) * s;
    visemeSmooth.ee += (wEe - visemeSmooth.ee) * s;
    visemeSmooth.oh += (wOh - visemeSmooth.oh) * s;

    setExpression("aa", visemeSmooth.aa);
    setExpression("ih", visemeSmooth.ih);
    setExpression("ou", visemeSmooth.ou);
    setExpression("ee", visemeSmooth.ee);
    setExpression("oh", visemeSmooth.oh);
    setExpression("jawOpen", drive * 0.95 + vel * 0.05);

    if (restBones && restBones.jaw && restBones.base && restBones.base.jaw) {
      var jb = restBones.base.jaw;
      setEuler(
        restBones.jaw,
        jb.x + drive * 0.32 + vel * 0.04,
        jb.y,
        jb.z
      );
    }
  }

  function applyStateExpressions(state) {
    if (!vrm || !vrm.expressionManager) return;
    ["happy", "angry", "sad", "relaxed", "surprised", "neutral"].forEach(function (n) {
      try {
        vrm.expressionManager.setValue(n, 0);
      } catch (_) {}
    });
    exprPulse.happy = 0;
    exprPulse.relaxed = 0;
    exprPulse.surprised = 0;
    exprPulseT = 0.4 + Math.random() * 0.8;
    switch (state) {
      case STATE_LISTENING:
        setExpression("happy", 0.35);
        setExpression("relaxed", 0.2);
        lookTarget.x = 0;
        lookTarget.y = 0.05;
        lookWanderT = 0.6;
        break;
      case STATE_THINKING:
        setExpression("relaxed", 0.15);
        lookTarget.x = 0.32;
        lookTarget.y = 0.18;
        lookWanderT = 0.8;
        break;
      case STATE_SPEAKING:
        setExpression("happy", 0.25);
        lookTarget.x = 0;
        lookTarget.y = 0.02;
        lookWanderT = 1.2;
        gestureBurstT = 0.3;
        break;
      case STATE_IDLE:
      default:
        setExpression("relaxed", 0.12);
        lookTarget.x = 0;
        lookTarget.y = 0;
        lookWanderT = 1.5 + Math.random();
        break;
    }
  }

  function ensureScene() {
    if (renderer) return;
    var L = getLibs();
    if (!L || !L.THREE) {
      throw new Error("CompanionVrmLibs (three + three-vrm) not loaded");
    }
    var THREE = L.THREE;
    var canvas = document.getElementById("vrm-canvas");
    if (!canvas) throw new Error("vrm-canvas missing");

    try {
      renderer = new THREE.WebGLRenderer({
        canvas: canvas,
        alpha: true,
        antialias: true,
        powerPreference: "high-performance",
      });
    } catch (e1) {
      renderer = new THREE.WebGLRenderer({
        canvas: canvas,
        alpha: true,
        antialias: false,
      });
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setClearColor(0x000000, 0);
    try {
      if (THREE.SRGBColorSpace) renderer.outputColorSpace = THREE.SRGBColorSpace;
    } catch (_) {}

    scene = new THREE.Scene();
    modelRoot = new THREE.Group();
    modelRoot.name = "companion-model-root";
    scene.add(modelRoot);

    camera = new THREE.PerspectiveCamera(30, 1, 0.1, 40);
    clock = new THREE.Clock();

    if (typeof L.OrbitControls === "function") {
      controls = new L.OrbitControls(camera, canvas);
      controls.enableDamping = true;
      controls.dampingFactor = 0.12;
      controls.enablePan = true;
      controls.screenSpacePanning = true;
      controls.rotateSpeed = 0.7;
      controls.panSpeed = 0.55;
      controls.zoomSpeed = 0.85;
      controls.minPolarAngle = 0.15;
      controls.maxPolarAngle = Math.PI * 0.92;
      controls.minDistance = 0.5;
      controls.maxDistance = 8;
      // Keep last orbit after finger lift (do not auto-revert).
      controls.enableZoom = true;
      // One finger rotate, two finger pinch-zoom + pan (mobile-friendly).
      try {
        if (THREE.TOUCH) {
          controls.touches = {
            ONE: THREE.TOUCH.ROTATE,
            TWO: THREE.TOUCH.DOLLY_PAN,
          };
        }
      } catch (_) {}
      try {
        if (THREE.MOUSE) {
          controls.mouseButtons = {
            LEFT: THREE.MOUSE.ROTATE,
            MIDDLE: THREE.MOUSE.DOLLY,
            RIGHT: THREE.MOUSE.PAN,
          };
        }
      } catch (_) {}
      // Mark user framing so nothing re-centers mid-session; persist last orbit.
      try {
        controls.addEventListener("start", function () {
          userFramed = true;
        });
        controls.addEventListener("end", function () {
          userFramed = true;
          schedulePersistOrbit();
        });
        controls.addEventListener("change", function () {
          userFramed = true;
        });
      } catch (_) {}
    }

    var amb = new THREE.AmbientLight(0xffffff, 0.62);
    scene.add(amb);
    var key = new THREE.DirectionalLight(0xffffff, 1.2);
    key.position.set(1.2, 2.2, 1.5);
    scene.add(key);
    var fill = new THREE.DirectionalLight(0xa8c0ff, 0.48);
    fill.position.set(-1.4, 1.0, -0.6);
    scene.add(fill);
    var rim = new THREE.DirectionalLight(0xffe0c0, 0.35);
    rim.position.set(0, 1.2, -1.5);
    scene.add(rim);
    // Soft "game room" up-light so the floor reads as a stage pad.
    var hemi = new THREE.HemisphereLight(0x9eb6ff, 0x1a1520, 0.35);
    scene.add(hemi);
    ensureFloor(THREE);

    // Double-tap resets framing — but multi-touch pan/zoom finger-ups must NOT.
    var lastTap = 0;
    var sawMultiTouch = false;
    activePointers = 0;
    canvas.addEventListener(
      "pointerdown",
      function (ev) {
        activePointers += 1;
        if (activePointers >= 2) sawMultiTouch = true;
        pointerMoved = false;
        pointerDownX = ev.clientX || 0;
        pointerDownY = ev.clientY || 0;
      },
      { passive: true }
    );
    canvas.addEventListener(
      "pointermove",
      function (ev) {
        if (activePointers < 1) return;
        var dx = (ev.clientX || 0) - pointerDownX;
        var dy = (ev.clientY || 0) - pointerDownY;
        if (dx * dx + dy * dy > 100) pointerMoved = true;
      },
      { passive: true }
    );
    function onPointerEnd(ev) {
      if (activePointers > 0) activePointers -= 1;
      if (ev.pointerType === "mouse" && ev.button !== 0) {
        if (activePointers === 0) sawMultiTouch = false;
        return;
      }
      // Only a clean single-finger tap counts toward double-tap reset.
      var isTap =
        !sawMultiTouch &&
        !pointerMoved &&
        activePointers === 0 &&
        !(ev.pointerType === "mouse" && ev.button !== 0);
      if (activePointers === 0) sawMultiTouch = false;
      if (!isTap) {
        lastTap = 0;
        return;
      }
      var now = Date.now();
      if (now - lastTap < 320) {
        userFramed = false;
        pendingOrbit = null;
        fitCamera(true);
        try {
          hostCall("clearOrbit");
        } catch (_) {}
        lastTap = 0;
      } else {
        lastTap = now;
      }
    }
    canvas.addEventListener("pointerup", onPointerEnd, { passive: true });
    canvas.addEventListener("pointercancel", onPointerEnd, { passive: true });

    window.addEventListener("resize", function () {
      fitCamera(false);
    });
    fitCamera(true);

    function tick() {
      animFrame = requestAnimationFrame(tick);
      var dt = clock ? clock.getDelta() : 0.016;
      if (dt > 0.1) dt = 0.05;
      idleTime += dt;

      // Track host envelope tightly — no fake micro-wobble (that desynced lips).
      var prevMouth = mouthValue;
      var mouthDelta = targetMouth - mouthValue;
      var mouthRate = mouthDelta > 0 ? 38 : 22;
      if (Math.abs(mouthDelta) > 0.0005) {
        mouthValue += mouthDelta * Math.min(1, dt * mouthRate);
      }
      if (currentState === STATE_SPEAKING || targetMouth > 0.03) {
        speechPhase += dt * (3.2 + targetMouth * 2.5);
      } else {
        speechPhase *= 0.88;
      }
      mouthVelocity = (mouthValue - prevMouth) / Math.max(dt, 0.001);
      if (!usingFallback && (currentState === STATE_SPEAKING || targetMouth > 0.015 || mouthValue > 0.015)) {
        applyMouth(mouthValue);
      } else if (!usingFallback && mouthValue <= 0.015 && targetMouth <= 0.015) {
        if (visemeSmooth.aa > 0.001) clearTalkExpressions();
      }

      if (vrm && !usingFallback) {
        applyBodyMotion(dt);
        updateLookWander(dt);
        updateMicroExpressions(dt);

        lookSmooth.x += (lookTarget.x - lookSmooth.x) * Math.min(1, dt * 3.4);
        lookSmooth.y += (lookTarget.y - lookSmooth.y) * Math.min(1, dt * 3.4);
        if (vrm.lookAt) {
          try {
            if (typeof vrm.lookAt.lookAt === "function" && camera) {
              var THREE2 = getLibs().THREE;
              // Eyes only toward camera + horizontal wander (tiny vertical offset).
              var target = new THREE2.Vector3(
                camera.position.x + lookSmooth.x * 0.18,
                camera.position.y + lookSmooth.y * 0.06,
                camera.position.z
              );
              vrm.lookAt.lookAt(target);
            }
          } catch (_) {}
        }

        blinkNext -= dt;
        if (blinkT >= 0) {
          blinkT += dt;
          var b =
            blinkT < 0.05
              ? blinkT / 0.05
              : blinkT < 0.1
                ? 1
                : 1 - (blinkT - 0.1) / 0.07;
          if (b < 0) {
            blinkT = -1;
            b = 0;
            if (blinkDouble) {
              blinkDouble = false;
              blinkNext = 0.12 + Math.random() * 0.1;
            } else {
              blinkNext = 1.8 + Math.random() * 3.8;
              // Occasional double-blink.
              if (Math.random() < 0.18) blinkDouble = true;
            }
          }
          setExpression("blink", Math.max(0, Math.min(1, b)));
        } else if (blinkNext <= 0) {
          blinkT = 0;
        }

        try {
          vrm.update(dt);
        } catch (_) {}
      } else if (usingFallback) {
        setFallbackMouth(mouthValue);
      }

      if (controls) {
        try {
          controls.update();
        } catch (_) {}
      }

      if (renderer && scene && camera) {
        renderer.render(scene, camera);
      }
    }
    tick();
  }

  function disposeGltfVrm(gltf, L) {
    try {
      var loaded = gltf && gltf.userData && gltf.userData.vrm;
      if (loaded && loaded.scene && L && L.VRMUtils) {
        L.VRMUtils.deepDispose(loaded.scene);
      } else if (gltf && gltf.scene && L && L.VRMUtils) {
        L.VRMUtils.deepDispose(gltf.scene);
      }
    } catch (_) {}
  }

  function installVrmFromGltf(gltf, label, L, token) {
    if (token !== loadToken) {
      disposeGltfVrm(gltf, L);
      return null;
    }

    var loaded = gltf.userData.vrm;
    if (!loaded) {
      throw new Error("No VRM data in file (need a .vrm avatar, not plain GLB)");
    }

    // Performance helpers from three-vrm examples (safe if missing).
    try {
      if (L.VRMUtils) {
        if (typeof L.VRMUtils.removeUnnecessaryVertices === "function") {
          L.VRMUtils.removeUnnecessaryVertices(gltf.scene);
        }
        if (typeof L.VRMUtils.combineSkeletons === "function") {
          L.VRMUtils.combineSkeletons(gltf.scene);
        }
        if (typeof L.VRMUtils.combineMorphs === "function") {
          L.VRMUtils.combineMorphs(loaded);
        }
        if (typeof L.VRMUtils.rotateVRM0 === "function") {
          L.VRMUtils.rotateVRM0(loaded);
        }
      }
    } catch (_) {}

    try {
      loaded.scene.traverse(function (obj) {
        if (obj.isMesh || obj.isSkinnedMesh) {
          obj.frustumCulled = false;
        }
      });
    } catch (_) {}

    // Only one avatar in the stage.
    destroyVrm();
    if (token !== loadToken) {
      disposeGltfVrm(gltf, L);
      return null;
    }

    vrm = loaded;
    if (!modelRoot) {
      ensureScene();
    }
    modelRoot.add(vrm.scene);

    // Natural standing pose + idle baseline (not bind T-pose).
    // Keep host-driven state (listening/thinking/speaking) across reloads so a
    // mid-session model swap does not snap the avatar back to idle.
    var keepState = currentState || STATE_IDLE;
    captureRestPose();

    currentState = keepState;
    pickStatePoseTarget(keepState);
    applyStateExpressions(keepState);
    applyMouth(targetMouth || mouthValue || 0);
    usingFallback = false;
    showFallback(false);
    // Frame once on install; prefer last-run orbit from host prefs.
    userFramed = false;
    if (!pendingOrbit) {
      pendingOrbit = loadSavedOrbitFromHost();
    }
    fitCamera(true);
    if (pendingOrbit) {
      if (applyOrbit(pendingOrbit)) {
        // Keep pending so resize does not steal framing; clear only on explicit reset.
      } else {
        pendingOrbit = null;
      }
    }
    return label || "VRM";
  }

  function parseVrmBuffer(arrayBuffer, label, token) {
    var L = getLibs();
    if (!L) return Promise.reject(new Error("CompanionVrmLibs not loaded"));
    ensureScene();

    if (token !== loadToken) {
      return Promise.reject(new Error("cancelled"));
    }

    if (!arrayBuffer || arrayBuffer.byteLength < 12) {
      return Promise.reject(new Error("VRM buffer empty"));
    }
    var head = new Uint8Array(arrayBuffer, 0, 4);
    if (
      head[0] !== 0x67 ||
      head[1] !== 0x6c ||
      head[2] !== 0x54 ||
      head[3] !== 0x46
    ) {
      return Promise.reject(
        new Error("Not a glTF/VRM binary (bad magic) — is this a .vrm file?")
      );
    }

    var loader = new L.GLTFLoader();
    loader.register(function (parser) {
      return new L.VRMLoaderPlugin(parser, { autoUpdateHumanBones: true });
    });

    return new Promise(function (resolve, reject) {
      try {
        loader.parse(
          arrayBuffer,
          "",
          function (gltf) {
            try {
              if (token !== loadToken) {
                disposeGltfVrm(gltf, L);
                reject(new Error("cancelled"));
                return;
              }
              var name = installVrmFromGltf(gltf, label, L, token);
              if (name == null) {
                reject(new Error("cancelled"));
                return;
              }
              resolve(name);
            } catch (e) {
              reject(e);
            }
          },
          function (err) {
            reject(
              new Error(
                ((err && err.message) || String(err) || "parse failed") +
                  (label ? " (" + label + ")" : "")
              )
            );
          }
        );
      } catch (e) {
        reject(e);
      }
    });
  }

  /**
   * Read VRM bytes from Kotlin (works offline; no file:// fetch).
   * Yields between chunks so the UI can paint progress.
   */
  function loadVrmFromBridge(path, token) {
    var bridge = hostBridge();
    if (!bridge || typeof bridge.openVrm !== "function") {
      return Promise.reject(new Error("Native VRM bridge missing"));
    }
    if (typeof bridge.readVrmBase64 !== "function") {
      return Promise.reject(new Error("Native VRM read bridge missing"));
    }

    if (token !== loadToken) {
      return Promise.reject(new Error("cancelled"));
    }

    var size;
    try {
      size = bridge.openVrm(path || "bundled");
    } catch (e) {
      return Promise.reject(
        new Error("openVrm threw: " + ((e && e.message) || String(e)))
      );
    }
    if (typeof size !== "number" || size <= 0) {
      try {
        if (typeof bridge.closeVrm === "function") bridge.closeVrm();
      } catch (_) {}
      return Promise.reject(
        new Error("openVrm failed (code " + size + ") path=" + (path || "bundled"))
      );
    }

    var label = "VRM";
    try {
      if (typeof bridge.vrmLabel === "function") {
        var n = bridge.vrmLabel();
        if (n) label = String(n).replace(/\.vrm$/i, "");
      }
    } catch (_) {}

    var bytes = new Uint8Array(size);
    var offset = 0;

    function closeBridge() {
      try {
        if (typeof bridge.closeVrm === "function") bridge.closeVrm();
      } catch (_) {}
    }

    function readNext() {
      if (token !== loadToken) {
        closeBridge();
        return Promise.reject(new Error("cancelled"));
      }
      if (offset >= size) {
        closeBridge();
        setHud("Parsing " + label + "…");
        return parseVrmBuffer(bytes.buffer, label, token);
      }
      var n = Math.min(BRIDGE_CHUNK, size - offset);
      var b64;
      try {
        b64 = bridge.readVrmBase64(offset, n);
      } catch (e) {
        closeBridge();
        return Promise.reject(
          new Error("readVrmBase64 threw at " + offset + ": " + ((e && e.message) || e))
        );
      }
      if (!b64) {
        closeBridge();
        return Promise.reject(new Error("Empty VRM chunk at offset " + offset));
      }
      var bin;
      try {
        bin = atob(b64);
      } catch (e) {
        closeBridge();
        return Promise.reject(new Error("Base64 decode failed at " + offset));
      }
      for (var i = 0; i < bin.length; i++) {
        bytes[offset + i] = bin.charCodeAt(i);
      }
      offset += bin.length;
      var pct = Math.min(99, Math.floor((offset / size) * 100));
      setHud("Loading " + label + "… " + pct + "%");

      return new Promise(function (resolve) {
        setTimeout(resolve, 0);
      }).then(readNext);
    }

    setHud("Loading " + label + "… 0%");
    return readNext();
  }

  function activateFallback(reason) {
    destroyVrm();
    showFallback(true);
    setFallbackState(currentState || STATE_IDLE);
    setFallbackMouth(mouthValue);
    usingFallback = true;
    var msg = reason || "model load failed; using fallback avatar";
    if (String(msg).indexOf("cancelled") >= 0) {
      // Superseded loads are silent — not user-facing errors.
      setHud("");
      return;
    }
    setHud("VRM failed — placeholder face");
    var label = document.getElementById("fallback-label");
    if (label) {
      var short = String(msg).replace(/\s+/g, " ").trim();
      if (short.length > 72) short = short.slice(0, 69) + "…";
      label.textContent = short ? "VRM unavailable — " + short : "VRM unavailable";
    }
    notifyError(msg);
  }

  /**
   * @param {'bundled'|'user'|string} source
   * @param {string} [path] absolute filesystem path, "bundled", or file:// URL
   */
  async function loadModel(source, path) {
    var src = (source || "bundled").toString().toLowerCase();
    var token = ++loadToken;

    // Tear down previous avatar immediately so stacks never appear mid-load.
    destroyVrm();
    showFallback(false);
    setHud("Loading VRM…");

    try {
      if (!getLibs()) {
        throw new Error("CompanionVrmLibs missing — vendor bundle failed to load");
      }
      ensureScene();

      var bridgePath;
      if (src === "user") {
        if (!path || !String(path).trim()) {
          throw new Error("user model path is empty — pick a .vrm in Settings");
        }
        bridgePath = String(path).trim();
      } else {
        bridgePath = path && String(path).trim() ? String(path).trim() : "bundled";
      }

      var label = await loadVrmFromBridge(bridgePath, token);
      if (token !== loadToken) {
        return false;
      }
      setHud(label + " (VRM)");
      setTimeout(function () {
        if (!usingFallback && token === loadToken) setHud("");
      }, 3500);
      notifyModelLoaded(label);
      return true;
    } catch (e) {
      if (token !== loadToken) return false;
      var msg = (e && e.message) || String(e);
      if (msg === "cancelled" || msg.indexOf("cancelled") >= 0) {
        return false;
      }
      try {
        console.warn("[CompanionStage] load failed", msg);
      } catch (_) {}
      activateFallback(msg);
      return false;
    }
  }

  function setState(state) {
    var s = (state || STATE_IDLE).toString().toLowerCase();
    if (
      s !== STATE_IDLE &&
      s !== STATE_LISTENING &&
      s !== STATE_THINKING &&
      s !== STATE_SPEAKING
    ) {
      s = STATE_IDLE;
    }
    var prev = currentState;
    currentState = s;
    setFallbackState(s);
    if (usingFallback || !vrm) return;
    if (prev !== s) {
      pickStatePoseTarget(s);
    }
    applyStateExpressions(s);
    if (s !== STATE_SPEAKING && targetMouth < 0.02) {
      clearTalkExpressions();
    }
  }

  function setMouth(v) {
    targetMouth = Math.max(0, Math.min(1, Number(v) || 0));
    if (usingFallback) {
      mouthValue = mouthValue * 0.25 + targetMouth * 0.75;
      setFallbackMouth(mouthValue);
      return;
    }
    // Snap hard on onsets/closures so lips land with the audio peak.
    if (Math.abs(targetMouth - mouthValue) > 0.08) {
      mouthValue = mouthValue + (targetMouth - mouthValue) * 0.78;
    }
    applyMouth(mouthValue);
  }

  function playMotion(name) {
    if (!name) return;
    var n = String(name).toLowerCase();
    if (n.indexOf("happy") >= 0) {
      setExpression("happy", 0.7);
      return;
    }
    if (n.indexOf("sad") >= 0) {
      setExpression("sad", 0.6);
      return;
    }
    if (n.indexOf("angry") >= 0) {
      setExpression("angry", 0.6);
      return;
    }
    if (n.indexOf("surprise") >= 0) {
      setExpression("surprised", 0.7);
      return;
    }
    // Body gestures (wave, nod, shrug, …) via VR hand targets.
    playGesture(n, { intensity: 1 });
  }

  function resetCamera() {
    userFramed = false;
    pendingOrbit = null;
    fitCamera(true);
    try {
      hostCall("clearOrbit");
    } catch (_) {}
  }

  function setOrbit(jsonOrObj) {
    var o = parseOrbitJson(jsonOrObj);
    if (!o) return false;
    pendingOrbit = o;
    if (camera && (vrm || usingFallback)) {
      return applyOrbit(o);
    }
    return true;
  }

  function getOrbit() {
    return captureOrbit();
  }

  window.CompanionStage = {
    loadModel: loadModel,
    setState: setState,
    setMouth: setMouth,
    playMotion: playMotion,
    playGesture: playGesture,
    setHands: setHands,
    setLook: setLook,
    resetBody: resetBody,
    resetCamera: resetCamera,
    setOrbit: setOrbit,
    getOrbit: getOrbit,
    getState: function () {
      return currentState;
    },
    isFallback: function () {
      return usingFallback;
    },
  };

  function boot() {
    try {
      if (!getLibs()) {
        throw new Error("CompanionVrmLibs missing — vendor bundle failed to load");
      }
      ensureScene();
    } catch (e) {
      activateFallback((e && e.message) || String(e));
      notifyReady();
      return;
    }
    // Host onReady → single pushLoadModel via Compose LaunchedEffect.
    notifyReady();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
