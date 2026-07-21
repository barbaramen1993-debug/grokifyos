/**
 * Companion VRM stage — offline Three.js + @pixiv/three-vrm + OrbitControls.
 * Host bridge: window.GrokifyCompanion.{onReady,onModelLoaded,onError,onDebugLog,onJointPicked,openVrm,readVrmBase64,closeVrm}
 * Stage API:   window.CompanionStage.{loadModel,setState,setMouth,playMotion,
 *              playGesture,setHands,setLook,resetBody,exportBodyState,setDebugSkeleton,
 *              setJointLabel,setJointLabels,getJointLabels,selectJoint}
 *
 * Avatar is driven like a VRChat body: virtual HMD + left/right wrist controllers
 * (spring-damper + gravity ragdoll). Arms use two-bone IK to those hand points.
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
  /** WebGL canvas element (#vrm-canvas) — module scope for pick/debug/labels. */
  var canvas = null;
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
  /** Tool-driven look stays until this idleTime (wander / setState cannot steal it). */
  var lookHoldUntil = 0;
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
    // Live shoulder anchors (hips-local), filled on load from bind bones.
    shoulderLeft: { x: -0.16, y: 1.22, z: 0 },
    shoulderRight: { x: 0.16, y: 1.22, z: 0 },
    // Soft ragdoll: hands fall under gravity, spring home when free.
    gravity: 7.2,
    spring: 9.5,
    damp: 5.2,
  };
  /** After VRM install, remeasure hangs once world matrices settle. */
  var restRecalibLeft = 0;
  /** Active scripted gesture (null when idle VR physics owns hands). */
  var activeGesture = null;
  var floorMesh = null;
  var floorRing = null;
  /**
   * Per-arm two-bone IK meta (bind quats, bone axes, lengths).
   * Arms are ragdolled: free hand points spring under gravity; bones IK to wrists.
   */
  var armMeta = { left: null, right: null };
  /** Reused THREE scratch for IK (allocated lazily). */
  var ikScratch = null;
  /** Debug: SkeletonHelper + joint spheres + VR controller markers. */
  var debugSkeletonOn = false;
  var skeletonHelper = null;
  var debugGizmoRoot = null;
  var debugJointMeshes = null;
  var debugCtrlMeshes = null;
  var debugHudEl = null;
  /** Custom joint display names (key → label). Defaults = humanoid ids. */
  var jointLabels = {};
  /** Currently selected joint key for rename highlight. */
  var selectedJointKey = null;
  /** DOM layer for floating joint name tags. */
  var jointLabelLayer = null;
  var jointLabelEls = null;
  /** Raycaster for tap-to-select joints. */
  var jointRaycaster = null;
  var jointPickNdc = null;
  /** All pickable joint/controller keys (debug spheres). */
  var JOINT_KEYS = [
    "hips",
    "spine",
    "chest",
    "upperChest",
    "neck",
    "head",
    "leftUpperArm",
    "leftLowerArm",
    "leftHand",
    "rightUpperArm",
    "rightLowerArm",
    "rightHand",
    "leftUpperLeg",
    "leftLowerLeg",
    "rightUpperLeg",
    "rightLowerLeg",
  ];
  var CTRL_LABEL_KEYS = ["vrLeft", "vrRight", "vrHead"];
  /**
   * Last sampled hips-local positions for motion logging (debug only).
   * key → { x, y, z, t }
   */
  var jointMotionLast = {};
  /** Min displacement (m) before a joint is logged as moved. */
  var JOINT_MOTION_EPS = 0.012;
  /** Min ms between logs for the same joint. */
  var JOINT_MOTION_MIN_MS = 280;
  /** Cap how many joint-move lines we emit in one frame. */
  var JOINT_MOTION_MAX_PER_FRAME = 6;

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
  function defaultJointLabel(key) {
    if (!key) return "";
    if (key === "vrLeft") return "left controller";
    if (key === "vrRight") return "right controller";
    if (key === "vrHead") return "head HMD";
    // camelCase → spaced words: leftUpperArm → left Upper Arm
    return String(key)
      .replace(/([a-z])([A-Z])/g, "$1 $2")
      .replace(/^./, function (c) {
        return c.toLowerCase();
      });
  }

  function jointDisplayName(key) {
    if (!key) return "";
    var custom = jointLabels[key];
    if (typeof custom === "string" && custom.trim()) return custom.trim();
    return defaultJointLabel(key);
  }

  function setJointLabel(key, label) {
    var k = String(key || "").trim();
    if (!k) return false;
    var v = label == null ? "" : String(label).trim();
    if (!v || v === defaultJointLabel(k) || v === k) {
      delete jointLabels[k];
    } else {
      // Cap length for prefs + AI payloads.
      jointLabels[k] = v.slice(0, 64);
    }
    selectedJointKey = k;
    updateJointLabelDom();
    persistJointLabels();
    return true;
  }

  function setJointLabels(map) {
    if (!map || typeof map !== "object") return false;
    jointLabels = {};
    for (var k in map) {
      if (!Object.prototype.hasOwnProperty.call(map, k)) continue;
      var v = map[k];
      if (typeof v === "string" && v.trim()) {
        jointLabels[String(k).trim()] = v.trim().slice(0, 64);
      }
    }
    updateJointLabelDom();
    return true;
  }

  function getJointLabels() {
    var out = {};
    for (var k in jointLabels) {
      if (Object.prototype.hasOwnProperty.call(jointLabels, k)) {
        out[k] = jointLabels[k];
      }
    }
    return out;
  }

  function persistJointLabels() {
    try {
      hostCall("saveJointLabels", JSON.stringify(getJointLabels()));
    } catch (_) {}
  }

  function loadJointLabelsFromHost() {
    try {
      var bridge = hostBridge();
      if (!bridge || typeof bridge.getJointLabels !== "function") return;
      var raw = bridge.getJointLabels();
      if (!raw || typeof raw !== "string" || !raw.trim()) return;
      var parsed = JSON.parse(raw);
      if (parsed && typeof parsed === "object") setJointLabels(parsed);
    } catch (_) {}
  }

  function disposeJointLabelDom() {
    if (jointLabelLayer) {
      try {
        if (jointLabelLayer.parentNode) {
          jointLabelLayer.parentNode.removeChild(jointLabelLayer);
        }
      } catch (_) {}
    }
    jointLabelLayer = null;
    jointLabelEls = null;
  }

  function ensureJointLabelDom() {
    if (jointLabelLayer) return jointLabelLayer;
    try {
      jointLabelLayer = document.getElementById("joint-label-layer");
      if (!jointLabelLayer) {
        jointLabelLayer = document.createElement("div");
        jointLabelLayer.id = "joint-label-layer";
        jointLabelLayer.setAttribute("aria-hidden", "true");
        var root = document.getElementById("stage-root") || document.body;
        root.appendChild(jointLabelLayer);
      }
    } catch (_) {
      jointLabelLayer = null;
    }
    return jointLabelLayer;
  }

  function fmtXyz(v, digits) {
    var d = digits == null ? 2 : digits;
    function n(x) {
      if (typeof x !== "number" || !isFinite(x)) return "—";
      var s = x.toFixed(d);
      // keep sign column readable for negatives
      return s;
    }
    if (!v) return "— — —";
    return n(v.x) + "  " + n(v.y) + "  " + n(v.z);
  }

  function updateJointLabelDom() {
    if (!debugSkeletonOn) return;
    var layer = ensureJointLabelDom();
    if (!layer) return;
    if (!jointLabelEls) jointLabelEls = {};
    var keys = JOINT_KEYS.concat(CTRL_LABEL_KEYS);
    for (var i = 0; i < keys.length; i++) {
      var key = keys[i];
      var el = jointLabelEls[key];
      if (!el) {
        el = document.createElement("div");
        el.className = "joint-tag";
        el.dataset.joint = key;
        var nameEl = document.createElement("span");
        nameEl.className = "joint-name";
        var xyzEl = document.createElement("span");
        xyzEl.className = "joint-xyz";
        el.appendChild(nameEl);
        el.appendChild(xyzEl);
        layer.appendChild(el);
        jointLabelEls[key] = el;
      }
      var nameSpan = el.querySelector(".joint-name");
      if (nameSpan) nameSpan.textContent = jointDisplayName(key);
      el.title = key + " · hips-local xyz · tap to rename";
      if (selectedJointKey === key) el.classList.add("selected");
      else el.classList.remove("selected");
    }
  }

  function disposeDebugVisuals() {
    if (skeletonHelper) {
      try {
        if (scene) scene.remove(skeletonHelper);
      } catch (_) {}
      try {
        if (skeletonHelper.geometry) skeletonHelper.geometry.dispose();
      } catch (_) {}
      try {
        if (skeletonHelper.material) {
          if (Array.isArray(skeletonHelper.material)) {
            skeletonHelper.material.forEach(function (m) {
              try {
                m.dispose();
              } catch (_) {}
            });
          } else {
            skeletonHelper.material.dispose();
          }
        }
      } catch (_) {}
      skeletonHelper = null;
    }
    if (debugGizmoRoot) {
      try {
        if (scene) scene.remove(debugGizmoRoot);
      } catch (_) {}
      try {
        debugGizmoRoot.traverse(function (obj) {
          if (obj.geometry) {
            try {
              obj.geometry.dispose();
            } catch (_) {}
          }
          if (obj.material) {
            try {
              if (Array.isArray(obj.material)) {
                obj.material.forEach(function (m) {
                  m.dispose();
                });
              } else {
                obj.material.dispose();
              }
            } catch (_) {}
          }
        });
      } catch (_) {}
      debugGizmoRoot = null;
    }
    debugJointMeshes = null;
    debugCtrlMeshes = null;
    disposeJointLabelDom();
    if (debugHudEl) {
      try {
        debugHudEl.style.display = "none";
        debugHudEl.textContent = "";
      } catch (_) {}
    }
  }

  function ensureDebugHud() {
    if (debugHudEl) return debugHudEl;
    try {
      debugHudEl = document.getElementById("debug-skel-hud");
      if (!debugHudEl) {
        debugHudEl = document.createElement("div");
        debugHudEl.id = "debug-skel-hud";
        debugHudEl.setAttribute("aria-hidden", "true");
        var root = document.getElementById("stage-root") || document.body;
        root.appendChild(debugHudEl);
      }
    } catch (_) {
      debugHudEl = null;
    }
    return debugHudEl;
  }

  /**
   * Build SkeletonHelper wireframe + joint spheres + VR controller points.
   * Call after VRM install when debug is on.
   */
  function rebuildDebugVisuals() {
    disposeDebugVisuals();
    if (!debugSkeletonOn || !vrm || !scene) return;
    var L = getLibs();
    if (!L || !L.THREE) return;
    var THREE = L.THREE;

    try {
      skeletonHelper = new THREE.SkeletonHelper(vrm.scene);
      if (skeletonHelper.material) {
        var mats = Array.isArray(skeletonHelper.material)
          ? skeletonHelper.material
          : [skeletonHelper.material];
        mats.forEach(function (m) {
          m.depthTest = false;
          m.depthWrite = false;
          m.transparent = true;
          m.opacity = 0.95;
          if (m.color) m.color.setHex(0x6ee7ff);
        });
      }
      skeletonHelper.renderOrder = 999;
      scene.add(skeletonHelper);
    } catch (e) {
      skeletonHelper = null;
    }

    debugGizmoRoot = new THREE.Group();
    debugGizmoRoot.name = "companion-debug-gizmos";
    debugGizmoRoot.renderOrder = 1000;

    var jointColors = {
      head: 0xffe066,
      leftHand: 0x4fd1c5,
      rightHand: 0xf687b3,
      leftUpperArm: 0x63b3ed,
      rightUpperArm: 0xfc8181,
      leftLowerArm: 0x90cdf4,
      rightLowerArm: 0xfbb6ce,
      hips: 0xa78bfa,
    };
    debugJointMeshes = {};
    var sphereGeo = new THREE.SphereGeometry(0.022, 12, 12);
    for (var ji = 0; ji < JOINT_KEYS.length; ji++) {
      var jk = JOINT_KEYS[ji];
      var col = jointColors[jk] != null ? jointColors[jk] : 0xb8c0cc;
      var mat = new THREE.MeshBasicMaterial({
        color: col,
        depthTest: false,
        depthWrite: false,
        transparent: true,
        opacity: 0.92,
      });
      var mesh = new THREE.Mesh(sphereGeo, mat);
      mesh.name = "joint-" + jk;
      mesh.userData.jointKey = jk;
      mesh.userData.pickable = true;
      mesh.renderOrder = 1001;
      debugGizmoRoot.add(mesh);
      debugJointMeshes[jk] = mesh;
    }

    // VR controller / HMD markers (slightly larger, distinct colors).
    debugCtrlMeshes = {};
    function ctrlMarker(name, jointKey, color, radius) {
      var g = new THREE.SphereGeometry(radius, 12, 12);
      var m = new THREE.MeshBasicMaterial({
        color: color,
        depthTest: false,
        depthWrite: false,
        transparent: true,
        opacity: 0.85,
      });
      var mesh = new THREE.Mesh(g, m);
      mesh.name = name;
      mesh.userData.jointKey = jointKey;
      mesh.userData.pickable = true;
      mesh.renderOrder = 1002;
      debugGizmoRoot.add(mesh);
      // Wire ring for controller feel.
      try {
        var ring = new THREE.Mesh(
          new THREE.TorusGeometry(radius * 1.35, radius * 0.12, 6, 20),
          new THREE.MeshBasicMaterial({
            color: color,
            depthTest: false,
            depthWrite: false,
            transparent: true,
            opacity: 0.7,
          })
        );
        ring.rotation.x = Math.PI / 2;
        ring.userData.pickable = false;
        mesh.add(ring);
      } catch (_) {}
      return mesh;
    }
    debugCtrlMeshes.left = ctrlMarker("vr-ctrl-left", "vrLeft", 0x22d3ee, 0.036);
    debugCtrlMeshes.right = ctrlMarker("vr-ctrl-right", "vrRight", 0xf472b6, 0.036);
    debugCtrlMeshes.head = ctrlMarker("vr-hmd", "vrHead", 0xfacc15, 0.032);
    debugCtrlMeshes.restLeft = ctrlMarker("vr-rest-left", null, 0x0e7490, 0.014);
    debugCtrlMeshes.restRight = ctrlMarker("vr-rest-right", null, 0x9d174d, 0.014);
    // Rest markers are not renamable pick targets.
    if (debugCtrlMeshes.restLeft) debugCtrlMeshes.restLeft.userData.pickable = false;
    if (debugCtrlMeshes.restRight) debugCtrlMeshes.restRight.userData.pickable = false;

    // Shoulder→controller guide lines (updated each frame).
    try {
      var lineMatL = new THREE.LineBasicMaterial({
        color: 0x22d3ee,
        depthTest: false,
        transparent: true,
        opacity: 0.55,
      });
      var lineMatR = new THREE.LineBasicMaterial({
        color: 0xf472b6,
        depthTest: false,
        transparent: true,
        opacity: 0.55,
      });
      var geoL = new THREE.BufferGeometry().setFromPoints([
        new THREE.Vector3(),
        new THREE.Vector3(),
      ]);
      var geoR = new THREE.BufferGeometry().setFromPoints([
        new THREE.Vector3(),
        new THREE.Vector3(),
      ]);
      debugCtrlMeshes.lineL = new THREE.Line(geoL, lineMatL);
      debugCtrlMeshes.lineR = new THREE.Line(geoR, lineMatR);
      debugCtrlMeshes.lineL.renderOrder = 1000;
      debugCtrlMeshes.lineR.renderOrder = 1000;
      debugGizmoRoot.add(debugCtrlMeshes.lineL);
      debugGizmoRoot.add(debugCtrlMeshes.lineR);
    } catch (_) {}

    scene.add(debugGizmoRoot);
    ensureDebugHud();
    if (debugHudEl) {
      debugHudEl.style.display = "block";
    }
    ensureJointLabelDom();
    updateJointLabelDom();
    updateDebugVisuals();
  }

  /**
   * Raycast joint/controller spheres under a canvas client point.
   * Returns joint key or null.
   */
  function pickJointAt(clientX, clientY) {
    if (!debugSkeletonOn || !camera || !canvas) return null;
    var L = getLibs();
    if (!L || !L.THREE) return null;
    var THREE = L.THREE;
    if (!jointRaycaster) jointRaycaster = new THREE.Raycaster();
    if (!jointPickNdc) jointPickNdc = new THREE.Vector2();
    // Slightly fat pick radius for fingers.
    try {
      jointRaycaster.params.Points = jointRaycaster.params.Points || {};
      if (typeof jointRaycaster.params.Mesh === "undefined") {
        // no-op: mesh uses geometry bounds
      }
    } catch (_) {}

    var rect = canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return null;
    jointPickNdc.x = ((clientX - rect.left) / rect.width) * 2 - 1;
    jointPickNdc.y = -((clientY - rect.top) / rect.height) * 2 + 1;
    jointRaycaster.setFromCamera(jointPickNdc, camera);

    var targets = [];
    if (debugJointMeshes) {
      for (var k in debugJointMeshes) {
        if (!Object.prototype.hasOwnProperty.call(debugJointMeshes, k)) continue;
        var m = debugJointMeshes[k];
        if (m && m.visible) targets.push(m);
      }
    }
    if (debugCtrlMeshes) {
      ["left", "right", "head"].forEach(function (side) {
        var cm = debugCtrlMeshes[side];
        if (cm && cm.visible) targets.push(cm);
      });
    }
    if (!targets.length) return null;

    var hits = jointRaycaster.intersectObjects(targets, true);
    for (var hi = 0; hi < hits.length; hi++) {
      var obj = hits[hi].object;
      // Walk up to pickable joint marker (ring child → sphere parent).
      var walk = obj;
      while (walk) {
        if (walk.userData && walk.userData.pickable && walk.userData.jointKey) {
          return walk.userData.jointKey;
        }
        walk = walk.parent;
      }
    }
    return null;
  }

  function r3n(n) {
    return typeof n === "number" && isFinite(n) ? Math.round(n * 1000) / 1000 : 0;
  }

  function jointWorldLocal(key) {
    var local = null;
    var world = null;
    var L = getLibs();
    var THREE = L && L.THREE;
    function fromNode(node) {
      if (!node || !THREE) return;
      try {
        local = hipsLocalOf(node);
        node.updateWorldMatrix(true, false);
        var w = new THREE.Vector3();
        node.getWorldPosition(w);
        world = { x: w.x, y: w.y, z: w.z };
      } catch (_) {}
    }
    if (key === "vrLeft" && vr) {
      local = { x: vr.left.x, y: vr.left.y, z: vr.left.z };
      if (THREE) {
        var wl = new THREE.Vector3();
        hipsLocalToWorld(vr.left, wl);
        world = { x: wl.x, y: wl.y, z: wl.z };
      }
    } else if (key === "vrRight" && vr) {
      local = { x: vr.right.x, y: vr.right.y, z: vr.right.z };
      if (THREE) {
        var wr = new THREE.Vector3();
        hipsLocalToWorld(vr.right, wr);
        world = { x: wr.x, y: wr.y, z: wr.z };
      }
    } else if (key === "vrHead" && vr) {
      local = { x: vr.head.x, y: vr.head.y, z: vr.head.z };
      if (THREE) {
        var wh = new THREE.Vector3();
        hipsLocalToWorld(vr.head, wh);
        world = { x: wh.x, y: wh.y, z: wh.z };
      }
    } else if (restBones && restBones[key]) {
      fromNode(restBones[key]);
    }
    return {
      id: key,
      name: jointDisplayName(key),
      default_name: defaultJointLabel(key),
      custom: !!(jointLabels[key] && String(jointLabels[key]).trim()),
      local: local
        ? { x: r3n(local.x), y: r3n(local.y), z: r3n(local.z) }
        : null,
      world: world
        ? { x: r3n(world.x), y: r3n(world.y), z: r3n(world.z) }
        : null,
    };
  }

  /** Push a line into the Android AI debug overlay (no-op if bridge missing). */
  function hostDebugLog(kind, summary, detail) {
    try {
      hostCall(
        "onDebugLog",
        String(kind || "joint"),
        String(summary || "").slice(0, 240),
        String(detail || "").slice(0, 8000)
      );
    } catch (_) {}
  }

  function formatJointLogDetail(info, reason) {
    var o = {
      id: info && info.id,
      name: info && info.name,
      reason: reason || "move",
      local: info && info.local,
      world: info && info.world,
    };
    try {
      return JSON.stringify(o);
    } catch (_) {
      return "";
    }
  }

  function formatJointLogSummary(info, reason) {
    var label = (info && (info.name || info.id)) || "?";
    var id = (info && info.id) || "";
    var tag = reason === "pick" ? "pick" : "move";
    return (
      tag +
      " " +
      label +
      (id && id !== label ? " [" + id + "]" : "") +
      "  L " +
      fmtXyz(info && info.local, 3)
    );
  }

  /**
   * When debug skeleton is on, sample joint hips-local positions and log
   * significant moves into the host debug overlay (for copy/paste).
   */
  function recordJointMotion(localByKey, worldByKey) {
    if (!debugSkeletonOn || !localByKey) return;
    var now =
      typeof performance !== "undefined" && performance.now
        ? performance.now()
        : Date.now();
    var keys = JOINT_KEYS.concat(CTRL_LABEL_KEYS);
    var logged = 0;
    for (var i = 0; i < keys.length; i++) {
      if (logged >= JOINT_MOTION_MAX_PER_FRAME) break;
      var key = keys[i];
      var loc = localByKey[key];
      if (!loc) continue;
      var lx = loc.x;
      var ly = loc.y;
      var lz = loc.z;
      if (
        typeof lx !== "number" ||
        typeof ly !== "number" ||
        typeof lz !== "number"
      ) {
        continue;
      }
      var prev = jointMotionLast[key];
      if (!prev) {
        // Establish baseline without spamming the log on first frame.
        jointMotionLast[key] = { x: lx, y: ly, z: lz, t: now };
        continue;
      }
      var dx = lx - prev.x;
      var dy = ly - prev.y;
      var dz = lz - prev.z;
      var dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
      if (dist < JOINT_MOTION_EPS) continue;
      if (now - prev.t < JOINT_MOTION_MIN_MS) continue;
      jointMotionLast[key] = { x: lx, y: ly, z: lz, t: now };
      var w = worldByKey && worldByKey[key];
      var info = {
        id: key,
        name: jointDisplayName(key),
        local: { x: r3n(lx), y: r3n(ly), z: r3n(lz) },
        world: w
          ? { x: r3n(w.x), y: r3n(w.y), z: r3n(w.z) }
          : null,
      };
      hostDebugLog(
        "joint",
        formatJointLogSummary(info, "move"),
        formatJointLogDetail(info, "move")
      );
      logged++;
    }
  }

  /**
   * Select a joint and notify the Android host to show a rename dialog.
   */
  function selectJoint(key) {
    if (!key) return false;
    selectedJointKey = key;
    updateJointLabelDom();
    var info = jointWorldLocal(key);
    try {
      hostCall("onJointPicked", JSON.stringify(info));
    } catch (_) {}
    // Always record the pick so coords are copyable from the debug log.
    hostDebugLog(
      "joint",
      formatJointLogSummary(info, "pick"),
      formatJointLogDetail(info, "pick")
    );
    if (debugHudEl) {
      try {
        debugHudEl.textContent =
          "selected " +
          info.name +
          " [" +
          key +
          "]  L " +
          fmtXyz(info.local, 3) +
          "  W " +
          fmtXyz(info.world, 3) +
          "  · logged · tap rename";
      } catch (_) {}
    }
    return true;
  }

  /** Hips-local meters → world position. */
  function hipsLocalToWorld(local, out) {
    var L = getLibs();
    if (!L || !L.THREE || !out) return out;
    out.set(
      local && typeof local.x === "number" ? local.x : 0,
      local && typeof local.y === "number" ? local.y : 0,
      local && typeof local.z === "number" ? local.z : 0
    );
    if (restBones && restBones.hips) {
      try {
        restBones.hips.updateWorldMatrix(true, false);
        out.applyMatrix4(restBones.hips.matrixWorld);
      } catch (_) {}
    }
    return out;
  }

  function updateDebugVisuals() {
    if (!debugSkeletonOn) return;
    var L = getLibs();
    if (!L || !L.THREE) return;
    var THREE = L.THREE;
    var tmp = new THREE.Vector3();
    var worldByKey = {};
    var localByKey = {};
    var hipsInv = null;
    if (restBones && restBones.hips) {
      try {
        restBones.hips.updateWorldMatrix(true, false);
        hipsInv = new THREE.Matrix4().copy(restBones.hips.matrixWorld).invert();
      } catch (_) {
        hipsInv = null;
      }
    }
    function storeWorldLocal(key, worldVec, localOverride) {
      if (!worldVec) return;
      worldByKey[key] = worldVec.clone();
      if (localOverride) {
        localByKey[key] = {
          x: localOverride.x,
          y: localOverride.y,
          z: localOverride.z,
        };
      } else if (hipsInv) {
        var loc = worldVec.clone().applyMatrix4(hipsInv);
        localByKey[key] = { x: loc.x, y: loc.y, z: loc.z };
      }
    }

    if (debugJointMeshes && restBones) {
      for (var key in debugJointMeshes) {
        if (!Object.prototype.hasOwnProperty.call(debugJointMeshes, key)) continue;
        var mesh = debugJointMeshes[key];
        var node = restBones[key];
        if (!mesh || !node) {
          if (mesh) mesh.visible = false;
          continue;
        }
        mesh.visible = true;
        try {
          node.updateWorldMatrix(true, false);
          node.getWorldPosition(tmp);
          mesh.position.copy(tmp);
          storeWorldLocal(key, tmp, null);
          var sel = selectedJointKey === key;
          mesh.scale.setScalar(sel ? 1.55 : 1);
          if (mesh.material && mesh.material.opacity != null) {
            mesh.material.opacity = sel ? 1 : 0.92;
          }
        } catch (_) {
          mesh.visible = false;
        }
      }
    }

    if (debugCtrlMeshes && vr) {
      hipsLocalToWorld(vr.left, tmp);
      if (debugCtrlMeshes.left) {
        debugCtrlMeshes.left.position.copy(tmp);
        storeWorldLocal("vrLeft", tmp, vr.left);
        debugCtrlMeshes.left.scale.setScalar(
          selectedJointKey === "vrLeft" ? 1.45 : 1
        );
      }
      hipsLocalToWorld(vr.right, tmp);
      if (debugCtrlMeshes.right) {
        debugCtrlMeshes.right.position.copy(tmp);
        storeWorldLocal("vrRight", tmp, vr.right);
        debugCtrlMeshes.right.scale.setScalar(
          selectedJointKey === "vrRight" ? 1.45 : 1
        );
      }
      hipsLocalToWorld(vr.head, tmp);
      if (debugCtrlMeshes.head) {
        debugCtrlMeshes.head.position.copy(tmp);
        storeWorldLocal("vrHead", tmp, vr.head);
        debugCtrlMeshes.head.scale.setScalar(
          selectedJointKey === "vrHead" ? 1.45 : 1
        );
      }
      hipsLocalToWorld(vr.restLeft, tmp);
      if (debugCtrlMeshes.restLeft) debugCtrlMeshes.restLeft.position.copy(tmp);
      hipsLocalToWorld(vr.restRight, tmp);
      if (debugCtrlMeshes.restRight) debugCtrlMeshes.restRight.position.copy(tmp);

      // Shoulder → controller guide lines.
      try {
        if (debugCtrlMeshes.lineL && debugCtrlMeshes.lineL.geometry) {
          var shL = hipsLocalToWorld(vr.shoulderLeft, new THREE.Vector3());
          var wrL = hipsLocalToWorld(vr.left, new THREE.Vector3());
          var posL = debugCtrlMeshes.lineL.geometry.attributes.position;
          posL.setXYZ(0, shL.x, shL.y, shL.z);
          posL.setXYZ(1, wrL.x, wrL.y, wrL.z);
          posL.needsUpdate = true;
        }
        if (debugCtrlMeshes.lineR && debugCtrlMeshes.lineR.geometry) {
          var shR = hipsLocalToWorld(vr.shoulderRight, new THREE.Vector3());
          var wrR = hipsLocalToWorld(vr.right, new THREE.Vector3());
          var posR = debugCtrlMeshes.lineR.geometry.attributes.position;
          posR.setXYZ(0, shR.x, shR.y, shR.z);
          posR.setXYZ(1, wrR.x, wrR.y, wrR.z);
          posR.needsUpdate = true;
        }
      } catch (_) {}
    }

    // Project world joint positions → screen tags with live XYZ.
    if (camera && canvas && jointLabelEls) {
      try {
        var rect = canvas.getBoundingClientRect();
        var w = rect.width || 1;
        var h = rect.height || 1;
        var proj = new THREE.Vector3();
        var allKeys = JOINT_KEYS.concat(CTRL_LABEL_KEYS);
        for (var li = 0; li < allKeys.length; li++) {
          var lk = allKeys[li];
          var el = jointLabelEls[lk];
          var wp = worldByKey[lk];
          if (!el || !wp) {
            if (el) el.style.display = "none";
            continue;
          }
          proj.copy(wp);
          proj.project(camera);
          if (proj.z < -1 || proj.z > 1) {
            el.style.display = "none";
            continue;
          }
          var sx = (proj.x * 0.5 + 0.5) * w;
          var sy = (-proj.y * 0.5 + 0.5) * h;
          el.style.display = "block";
          el.style.left = sx.toFixed(1) + "px";
          el.style.top = (sy - 14).toFixed(1) + "px";
          if (selectedJointKey === lk) el.classList.add("selected");
          else el.classList.remove("selected");

          var nameSpan = el.querySelector(".joint-name");
          var xyzSpan = el.querySelector(".joint-xyz");
          var loc = localByKey[lk];
          if (nameSpan) nameSpan.textContent = jointDisplayName(lk);
          if (xyzSpan) {
            // Hips-local (same space as set_hands / observe_environment).
            xyzSpan.textContent = "xyz " + fmtXyz(loc, 2);
          }
          el.title =
            lk +
            "\nhips-local: " +
            fmtXyz(loc, 3) +
            "\nworld: " +
            fmtXyz(wp, 3) +
            "\ntap to rename";
        }
      } catch (_) {}
    }

    // Record significant joint motion into host debug log (copyable).
    try {
      recordJointMotion(localByKey, worldByKey);
    } catch (_) {}

    if (debugHudEl && vr) {
      try {
        if (selectedJointKey) {
          var selLoc = localByKey[selectedJointKey];
          var selW = worldByKey[selectedJointKey];
          debugHudEl.textContent =
            jointDisplayName(selectedJointKey) +
            " [" +
            selectedJointKey +
            "]  L " +
            fmtXyz(selLoc, 3) +
            "  W " +
            fmtXyz(selW, 3);
        } else {
          var g = activeGesture
            ? activeGesture.kind || activeGesture.name || "?"
            : "—";
          debugHudEl.textContent =
            "joint xyz = hips-local · L " +
            fmtXyz(vr.left, 2) +
            "  R " +
            fmtXyz(vr.right, 2) +
            "  g " +
            g +
            " · moves → debug log";
        }
      } catch (_) {}
    }
  }

  function setDebugSkeleton(on) {
    debugSkeletonOn = !!on;
    if (!debugSkeletonOn) {
      selectedJointKey = null;
      jointMotionLast = {};
      disposeDebugVisuals();
      return true;
    }
    jointMotionLast = {};
    loadJointLabelsFromHost();
    if (vrm && scene) {
      rebuildDebugVisuals();
    } else {
      ensureDebugHud();
      if (debugHudEl) {
        debugHudEl.style.display = "block";
        debugHudEl.textContent =
          "debug on — waiting for VRM · tap joints · moves logged";
      }
    }
    return true;
  }

  function destroyVrm() {
    disposeDebugVisuals();
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
    armMeta.left = null;
    armMeta.right = null;
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
   * Soft hang rest for the whole body. VRM normalized bones start in T-pose;
   * pure geometric IK alone left arms stuck at shoulder height on many models.
   *
   * Idle arms use soft A-pose eulers (Z ≈ ±1.26 ≈ 72° from T). Raised hands /
   * gestures switch to two-bone IK from bind. Rest wrist targets are measured
   * from the live hang pose so controllers match what you see.
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

    // Bind quaternions (T-pose / author rest) — raised-arm IK starts from these.
    restBones.bindQ = {};
    var bind = {};
    Object.keys(restBones).forEach(function (k) {
      if (k === "bindQ" || k === "base") return;
      var n = restBones[k];
      if (!n) return;
      if (n.quaternion) restBones.bindQ[k] = n.quaternion.clone();
      if (n.rotation) bind[k] = { x: n.rotation.x, y: n.rotation.y, z: n.rotation.z };
    });
    restBones.bindEuler = bind;

    function hang(key, dx, dy, dz) {
      var n = restBones[key];
      var b = bind[key];
      if (!n || !b) return;
      setEuler(n, b.x + dx, b.y + dy, b.z + dz);
    }

    applySoftHangEulers(hang);

    // Base eulers = soft hang (idle path uses addEuler from these).
    restBones.base = {};
    Object.keys(restBones).forEach(function (k) {
      if (k === "base" || k === "bindQ" || k === "bindEuler") return;
      var n = restBones[k];
      if (!n || !n.rotation) return;
      restBones.base[k] = {
        x: n.rotation.x,
        y: n.rotation.y,
        z: n.rotation.z,
      };
    });

    // Arm lengths / local axes from BIND (straight T-pose reach) for raised IK.
    restoreArmBindPose();
    try {
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
    } catch (_) {}
    measureArmMeta("left");
    measureArmMeta("right");

    // Re-apply hang so first frames aren't T-pose; then measure rest wrists.
    applySoftHangEulers(hang);
    try {
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
    } catch (_) {}
    calibrateVrRestsFromBones();

    statePose = emptyPose();
    statePoseTarget = emptyPose();
    poseVariant = 0;
    poseVariantT = 0;
    poseBlend = 0;
    nodPulse = 0;
    nodNextT = 2.5 + Math.random() * 2;
    activeGesture = null;
    // Remeasure after a few frames once the scene graph world matrices settle.
    restRecalibLeft = 8;
    pickStatePoseTarget(currentState || STATE_IDLE);
    placeFloorUnderAvatar();
  }

  /**
   * Soft A-pose deltas from T-bind on VRM normalized bones.
   * Z≈±1.26 (~72°) drops arms to the sides; slight elbow bend + hands forward.
   * Full hang is ~1.45; lower values read as Y-pose (hands at shoulder height).
   */
  function applySoftHangEulers(hangFn) {
    if (typeof hangFn !== "function") {
      if (!restBones || !restBones.bindEuler) return;
      hangFn = function (key, dx, dy, dz) {
        var n = restBones[key];
        var b = restBones.bindEuler[key];
        if (!n || !b) return;
        setEuler(n, b.x + dx, b.y + dy, b.z + dz);
      };
    }
    hangFn("leftUpperArm", 0.12, 0.08, 1.26);
    hangFn("rightUpperArm", 0.12, -0.08, -1.26);
    hangFn("leftLowerArm", 0.32, 0.06, 0.08);
    hangFn("rightLowerArm", 0.32, -0.06, -0.08);
    hangFn("leftHand", 0.06, 0.04, 0.08);
    hangFn("rightHand", 0.06, -0.04, -0.08);
    hangFn("hips", 0.01, 0.015, 0.008);
    hangFn("spine", 0.018, -0.01, -0.006);
    hangFn("chest", 0.012, 0.005, 0);
    hangFn("neck", 0, 0, 0);
    hangFn("head", 0, 0, 0);
    hangFn("leftUpperLeg", 0.01, 0, 0.012);
    hangFn("rightUpperLeg", -0.006, 0, -0.01);
    hangFn("leftLowerLeg", -0.012, 0, 0);
    hangFn("rightLowerLeg", -0.008, 0, 0);
  }

  function restoreArmBindPose() {
    if (!restBones || !restBones.bindQ) return;
    ["leftUpperArm", "rightUpperArm", "leftLowerArm", "rightLowerArm", "leftHand", "rightHand"].forEach(
      function (k) {
        var n = restBones[k];
        var q = restBones.bindQ[k];
        if (n && q) n.quaternion.copy(q);
      }
    );
  }

  function ensureIkScratch() {
    var L = getLibs();
    if (!L || !L.THREE) return null;
    if (ikScratch) return ikScratch;
    var T = L.THREE;
    ikScratch = {
      shoulder: new T.Vector3(),
      elbow: new T.Vector3(),
      target: new T.Vector3(),
      toTarget: new T.Vector3(),
      forward: new T.Vector3(),
      bend: new T.Vector3(),
      pole: new T.Vector3(),
      side: new T.Vector3(),
      axis: new T.Vector3(),
      dir: new T.Vector3(),
      q: new T.Quaternion(),
      qParent: new T.Quaternion(),
      qInv: new T.Quaternion(),
    };
    return ikScratch;
  }

  /** Measure upper/lower length + child-aim axes at bind for two-bone IK. */
  function measureArmMeta(side) {
    var L = getLibs();
    if (!L || !L.THREE || !restBones) {
      armMeta[side] = null;
      return;
    }
    var THREE = L.THREE;
    var isLeft = side === "left";
    var upper = restBones[isLeft ? "leftUpperArm" : "rightUpperArm"];
    var lower = restBones[isLeft ? "leftLowerArm" : "rightLowerArm"];
    var hand = restBones[isLeft ? "leftHand" : "rightHand"];
    if (!upper || !lower || !hand) {
      armMeta[side] = null;
      return;
    }
    try {
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
      upper.updateWorldMatrix(true, true);
      lower.updateWorldMatrix(true, true);
      hand.updateWorldMatrix(true, false);
    } catch (_) {}

    var pU = new THREE.Vector3();
    var pL = new THREE.Vector3();
    var pH = new THREE.Vector3();
    try {
      upper.getWorldPosition(pU);
      lower.getWorldPosition(pL);
      hand.getWorldPosition(pH);
    } catch (_) {
      armMeta[side] = null;
      return;
    }

    var upperLen = pU.distanceTo(pL);
    var lowerLen = pL.distanceTo(pH);
    if (!(upperLen > 0.04)) upperLen = 0.28;
    if (!(lowerLen > 0.04)) lowerLen = 0.25;

    function localAxis(boneNode, childNode) {
      var bw = new THREE.Vector3();
      var cw = new THREE.Vector3();
      boneNode.getWorldPosition(bw);
      childNode.getWorldPosition(cw);
      var worldDir = cw.sub(bw);
      if (worldDir.lengthSq() < 1e-10) {
        return new THREE.Vector3(isLeft ? 1 : -1, 0, 0);
      }
      worldDir.normalize();
      var q = new THREE.Quaternion();
      boneNode.getWorldQuaternion(q);
      q.invert();
      return worldDir.applyQuaternion(q).normalize();
    }

    armMeta[side] = {
      upperLen: upperLen,
      lowerLen: lowerLen,
      upperAxis: localAxis(upper, lower),
      lowerAxis: localAxis(lower, hand),
      bindUpper: upper.quaternion.clone(),
      bindLower: lower.quaternion.clone(),
      bindHand: hand.quaternion.clone(),
    };
  }

  /** Hips-local position of a bone/node (or null). */
  function hipsLocalOf(node) {
    var L = getLibs();
    if (!L || !L.THREE || !restBones || !restBones.hips || !node) return null;
    try {
      restBones.hips.updateWorldMatrix(true, true);
      node.updateWorldMatrix(true, false);
      var w = new L.THREE.Vector3();
      node.getWorldPosition(w);
      var inv = new L.THREE.Matrix4().copy(restBones.hips.matrixWorld).invert();
      w.applyMatrix4(inv);
      return { x: w.x, y: w.y, z: w.z };
    } catch (_) {
      return null;
    }
  }

  /** Viewer / orbit camera in hips-local meters (where the user is looking from). */
  function cameraHipsLocal() {
    if (!camera) return null;
    var L = getLibs();
    if (!L || !L.THREE || !restBones || !restBones.hips) {
      return {
        x: camera.position.x,
        y: camera.position.y,
        z: camera.position.z,
      };
    }
    try {
      restBones.hips.updateWorldMatrix(true, false);
      var w = new L.THREE.Vector3(
        camera.position.x,
        camera.position.y,
        camera.position.z
      );
      var inv = new L.THREE.Matrix4().copy(restBones.hips.matrixWorld).invert();
      w.applyMatrix4(inv);
      return { x: w.x, y: w.y, z: w.z };
    } catch (_) {
      return {
        x: camera.position.x,
        y: camera.position.y,
        z: camera.position.z,
      };
    }
  }

  /** Measured full arm length (upper + lower), with sane fallback. */
  function armReachLen(side) {
    var meta = armMeta && armMeta[side];
    if (meta && meta.upperLen > 0 && meta.lowerLen > 0) {
      return meta.upperLen + meta.lowerLen;
    }
    return 0.55;
  }

  /**
   * Keep a controller target inside the arm workspace from the live shoulder.
   * maxFrac ~0.88 leaves elbow bend so IK doesn't lock straight.
   */
  function clampToArmReach(side, x, y, z, maxFrac) {
    maxFrac = typeof maxFrac === "number" ? maxFrac : 0.88;
    var isLeft = side === "left";
    var sh = isLeft ? vr.shoulderLeft : vr.shoulderRight;
    if (!sh) return { x: x, y: y, z: z };
    var reach = armReachLen(side);
    var rx = x - sh.x;
    var ry = y - sh.y;
    var rz = z - sh.z;
    var d = Math.sqrt(rx * rx + ry * ry + rz * rz) || 0;
    var maxUse = reach * maxFrac;
    if (d > maxUse && d > 1e-6) {
      var s = maxUse / d;
      return { x: sh.x + rx * s, y: sh.y + ry * s, z: sh.z + rz * s };
    }
    return { x: x, y: y, z: z };
  }

  /**
   * Rest wrist/head targets from LIVE hang-pose bones (hips-local).
   * Call only after soft A-pose eulers are applied so hands are down the sides,
   * not invented from shoulder + fraction (that drifted to shoulder height).
   * Bounds are relative to measured shoulders/reach (works for short VRMs).
   */
  function calibrateVrRestsFromBones() {
    var headL = hipsLocalOf(restBones && restBones.head) || {
      x: 0,
      y: 1.45,
      z: 0.05,
    };
    var lSh =
      hipsLocalOf(restBones && restBones.leftUpperArm) || {
        x: -0.16,
        y: 1.22,
        z: 0,
      };
    var rSh =
      hipsLocalOf(restBones && restBones.rightUpperArm) || {
        x: 0.16,
        y: 1.22,
        z: 0,
      };
    var leftL =
      hipsLocalOf(restBones && restBones.leftHand) || {
        x: -0.18,
        y: 0.72,
        z: 0.1,
      };
    var rightL =
      hipsLocalOf(restBones && restBones.rightHand) || {
        x: 0.18,
        y: 0.72,
        z: 0.1,
      };

    vr.shoulderLeft = { x: lSh.x, y: lSh.y, z: lSh.z };
    vr.shoulderRight = { x: rSh.x, y: rSh.y, z: rSh.z };

    var reachL = armReachLen("left");
    var reachR = armReachLen("right");
    var shY = Math.min(lSh.y, rSh.y);
    // Scale-relative hang band: below chest, can hang near hips on short models.
    var yCeil = shY - Math.min(0.12, Math.min(reachL, reachR) * 0.28);
    var yFloor = shY - Math.max(reachL, reachR) * 1.2;

    // Nudge slightly out/forward; clamp Y below chest so rest never looks like T/Y.
    leftL.x = Math.min(leftL.x, lSh.x - Math.min(0.06, reachL * 0.12));
    rightL.x = Math.max(rightL.x, rSh.x + Math.min(0.06, reachR * 0.12));
    leftL.z = Math.max(leftL.z, lSh.z + Math.min(0.04, reachL * 0.08));
    rightL.z = Math.max(rightL.z, rSh.z + Math.min(0.04, reachR * 0.08));
    leftL.y = clamp(leftL.y, yFloor, yCeil);
    rightL.y = clamp(rightL.y, yFloor, yCeil);
    // If measurement still looks shoulder-high (T-pose matrices), force soft hang.
    if (leftL.y > lSh.y - reachL * 0.35) {
      leftL.y = lSh.y - reachL * 0.72;
      leftL.x = lSh.x - reachL * 0.18;
      leftL.z = Math.max(lSh.z + reachL * 0.1, leftL.z);
    }
    if (rightL.y > rSh.y - reachR * 0.35) {
      rightL.y = rSh.y - reachR * 0.72;
      rightL.x = rSh.x + reachR * 0.18;
      rightL.z = Math.max(rSh.z + reachR * 0.1, rightL.z);
    }
    leftL.y = clamp(leftL.y, yFloor, yCeil);
    rightL.y = clamp(rightL.y, yFloor, yCeil);

    vr.restHead = headL;
    vr.restLeft = leftL;
    vr.restRight = rightL;
    vr.head.x = headL.x;
    vr.head.y = headL.y;
    vr.head.z = headL.z;
    // Snap free hands home only when not mid-gesture / locked.
    if (!vr.left.locked && !(activeGesture && activeGesture.kind)) {
      vr.left.x = leftL.x;
      vr.left.y = leftL.y;
      vr.left.z = leftL.z;
      vr.left.vx = vr.left.vy = vr.left.vz = 0;
    }
    if (!vr.right.locked && !(activeGesture && activeGesture.kind)) {
      vr.right.x = rightL.x;
      vr.right.y = rightL.y;
      vr.right.z = rightL.z;
      vr.right.vx = vr.right.vy = vr.right.vz = 0;
    }
  }

  /**
   * Wave / point peaks relative to this avatar + camera (viewer).
   * Offsets use measured arm reach so short VRMs stay inside the workspace.
   * Wave is shoulder-height + out, never straight overhead.
   */
  function gesturePeak(kind, side, inten) {
    inten = typeof inten === "number" ? inten : 1;
    var isLeft = side === "left";
    var sx = isLeft ? -1 : 1;
    var sh = isLeft ? vr.shoulderLeft : vr.shoulderRight;
    if (!sh) {
      sh = isLeft
        ? { x: -0.16, y: 1.22, z: 0 }
        : { x: 0.16, y: 1.22, z: 0 };
    }
    var headY = (vr.restHead && vr.restHead.y) || sh.y + 0.2;
    var reach = armReachLen(side);
    var cam = cameraHipsLocal();
    var peak = { x: sh.x, y: sh.y, z: sh.z };

    if (kind === "wave") {
      // Friendly wave: slightly above shoulder, out, toward viewer — not arm-up.
      peak.x = sh.x + sx * reach * (0.38 + 0.1 * inten);
      peak.y = sh.y + reach * (0.22 + 0.08 * inten);
      // Cap below head so short models don't invert min(sh+lift, head-0.18).
      peak.y = Math.min(peak.y, headY - reach * 0.08);
      peak.y = Math.max(peak.y, sh.y + reach * 0.06);
      peak.z = sh.z + reach * (0.42 + 0.12 * inten);
    } else if (kind === "point") {
      peak.x = sh.x + sx * reach * (0.18 + 0.06 * inten);
      peak.y = sh.y + reach * 0.02;
      peak.z = sh.z + reach * (0.62 + 0.12 * inten);
    } else if (kind === "cheer") {
      peak.x = sh.x + sx * reach * 0.28;
      peak.y = Math.min(headY + reach * 0.12, sh.y + reach * 0.72);
      peak.z = sh.z + reach * 0.22;
    } else {
      peak.x = sh.x + sx * reach * 0.28;
      peak.y = sh.y + reach * 0.2;
      peak.z = sh.z + reach * 0.28;
    }

    // Pull toward camera so wave/point face the viewer.
    if (cam) {
      var dx = cam.x - peak.x;
      var dz = cam.z - peak.z;
      var len = Math.sqrt(dx * dx + dz * dz) || 1;
      var pull = reach * (kind === "point" ? 0.22 : 0.18);
      peak.x += (dx / len) * pull * inten;
      peak.z += (dz / len) * Math.min(reach * 0.35, pull + reach * 0.08 * inten);
      // Mild height match toward camera eye level (not forced; never overhead).
      if (kind === "wave") {
        peak.y += clamp((cam.y - peak.y) * 0.06, -reach * 0.08, reach * 0.1);
        peak.y = Math.min(peak.y, headY - reach * 0.05);
      }
    }

    // Keep inside arm reach from shoulder (prevents locked-straight arm).
    return clampToArmReach(side, peak.x, peak.y, peak.z, 0.88);
  }

  /** Look toward the orbit camera (viewer). */
  function lookTowardCamera(holdSec) {
    var cam = cameraHipsLocal();
    var head = (vr && vr.restHead) || { x: 0, y: 1.4, z: 0 };
    if (!cam) {
      lookTarget.x = 0;
      lookTarget.y = 0;
      lookHoldUntil = idleTime + (holdSec || 2);
      return;
    }
    var dx = cam.x - head.x;
    var dy = cam.y - head.y;
    var dz = cam.z - head.z;
    var horiz = Math.sqrt(dx * dx + dz * dz) || 1;
    // Normalize into tool look space roughly -1..1 (viewer is usually in front).
    lookTarget.x = clamp(dx / Math.max(horiz, 0.4) * 0.55, -1, 1);
    lookTarget.y = clamp(dy / Math.max(horiz, 0.4) * 0.45, -1, 1);
    lookHoldUntil = idleTime + clamp(holdSec || 2.2, 0.4, 12);
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
    // All absolute targets must be scale-relative to this VRM (never human-meter hardcoded).
    if (!activeGesture) {
      if (state === STATE_THINKING) {
        // Chin / temple-near pose from measured head + shoulder, within arm reach.
        var shR = vr.shoulderRight || { x: 0.16, y: 0.3, z: 0 };
        var hy = (vr.restHead && vr.restHead.y) || shR.y + 0.12;
        var rReach = armReachLen("right");
        setHandTarget(
          "right",
          shR.x + rReach * 0.12,
          Math.min(hy - rReach * 0.15, shR.y + rReach * 0.35),
          shR.z + rReach * 0.35,
          2.0,
          true
        );
      } else if (state === STATE_LISTENING) {
        setHandTarget("left", vr.restLeft.x, vr.restLeft.y, vr.restLeft.z + 0.02, 0, false);
        setHandTarget("right", vr.restRight.x, vr.restRight.y, vr.restRight.z + 0.02, 0, false);
      } else if (state === STATE_SPEAKING) {
        // Tiny ready raise; gravity returns to hang rest.
        var sr = armReachLen("right");
        setHandTarget(
          "right",
          vr.restRight.x + sr * 0.03,
          vr.restRight.y + sr * 0.06,
          vr.restRight.z + sr * 0.06,
          0.6,
          false
        );
      }
    }
  }

  function setHandTarget(side, x, y, z, holdSec, locked) {
    var h = side === "left" ? vr.left : vr.right;
    if (!h) return;
    // Always clamp tool/gesture/state targets into the arm workspace.
    var c = clampToArmReach(side, x, y, z, locked ? 0.88 : 0.95);
    h.x = c.x;
    h.y = c.y;
    h.z = c.z;
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

  function easeInOut(t) {
    t = clamp(t, 0, 1);
    return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
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
      // Soft bounds relative to measured shoulders/reach (short + tall VRMs).
      var shL = vr.shoulderLeft || { x: -0.16, y: 0.3, z: 0 };
      var shR = vr.shoulderRight || { x: 0.16, y: 0.3, z: 0 };
      var shY = Math.min(shL.y, shR.y);
      var maxR = Math.max(armReachLen("left"), armReachLen("right"));
      h.x = clamp(h.x, -maxR * 1.55, maxR * 1.55);
      h.y = clamp(h.y, shY - maxR * 1.25, shY + maxR * 0.95);
      h.z = clamp(h.z, -maxR * 0.45, maxR * 1.35);
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
   * Aim a bone so its bind-time child axis points at a world-space target.
   * Used by two-bone arm IK (VRChat-style wrist controllers).
   */
  function aimBoneToward(boneNode, worldTarget, localAxis, S) {
    if (!boneNode || !boneNode.parent || !localAxis || !S) return;
    try {
      boneNode.parent.updateWorldMatrix(true, false);
      boneNode.updateWorldMatrix(true, false);
      boneNode.getWorldPosition(S.shoulder);
    } catch (_) {
      return;
    }
    S.dir.copy(worldTarget).sub(S.shoulder);
    if (S.dir.lengthSq() < 1e-12) return;
    S.dir.normalize();
    try {
      boneNode.parent.getWorldQuaternion(S.qParent);
    } catch (_) {
      return;
    }
    S.qInv.copy(S.qParent).invert();
    S.dir.applyQuaternion(S.qInv);
    if (S.dir.lengthSq() < 1e-12) return;
    S.dir.normalize();
    S.axis.copy(localAxis);
    if (S.axis.lengthSq() < 1e-12) return;
    S.axis.normalize();
    // Guard near-opposite axes (setFromUnitVectors can flip wildly).
    if (S.axis.dot(S.dir) < -0.999) {
      S.side.set(Math.abs(S.axis.x) < 0.9 ? 1 : 0, 1, 0).normalize();
      S.q.setFromAxisAngle(S.side.cross(S.axis).normalize(), Math.PI);
    } else {
      S.q.setFromUnitVectors(S.axis, S.dir);
    }
    boneNode.quaternion.copy(S.q);
  }

  /**
   * True when this hand should leave soft-hang eulers and use two-bone IK.
   * Idle / free hands near rest stay on reliable A-pose eulers (no T-pose).
   */
  function handNeedsIk(side) {
    var isLeft = side === "left";
    var h = isLeft ? vr.left : vr.right;
    var rest = isLeft ? vr.restLeft : vr.restRight;
    if (!h || !rest) return false;
    if (h.locked) return true;
    if (activeGesture && activeGesture.kind) {
      var gs = activeGesture.side || "right";
      if (gs === "both" || gs === side) {
        var k = activeGesture.kind;
        if (
          k === "wave" ||
          k === "point" ||
          k === "cheer" ||
          k === "clap" ||
          k === "shrug" ||
          k === "think" ||
          k === "hands_on_hips" ||
          k === "crossed_arms"
        ) {
          return true;
        }
      }
    }
    var lift = h.y - rest.y;
    var lateral = Math.hypot(h.x - rest.x, h.z - rest.z);
    return lift > 0.07 || lateral > 0.1;
  }

  /**
   * Soft hang eulers (+ optional raise blend). Base already includes A-pose;
   * raise cancels hang Z toward T and lifts the arm for simple non-IK motion.
   */
  function applySoftHangArm(side, raiseAmt) {
    var isLeft = side === "left";
    var sign = isLeft ? 1 : -1;
    var upperKey = isLeft ? "leftUpperArm" : "rightUpperArm";
    var lowerKey = isLeft ? "leftLowerArm" : "rightLowerArm";
    var handKey = isLeft ? "leftHand" : "rightHand";
    var raise = clamp(raiseAmt || 0, 0, 1.4);
    // Base Z hang is +1.26 left / -1.26 right; cancel with -sign * raise * ~1.2
    addEuler(
      restBones[upperKey],
      upperKey,
      -raise * 0.85,
      sign * raise * 0.12,
      -sign * raise * 1.18
    );
    addEuler(restBones[lowerKey], lowerKey, raise * 0.2, 0, -sign * raise * 0.05);
    addEuler(restBones[handKey], handKey, raise * 0.08, 0, 0);
  }

  /**
   * Two-bone IK: shoulder → elbow → wrist reaches virtual VR controller point.
   * Idle hands use soft A-pose eulers; raised/locked hands use bind-relative IK.
   */
  function applyHandIk(side) {
    var L = getLibs();
    var meta = armMeta[side];
    var S = ensureIkScratch();
    var isLeft = side === "left";
    var upperKey = isLeft ? "leftUpperArm" : "rightUpperArm";
    var lowerKey = isLeft ? "leftLowerArm" : "rightLowerArm";
    var handKey = isLeft ? "leftHand" : "rightHand";
    var h = isLeft ? vr.left : vr.right;
    var rest = isLeft ? vr.restLeft : vr.restRight;
    if (!restBones || !h) return;

    // Default path: soft A-pose hang (fixes shoulder-height T-pose on load).
    if (!handNeedsIk(side) || !L || !L.THREE || !meta || !S || !restBones.hips) {
      var restY = rest ? rest.y : 0.7;
      var raise = clamp((h.y - restY) / 0.45, 0, 1.3);
      applySoftHangArm(side, raise);
      return;
    }

    var upper = restBones[upperKey];
    var lower = restBones[lowerKey];
    var hand = restBones[handKey];
    if (!upper || !lower || !hand) {
      applySoftHangArm(side, 0);
      return;
    }

    // Start from bind so measured axes stay valid every frame.
    upper.quaternion.copy(meta.bindUpper);
    lower.quaternion.copy(meta.bindLower);
    hand.quaternion.copy(meta.bindHand);

    try {
      restBones.hips.updateWorldMatrix(true, true);
      upper.updateWorldMatrix(true, true);
      upper.getWorldPosition(S.shoulder);
    } catch (_) {
      return;
    }

    // Hips-local controller → world.
    S.target.set(h.x, h.y, h.z);
    try {
      S.target.applyMatrix4(restBones.hips.matrixWorld);
    } catch (_) {
      return;
    }

    // Speaking: free hands get tiny controller jitter (ragdoll noise).
    if (currentState === STATE_SPEAKING && !h.locked && mouthValue > 0.04) {
      var j = mouthValue * 0.012;
      S.target.x += Math.sin(idleTime * 2.4 + (isLeft ? 0 : 1.7)) * j;
      S.target.y += Math.abs(Math.sin(idleTime * 3.1)) * j * 0.6;
    }

    S.toTarget.copy(S.target).sub(S.shoulder);
    var dist = S.toTarget.length();
    var a = meta.upperLen;
    var b = meta.lowerLen;
    var maxReach = (a + b) * 0.995;
    var minReach = Math.abs(a - b) + 0.02;
    if (dist < 1e-5) return;
    if (dist > maxReach) {
      S.toTarget.multiplyScalar(maxReach / dist);
      dist = maxReach;
      S.target.copy(S.shoulder).add(S.toTarget);
    } else if (dist < minReach) {
      S.toTarget.multiplyScalar(minReach / dist);
      dist = minReach;
      S.target.copy(S.shoulder).add(S.toTarget);
    }

    // Law of cosines — angle between upper bone and shoulder→target.
    var cosUpper = (a * a + dist * dist - b * b) / (2 * a * dist);
    cosUpper = clamp(cosUpper, -1, 1);
    var upperAngle = Math.acos(cosUpper);

    S.forward.copy(S.toTarget).multiplyScalar(1 / dist);

    // Elbow pole: hang = slightly behind + out; raised = more out (readable bend,
    // not elbows jammed forward or locked straight).
    var raiseAmt = clamp((h.y - (rest ? rest.y : 0.7)) / 0.45, 0, 1);
    S.pole.set(0, raiseAmt * 0.2, -1 + raiseAmt * 0.55);
    try {
      S.pole.transformDirection(restBones.hips.matrixWorld);
    } catch (_) {}
    S.side.set(isLeft ? -1 : 1, 0, 0);
    try {
      S.side.transformDirection(restBones.hips.matrixWorld);
    } catch (_) {}
    S.pole.addScaledVector(S.side, 0.55 + raiseAmt * 0.4);
    // Project pole onto plane ⊥ forward → bend direction.
    S.bend.copy(S.pole).addScaledVector(S.forward, -S.pole.dot(S.forward));
    if (S.bend.lengthSq() < 1e-8) {
      S.bend.set(0, 1, 0).addScaledVector(S.forward, -S.forward.y);
    }
    if (S.bend.lengthSq() < 1e-8) S.bend.set(isLeft ? -1 : 1, 0, 0);
    S.bend.normalize();

    S.elbow
      .copy(S.shoulder)
      .addScaledVector(S.forward, Math.cos(upperAngle) * a)
      .addScaledVector(S.bend, Math.sin(upperAngle) * a);

    aimBoneToward(upper, S.elbow, meta.upperAxis, S);
    try {
      upper.updateWorldMatrix(true, false);
    } catch (_) {}
    aimBoneToward(lower, S.target, meta.lowerAxis, S);
    try {
      lower.updateWorldMatrix(true, false);
    } catch (_) {}

    // Wrist: face viewer during wave; otherwise light follow of controller motion.
    hand.quaternion.copy(meta.bindHand);
    var lat = h.x - rest.x;
    var lift = h.y - rest.y;
    var waving =
      activeGesture &&
      (activeGesture.kind === "wave" || activeGesture.kind === "point") &&
      h.locked &&
      (activeGesture.side === side ||
        activeGesture.side === "both" ||
        (!activeGesture.side && side === "right"));
    if (waving && activeGesture.kind === "wave") {
      // Palm toward camera, fingers up-ish — classic "hi" wave.
      hand.rotateX(clamp(-0.55 - lift * 0.1, -0.95, -0.2));
      hand.rotateZ(isLeft ? 0.55 : -0.55);
      var camH = cameraHipsLocal();
      if (camH) {
        var yaw = Math.atan2(camH.x - h.x, Math.max(0.05, camH.z - h.z));
        hand.rotateY(clamp(yaw * 0.55 + lat * 1.8, -0.95, 0.95));
      } else {
        hand.rotateY(clamp(lat * 2.2 * (isLeft ? 1 : -1), -0.9, 0.9));
      }
      // Flap follow from lateral oscillator on controller X.
      hand.rotateZ(clamp(lat * 2.8, -0.55, 0.55));
    } else if (waving && activeGesture.kind === "point") {
      hand.rotateX(clamp(-0.15, -0.4, 0.2));
      hand.rotateY(clamp(lat * (isLeft ? 0.4 : -0.4), -0.4, 0.4));
      hand.rotateZ(isLeft ? 0.1 : -0.1);
    } else {
      hand.rotateX(clamp(lift * 0.12, -0.3, 0.4));
      hand.rotateY(clamp(lat * (isLeft ? 0.7 : -0.7), -0.55, 0.55));
      hand.rotateZ(clamp(lat * 0.28 + (isLeft ? 0.06 : -0.06), -0.4, 0.4));
    }
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
      // Raise to shoulder-high (not overhead), toward camera, flap palm at viewer.
      var side = g.side === "left" ? "left" : "right";
      var rest = side === "left" ? vr.restLeft : vr.restRight;
      var peak = gesturePeak("wave", side, inten);
      var peakX = peak.x;
      var peakY = peak.y;
      var peakZ = peak.z;
      if (u < 0.14) {
        lookTowardCamera(g.duration);
        var k0 = easeInOut(u / 0.14);
        setHandTarget(
          side,
          rest.x + (peakX - rest.x) * k0,
          rest.y + (peakY - rest.y) * k0,
          rest.z + (peakZ - rest.z) * k0,
          0,
          true
        );
      } else if (u < 0.82) {
        // Lateral flap in the plane facing the camera (not vertical arm thrash).
        var camW = cameraHipsLocal();
        var waveReach = armReachLen(side);
        var flap =
          Math.sin((g.t - g.duration * 0.14) * 11.5) * waveReach * 0.28 * inten;
        var fx = peakX + flap;
        var fz = peakZ;
        if (camW) {
          // Oscillate along camera-facing tangent (side-to-side from viewer).
          var tdx = camW.x - peakX;
          var tdz = camW.z - peakZ;
          var tlen = Math.sqrt(tdx * tdx + tdz * tdz) || 1;
          // Perp in XZ: (-dz, dx)
          fx = peakX + (-tdz / tlen) * flap;
          fz = peakZ + (tdx / tlen) * flap;
        }
        setHandTarget(side, fx, peakY + Math.abs(flap) * 0.08, fz, 0, true);
      } else {
        var k1 = easeInOut((u - 0.82) / 0.18);
        setHandTarget(
          side,
          peakX + (rest.x - peakX) * k1,
          peakY + (rest.y - peakY) * k1,
          peakZ + (rest.z - peakZ) * k1,
          0,
          true
        );
      }
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
      lookTarget.x = Math.sin(g.t * 9) * 0.85 * inten * (1 - u * 0.5);
      lookTarget.y = 0.02;
      lookHoldUntil = idleTime + 0.15;
      if (g.t > g.duration) activeGesture = null;
      return false;
    }
    if (g.kind === "point") {
      // Controller pushed toward camera / forward at shoulder height.
      var ps = g.side === "left" ? "left" : "right";
      var pSign = ps === "left" ? -1 : 1;
      var pp = gesturePeak("point", ps, inten);
      setHandTarget(ps, pp.x, pp.y, pp.z, 0, true);
      lookTowardCamera(0.35);
      lookTarget.x = clamp(lookTarget.x + pSign * 0.2 * inten, -1, 1);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "shrug") {
      var ls = vr.shoulderLeft || { x: -0.16, y: 1.2, z: 0 };
      var rs = vr.shoulderRight || { x: 0.16, y: 1.2, z: 0 };
      setHandTarget("left", ls.x - 0.1, ls.y - 0.08 * (1 - 0.3 * inten), ls.z + 0.1, 0, true);
      setHandTarget("right", rs.x + 0.1, rs.y - 0.08 * (1 - 0.3 * inten), rs.z + 0.1, 0, true);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "think") {
      // Right controller to chin/temple from live head + shoulder (scale-relative).
      var shT = vr.shoulderRight || { x: 0.16, y: 0.3, z: 0 };
      var hy = (vr.restHead && vr.restHead.y) || shT.y + 0.12;
      var rT = armReachLen("right");
      setHandTarget(
        "right",
        shT.x + rT * 0.1,
        Math.min(hy - rT * 0.12, shT.y + rT * 0.32),
        shT.z + rT * 0.38,
        0,
        true
      );
      lookTarget.x = 0.28;
      lookTarget.y = 0.14;
      lookHoldUntil = idleTime + 0.25;
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "clap") {
      var c = Math.abs(Math.sin(g.t * 12));
      var shCl = vr.shoulderLeft || { x: -0.16, y: 0.3, z: 0 };
      var rCl = Math.min(armReachLen("left"), armReachLen("right"));
      var cy = shCl.y - rCl * 0.12;
      setHandTarget("left", -rCl * (0.18 + c * 0.06), cy, shCl.z + rCl * 0.55, 0, true);
      setHandTarget("right", rCl * (0.18 + c * 0.06), cy, shCl.z + rCl * 0.55, 0, true);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "cheer") {
      var cl = gesturePeak("cheer", "left", inten);
      var cr = gesturePeak("cheer", "right", inten);
      var lift = ease * armReachLen("right") * 0.12 * inten;
      setHandTarget("left", cl.x, cl.y + lift, cl.z, 0, true);
      setHandTarget("right", cr.x, cr.y + lift, cr.z, 0, true);
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
      var hl = vr.restLeft || { x: -0.18, y: 0.75, z: 0.1 };
      var hr = vr.restRight || { x: 0.18, y: 0.75, z: 0.1 };
      setHandTarget("left", hl.x - 0.04, hl.y + 0.06, hl.z + 0.02, 0, true);
      setHandTarget("right", hr.x + 0.04, hr.y + 0.06, hr.z + 0.02, 0, true);
      if (g.t > g.duration) activeGesture = null;
      return true;
    }
    if (g.kind === "crossed_arms") {
      var shCx = vr.shoulderLeft || { x: -0.16, y: 0.3, z: 0 };
      var rCx = Math.min(armReachLen("left"), armReachLen("right"));
      var csy = shCx.y - rCx * 0.28;
      setHandTarget("left", rCx * 0.18, csy, shCx.z + rCx * 0.32, 0, true);
      setHandTarget(
        "right",
        -rCx * 0.18,
        csy - rCx * 0.04,
        shCx.z + rCx * 0.28,
        0,
        true
      );
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
    lookTarget.x = 0;
    lookTarget.y = 0;
    lookHoldUntil = 0;
  }

  /**
   * VRChat-style body: torso first, then ragdoll hand points → two-bone arm IK.
   * Virtual wrist controllers are the source of truth for arm pose.
   */
  function applyBodyMotion(dt) {
    if (!vrm || !restBones || !restBones.base) return;
    // After load: remeasure bind arm meta, re-apply hang, sample rest wrists.
    if (restRecalibLeft > 0) {
      restRecalibLeft -= 1;
      if (restRecalibLeft === 0 && !activeGesture) {
        try {
          restoreArmBindPose();
          if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
          measureArmMeta("left");
          measureArmMeta("right");
          applySoftHangEulers();
          if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
          calibrateVrRestsFromBones();
        } catch (_) {}
      }
    }
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

    // 1) Pose torso / legs / head first so shoulders are in the right place.
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

    // Virtual HMD look: tool look_at x=-1..1 must read as a clear head turn
    // (old gain 0.18 rad total ≈ 10° — invisible). Neck+head stack ~±45° at full.
    var lookYaw = lookSmooth.x * 0.55;
    var lookPitch = -lookSmooth.y * 0.28;
    var lookRoll = lookSmooth.x * 0.06;
    var headDx = (vr.head.x - vr.restHead.x) * 0.25;
    addEuler(
      restBones.neck,
      "neck",
      nodAmt * 0.4 + breath * 0.04 + lookPitch * 0.35,
      lookYaw * 0.48 + sway * 0.05 + headDx * 0.3,
      lookRoll * 0.35 + shoulder * 0.04
    );
    addEuler(
      restBones.head,
      "head",
      nodAmt * 0.6 + lookPitch * 0.55,
      lookYaw * 0.62 + sway * 0.04 + headDx * 0.4,
      lookRoll * 0.45
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

    // 2) Arms: two-bone IK to ragdolled VR wrist controllers (after shoulders moved).
    applyHandIk("left");
    applyHandIk("right");
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
    // Direction aliases (tools / natural language helpers).
    if (typeof o.direction === "string") {
      var d = String(o.direction).toLowerCase().replace(/[\s-]+/g, "_");
      if (d === "left") {
        o.x = -1;
        o.y = typeof o.y === "number" ? o.y : 0;
      } else if (d === "right") {
        o.x = 1;
        o.y = typeof o.y === "number" ? o.y : 0;
      } else if (d === "up") {
        o.x = typeof o.x === "number" ? o.x : 0;
        o.y = 1;
      } else if (d === "down") {
        o.x = typeof o.x === "number" ? o.x : 0;
        o.y = -1;
      } else if (d === "forward" || d === "center") {
        o.x = 0;
        o.y = 0;
      } else if (d === "camera" || d === "viewer" || d === "user") {
        lookTowardCamera(
          typeof o.hold_sec === "number"
            ? o.hold_sec
            : typeof o.holdSec === "number"
              ? o.holdSec
              : 5
        );
        return true;
      }
    }
    if (typeof o.x === "number") lookTarget.x = clamp(o.x, -1, 1);
    if (typeof o.y === "number") lookTarget.y = clamp(o.y, -1, 1);
    var hold =
      typeof o.hold_sec === "number"
        ? o.hold_sec
        : typeof o.holdSec === "number"
          ? o.holdSec
          : 5;
    lookHoldUntil = idleTime + clamp(hold, 0.4, 30);
    // Nudge virtual HMD slightly in look direction (VRChat-style headset).
    if (vr && vr.restHead) {
      vr.head.x = vr.restHead.x + lookTarget.x * 0.08;
      vr.head.y = vr.restHead.y + lookTarget.y * 0.04;
      vr.head.z = vr.restHead.z;
      vr.head.locked = true;
    }
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
    // Tool / gesture look owns the HMD until hold expires.
    if (idleTime < lookHoldUntil) return;
    if (vr && vr.head && vr.head.locked && idleTime >= lookHoldUntil) {
      vr.head.locked = false;
    }
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
        if (idleTime >= lookHoldUntil) {
          lookTarget.x = 0;
          lookTarget.y = 0.05;
          lookWanderT = 0.6;
        }
        break;
      case STATE_THINKING:
        setExpression("relaxed", 0.15);
        if (idleTime >= lookHoldUntil) {
          lookTarget.x = 0.32;
          lookTarget.y = 0.18;
          lookWanderT = 0.8;
        }
        break;
      case STATE_SPEAKING:
        setExpression("happy", 0.25);
        if (idleTime >= lookHoldUntil) {
          lookTarget.x = 0;
          lookTarget.y = 0.02;
          lookWanderT = 1.2;
        }
        gestureBurstT = 0.3;
        break;
      case STATE_IDLE:
      default:
        setExpression("relaxed", 0.12);
        if (idleTime >= lookHoldUntil) {
          lookTarget.x = 0;
          lookTarget.y = 0;
          lookWanderT = 1.5 + Math.random();
        }
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
    canvas = document.getElementById("vrm-canvas");
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
      // Only a clean single-finger tap counts toward joint pick / double-tap reset.
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
      // Debug: tap a joint/controller sphere → rename dialog (not camera reset).
      if (debugSkeletonOn) {
        try {
          var hitKey = pickJointAt(ev.clientX || 0, ev.clientY || 0);
          if (hitKey) {
            selectJoint(hitKey);
            lastTap = 0;
            return;
          }
        } catch (_) {}
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
                camera.position.x + lookSmooth.x * 0.65,
                camera.position.y + lookSmooth.y * 0.28,
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
        if (debugSkeletonOn) {
          updateDebugVisuals();
        }
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
    if (debugSkeletonOn) {
      rebuildDebugVisuals();
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

  /**
   * Full environment + avatar snapshot for AI tool calling.
   * Coordinate space for VR controllers: hips-local meters
   *   x = right+, y = up+, z = forward+ (toward camera / face-out).
   * Use rest.* as the hang baseline; set_hands targets should be absolute
   * points in the same space so two-bone IK can reach them.
   */
  function exportBodyState() {
    function vec3(o) {
      if (!o) return null;
      return {
        x: round3(o.x),
        y: round3(o.y),
        z: round3(o.z),
      };
    }
    function round3(n) {
      return typeof n === "number" && isFinite(n) ? Math.round(n * 1000) / 1000 : 0;
    }
    function handSnap(h) {
      if (!h) return null;
      return {
        x: round3(h.x),
        y: round3(h.y),
        z: round3(h.z),
        locked: !!h.locked,
        vx: round3(h.vx || 0),
        vy: round3(h.vy || 0),
        vz: round3(h.vz || 0),
        hold_remaining: Math.max(0, round3((h.holdUntil || 0) - idleTime)),
      };
    }
    function boneLocal(node) {
      var p = hipsLocalOf(node);
      return p ? { x: round3(p.x), y: round3(p.y), z: round3(p.z) } : null;
    }
    function boneWorld(node) {
      if (!node) return null;
      try {
        node.updateWorldMatrix(true, false);
        var L = getLibs();
        if (!L || !L.THREE) return null;
        var w = new L.THREE.Vector3();
        node.getWorldPosition(w);
        return { x: round3(w.x), y: round3(w.y), z: round3(w.z) };
      } catch (_) {
        return null;
      }
    }
    function boneEuler(node) {
      if (!node || !node.rotation) return null;
      return {
        x: round3(node.rotation.x),
        y: round3(node.rotation.y),
        z: round3(node.rotation.z),
      };
    }
    function jointSnap(key) {
      if (!restBones || !restBones[key]) return null;
      return {
        id: key,
        name: jointDisplayName(key),
        default_name: defaultJointLabel(key),
        custom: !!(jointLabels[key] && String(jointLabels[key]).trim()),
        local: boneLocal(restBones[key]),
        world: boneWorld(restBones[key]),
        euler: boneEuler(restBones[key]),
      };
    }
    function armReach(side) {
      var meta = armMeta && armMeta[side];
      if (!meta) return null;
      var maxR = (meta.upperLen || 0) + (meta.lowerLen || 0);
      return {
        upper_len: round3(meta.upperLen || 0),
        lower_len: round3(meta.lowerLen || 0),
        max_reach: round3(maxR),
      };
    }

    var boneKeys = JOINT_KEYS;
    var bones = null;
    if (restBones) {
      bones = {};
      for (var bi = 0; bi < boneKeys.length; bi++) {
        var bk = boneKeys[bi];
        if (restBones[bk]) bones[bk] = jointSnap(bk);
      }
      if (bones.hips && !bones.hips.local) {
        bones.hips.local = { x: 0, y: 0, z: 0 };
      }
    }

    // User-facing names → live pose (AI can ask “where is my left wand?”).
    var namedJoints = {};
    function putNamed(entry) {
      if (!entry || !entry.name) return;
      namedJoints[entry.name] = {
        id: entry.id,
        local: entry.local,
        world: entry.world,
        custom: !!entry.custom,
      };
    }
    if (bones) {
      for (var nk in bones) {
        if (Object.prototype.hasOwnProperty.call(bones, nk)) putNamed(bones[nk]);
      }
    }
    putNamed(jointWorldLocal("vrLeft"));
    putNamed(jointWorldLocal("vrRight"));
    putNamed(jointWorldLocal("vrHead"));

    var labelMap = getJointLabels();

    // Live IK joint chain (world samples already in bones; expose chain lengths too).
    var chains = {
      left_arm: {
        shoulder: bones && bones.leftUpperArm ? bones.leftUpperArm.local : vec3(vr.shoulderLeft),
        elbow: bones && bones.leftLowerArm ? bones.leftLowerArm.local : null,
        wrist: bones && bones.leftHand ? bones.leftHand.local : handSnap(vr.left),
        controller: handSnap(vr.left),
        rest: vec3(vr.restLeft),
        reach: armReach("left"),
      },
      right_arm: {
        shoulder: bones && bones.rightUpperArm ? bones.rightUpperArm.local : vec3(vr.shoulderRight),
        elbow: bones && bones.rightLowerArm ? bones.rightLowerArm.local : null,
        wrist: bones && bones.rightHand ? bones.rightHand.local : handSnap(vr.right),
        controller: handSnap(vr.right),
        rest: vec3(vr.restRight),
        reach: armReach("right"),
      },
    };

    var gesture = null;
    if (activeGesture) {
      gesture = {
        kind: activeGesture.kind,
        t: round3(activeGesture.t || 0),
        duration: round3(activeGesture.duration || 0),
        side: activeGesture.side || "both",
        intensity: round3(activeGesture.intensity || 1),
        progress: round3(
          activeGesture.duration > 0
            ? Math.min(1, (activeGesture.t || 0) / activeGesture.duration)
            : 1
        ),
      };
    }

    var floorY = floorMesh ? round3(floorMesh.position.y) : 0;
    var height = null;
    if (bones && bones.head && bones.head.local) {
      height = bones.head.local.y;
    }

    var camLocal = cameraHipsLocal();
    var camOrbit = captureOrbit();
    var headLocal =
      (bones && bones.head && bones.head.local) || vec3(vr.restHead) || { x: 0, y: 1.4, z: 0 };
    var camRel = null;
    if (camLocal && headLocal) {
      var cdx = camLocal.x - headLocal.x;
      var cdy = camLocal.y - headLocal.y;
      var cdz = camLocal.z - headLocal.z;
      var ch = Math.sqrt(cdx * cdx + cdz * cdz) || 1;
      camRel = {
        from_head: {
          x: round3(cdx),
          y: round3(cdy),
          z: round3(cdz),
        },
        distance: round3(Math.sqrt(cdx * cdx + cdy * cdy + cdz * cdz)),
        // Suggested look_at toward viewer (approx).
        look_toward_camera: {
          x: round3(clamp(cdx / Math.max(ch, 0.4) * 0.55, -1, 1)),
          y: round3(clamp(cdy / Math.max(ch, 0.4) * 0.45, -1, 1)),
        },
      };
    }

    var waveR = gesturePeak("wave", "right", 1);
    var waveL = gesturePeak("wave", "left", 1);
    var pointR = gesturePeak("point", "right", 1);

    return {
      ok: true,
      loaded: !!(vrm && !usingFallback),
      fallback: !!usingFallback,
      space: "hips_local",
      unit: "meters_avatar_scale",
      axes: { x: "right+", y: "up+", z: "forward+" },
      notes:
        "Soft hang rest is measured from this VRM's shoulders + arm lengths on load. " +
        "Wave/point peaks face the camera (viewer). Prefer body_gesture for named moves; " +
        "use joints/chains + camera for custom set_hands. " +
        "User-renamed joints live in joint_labels + named_joints (name → id/local/world). " +
        "Each bone has id + name (custom or default humanoid label).",
      state: currentState,
      t: round3(idleTime),
      look: {
        x: round3(lookTarget.x),
        y: round3(lookTarget.y),
        hold_remaining: Math.max(0, round3(lookHoldUntil - idleTime)),
      },
      gesture: gesture,
      vr: {
        head: {
          x: round3(vr.head.x),
          y: round3(vr.head.y),
          z: round3(vr.head.z),
          locked: !!vr.head.locked,
          name: jointDisplayName("vrHead"),
        },
        left: Object.assign({}, handSnap(vr.left) || {}, {
          name: jointDisplayName("vrLeft"),
        }),
        right: Object.assign({}, handSnap(vr.right) || {}, {
          name: jointDisplayName("vrRight"),
        }),
        rest: {
          head: vec3(vr.restHead),
          left: vec3(vr.restLeft),
          right: vec3(vr.restRight),
        },
        shoulders: {
          left: vec3(vr.shoulderLeft),
          right: vec3(vr.shoulderRight),
        },
        physics: {
          gravity: vr.gravity,
          spring: vr.spring,
          damp: vr.damp,
        },
      },
      bones: bones,
      joint_labels: labelMap,
      named_joints: namedJoints,
      joints: chains,
      arm_reach: {
        left: armReach("left"),
        right: armReach("right"),
      },
      environment: {
        floor_y: floorY,
        approx_avatar_height: height,
        camera_world: camOrbit,
        camera_hips_local: camLocal
          ? { x: round3(camLocal.x), y: round3(camLocal.y), z: round3(camLocal.z) }
          : null,
        camera_relative: camRel,
        viewer: "orbit camera = user / phone screen viewpoint",
      },
      control_schema: {
        set_hands: {
          description:
            "Place wrist controller points in hips_local meters; arms two-bone-IK to them. " +
            "Stay within arm_reach.max_reach of the shoulder or the arm locks straight.",
          fields: {
            left: "{x,y,z}",
            right: "{x,y,z}",
            hold_sec: "seconds locked before gravity returns hands to rest",
          },
          hang_rest: {
            left: vec3(vr.restLeft),
            right: vec3(vr.restRight),
          },
          examples: {
            wave_right: { right: vec3(waveR), hold_sec: 1.2 },
            wave_left: { left: vec3(waveL), hold_sec: 1.2 },
            point_toward_camera_right: { right: vec3(pointR), hold_sec: 1.5 },
            hang_rest_both: {
              left: vec3(vr.restLeft),
              right: vec3(vr.restRight),
              hold_sec: 0.2,
            },
          },
        },
        look_at: {
          description:
            "x=-1 left .. 1 right; y=-1 down .. 1 up; direction=camera aims at viewer",
          current: { x: round3(lookTarget.x), y: round3(lookTarget.y) },
          toward_camera:
            camRel && camRel.look_toward_camera ? camRel.look_toward_camera : { x: 0, y: 0 },
        },
        body_gesture: {
          description:
            "Named full moves (wave, nod, point, shrug, …). Wave faces camera automatically.",
        },
      },
    };
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
    exportBodyState: exportBodyState,
    setDebugSkeleton: setDebugSkeleton,
    getDebugSkeleton: function () {
      return !!debugSkeletonOn;
    },
    setJointLabel: setJointLabel,
    setJointLabels: setJointLabels,
    getJointLabels: getJointLabels,
    selectJoint: selectJoint,
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
      loadJointLabelsFromHost();
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
