/**
 * Companion VRM stage — offline Three.js + @pixiv/three-vrm + OrbitControls.
 * Host bridge: window.GrokifyCompanion.{onReady,onModelLoaded,onError,openVrm,readVrmBase64,closeVrm}
 * Stage API:   window.CompanionStage.{loadModel,setState,setMouth,playMotion}
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
  var usingFallback = false;
  var readyNotified = false;
  var animFrame = 0;
  var idleTime = 0;
  var lookTarget = { x: 0, y: 0 };
  var lookSmooth = { x: 0, y: 0 };
  var blinkNext = 2.5;
  var blinkT = -1;
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
  var statePose = null;
  var statePoseTarget = null;

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
   * Drop arms from bind T-pose into a natural standing rest pose.
   * Uses normalized humanoid bones (VRM 0 + 1 via three-vrm).
   */
  function captureRestPose() {
    restBones = {
      hips: bone("hips"),
      spine: bone("spine"),
      chest: bone("chest"),
      upperChest: bone("upperChest"),
      neck: bone("neck"),
      head: bone("head"),
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

    // Soft A-pose: arms hang with mild elbow bend, hands slightly forward.
    setEuler(restBones.leftUpperArm, 0.18, 0.12, 1.05);
    setEuler(restBones.rightUpperArm, 0.18, -0.12, -1.05);
    setEuler(restBones.leftLowerArm, 0.42, 0.08, 0.12);
    setEuler(restBones.rightLowerArm, 0.42, -0.08, -0.12);
    setEuler(restBones.leftHand, 0.08, 0.05, 0.1);
    setEuler(restBones.rightHand, 0.08, -0.05, -0.1);
    // Contrapposto-ish stance (weight soft on one side).
    setEuler(restBones.hips, 0.03, 0.04, 0.02);
    setEuler(restBones.spine, 0.05, -0.03, -0.01);
    setEuler(restBones.chest, 0.04, 0.02, 0);
    setEuler(restBones.neck, -0.03, 0, 0);
    setEuler(restBones.head, 0.03, 0, 0);
    if (restBones.leftUpperLeg) setEuler(restBones.leftUpperLeg, 0.02, 0, 0.03);
    if (restBones.rightUpperLeg) setEuler(restBones.rightUpperLeg, -0.01, 0, -0.02);
    if (restBones.leftLowerLeg) setEuler(restBones.leftLowerLeg, -0.03, 0, 0);
    if (restBones.rightLowerLeg) setEuler(restBones.rightLowerLeg, -0.01, 0, 0);

    // Stash base rotations for idle overlays.
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

    statePose = emptyPose();
    statePoseTarget = emptyPose();
    poseVariant = 0;
    poseVariantT = 0;
    poseBlend = 0;
    pickStatePoseTarget(currentState || STATE_IDLE);
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

  /** State-specific posture targets (additive on rest). */
  function pickStatePoseTarget(state) {
    var p = emptyPose();
    if (state === STATE_LISTENING) {
      // Lean in, attentive tilt, open hands slightly.
      p.spine = [0.06, 0, 0];
      p.chest = [0.05, 0, 0];
      p.neck = [0.04, 0, 0];
      p.head = [0.05, 0.02, 0];
      p.leftUpperArm = [0.08, 0.05, -0.06];
      p.rightUpperArm = [0.08, -0.05, 0.06];
      p.leftLowerArm = [0.1, 0, 0];
      p.rightLowerArm = [0.1, 0, 0];
    } else if (state === STATE_THINKING) {
      // Head tilt + right hand toward chin, weight shift.
      p.hips = [0.02, -0.06, 0.04];
      p.spine = [0.02, 0.08, 0.03];
      p.chest = [0.03, 0.05, 0.02];
      p.neck = [0.12, 0.18, 0.08];
      p.head = [0.1, 0.22, 0.1];
      p.rightUpperArm = [-0.35, -0.55, 0.55];
      p.rightLowerArm = [1.1, 0.2, -0.15];
      p.rightHand = [0.15, 0.1, -0.2];
      p.leftUpperArm = [0.05, 0.1, 0.05];
      p.leftLowerArm = [0.15, 0, 0.05];
    } else if (state === STATE_SPEAKING) {
      // Open stance, slight forward lean, animated hands ready.
      p.hips = [0.02, 0, 0];
      p.spine = [0.04, 0, 0];
      p.chest = [0.06, 0, 0];
      p.neck = [0.02, 0, 0];
      p.head = [0.03, 0, 0];
      p.leftUpperArm = [0.12, 0.15, -0.12];
      p.rightUpperArm = [0.12, -0.15, 0.12];
      p.leftLowerArm = [0.35, 0.1, 0.08];
      p.rightLowerArm = [0.35, -0.1, -0.08];
      p.leftHand = [0.1, 0.05, 0.05];
      p.rightHand = [0.1, -0.05, -0.05];
    } else {
      // Idle: relaxed neutral (variant micro-offsets applied in motion).
      p.spine = [0.01, 0, 0];
      p.head = [0.01, 0, 0];
    }
    statePoseTarget = p;
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

  /**
   * Procedural idle / state body motion on top of rest pose.
   */
  function applyBodyMotion(dt) {
    if (!vrm || !restBones || !restBones.base) return;
    var t = idleTime;
    posePhase += dt;
    poseVariantT += dt;

    // Alternate weight-shift variants every ~5–7s for less mechanical idle.
    if (poseVariantT > 5.5 + (poseVariant % 3) * 0.7) {
      poseVariantT = 0;
      poseVariant = (poseVariant + 1) % 3;
    }
    var targetBlend = poseVariant === 0 ? 0 : poseVariant === 1 ? 1 : 0.45;
    poseBlend += (targetBlend - poseBlend) * Math.min(1, dt * 0.55);

    blendStatePose(dt);

    var breath = Math.sin(t * 1.65) * 0.018 + Math.sin(t * 0.4) * 0.006;
    var sway = Math.sin(t * 0.55) * 0.028 + Math.sin(t * 1.15) * 0.01;
    var weight = (poseBlend - 0.5) * 0.04 + Math.sin(t * 0.35) * 0.012;
    var shoulder = Math.sin(t * 0.7) * 0.02;

    if (currentState === STATE_THINKING) {
      sway = Math.sin(t * 0.45) * 0.018;
      breath = Math.sin(t * 1.4) * 0.015;
    } else if (currentState === STATE_SPEAKING) {
      sway = Math.sin(t * 0.9) * 0.032;
      breath = Math.sin(t * 2.3) * 0.026 + mouthValue * 0.012;
      // Talking hand gestures (right more active).
      var gest = Math.sin(t * 2.4) * (0.08 + mouthValue * 0.12);
      var gest2 = Math.sin(t * 1.7 + 1.1) * (0.05 + mouthValue * 0.08);
      addEuler(
        restBones.rightUpperArm,
        "rightUpperArm",
        gest * 0.9,
        -gest2 * 0.5,
        gest * 0.35
      );
      addEuler(
        restBones.rightLowerArm,
        "rightLowerArm",
        Math.abs(gest) * 0.5,
        gest2 * 0.2,
        -gest * 0.15
      );
      addEuler(
        restBones.leftUpperArm,
        "leftUpperArm",
        gest2 * 0.45,
        gest * 0.2,
        -gest2 * 0.2
      );
      addEuler(
        restBones.head,
        "head",
        Math.sin(t * 2.1) * 0.02 + mouthValue * 0.015,
        Math.sin(t * 1.3) * 0.03,
        Math.sin(t * 0.8) * 0.015
      );
    } else if (currentState === STATE_LISTENING) {
      sway = Math.sin(t * 0.5) * 0.02;
      // Soft attentive nod cycle.
      addEuler(
        restBones.head,
        "head",
        Math.sin(t * 0.65) * 0.025 + 0.01,
        Math.sin(t * 0.35) * 0.04,
        0
      );
    } else {
      // Idle micro-gestures + occasional shoulder roll.
      addEuler(
        restBones.leftUpperArm,
        "leftUpperArm",
        Math.sin(t * 0.8) * 0.035 + poseBlend * 0.03,
        Math.sin(t * 0.5) * 0.025,
        Math.sin(t * 0.65) * 0.03 - poseBlend * 0.04
      );
      addEuler(
        restBones.rightUpperArm,
        "rightUpperArm",
        Math.sin(t * 0.85 + 0.5) * 0.035 - poseBlend * 0.02,
        Math.sin(t * 0.55 + 0.3) * 0.025,
        Math.sin(t * 0.7 + 0.4) * 0.03 + poseBlend * 0.04
      );
      addEuler(
        restBones.leftLowerArm,
        "leftLowerArm",
        Math.sin(t * 0.9) * 0.05,
        0,
        Math.sin(t * 0.6) * 0.025
      );
      addEuler(
        restBones.rightLowerArm,
        "rightLowerArm",
        Math.sin(t * 0.95 + 0.6) * 0.05,
        0,
        Math.sin(t * 0.62 + 0.4) * 0.025
      );
    }

    addEuler(restBones.hips, "hips", 0.012 + weight * 0.5, sway * 0.4 + weight * 0.8, weight * 0.35);
    addEuler(
      restBones.spine,
      "spine",
      0.02 + breath * 0.7,
      sway * 0.5 - weight * 0.4,
      sway * 0.12 + shoulder * 0.3
    );
    if (restBones.chest) {
      addEuler(restBones.chest, "chest", breath * 1.05, sway * 0.35, shoulder * 0.5);
    }
    if (restBones.upperChest) {
      addEuler(restBones.upperChest, "upperChest", breath * 0.55, sway * 0.18, 0);
    }
    addEuler(restBones.neck, "neck", -0.015 + breath * 0.12, sway * 0.55, shoulder * 0.2);
    // Head always gets look + gentle idle unless speaking/listening already wrote extras.
    if (currentState !== STATE_SPEAKING && currentState !== STATE_LISTENING) {
      addEuler(
        restBones.head,
        "head",
        0.02 + Math.sin(t * 0.75) * 0.018,
        sway * 0.75 + lookSmooth.x * 0.28,
        lookSmooth.x * 0.1 + Math.sin(t * 0.4) * 0.012
      );
    } else {
      // Still apply look offset on top of state motion via neck.
      addEuler(restBones.neck, "neck", lookSmooth.y * 0.08, lookSmooth.x * 0.15, 0);
    }

    // Weight shift legs.
    if (restBones.leftUpperLeg) {
      addEuler(restBones.leftUpperLeg, "leftUpperLeg", weight * 0.55, 0, weight * 0.25);
    }
    if (restBones.rightUpperLeg) {
      addEuler(restBones.rightUpperLeg, "rightUpperLeg", -weight * 0.55, 0, -weight * 0.25);
    }
    if (restBones.leftLowerLeg) {
      addEuler(restBones.leftLowerLeg, "leftLowerLeg", -weight * 0.3, 0, 0);
    }
    if (restBones.rightLowerLeg) {
      addEuler(restBones.rightLowerLeg, "rightLowerLeg", weight * 0.3, 0, 0);
    }
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
    ["aa", "ih", "ou", "ee", "oh"].forEach(function (n) {
      setExpression(n, 0);
    });
  }

  function applyMouth(v) {
    var open = Math.max(0, Math.min(1, Number(v) || 0));
    mouthValue = open;
    if (usingFallback) {
      setFallbackMouth(open);
      return;
    }
    if (!vrm) return;
    clearTalkExpressions();
    if (open > 0.01) {
      setExpression("aa", open);
      setExpression("oh", open * 0.25);
    }
  }

  function applyStateExpressions(state) {
    if (!vrm || !vrm.expressionManager) return;
    ["happy", "angry", "sad", "relaxed", "surprised", "neutral"].forEach(function (n) {
      try {
        vrm.expressionManager.setValue(n, 0);
      } catch (_) {}
    });
    switch (state) {
      case STATE_LISTENING:
        setExpression("happy", 0.35);
        setExpression("relaxed", 0.2);
        lookTarget.x = 0;
        lookTarget.y = 0.05;
        break;
      case STATE_THINKING:
        setExpression("relaxed", 0.15);
        lookTarget.x = 0.35;
        lookTarget.y = 0.2;
        break;
      case STATE_SPEAKING:
        setExpression("happy", 0.25);
        lookTarget.x = 0;
        lookTarget.y = 0.02;
        break;
      case STATE_IDLE:
      default:
        setExpression("relaxed", 0.12);
        lookTarget.x = 0;
        lookTarget.y = 0;
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
      // Mark user framing so nothing re-centers mid-session.
      try {
        controls.addEventListener("start", function () {
          userFramed = true;
        });
        controls.addEventListener("end", function () {
          userFramed = true;
        });
        controls.addEventListener("change", function () {
          userFramed = true;
        });
      } catch (_) {}
    }

    var amb = new THREE.AmbientLight(0xffffff, 0.7);
    scene.add(amb);
    var key = new THREE.DirectionalLight(0xffffff, 1.15);
    key.position.set(1.2, 1.8, 1.5);
    scene.add(key);
    var fill = new THREE.DirectionalLight(0xa8c0ff, 0.5);
    fill.position.set(-1.4, 1.0, -0.6);
    scene.add(fill);
    var rim = new THREE.DirectionalLight(0xffe0c0, 0.3);
    rim.position.set(0, 1.2, -1.5);
    scene.add(rim);

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
        fitCamera(true);
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

      var mouthDelta = targetMouth - mouthValue;
      if (Math.abs(mouthDelta) > 0.001) {
        mouthValue += mouthDelta * Math.min(1, dt * 18);
        if (!usingFallback) applyMouth(mouthValue);
      }

      if (vrm && !usingFallback) {
        applyBodyMotion(dt);

        lookSmooth.x += (lookTarget.x - lookSmooth.x) * Math.min(1, dt * 3);
        lookSmooth.y += (lookTarget.y - lookSmooth.y) * Math.min(1, dt * 3);
        if (vrm.lookAt) {
          try {
            if (typeof vrm.lookAt.lookAt === "function" && camera) {
              var THREE2 = getLibs().THREE;
              // Gaze toward camera with slight state offset.
              var target = new THREE2.Vector3(
                camera.position.x + lookSmooth.x * 0.15,
                camera.position.y + lookSmooth.y * 0.1,
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
            blinkT < 0.06
              ? blinkT / 0.06
              : blinkT < 0.12
                ? 1
                : 1 - (blinkT - 0.12) / 0.08;
          if (b < 0) {
            blinkT = -1;
            b = 0;
            blinkNext = 2.2 + Math.random() * 3.5;
          }
          setExpression("blink", Math.max(0, Math.min(1, b)));
        } else if (blinkNext <= 0) {
          blinkT = 0;
        }

        if (currentState === STATE_SPEAKING || targetMouth > 0.02) {
          applyMouth(mouthValue);
        }

        try {
          vrm.update(dt);
        } catch (_) {}
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
    captureRestPose();

    currentState = STATE_IDLE;
    applyStateExpressions(STATE_IDLE);
    applyMouth(0);
    usingFallback = false;
    showFallback(false);
    // Frame once on install only — never again unless double-tap / resetCamera.
    userFramed = false;
    fitCamera(true);
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
      mouthValue = targetMouth;
      setFallbackMouth(mouthValue);
      return;
    }
    if (Math.abs(targetMouth - mouthValue) > 0.2) {
      mouthValue = mouthValue + (targetMouth - mouthValue) * 0.5;
    }
    applyMouth(mouthValue);
  }

  function playMotion(name) {
    if (!name) return;
    var n = String(name).toLowerCase();
    if (n.indexOf("happy") >= 0) setExpression("happy", 0.7);
    else if (n.indexOf("sad") >= 0) setExpression("sad", 0.6);
    else if (n.indexOf("angry") >= 0) setExpression("angry", 0.6);
    else if (n.indexOf("surprise") >= 0) setExpression("surprised", 0.7);
  }

  function resetCamera() {
    userFramed = false;
    fitCamera(true);
  }

  window.CompanionStage = {
    loadModel: loadModel,
    setState: setState,
    setMouth: setMouth,
    playMotion: playMotion,
    resetCamera: resetCamera,
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
