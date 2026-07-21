/**
 * Companion VRM stage — offline Three.js + @pixiv/three-vrm + three-vrm-animation.
 * Host bridge: window.GrokifyCompanion.{onReady,onModelLoaded,onError,onDebugLog,onJointPicked,openVrm,readVrmBase64,closeVrm,listVrmaClips}
 * Stage API:   window.CompanionStage.{loadModel,setState,setMouth,playMotion,
 *              playGesture,playTemplate,playVrma,stopVrma,listVrma,exportMotionLibrary,setHands,setLook,
 *              playAiMotion,resetBody,exportBodyState,
 *              setDebugSkeleton,setJointLabel,setJointLabels,getJointLabels,selectJoint}
 *
 * Motion layers (priority):
 *  1) VRMA clips — portable humanoid animations (any VRM), via AnimationMixer
 *  2) Joint-XYZ templates / scripted gestures — wrist IK rebuilt per avatar
 *  3) Soft hang idle + spring VR controllers
 *
 * Android WebView cannot reliably fetch() file:// VRMs ("Failed to fetch").
 * Bytes are streamed from Kotlin via openVrm/readVrmBase64, then GLTFLoader.parse.
 * VRMA uses path alias anim:<id> through the same bridge.
 *
 * Canvas gestures are orbit only (rotate / pan / pinch-zoom). Chat & voice stay on host UI buttons.
 *
 * Vendored libs: window.CompanionVrmLibs = {
 *   THREE, GLTFLoader, OrbitControls, VRMLoaderPlugin, VRMUtils, VRMExpressionPresetName,
 *   VRMAnimationLoaderPlugin, createVRMAnimationClip
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
  /**
   * VRMA playback state. When active, bone IK/hang is suspended so the
   * AnimationMixer fully owns humanoid joints (works on any VRM).
   */
  var vrmaMixer = null;
  var vrmaAction = null;
  var vrmaClipId = null;
  var vrmaUntil = 0;
  var vrmaLoop = false;
  var vrmaRawCache = {};
  var vrmaClipCache = {};
  var vrmaLoadGen = 0;
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
    try {
      destroyVrmaState();
    } catch (_) {}
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
   * Soft hang rest for the whole body. VRM normalized bones start in T-pose.
   *
   * Idle arms prefer two-bone IK to *geometric* hang wrist targets (works on
   * any VRM axis convention). Soft-hang eulers are auto-solved per avatar as
   * a no-meta fallback and to seed base torso/leg pose.
   *
   * Hardcoded Z≈±1.26 was too shallow on many models → shoulder-high "Y pose"
   * at load and again after VRMA handoff.
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
      if (k === "bindQ" || k === "base" || k === "hangDeltas" || k === "bindEuler") return;
      var n = restBones[k];
      if (!n) return;
      if (n.quaternion) restBones.bindQ[k] = n.quaternion.clone();
      if (n.rotation) bind[k] = { x: n.rotation.x, y: n.rotation.y, z: n.rotation.z };
    });
    restBones.bindEuler = bind;

    // Arm lengths / local axes from BIND (straight T-pose reach) for IK.
    try {
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
    } catch (_) {}
    measureArmMeta("left");
    measureArmMeta("right");

    // Per-avatar hang deltas (which local axis drops the hand) + apply.
    restBones.hangDeltas = solveHangDeltas();
    applySoftHangEulers();

    // Base eulers = soft hang (torso / idle fallback uses addEuler from these).
    restBones.base = {};
    Object.keys(restBones).forEach(function (k) {
      if (
        k === "base" ||
        k === "bindQ" ||
        k === "bindEuler" ||
        k === "hangDeltas"
      ) {
        return;
      }
      var n = restBones[k];
      if (!n || !n.rotation) return;
      restBones.base[k] = {
        x: n.rotation.x,
        y: n.rotation.y,
        z: n.rotation.z,
      };
    });

    // Geometric hang wrists (not "whatever the euler left us at") then snap.
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
   * Default soft-hang deltas from T-bind (full-ish hang, not shallow A/Y).
   * Z≈±1.52 (~87°) drops arms to the sides; shallow ~1.0 looks like Y-pose.
   */
  function defaultHangDeltas() {
    return {
      leftUpperArm: [0.1, 0.06, 1.52],
      rightUpperArm: [0.1, -0.06, -1.52],
      leftLowerArm: [0.42, 0.05, 0.1],
      rightLowerArm: [0.42, -0.05, -0.1],
      leftHand: [0.08, 0.03, 0.06],
      rightHand: [0.08, -0.03, -0.06],
      hips: [0.01, 0.015, 0.008],
      spine: [0.018, -0.01, -0.006],
      chest: [0.012, 0.005, 0],
      neck: [0, 0, 0],
      head: [0, 0, 0],
      leftUpperLeg: [0.01, 0, 0.012],
      rightUpperLeg: [-0.006, 0, -0.01],
      leftLowerLeg: [-0.012, 0, 0],
      rightLowerLeg: [-0.008, 0, 0],
    };
  }

  /**
   * Probe which local upper-arm axis+sign drops the hand most from bind T-pose.
   * Avoids fixed Z± hang that becomes a Y-pose on nonstandard normalized bones.
   */
  function solveHangDeltas() {
    var deltas = defaultHangDeltas();
    if (!restBones || !restBones.bindEuler) return deltas;

    function trialUpper(side, axis, sign, amount) {
      var upperKey = side === "left" ? "leftUpperArm" : "rightUpperArm";
      var lowerKey = side === "left" ? "leftLowerArm" : "rightLowerArm";
      var handKey = side === "left" ? "leftHand" : "rightHand";
      var upper = restBones[upperKey];
      var lower = restBones[lowerKey];
      var hand = restBones[handKey];
      var bU = restBones.bindEuler[upperKey];
      var bL = restBones.bindEuler[lowerKey];
      var bH = restBones.bindEuler[handKey];
      if (!upper || !hand || !bU) return null;
      // Restore bind for this arm.
      if (restBones.bindQ[upperKey]) upper.quaternion.copy(restBones.bindQ[upperKey]);
      if (lower && restBones.bindQ[lowerKey]) lower.quaternion.copy(restBones.bindQ[lowerKey]);
      if (hand && restBones.bindQ[handKey]) hand.quaternion.copy(restBones.bindQ[handKey]);
      var dx = axis === "x" ? sign * amount : 0;
      var dy = axis === "y" ? sign * amount : 0;
      var dz = axis === "z" ? sign * amount : 0;
      setEuler(upper, bU.x + dx, bU.y + dy, bU.z + dz);
      // Mild elbow bend so the trial isn't a locked straight stick.
      if (lower && bL) {
        setEuler(
          lower,
          bL.x + 0.35,
          bL.y + (side === "left" ? 0.04 : -0.04),
          bL.z + (side === "left" ? 0.08 : -0.08)
        );
      }
      if (hand && bH) {
        setEuler(hand, bH.x + 0.06, bH.y, bH.z);
      }
      try {
        if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
        upper.updateWorldMatrix(true, true);
        if (lower) lower.updateWorldMatrix(true, true);
        hand.updateWorldMatrix(true, false);
      } catch (_) {}
      return hipsLocalOf(hand);
    }

    function solveSide(side) {
      var best = null;
      var axes = ["z", "x", "y"];
      var signs = [1, -1];
      var amounts = [1.52, 1.35, 1.15];
      var i, j, k, pos, drop, score;
      var sh =
        hipsLocalOf(
          restBones[side === "left" ? "leftUpperArm" : "rightUpperArm"]
        ) || { x: 0, y: 1.2, z: 0 };
      // Also score lateral separation (arms should hang slightly out, not across body).
      for (i = 0; i < axes.length; i++) {
        for (j = 0; j < signs.length; j++) {
          for (k = 0; k < amounts.length; k++) {
            pos = trialUpper(side, axes[i], signs[j], amounts[k]);
            if (!pos) continue;
            drop = sh.y - pos.y;
            // Prefer lowest hand; small bonus for hanging slightly outward.
            var out =
              side === "left" ? sh.x - pos.x : pos.x - sh.x;
            score = drop + Math.max(0, Math.min(out, 0.12)) * 0.35;
            if (!best || score > best.score) {
              best = {
                score: score,
                drop: drop,
                axis: axes[i],
                sign: signs[j],
                amount: amounts[k],
                pos: pos,
              };
            }
          }
        }
      }
      // Restore arm bind after probing.
      restoreArmBindPose();
      if (!best || best.drop < 0.12) return null;
      var d = [0.1, side === "left" ? 0.06 : -0.06, 0];
      if (best.axis === "x") d[0] = best.sign * best.amount;
      if (best.axis === "y") d[1] = best.sign * best.amount;
      if (best.axis === "z") d[2] = best.sign * best.amount;
      // Keep a little lift/roll so it isn't a corpse hang.
      if (best.axis !== "x") d[0] += 0.08;
      return d;
    }

    try {
      var lU = solveSide("left");
      var rU = solveSide("right");
      if (lU) deltas.leftUpperArm = lU;
      if (rU) deltas.rightUpperArm = rU;
      try {
        console.log(
          "[CompanionStage] hang solve",
          "L=" + (lU ? lU.join(",") : "default"),
          "R=" + (rU ? rU.join(",") : "default")
        );
      } catch (_) {}
    } catch (e) {
      try {
        console.warn("[CompanionStage] hang solve failed", (e && e.message) || e);
      } catch (_) {}
    }
    // Ensure bind restored.
    restoreArmBindPose();
    return deltas;
  }

  /**
   * Apply soft hang from restBones.hangDeltas (or defaults) onto bind eulers.
   */
  function applySoftHangEulers(hangFn) {
    var deltas =
      (restBones && restBones.hangDeltas) || defaultHangDeltas();
    if (typeof hangFn !== "function") {
      if (!restBones || !restBones.bindEuler) return;
      hangFn = function (key, dx, dy, dz) {
        var n = restBones[key];
        var b = restBones.bindEuler[key];
        if (!n || !b) return;
        setEuler(n, b.x + dx, b.y + dy, b.z + dz);
      };
    }
    Object.keys(deltas).forEach(function (key) {
      var d = deltas[key];
      if (!d || d.length < 3) return;
      hangFn(key, d[0], d[1], d[2]);
    });
  }

  /** Re-capture base eulers from the current bone rotations (post-hang). */
  function captureBaseFromBones() {
    if (!restBones) return;
    restBones.base = restBones.base || {};
    Object.keys(restBones).forEach(function (k) {
      if (
        k === "base" ||
        k === "bindQ" ||
        k === "bindEuler" ||
        k === "hangDeltas"
      ) {
        return;
      }
      var n = restBones[k];
      if (!n || !n.rotation) return;
      restBones.base[k] = {
        x: n.rotation.x,
        y: n.rotation.y,
        z: n.rotation.z,
      };
    });
  }

  /**
   * Force avatar back to measured soft hang (after VRMA / reset).
   * Stops mixer influence, reapplies hang eulers, snaps VR wrists home.
   */
  function restoreHangPose(opts) {
    opts = opts || {};
    try {
      if (vrmaMixer) {
        vrmaMixer.stopAllAction();
        // Advance once so bindings release last clip weights.
        vrmaMixer.update(0);
      }
    } catch (_) {}
    if (!restBones || !restBones.bindEuler) return;
    try {
      applySoftHangEulers();
      if (opts.recaptureBase) captureBaseFromBones();
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
      if (opts.recalibrate !== false) {
        // Shoulders from current hang; wrists geometric if still high.
        calibrateVrRestsFromBones();
      } else {
        // Snap free controllers to existing rest without remeasure.
        if (!vr.left.locked) {
          vr.left.x = vr.restLeft.x;
          vr.left.y = vr.restLeft.y;
          vr.left.z = vr.restLeft.z;
          vr.left.vx = vr.left.vy = vr.left.vz = 0;
        }
        if (!vr.right.locked) {
          vr.right.x = vr.restRight.x;
          vr.right.y = vr.restRight.y;
          vr.right.z = vr.restRight.z;
          vr.right.vx = vr.right.vy = vr.right.vz = 0;
        }
      }
    } catch (_) {}
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
   * Rest wrist/head targets in hips-local space.
   *
   * Prefer *geometric* hang: shoulder + down/out/forward scaled by arm reach.
   * Measuring from live bones alone re-encoded shallow euler hang as a permanent
   * Y-pose rest (start + post-gesture). Live hand samples only win when they
   * clearly hang below the geometric target.
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
    var leftLive = hipsLocalOf(restBones && restBones.leftHand);
    var rightLive = hipsLocalOf(restBones && restBones.rightHand);

    vr.shoulderLeft = { x: lSh.x, y: lSh.y, z: lSh.z };
    vr.shoulderRight = { x: rSh.x, y: rSh.y, z: rSh.z };

    var reachL = armReachLen("left");
    var reachR = armReachLen("right");
    var shY = Math.min(lSh.y, rSh.y);
    // Scale-relative hang band: below chest, can hang near hips on short models.
    var yCeil = shY - Math.min(0.14, Math.min(reachL, reachR) * 0.32);
    var yFloor = shY - Math.max(reachL, reachR) * 1.2;

    // Nudge slightly out + toward the viewer (camera), not hard-coded +Z.
    // Hips-local +Z is NOT always face-out on VRM normalized skeletons.
    var fwdL = viewerForwardXZ(lSh);
    var fwdR = viewerForwardXZ(rSh);
    var rightLdir = bodyRightXZ(fwdL);
    var rightRdir = bodyRightXZ(fwdR);

    // Geometric soft hang — primary rest (arms down the sides, slight A-pose).
    function geometricHang(sh, reach, fwd, rightDir, isLeft) {
      var down = reach * 0.78;
      var out = reach * 0.16;
      var fwdAmt = reach * 0.08;
      var side = isLeft ? -1 : 1;
      return {
        x: sh.x + rightDir.x * side * out + fwd.x * fwdAmt,
        y: sh.y - down,
        z: sh.z + rightDir.z * side * out + fwd.z * fwdAmt,
      };
    }

    var leftGeo = geometricHang(lSh, reachL, fwdL, rightLdir, true);
    var rightGeo = geometricHang(rSh, reachR, fwdR, rightRdir, false);

    // Live sample wins only if it hangs at least as low as geo (euler hang worked).
    function pickHang(live, geo, sh, reach) {
      if (
        live &&
        typeof live.y === "number" &&
        live.y <= geo.y + reach * 0.06 &&
        live.y < sh.y - reach * 0.45
      ) {
        return { x: live.x, y: live.y, z: live.z };
      }
      return { x: geo.x, y: geo.y, z: geo.z };
    }

    var leftL = pickHang(leftLive, leftGeo, lSh, reachL);
    var rightL = pickHang(rightLive, rightGeo, rSh, reachR);

    leftL.y = clamp(leftL.y, yFloor, yCeil);
    rightL.y = clamp(rightL.y, yFloor, yCeil);
    // Hard floor: never accept shoulder-high rest (the old Y-pose).
    if (leftL.y > lSh.y - reachL * 0.5) {
      leftL.x = leftGeo.x;
      leftL.y = clamp(leftGeo.y, yFloor, yCeil);
      leftL.z = leftGeo.z;
    }
    if (rightL.y > rSh.y - reachR * 0.5) {
      rightL.x = rightGeo.x;
      rightL.y = clamp(rightGeo.y, yFloor, yCeil);
      rightL.z = rightGeo.z;
    }

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
   * Horizontal unit vector from a hips-local origin toward the orbit camera.
   * Do NOT assume hips-local +Z is viewer-forward — many VRMs have hips yawed
   * ~180° so +Z points into the scene (behind the body from the phone camera).
   */
  function viewerForwardXZ(origin) {
    var ox = origin && typeof origin.x === "number" ? origin.x : 0;
    var oz = origin && typeof origin.z === "number" ? origin.z : 0;
    var cam = cameraHipsLocal();
    if (cam) {
      var dx = cam.x - ox;
      var dz = cam.z - oz;
      var len = Math.sqrt(dx * dx + dz * dz);
      if (len > 1e-4) {
        return { x: dx / len, z: dz / len, cam: cam };
      }
    }
    // Last resort: hips-local +Z (documented face-out when hips face world).
    return { x: 0, z: 1, cam: cam };
  }

  /**
   * Body-right unit in hips XZ given a viewer-forward vector (up × forward).
   */
  function bodyRightXZ(fwd) {
    // (0,1,0) × (fx,0,fz) = (fz, 0, -fx)
    return { x: fwd.z, z: -fwd.x };
  }

  /**
   * Wave / point peaks relative to this avatar + camera (viewer).
   * Offsets use measured arm reach so short VRMs stay inside the workspace.
   * Always place peaks toward the camera — never hardcode +Z as "forward".
   */
  function gesturePeak(kind, side, inten) {
    inten = typeof inten === "number" ? inten : 1;
    var isLeft = side === "left";
    var sh = isLeft ? vr.shoulderLeft : vr.shoulderRight;
    if (!sh) {
      sh = isLeft
        ? { x: -0.16, y: 1.22, z: 0 }
        : { x: 0.16, y: 1.22, z: 0 };
    }
    var headY = (vr.restHead && vr.restHead.y) || sh.y + 0.2;
    var reach = armReachLen(side);
    var fwd = viewerForwardXZ(sh);
    var right = bodyRightXZ(fwd);
    // Outward from torso for this hand (left = −body-right).
    var outX = isLeft ? -right.x : right.x;
    var outZ = isLeft ? -right.z : right.z;
    var peak = { x: sh.x, y: sh.y, z: sh.z };

    if (kind === "wave") {
      // Classic "hi": hand near ear/crown height, out, clearly toward viewer.
      // Previous peak was only ~0.2*reach above shoulder and used +Z as forward,
      // which landed at shoulder height and behind the body on many VRMs.
      peak.y = sh.y + reach * (0.48 + 0.1 * inten);
      peak.y = Math.min(peak.y, headY + reach * 0.06);
      peak.y = Math.max(peak.y, sh.y + reach * 0.32);
      peak.x =
        sh.x +
        outX * reach * (0.3 + 0.08 * inten) +
        fwd.x * reach * (0.5 + 0.1 * inten);
      peak.z =
        sh.z +
        outZ * reach * (0.3 + 0.08 * inten) +
        fwd.z * reach * (0.5 + 0.1 * inten);
    } else if (kind === "point") {
      peak.y = sh.y + reach * 0.04;
      peak.x =
        sh.x +
        outX * reach * (0.12 + 0.05 * inten) +
        fwd.x * reach * (0.68 + 0.1 * inten);
      peak.z =
        sh.z +
        outZ * reach * (0.12 + 0.05 * inten) +
        fwd.z * reach * (0.68 + 0.1 * inten);
    } else if (kind === "cheer") {
      peak.y = Math.min(headY + reach * 0.14, sh.y + reach * 0.78);
      peak.x =
        sh.x + outX * reach * 0.22 + fwd.x * reach * 0.28;
      peak.z =
        sh.z + outZ * reach * 0.22 + fwd.z * reach * 0.28;
    } else {
      peak.y = sh.y + reach * 0.22;
      peak.x =
        sh.x + outX * reach * 0.24 + fwd.x * reach * 0.32;
      peak.z =
        sh.z + outZ * reach * 0.24 + fwd.z * reach * 0.32;
    }

    // Keep inside arm reach from shoulder (prevents locked-straight arm).
    return clampToArmReach(side, peak.x, peak.y, peak.z, 0.88);
  }

  /**
   * Motion template library: joint-XYZ recipes for THIS VRM.
   * Built from measured shoulders / rest wrists / arm reach / camera — not human-scale guesses.
   * Voice agent plays named templates; frames are recomputed at play time so camera moves stay valid.
   */
  var motionLibraryMeta = null;

  function round3lib(n) {
    return typeof n === "number" && isFinite(n) ? Math.round(n * 1000) / 1000 : 0;
  }

  function handRestObj(side) {
    var r = side === "left" ? vr.restLeft : vr.restRight;
    if (!r) return { rest: true };
    return { x: round3lib(r.x), y: round3lib(r.y), z: round3lib(r.z) };
  }

  function handPeakObj(kind, side, inten) {
    var p = gesturePeak(kind, side, inten || 1);
    return { x: round3lib(p.x), y: round3lib(p.y), z: round3lib(p.z) };
  }

  /** Lateral flap offset in camera-facing plane (for wave). */
  function waveFlapOffset(side, sign, inten) {
    var isLeft = side === "left";
    var sh = isLeft ? vr.shoulderLeft : vr.shoulderRight;
    if (!sh) sh = { x: 0, y: 1, z: 0 };
    var reach = armReachLen(side);
    var fwd = viewerForwardXZ(sh);
    var right = bodyRightXZ(fwd);
    var amp = reach * (0.1 + 0.04 * (inten || 1));
    return {
      x: right.x * amp * sign,
      y: 0,
      z: right.z * amp * sign,
    };
  }

  function lookCameraObj() {
    var cam = cameraHipsLocal();
    var head = (vr && vr.restHead) || { x: 0, y: 1.4, z: 0 };
    if (!cam) return { x: 0, y: 0.05, hold_sec: 2.5 };
    var dx = cam.x - head.x;
    var dy = cam.y - head.y;
    var dz = cam.z - head.z;
    var horiz = Math.sqrt(dx * dx + dz * dz) || 1;
    return {
      x: round3lib(clamp(dx / Math.max(horiz, 0.4) * 0.55, -1, 1)),
      y: round3lib(clamp(dy / Math.max(horiz, 0.4) * 0.45, -1, 1)),
      hold_sec: 2.8,
    };
  }

  /**
   * Build absolute keyframe plan for a named template from LIVE joint XYZ.
   * Returns { ok, id, intent, look?, frames[] } for playAiMotion.
   */
  function buildTemplatePlan(name, opts) {
    opts = opts || {};
    var n = String(name || "")
      .toLowerCase()
      .replace(/[\s-]+/g, "_");
    var inten =
      typeof opts.intensity === "number" ? clamp(opts.intensity, 0.2, 1.5) : 1;
    var sideRaw = opts.side != null ? String(opts.side).toLowerCase().trim() : "";
    if (sideRaw === "l") sideRaw = "left";
    if (sideRaw === "r") sideRaw = "right";
    if (sideRaw === "all") sideRaw = "both";

    // Resolve aliases
    if (n === "hello") n = "wave";
    if (n === "yes") n = "nod";
    if (n === "no") n = "shake_head";
    if (n === "celebrate") n = "cheer";
    if (n === "reset" || n === "reset_body" || n === "idle") n = "rest";

    // wave / point / cheer with side suffix or opts.side
    var side = sideRaw;
    var m = n.match(/^(wave|point|cheer)_(left|right|both)$/);
    if (m) {
      n = m[1];
      side = m[2];
    }
    if ((n === "wave" || n === "point" || n === "cheer") && !side) {
      side = "right";
    }
    if (side !== "left" && side !== "right" && side !== "both") {
      side = n === "cheer" ? "both" : "right";
    }

    var look = lookCameraObj();
    var frames = [];
    var planId = n === "wave" || n === "point" ? n + "_" + side : n;

    function addFrame(at, left, right, hold, lookFr) {
      var fr = { at_ms: at, hold_sec: hold != null ? hold : 0.4 };
      if (left) fr.left = left;
      if (right) fr.right = right;
      if (lookFr) fr.look = lookFr;
      frames.push(fr);
    }

    if (n === "rest") {
      addFrame(0, handRestObj("left"), handRestObj("right"), 0.25);
      return {
        ok: true,
        id: "rest",
        intent: "rest",
        source: "joint_xyz_template",
        look: { x: look.x, y: look.y, hold_sec: 1.2 },
        frames: frames,
      };
    }

    if (n === "nod") {
      return {
        ok: true,
        id: "nod",
        intent: "nod",
        source: "joint_xyz_template",
        look: { x: look.x, y: 0.35, hold_sec: 0.35 },
        frames: [
          {
            at_ms: 0,
            look: { x: look.x, y: 0.35, hold_sec: 0.28 },
            hold_sec: 0.28,
          },
          {
            at_ms: 280,
            look: { x: look.x, y: -0.12, hold_sec: 0.22 },
            hold_sec: 0.22,
          },
          {
            at_ms: 520,
            look: { x: look.x, y: look.y, hold_sec: 1.2 },
            hold_sec: 0.3,
          },
        ],
      };
    }

    if (n === "shake_head") {
      return {
        ok: true,
        id: "shake_head",
        intent: "shake_head",
        source: "joint_xyz_template",
        look: { x: -0.55, y: look.y, hold_sec: 0.25 },
        frames: [
          { at_ms: 0, look: { x: -0.55, y: look.y, hold_sec: 0.22 }, hold_sec: 0.22 },
          { at_ms: 240, look: { x: 0.55, y: look.y, hold_sec: 0.22 }, hold_sec: 0.22 },
          { at_ms: 480, look: { x: -0.4, y: look.y, hold_sec: 0.2 }, hold_sec: 0.2 },
          { at_ms: 700, look: { x: look.x, y: look.y, hold_sec: 1.2 }, hold_sec: 0.3 },
        ],
      };
    }

    if (n === "wave") {
      var sides = side === "both" ? ["left", "right"] : [side];
      // Raise
      var raiseL = sides.indexOf("left") >= 0 ? handPeakObj("wave", "left", inten) : null;
      var raiseR = sides.indexOf("right") >= 0 ? handPeakObj("wave", "right", inten) : null;
      addFrame(0, raiseL, raiseR, 0.35, look);
      // Flap 3 times using camera-plane offsets from live peak
      var flaps = [1, -1, 1, -0.4];
      for (var fi = 0; fi < flaps.length; fi++) {
        var sign = flaps[fi];
        var lH = null;
        var rH = null;
        if (raiseL) {
          var fL = waveFlapOffset("left", sign, inten);
          lH = {
            x: round3lib(raiseL.x + fL.x),
            y: raiseL.y,
            z: round3lib(raiseL.z + fL.z),
          };
          lH = clampToArmReach("left", lH.x, lH.y, lH.z, 0.88);
          lH = { x: round3lib(lH.x), y: round3lib(lH.y), z: round3lib(lH.z) };
        }
        if (raiseR) {
          var fR = waveFlapOffset("right", sign, inten);
          rH = {
            x: round3lib(raiseR.x + fR.x),
            y: raiseR.y,
            z: round3lib(raiseR.z + fR.z),
          };
          rH = clampToArmReach("right", rH.x, rH.y, rH.z, 0.88);
          rH = { x: round3lib(rH.x), y: round3lib(rH.y), z: round3lib(rH.z) };
        }
        addFrame(320 + fi * 220, lH, rH, 0.22);
      }
      // Return rest
      addFrame(
        320 + flaps.length * 220 + 80,
        sides.indexOf("left") >= 0 ? handRestObj("left") : null,
        sides.indexOf("right") >= 0 ? handRestObj("right") : null,
        0.35
      );
      return {
        ok: true,
        id: planId,
        intent: "wave",
        side: side,
        source: "joint_xyz_template",
        look: look,
        frames: frames,
        measured: {
          peak_left: raiseL,
          peak_right: raiseR,
          rest_left: handRestObj("left"),
          rest_right: handRestObj("right"),
          reach_left: round3lib(armReachLen("left")),
          reach_right: round3lib(armReachLen("right")),
        },
      };
    }

    if (n === "point") {
      var pL = side === "left" || side === "both" ? handPeakObj("point", "left", inten) : null;
      var pR = side === "right" || side === "both" ? handPeakObj("point", "right", inten) : null;
      addFrame(0, pL, pR, 1.1, look);
      addFrame(
        1200,
        side === "left" || side === "both" ? handRestObj("left") : null,
        side === "right" || side === "both" ? handRestObj("right") : null,
        0.3
      );
      return {
        ok: true,
        id: planId,
        intent: "point",
        side: side,
        source: "joint_xyz_template",
        look: look,
        frames: frames,
      };
    }

    if (n === "cheer") {
      var cL = handPeakObj("cheer", "left", inten);
      var cR = handPeakObj("cheer", "right", inten);
      addFrame(0, cL, cR, 0.9, look);
      addFrame(1000, handRestObj("left"), handRestObj("right"), 0.3);
      return {
        ok: true,
        id: "cheer",
        intent: "cheer",
        source: "joint_xyz_template",
        look: look,
        frames: frames,
      };
    }

    if (n === "shrug") {
      var shL = vr.shoulderLeft || { x: -0.16, y: 1.2, z: 0 };
      var shR = vr.shoulderRight || { x: 0.16, y: 1.2, z: 0 };
      var rL = armReachLen("left");
      var rR = armReachLen("right");
      var fwdL = viewerForwardXZ(shL);
      var fwdR = viewerForwardXZ(shR);
      var leftUp = clampToArmReach(
        "left",
        shL.x - rL * 0.22,
        shL.y + rL * 0.08,
        shL.z + fwdL.x * rL * 0.12 + fwdL.z * rL * 0.12,
        0.85
      );
      var rightUp = clampToArmReach(
        "right",
        shR.x + rR * 0.22,
        shR.y + rR * 0.08,
        shR.z + fwdR.x * rR * 0.12 + fwdR.z * rR * 0.12,
        0.85
      );
      addFrame(
        0,
        { x: round3lib(leftUp.x), y: round3lib(leftUp.y), z: round3lib(leftUp.z) },
        { x: round3lib(rightUp.x), y: round3lib(rightUp.y), z: round3lib(rightUp.z) },
        0.9,
        look
      );
      addFrame(1100, handRestObj("left"), handRestObj("right"), 0.3);
      return {
        ok: true,
        id: "shrug",
        intent: "shrug",
        source: "joint_xyz_template",
        look: look,
        frames: frames,
      };
    }

    if (n === "think") {
      // Hand near temple from measured head + shoulder (optional intentional pose).
      var shRt = vr.shoulderRight || { x: 0.16, y: 1.2, z: 0 };
      var headY = (vr.restHead && vr.restHead.y) || shRt.y + 0.15;
      var rr = armReachLen("right");
      var fwdT = viewerForwardXZ(shRt);
      var thinkPt = clampToArmReach(
        "right",
        shRt.x + rr * 0.1,
        Math.min(headY - rr * 0.08, shRt.y + rr * 0.32),
        shRt.z + fwdT.x * rr * 0.28 + fwdT.z * rr * 0.28,
        0.85
      );
      addFrame(
        0,
        null,
        { x: round3lib(thinkPt.x), y: round3lib(thinkPt.y), z: round3lib(thinkPt.z) },
        1.8,
        { x: 0.25, y: 0.12, hold_sec: 2 }
      );
      addFrame(2000, null, handRestObj("right"), 0.3);
      return {
        ok: true,
        id: "think",
        intent: "think",
        source: "joint_xyz_template",
        frames: frames,
      };
    }

    if (n === "clap") {
      var shCl = vr.shoulderLeft || { x: -0.16, y: 1.2, z: 0 };
      var shCr = vr.shoulderRight || { x: 0.16, y: 1.2, z: 0 };
      var rCl = armReachLen("left");
      var fwdCl = viewerForwardXZ({
        x: (shCl.x + shCr.x) * 0.5,
        z: (shCl.z + shCr.z) * 0.5,
      });
      var midY = (shCl.y + shCr.y) * 0.5 - rCl * 0.25;
      var midX = (shCl.x + shCr.x) * 0.5 + fwdCl.x * rCl * 0.32;
      var midZ = (shCl.z + shCr.z) * 0.5 + fwdCl.z * rCl * 0.32;
      var cl = clampToArmReach("left", midX - 0.04, midY, midZ, 0.8);
      var cr = clampToArmReach("right", midX + 0.04, midY, midZ, 0.8);
      addFrame(
        0,
        { x: round3lib(cl.x), y: round3lib(cl.y), z: round3lib(cl.z) },
        { x: round3lib(cr.x), y: round3lib(cr.y), z: round3lib(cr.z) },
        0.25,
        look
      );
      var cl2 = clampToArmReach("left", midX - 0.02, midY, midZ, 0.8);
      var cr2 = clampToArmReach("right", midX + 0.02, midY, midZ, 0.8);
      addFrame(
        280,
        { x: round3lib(cl2.x), y: round3lib(cl2.y), z: round3lib(cl2.z) },
        { x: round3lib(cr2.x), y: round3lib(cr2.y), z: round3lib(cr2.z) },
        0.2
      );
      addFrame(560, handRestObj("left"), handRestObj("right"), 0.3);
      return {
        ok: true,
        id: "clap",
        intent: "clap",
        source: "joint_xyz_template",
        look: look,
        frames: frames,
      };
    }

    if (n === "bow" || n === "lean_in") {
      // Look + slight lean via torso is state-driven; hands stay rest, head dips.
      return {
        ok: true,
        id: n,
        intent: n,
        source: "joint_xyz_template",
        look: { x: look.x, y: n === "bow" ? -0.45 : 0.15, hold_sec: 1.2 },
        frames: [
          {
            at_ms: 0,
            left: handRestObj("left"),
            right: handRestObj("right"),
            look: { x: look.x, y: n === "bow" ? -0.45 : 0.18, hold_sec: 1.0 },
            hold_sec: 1.0,
          },
          {
            at_ms: 1200,
            look: look,
            hold_sec: 0.4,
          },
        ],
      };
    }

    if (n === "hands_on_hips") {
      var shLH = vr.shoulderLeft || { x: -0.16, y: 1.2, z: 0 };
      var shRH = vr.shoulderRight || { x: 0.16, y: 1.2, z: 0 };
      var rLH = armReachLen("left");
      var rRH = armReachLen("right");
      var hipsY = Math.min(
        (vr.restLeft && vr.restLeft.y) || shLH.y - rLH * 0.7,
        (vr.restRight && vr.restRight.y) || shRH.y - rRH * 0.7
      );
      var hl = clampToArmReach("left", shLH.x - rLH * 0.08, hipsY + rLH * 0.05, shLH.z + rLH * 0.05, 0.75);
      var hr = clampToArmReach("right", shRH.x + rRH * 0.08, hipsY + rRH * 0.05, shRH.z + rRH * 0.05, 0.75);
      addFrame(
        0,
        { x: round3lib(hl.x), y: round3lib(hl.y), z: round3lib(hl.z) },
        { x: round3lib(hr.x), y: round3lib(hr.y), z: round3lib(hr.z) },
        1.8,
        look
      );
      addFrame(2000, handRestObj("left"), handRestObj("right"), 0.3);
      return {
        ok: true,
        id: "hands_on_hips",
        intent: "hands_on_hips",
        source: "joint_xyz_template",
        frames: frames,
      };
    }

    if (n === "crossed_arms") {
      var shLC = vr.shoulderLeft || { x: -0.16, y: 1.2, z: 0 };
      var shRC = vr.shoulderRight || { x: 0.16, y: 1.2, z: 0 };
      var rLC = armReachLen("left");
      var rRC = armReachLen("right");
      var cy = Math.min(shLC.y, shRC.y) - Math.min(rLC, rRC) * 0.35;
      var cla = clampToArmReach("left", 0.04, cy, shLC.z + rLC * 0.15, 0.8);
      var cra = clampToArmReach("right", -0.04, cy - 0.02, shRC.z + rRC * 0.12, 0.8);
      addFrame(
        0,
        { x: round3lib(cla.x), y: round3lib(cla.y), z: round3lib(cla.z) },
        { x: round3lib(cra.x), y: round3lib(cra.y), z: round3lib(cra.z) },
        2.0,
        look
      );
      addFrame(2200, handRestObj("left"), handRestObj("right"), 0.3);
      return {
        ok: true,
        id: "crossed_arms",
        intent: "crossed_arms",
        source: "joint_xyz_template",
        frames: frames,
      };
    }

    return null;
  }

  /** Catalog of templates + VRMA clips available for this avatar. */
  function exportMotionLibrary() {
    var cam = cameraHipsLocal();
    var catalog = [
      { id: "rest", aliases: ["idle", "reset"], description: "Measured soft hang rest", source: "joint_xyz" },
      { id: "wave_right", aliases: ["wave", "hello"], description: "Wave (VRMA goodbye clip, any VRM)", source: "vrma", vrma: "goodbye" },
      { id: "wave_left", aliases: [], description: "Wave left (joint-XYZ peak)", source: "joint_xyz" },
      { id: "wave_both", aliases: [], description: "Wave both hands", source: "joint_xyz" },
      { id: "point_right", aliases: ["point"], description: "Point right wrist toward camera", source: "joint_xyz" },
      { id: "point_left", aliases: [], description: "Point left wrist toward camera", source: "joint_xyz" },
      { id: "nod", aliases: ["yes"], description: "Head nod via look keyframes", source: "joint_xyz" },
      { id: "shake_head", aliases: ["no"], description: "Head shake via look keyframes", source: "joint_xyz" },
      { id: "shrug", aliases: [], description: "Relax VRMA / shoulder shrug", source: "vrma", vrma: "relax" },
      { id: "think", aliases: ["thinking"], description: "Thinking VRMA clip", source: "vrma", vrma: "thinking" },
      { id: "clap", aliases: ["clapping"], description: "Clapping VRMA clip", source: "vrma", vrma: "clapping" },
      { id: "cheer", aliases: ["celebrate", "jump"], description: "Jump / cheer VRMA", source: "vrma", vrma: "jump" },
      { id: "bow", aliases: [], description: "Head dip", source: "joint_xyz" },
      { id: "lean_in", aliases: [], description: "Slight lean toward viewer", source: "joint_xyz" },
      { id: "hands_on_hips", aliases: [], description: "Wrists near hips from measured hang", source: "joint_xyz" },
      { id: "crossed_arms", aliases: [], description: "Arms crossed from shoulder XYZ", source: "joint_xyz" },
      { id: "goodbye", aliases: ["bye"], description: "Goodbye wave VRMA", source: "vrma", vrma: "goodbye" },
      { id: "angry", aliases: ["mad"], description: "Angry VRMA", source: "vrma", vrma: "angry" },
      { id: "sad", aliases: [], description: "Sad VRMA", source: "vrma", vrma: "sad" },
      { id: "sleepy", aliases: ["sleep"], description: "Sleepy VRMA", source: "vrma", vrma: "sleepy" },
      { id: "surprised", aliases: ["surprise"], description: "Surprised VRMA", source: "vrma", vrma: "surprised" },
      { id: "blush", aliases: ["shy"], description: "Blush VRMA", source: "vrma", vrma: "blush" },
      { id: "lookaround", aliases: ["look_around"], description: "Look around VRMA", source: "vrma", vrma: "lookaround" },
      { id: "relax", aliases: [], description: "Relaxed VRMA", source: "vrma", vrma: "relax" },
    ];
    var vrmaList = listVrma();
    motionLibraryMeta = {
      ok: true,
      source: "vrma+joint_xyz",
      space: "hips_local",
      note:
        "PREFERRED: body_pose with VRMA-backed ids (wave→goodbye, clap, think, jump, …) — " +
        "clips retarget to any VRM humanoid. Joint-XYZ templates recompute wrist targets from live " +
        "shoulders/rest/reach/camera when no clip maps.",
      joints: {
        shoulder_left: vr.shoulderLeft
          ? { x: round3lib(vr.shoulderLeft.x), y: round3lib(vr.shoulderLeft.y), z: round3lib(vr.shoulderLeft.z) }
          : null,
        shoulder_right: vr.shoulderRight
          ? { x: round3lib(vr.shoulderRight.x), y: round3lib(vr.shoulderRight.y), z: round3lib(vr.shoulderRight.z) }
          : null,
        rest_left: handRestObj("left"),
        rest_right: handRestObj("right"),
        reach_left: round3lib(armReachLen("left")),
        reach_right: round3lib(armReachLen("right")),
        camera_hips_local: cam
          ? { x: round3lib(cam.x), y: round3lib(cam.y), z: round3lib(cam.z) }
          : null,
      },
      catalog: catalog,
      template_ids: catalog.map(function (c) {
        return c.id;
      }),
      vrma: vrmaList,
      vrma_playing: isVrmaPlaying() ? vrmaClipId : null,
    };
    return motionLibraryMeta;
  }

  /**
   * Play a named template: prefer portable VRMA when mapped, else rebuild
   * joint-XYZ frames from live shoulders/rest/reach/camera.
   */
  function playTemplate(name, opts) {
    opts = opts || {};
    var n = String(name || "")
      .toLowerCase()
      .replace(/[\s-]+/g, "_");
    // VRMA-first for catalog gestures (any VRM, real joint relative motion).
    var vrmaId = resolveVrmaId(n, opts);
    if (vrmaId && canPlayVrma()) {
      playVrma(vrmaId, {
        loop: !!opts.loop,
        intensity: opts.intensity,
        fallback: n,
        side: opts.side,
      });
      return true;
    }
    var plan = buildTemplatePlan(name, opts);
    if (!plan || !plan.frames || !plan.frames.length) {
      try {
        console.warn("[CompanionStage] playTemplate unknown", name);
      } catch (_) {}
      return false;
    }
    try {
      console.log(
        "[CompanionStage] playTemplate",
        plan.id,
        "frames=" + plan.frames.length,
        "source=joint_xyz"
      );
    } catch (_) {}
    stopVrmaInternal(false);
    // playAiMotion is defined later in this IIFE; function decls are hoisted.
    return playAiMotion(plan);
  }

  // ─── VRMA (portable VRM Animation clips) ─────────────────────────────────

  /** Gesture / pose name → bundled .vrma id (filename without extension). */
  var VRMA_GESTURE_MAP = {
    wave: "goodbye",
    wave_left: "goodbye",
    wave_right: "goodbye",
    wave_both: "goodbye",
    hello: "goodbye",
    goodbye: "goodbye",
    bye: "goodbye",
    clap: "clapping",
    clapping: "clapping",
    think: "thinking",
    thinking: "thinking",
    cheer: "jump",
    celebrate: "jump",
    jump: "jump",
    shrug: "relax",
    relax: "relax",
    rest: "relax",
    idle: "relax",
    lookaround: "lookaround",
    look_around: "lookaround",
    sad: "sad",
    angry: "angry",
    mad: "angry",
    sleepy: "sleepy",
    sleep: "sleepy",
    surprised: "surprised",
    surprise: "surprised",
    blush: "blush",
    shy: "blush",
    test: "test",
  };

  /** Built-in catalog metadata (aliases for voice agent / tools). */
  var VRMA_CATALOG = [
    { id: "goodbye", aliases: ["wave", "hello", "bye"], description: "Wave goodbye / greeting" },
    { id: "clapping", aliases: ["clap"], description: "Clap hands" },
    { id: "thinking", aliases: ["think"], description: "Thinking pose" },
    { id: "jump", aliases: ["cheer", "celebrate"], description: "Jump / celebrate" },
    { id: "relax", aliases: ["shrug", "idle", "rest"], description: "Relaxed stance" },
    { id: "lookaround", aliases: ["look_around"], description: "Look around" },
    { id: "sad", aliases: [], description: "Sad emotion pose" },
    { id: "angry", aliases: ["mad"], description: "Angry emotion pose" },
    { id: "sleepy", aliases: ["sleep"], description: "Sleepy pose" },
    { id: "surprised", aliases: ["surprise"], description: "Surprised reaction" },
    { id: "blush", aliases: ["shy"], description: "Blush / shy" },
    { id: "test", aliases: [], description: "three-vrm sample clip" },
  ];

  function canPlayVrma() {
    var L = getLibs();
    return !!(
      vrm &&
      !usingFallback &&
      L &&
      L.VRMAnimationLoaderPlugin &&
      typeof L.createVRMAnimationClip === "function" &&
      L.THREE &&
      L.THREE.AnimationMixer
    );
  }

  function isVrmaPlaying() {
    return !!(vrmaAction && vrmaMixer && vrmaClipId);
  }

  function listVrma() {
    var ids = VRMA_CATALOG.map(function (c) {
      return c.id;
    });
    try {
      var bridge = hostBridge();
      if (bridge && typeof bridge.listVrmaClips === "function") {
        var raw = bridge.listVrmaClips();
        if (raw) {
          var arr = typeof raw === "string" ? JSON.parse(raw) : raw;
          if (arr && arr.length) {
            ids = arr.map(function (x) {
              return String(x).toLowerCase().replace(/\.vrma$/i, "");
            });
          }
        }
      }
    } catch (_) {}
    return {
      ok: true,
      source: "vrma",
      clips: VRMA_CATALOG.filter(function (c) {
        return ids.indexOf(c.id) >= 0 || true;
      }),
      ids: ids,
      map: VRMA_GESTURE_MAP,
      note:
        "VRMA clips retarget to any VRM 1.0 humanoid. Prefer body_pose with these ids for reliable motion.",
    };
  }

  function resolveVrmaId(name, opts) {
    opts = opts || {};
    if (opts.vrma) return String(opts.vrma).toLowerCase().replace(/\.vrma$/i, "");
    if (opts.clip) return String(opts.clip).toLowerCase().replace(/\.vrma$/i, "");
    var n = String(name || "")
      .toLowerCase()
      .replace(/[\s-]+/g, "_");
    if (!n) return null;
    if (VRMA_GESTURE_MAP[n]) return VRMA_GESTURE_MAP[n];
    // Direct clip id
    for (var i = 0; i < VRMA_CATALOG.length; i++) {
      if (VRMA_CATALOG[i].id === n) return n;
      var al = VRMA_CATALOG[i].aliases || [];
      for (var j = 0; j < al.length; j++) {
        if (al[j] === n) return VRMA_CATALOG[i].id;
      }
    }
    // wave_right etc.
    var m = n.match(/^(wave|hello|clap|think|cheer)(?:_(left|right|both))?$/);
    if (m && VRMA_GESTURE_MAP[m[1]]) return VRMA_GESTURE_MAP[m[1]];
    return null;
  }

  function stopVrmaInternal(restoreHang) {
    vrmaLoadGen++;
    try {
      if (vrmaAction) {
        vrmaAction.fadeOut(0.15);
        vrmaAction.stop();
      }
    } catch (_) {}
    vrmaAction = null;
    vrmaClipId = null;
    vrmaUntil = 0;
    vrmaLoop = false;
    try {
      if (vrmaMixer) {
        vrmaMixer.stopAllAction();
        vrmaMixer.update(0);
        // Keep mixer instance for reuse on same VRM.
      }
    } catch (_) {}
    if (restoreHang) {
      try {
        // Unlock + snap controllers, then force hang bones (not Y-pose end frame).
        vr.left.locked = false;
        vr.right.locked = false;
        vr.head.locked = false;
        activeGesture = null;
        restoreHangPose({ recalibrate: true, recaptureBase: false });
      } catch (_) {}
    }
  }

  function stopVrma() {
    stopVrmaInternal(true);
    return true;
  }

  function destroyVrmaState() {
    stopVrmaInternal(false);
    try {
      if (vrmaMixer) {
        vrmaMixer.stopAllAction();
        vrmaMixer.uncacheRoot(vrm && vrm.scene ? vrm.scene : null);
      }
    } catch (_) {}
    vrmaMixer = null;
    vrmaClipCache = {};
    // Keep raw buffers — independent of VRM instance.
  }

  function ensureVrmaMixer() {
    var L = getLibs();
    if (!L || !L.THREE || !vrm || !vrm.scene) return null;
    if (!vrmaMixer) {
      vrmaMixer = new L.THREE.AnimationMixer(vrm.scene);
    }
    return vrmaMixer;
  }

  /**
   * Read anim bytes via Kotlin bridge (anim:<id> alias).
   * Caches raw ArrayBuffers in vrmaRawCache.
   */
  function loadVrmaBuffer(id) {
    var key = String(id || "")
      .toLowerCase()
      .replace(/\.vrma$/i, "");
    if (vrmaRawCache[key]) {
      return Promise.resolve(vrmaRawCache[key]);
    }
    var bridge = hostBridge();
    if (!bridge || typeof bridge.openVrm !== "function") {
      return Promise.reject(new Error("Native VRM bridge missing for VRMA"));
    }
    var size;
    try {
      size = bridge.openVrm("anim:" + key);
    } catch (e) {
      return Promise.reject(
        new Error("openVrm anim threw: " + ((e && e.message) || String(e)))
      );
    }
    if (typeof size !== "number" || size <= 0) {
      try {
        if (typeof bridge.closeVrm === "function") bridge.closeVrm();
      } catch (_) {}
      return Promise.reject(new Error("openVrm anim failed code=" + size + " id=" + key));
    }
    var bytes = new Uint8Array(size);
    var offset = 0;
    function closeBridge() {
      try {
        if (typeof bridge.closeVrm === "function") bridge.closeVrm();
      } catch (_) {}
    }
    function readNext() {
      if (offset >= size) {
        closeBridge();
        vrmaRawCache[key] = bytes.buffer;
        return Promise.resolve(bytes.buffer);
      }
      var n = Math.min(BRIDGE_CHUNK, size - offset);
      var b64;
      try {
        b64 = bridge.readVrmBase64(offset, n);
      } catch (e) {
        closeBridge();
        return Promise.reject(e);
      }
      if (!b64) {
        closeBridge();
        return Promise.reject(new Error("Empty VRMA chunk at " + offset));
      }
      var bin;
      try {
        bin = atob(b64);
      } catch (e) {
        closeBridge();
        return Promise.reject(e);
      }
      for (var i = 0; i < bin.length; i++) {
        bytes[offset + i] = bin.charCodeAt(i);
      }
      offset += bin.length;
      return new Promise(function (resolve) {
        setTimeout(resolve, 0);
      }).then(readNext);
    }
    return readNext();
  }

  function parseVrmaClip(arrayBuffer, id) {
    var L = getLibs();
    if (!L || !L.GLTFLoader || !L.VRMAnimationLoaderPlugin) {
      return Promise.reject(new Error("VRMA loader not in vendor bundle"));
    }
    if (!vrm) return Promise.reject(new Error("no VRM loaded"));
    var cacheKey = id + "@" + (vrm.scene && vrm.scene.uuid ? vrm.scene.uuid : "vrm");
    if (vrmaClipCache[cacheKey]) {
      return Promise.resolve(vrmaClipCache[cacheKey]);
    }
    var loader = new L.GLTFLoader();
    loader.register(function (parser) {
      return new L.VRMAnimationLoaderPlugin(parser);
    });
    return new Promise(function (resolve, reject) {
      try {
        loader.parse(
          arrayBuffer,
          "",
          function (gltf) {
            try {
              var anims =
                gltf.userData && gltf.userData.vrmAnimations
                  ? gltf.userData.vrmAnimations
                  : null;
              if (!anims || !anims.length) {
                reject(new Error("No VRMAnimation in " + id));
                return;
              }
              var clip = L.createVRMAnimationClip(anims[0], vrm);
              if (!clip) {
                reject(new Error("createVRMAnimationClip failed for " + id));
                return;
              }
              clip.name = id;
              vrmaClipCache[cacheKey] = clip;
              resolve(clip);
            } catch (e) {
              reject(e);
            }
          },
          function (err) {
            reject(err || new Error("VRMA parse failed"));
          }
        );
      } catch (e) {
        reject(e);
      }
    });
  }

  /**
   * Play a bundled VRMA clip on the current VRM.
   * @returns {boolean} true if playback was scheduled (async load may still fail)
   */
  function playVrma(nameOrId, opts) {
    opts = opts || {};
    if (!canPlayVrma()) {
      try {
        console.warn("[CompanionStage] playVrma unavailable");
      } catch (_) {}
      // Fall back to joint-XYZ if requested.
      if (opts.fallback) {
        var plan = buildTemplatePlan(opts.fallback, opts);
        if (plan) return playAiMotion(plan);
      }
      return false;
    }
    var id = resolveVrmaId(nameOrId, opts) || String(nameOrId || "")
      .toLowerCase()
      .replace(/\.vrma$/i, "");
    if (!id) return false;

    // Cancel competing motion layers.
    try {
      clearAiMotionTimers();
      aiMotionGen++;
    } catch (_) {}
    activeGesture = null;
    vr.left.locked = false;
    vr.right.locked = false;

    var gen = ++vrmaLoadGen;
    var loop = !!opts.loop;
    vrmaLoop = loop;
    vrmaClipId = id; // mark early so body motion yields
    vrmaUntil = idleTime + 30; // safety until clip duration known

    try {
      console.log("[CompanionStage] playVrma", id, "loop=" + loop);
    } catch (_) {}

    loadVrmaBuffer(id)
      .then(function (buf) {
        if (gen !== vrmaLoadGen || !vrm) return null;
        return parseVrmaClip(buf, id);
      })
      .then(function (clip) {
        if (!clip || gen !== vrmaLoadGen || !vrm) return;
        var mixer = ensureVrmaMixer();
        if (!mixer) return;
        try {
          if (vrmaAction) {
            vrmaAction.fadeOut(0.2);
            vrmaAction.stop();
          }
        } catch (_) {}
        mixer.stopAllAction();
        var action = mixer.clipAction(clip);
        action.reset();
        action.setLoop(
          loop
            ? getLibs().THREE.LoopRepeat
            : getLibs().THREE.LoopOnce,
          loop ? Infinity : 1
        );
        action.clampWhenFinished = !loop;
        action.fadeIn(0.2);
        action.play();
        vrmaAction = action;
        vrmaClipId = id;
        var dur = typeof clip.duration === "number" && clip.duration > 0 ? clip.duration : 2.5;
        vrmaUntil = loop ? idleTime + 1e9 : idleTime + dur + 0.35;
        try {
          console.log(
            "[CompanionStage] VRMA playing",
            id,
            "tracks=" + (clip.tracks ? clip.tracks.length : 0),
            "dur=" + dur.toFixed(2)
          );
        } catch (_) {}
      })
      .catch(function (e) {
        try {
          console.warn(
            "[CompanionStage] playVrma failed",
            id,
            (e && e.message) || e
          );
        } catch (_) {}
        if (gen === vrmaLoadGen) {
          vrmaClipId = null;
          vrmaAction = null;
          // Template fallback for wave/etc.
          if (opts.fallback) {
            var p = buildTemplatePlan(opts.fallback, opts);
            if (p) playAiMotion(p);
          } else if (nameOrId && nameOrId !== id) {
            var p2 = buildTemplatePlan(nameOrId, opts);
            if (p2) playAiMotion(p2);
          }
        }
      });
    return true;
  }

  function updateVrma(dt) {
    if (!vrmaMixer || !isVrmaPlaying()) {
      if (vrmaClipId && !vrmaAction && idleTime > vrmaUntil) {
        vrmaClipId = null;
      }
      return;
    }
    try {
      vrmaMixer.update(dt);
    } catch (_) {}
    if (!vrmaLoop && idleTime >= vrmaUntil) {
      // Hard stop mixer — do not leave clampWhenFinished end-frame as rest.
      try {
        if (vrmaAction) {
          vrmaAction.stop();
        }
        vrmaMixer.stopAllAction();
        vrmaMixer.update(0);
      } catch (_) {}
      vrmaAction = null;
      vrmaClipId = null;
      vrmaUntil = 0;
      try {
        vr.left.locked = false;
        vr.right.locked = false;
        activeGesture = null;
        // Re-apply solved hang + geometric wrists (fixes post-gesture Y-pose).
        restoreHangPose({ recalibrate: true, recaptureBase: false });
      } catch (_) {}
    }
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
   * State posture is mostly body lean — arms stay at measured hang rest unless a
   * template / tool owns them. Auto chin-think / speak-raise made the right arm
   * twitch every turn and looked broken on many VRMs.
   */
  function pickStatePoseTarget(state) {
    var p = emptyPose();
    if (state === STATE_LISTENING) {
      p.spine = [0.012, 0, 0];
      p.chest = [0.01, 0, 0];
      p.neck = [0.002, 0, 0];
      p.head = [0.001, 0, 0];
    } else if (state === STATE_THINKING) {
      // Subtle torso only — no forced hand-to-chin (was glitchy between turns).
      p.hips = [0.004, -0.008, 0.006];
      p.spine = [0.008, 0.012, 0.006];
      p.chest = [0.008, 0.008, 0.004];
      p.neck = [0.006, 0.015, 0.006];
      p.head = [0.004, 0.02, 0.006];
    } else if (state === STATE_SPEAKING) {
      p.hips = [0.004, 0, 0];
      p.spine = [0.01, 0, 0];
      p.chest = [0.012, 0, 0];
      p.neck = [0.001, 0, 0];
      p.head = [0.001, 0, 0];
    } else {
      p.spine = [0.006, 0, 0];
    }
    statePoseTarget = p;
    // Hands: do NOT auto-pose on listen/think/speak. Soft hang + tools/templates only.
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
      // Symmetric Z: viewer may sit in hips −Z (common when hips yaw ~180°).
      h.z = clamp(h.z, -maxR * 1.35, maxR * 1.35);
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
   * True when this hand should use two-bone IK (bind-relative).
   * Prefer IK whenever armMeta exists — including idle hang — so rest is the
   * geometric wrist target, not shallow euler Y-pose. Euler hang is fallback
   * only when meta/bind axes are missing.
   */
  function handNeedsIk(side) {
    var isLeft = side === "left";
    var h = isLeft ? vr.left : vr.right;
    var rest = isLeft ? vr.restLeft : vr.restRight;
    if (!h || !rest) return false;
    // Measured skeleton → always IK (idle hang + gestures).
    if (armMeta && armMeta[side] && armMeta[side].upperLen > 0) return true;
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
   * Soft hang eulers (+ optional raise blend) — fallback when armMeta missing.
   * Base already includes solved hang; raise cancels hang toward T / lift.
   */
  function applySoftHangArm(side, raiseAmt) {
    var isLeft = side === "left";
    var sign = isLeft ? 1 : -1;
    var upperKey = isLeft ? "leftUpperArm" : "rightUpperArm";
    var lowerKey = isLeft ? "leftLowerArm" : "rightLowerArm";
    var handKey = isLeft ? "leftHand" : "rightHand";
    var raise = clamp(raiseAmt || 0, 0, 1.4);
    // Match ~1.52 hang magnitude so raise=1 returns near T-pose.
    addEuler(
      restBones[upperKey],
      upperKey,
      -raise * 0.9,
      sign * raise * 0.12,
      -sign * raise * 1.42
    );
    addEuler(restBones[lowerKey], lowerKey, raise * 0.2, 0, -sign * raise * 0.05);
    addEuler(restBones[handKey], handKey, raise * 0.08, 0, 0);
  }

  /**
   * Two-bone IK: shoulder → elbow → wrist reaches virtual VR controller point.
   * Idle uses IK to geometric hang rests; raised/locked same path.
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

    // No mouth→wrist jitter (caused weird arm wiggle while speaking).

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
        // Full atan2 — do not force cam.z > hand.z (viewer is often hips −Z).
        var yaw = Math.atan2(camH.x - h.x, camH.z - h.z);
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
      // Raise near head height, toward camera, flap palm at viewer.
      var waveSides =
        g.side === "both"
          ? ["left", "right"]
          : [g.side === "left" ? "left" : "right"];
      if (u < 0.14) lookTowardCamera(g.duration);
      for (var wi = 0; wi < waveSides.length; wi++) {
        var side = waveSides[wi];
        var rest = side === "left" ? vr.restLeft : vr.restRight;
        var peak = gesturePeak("wave", side, inten);
        var peakX = peak.x;
        var peakY = peak.y;
        var peakZ = peak.z;
        if (u < 0.18) {
          var k0 = easeInOut(u / 0.18);
          setHandTarget(
            side,
            rest.x + (peakX - rest.x) * k0,
            rest.y + (peakY - rest.y) * k0,
            rest.z + (peakZ - rest.z) * k0,
            0,
            true
          );
        } else if (u < 0.82) {
          // Lateral flap in the plane facing the camera (not behind the body).
          var camW = cameraHipsLocal();
          var waveReach = armReachLen(side);
          var flap =
            Math.sin((g.t - g.duration * 0.18) * 10.5) *
            waveReach *
            0.22 *
            inten;
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
            // Keep peak on the camera side of the shoulder (safety).
            var shW = side === "left" ? vr.shoulderLeft : vr.shoulderRight;
            if (shW) {
              var toCamX = camW.x - shW.x;
              var toCamZ = camW.z - shW.z;
              var handOffX = fx - shW.x;
              var handOffZ = fz - shW.z;
              if (handOffX * toCamX + handOffZ * toCamZ < 0) {
                var clen = Math.sqrt(toCamX * toCamX + toCamZ * toCamZ) || 1;
                fx = shW.x + (toCamX / clen) * waveReach * 0.4;
                fz = shW.z + (toCamZ / clen) * waveReach * 0.4;
              }
            }
          }
          setHandTarget(side, fx, peakY + Math.abs(flap) * 0.06, fz, 0, true);
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
      var pointSides =
        g.side === "both"
          ? ["left", "right"]
          : [g.side === "left" ? "left" : "right"];
      lookTowardCamera(0.35);
      for (var pi = 0; pi < pointSides.length; pi++) {
        var ps = pointSides[pi];
        var pSign = ps === "left" ? -1 : 1;
        var pp = gesturePeak("point", ps, inten);
        setHandTarget(ps, pp.x, pp.y, pp.z, 0, true);
        if (pointSides.length === 1) {
          lookTarget.x = clamp(lookTarget.x + pSign * 0.2 * inten, -1, 1);
        }
      }
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
    try {
      stopVrmaInternal(false);
    } catch (_) {}
    try {
      clearAiMotionTimers();
      aiMotionGen++;
    } catch (_) {}
    vr.left.locked = false;
    vr.right.locked = false;
    vr.head.locked = false;
    vr.left.holdUntil = 0;
    vr.right.holdUntil = 0;
    // Force hang bones + geometric rests (not whatever VRMA left behind).
    try {
      restoreHangPose({ recalibrate: true, recaptureBase: false });
    } catch (_) {
      vr.left.x = vr.restLeft.x;
      vr.left.y = vr.restLeft.y;
      vr.left.z = vr.restLeft.z;
      vr.right.x = vr.restRight.x;
      vr.right.y = vr.restRight.y;
      vr.right.z = vr.restRight.z;
      vr.left.vx = vr.left.vy = vr.left.vz = 0;
      vr.right.vx = vr.right.vy = vr.right.vz = 0;
    }
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
    // After load: remeasure bind arm meta, re-solve hang, geometric rest wrists.
    if (restRecalibLeft > 0) {
      restRecalibLeft -= 1;
      if (restRecalibLeft === 0 && !activeGesture && !isVrmaPlaying()) {
        try {
          restoreArmBindPose();
          if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
          measureArmMeta("left");
          measureArmMeta("right");
          restBones.hangDeltas = solveHangDeltas();
          applySoftHangEulers();
          captureBaseFromBones();
          if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
          calibrateVrRestsFromBones();
          try {
            exportMotionLibrary();
          } catch (_) {}
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
    var asymmetric = n === "wave" || n === "point" || n === "hello";
    var sideRaw =
      opts.side == null || opts.side === ""
        ? ""
        : String(opts.side).toLowerCase().trim();
    if (sideRaw === "l") sideRaw = "left";
    if (sideRaw === "r") sideRaw = "right";
    if (sideRaw === "all") sideRaw = "both";
    var side = sideRaw;
    if (side !== "left" && side !== "right" && side !== "both") {
      if (asymmetric) {
        // Default right when host omitted side (still plays — never silent).
        side = "right";
      } else {
        side = "right";
      }
    }
    try {
      console.log(
        "[CompanionStage] playGesture resolved",
        n,
        "side=" + side,
        "rawSide=" + (opts.side == null ? "(null)" : String(opts.side))
      );
    } catch (_) {}

    if (n === "reset" || n === "reset_body" || n === "idle") {
      resetBodyInternal();
      return true;
    }

    // Portable VRMA first (real joint animation on any VRM).
    var vrmaId = resolveVrmaId(n, opts);
    if (vrmaId && canPlayVrma() && n !== "point") {
      // Point stays joint-XYZ (camera-aimed); everything else with a VRMA map uses clips.
      return playVrma(vrmaId, {
        intensity: intensity,
        side: side,
        fallback: n,
        loop: !!opts.loop,
      });
    }

    var table = {
      wave: { kind: "wave", duration: 1.8, side: side },
      nod: { kind: "nod", duration: 0.9 },
      shake_head: { kind: "shake_head", duration: 1.1 },
      no: { kind: "shake_head", duration: 1.1 },
      point: { kind: "point", duration: 1.6, side: side },
      shrug: { kind: "shrug", duration: 1.4 },
      think: { kind: "think", duration: 2.2 },
      clap: { kind: "clap", duration: 1.2 },
      cheer: { kind: "cheer", duration: 1.5 },
      bow: { kind: "bow", duration: 1.6 },
      lean_in: { kind: "lean_in", duration: 1.4 },
      hands_on_hips: { kind: "hands_on_hips", duration: 2.0 },
      crossed_arms: { kind: "crossed_arms", duration: 2.2 },
      celebrate: { kind: "cheer", duration: 1.5 },
      hello: { kind: "wave", duration: 1.8, side: side },
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
    try {
      console.log(
        "[CompanionStage] playGesture",
        n,
        "side=" + activeGesture.side,
        "intensity=" + intensity
      );
    } catch (_) {}
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
    function resolveSideHand(side, handObj) {
      if (!handObj || typeof handObj !== "object") return null;
      if (handObj.rest === true) {
        var r = side === "left" ? vr.restLeft : vr.restRight;
        return { x: r.x, y: r.y, z: r.z };
      }
      return {
        x: num(handObj.x, side === "left" ? vr.restLeft.x : vr.restRight.x),
        y: num(handObj.y, side === "left" ? vr.restLeft.y : vr.restRight.y),
        z: num(handObj.z, side === "left" ? vr.restLeft.z : vr.restRight.z),
      };
    }
    if (o.left && typeof o.left === "object") {
      var L = resolveSideHand("left", o.left);
      if (L) setHandTarget("left", L.x, L.y, L.z, hold, locked);
    }
    if (o.right && typeof o.right === "object") {
      var R = resolveSideHand("right", o.right);
      if (R) setHandTarget("right", R.x, R.y, R.z, hold, locked);
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

  /** AI / template motion: schedule wrist/look keyframes. */
  var aiMotionTimers = [];
  var aiMotionGen = 0;

  function clearAiMotionTimers() {
    for (var i = 0; i < aiMotionTimers.length; i++) {
      try {
        clearTimeout(aiMotionTimers[i]);
      } catch (_) {}
    }
    aiMotionTimers = [];
  }

  function playAiMotion(jsonOrObj) {
    var o = jsonOrObj;
    if (typeof o === "string") {
      try {
        o = JSON.parse(o);
      } catch (_) {
        return false;
      }
    }
    if (!o || typeof o !== "object") return false;
    clearAiMotionTimers();
    aiMotionGen++;
    var gen = aiMotionGen;
    activeGesture = null;

    if (o.look && typeof o.look === "object") {
      try {
        setLook(o.look);
      } catch (_) {}
    }

    var frames = o.frames;
    if (!frames || !frames.length) {
      // Single-pose shorthand on root
      if (o.left || o.right || o.hand) {
        setHands(o);
        return true;
      }
      return false;
    }

    // Look-only frames (nod / shake) — schedule gaze without requiring hands.
    var anyHands = false;
    for (var hi = 0; hi < frames.length; hi++) {
      var hf = frames[hi];
      if (hf && (hf.left || hf.right || hf.hand)) anyHands = true;
    }
    if (!anyHands) {
      for (var li = 0; li < frames.length; li++) {
        (function (fr) {
          if (!fr || typeof fr !== "object") return;
          var at =
            typeof fr.at_ms === "number"
              ? fr.at_ms
              : typeof fr.t_ms === "number"
                ? fr.t_ms
                : 0;
          var tid = setTimeout(function () {
            if (gen !== aiMotionGen) return;
            if (fr.look && typeof fr.look === "object") {
              try {
                setLook(fr.look);
              } catch (_) {}
            }
          }, Math.max(0, at));
          aiMotionTimers.push(tid);
        })(frames[li]);
      }
      return true;
    }

    // Sort by at_ms
    var list = [];
    for (var fi = 0; fi < frames.length; fi++) {
      var fr = frames[fi];
      if (!fr || typeof fr !== "object") continue;
      var at =
        typeof fr.at_ms === "number"
          ? fr.at_ms
          : typeof fr.t_ms === "number"
            ? fr.t_ms
            : 0;
      list.push({ at: Math.max(0, at), fr: fr });
    }
    list.sort(function (a, b) {
      return a.at - b.at;
    });

    try {
      console.log("[CompanionStage] playAiMotion frames=" + list.length);
    } catch (_) {}

    for (var i = 0; i < list.length; i++) {
      (function (item) {
        var tid = setTimeout(function () {
          if (gen !== aiMotionGen) return;
          var fr = item.fr;
          var payload = {};
          if (fr.left) payload.left = fr.left;
          if (fr.right) payload.right = fr.right;
          if (fr.hand) {
            payload.hand = fr.hand;
            payload.x = fr.x;
            payload.y = fr.y;
            payload.z = fr.z;
          }
          if (typeof fr.hold_sec === "number") payload.hold_sec = fr.hold_sec;
          else payload.hold_sec = 0.45;
          if (payload.left || payload.right || payload.hand) {
            setHands(payload);
          }
          if (fr.look && typeof fr.look === "object") {
            try {
              setLook(fr.look);
            } catch (_) {}
          }
        }, item.at);
        aiMotionTimers.push(tid);
      })(list[i]);
    }
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
    // Soft baselines only — do NOT zero every expression or snap look on phase
    // changes (that looked like a camera/VRM glitch between voice turns).
    exprPulseT = Math.min(exprPulseT, 0.6);
    switch (state) {
      case STATE_LISTENING:
        setExpression("happy", 0.28);
        setExpression("relaxed", 0.18);
        // Keep gaze near camera; no hard snap.
        if (idleTime >= lookHoldUntil) {
          lookWanderT = Math.max(lookWanderT, 1.5);
        }
        break;
      case STATE_THINKING:
        setExpression("happy", 0.12);
        setExpression("relaxed", 0.16);
        if (idleTime >= lookHoldUntil) {
          // Mild thinking glance only if wander is free — small delta, not a jump.
          lookTarget.x = clamp(lookTarget.x * 0.5 + 0.12, -0.35, 0.35);
          lookTarget.y = clamp(lookTarget.y * 0.5 + 0.06, -0.2, 0.25);
          lookWanderT = Math.max(lookWanderT, 1.2);
        }
        break;
      case STATE_SPEAKING:
        setExpression("happy", 0.22);
        setExpression("relaxed", 0.08);
        if (idleTime >= lookHoldUntil) {
          lookWanderT = Math.max(lookWanderT, 2.0);
        }
        break;
      case STATE_IDLE:
      default:
        setExpression("relaxed", 0.12);
        if (idleTime >= lookHoldUntil) {
          lookWanderT = Math.max(lookWanderT, 1.8);
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
        if (isVrmaPlaying()) {
          updateVrma(dt);
          // VRMA owns humanoid bones — still allow look/blink/mouth layers.
        } else {
          // Still advance mixer fade-out / cleanup timers if any.
          if (vrmaMixer || vrmaClipId) updateVrma(dt);
          applyBodyMotion(dt);
        }
        updateLookWander(dt);
        updateMicroExpressions(dt);

        // Gentle look ease (was 3.4 — fast snaps looked like camera/VRM glitches between turns).
        lookSmooth.x += (lookTarget.x - lookSmooth.x) * Math.min(1, dt * 2.1);
        lookSmooth.y += (lookTarget.y - lookSmooth.y) * Math.min(1, dt * 2.1);
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
    // Warm VRMA raw cache in the background (small clips; any VRM can play them).
    try {
      preloadVrmaPack();
    } catch (_) {}
    return label || "VRM";
  }

  function preloadVrmaPack() {
    if (!canPlayVrma() && !getLibs()) return;
    var ids = VRMA_CATALOG.map(function (c) {
      return c.id;
    });
    var i = 0;
    function next() {
      if (i >= ids.length) return;
      var id = ids[i++];
      if (vrmaRawCache[id]) {
        setTimeout(next, 0);
        return;
      }
      loadVrmaBuffer(id)
        .then(function () {
          setTimeout(next, 20);
        })
        .catch(function () {
          setTimeout(next, 20);
        });
    }
    setTimeout(next, 400);
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
      // Soft expression blend only on real phase change (avoids flash every push).
      applyStateExpressions(s);
    }
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
    // Full-body VRMA when the name matches a clip / mapped gesture.
    var vid = resolveVrmaId(n, {});
    if (vid && canPlayVrma()) {
      playVrma(vid, { fallback: n.replace(/\s+/g, "_") });
    }
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
   *   x = right+, y = up+, z = hips-forward (may NOT equal viewer-forward).
   * Prefer camera_hips_local / examples.wave_* for viewer-facing targets.
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
      axes: {
        x: "hips_right+",
        y: "up+",
        z: "hips_forward+ (not always toward camera)",
      },
      notes:
        "PREFERRED motion: body_pose with VRMA-backed ids (wave→goodbye, clap, think, jump, …). " +
        "VRMA clips retarget relative joint motion onto ANY loaded VRM humanoid. " +
        "Joint-XYZ templates recompute wrist targets when no clip maps (e.g. point). " +
        "ai_move only for novel freeform poses. Soft hang rest is measured per VRM on load. " +
        "Do not assume hips +Z is face-out — use camera_hips_local.",
      motion_library: exportMotionLibrary(),
      vrma: listVrma(),
      vrma_playing: isVrmaPlaying() ? vrmaClipId : null,
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
            "Named full moves (wave, nod, point, shrug, clap, think, …). " +
            "Wave/clap/think/etc play portable VRMA when available; point uses joint-XYZ toward camera.",
        },
        play_vrma: {
          description:
            "Play a bundled .vrma clip id directly (goodbye, clapping, thinking, jump, …).",
          ids: listVrma().ids,
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
    playTemplate: playTemplate,
    playVrma: playVrma,
    stopVrma: stopVrma,
    listVrma: listVrma,
    exportMotionLibrary: exportMotionLibrary,
    buildTemplatePlan: buildTemplatePlan,
    setHands: setHands,
    setLook: setLook,
    playAiMotion: playAiMotion,
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
    isVrmaPlaying: isVrmaPlaying,
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
