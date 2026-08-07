/**
 * Companion VRM stage — offline Three.js + @pixiv/three-vrm + three-vrm-animation.
 * Host bridge: window.GrokifyCompanion.{onReady,onModelLoaded,onError,onDebugLog,onJointPicked,openVrm,readVrmBase64,closeVrm,listVrmaClips}
 * Stage API:   window.CompanionStage.{loadModel,setState,setMouth,playMotion,
 *              playGesture,playTemplate,playVrma,stopVrma,listVrma,exportMotionLibrary,setHands,setLook,
 *              playAiMotion,resetBody,recalibrateAvatar,exportBodyState,
 *              setDebugSkeleton,setJointLabel,setJointLabels,getJointLabels,selectJoint}
 *
 * Motion layers (priority):
 *  1) VRMA clips — portable humanoid animations (any VRM), via AnimationMixer
 *  2) Joint-XYZ templates / scripted gestures — wrist IK rebuilt per avatar
 *  3) Soft hang idle + spring VR controllers
 *
 * Self-collision: hips-local sphere/capsule/ellipsoid proxies (torso, clothes
 * bulk, head, shoulders) push wrists + elbows out so arms do not tunnel through
 * the body mesh. Debug skeleton mode draws these as wireframe meshes.
 *
 * Android WebView cannot reliably fetch() file:// VRMs ("Failed to fetch").
 * Bytes are streamed from Kotlin via openVrm/readVrmBase64, then GLTFLoader.parse.
 * VRMA uses path alias anim:<id> through the same bridge.
 *
 * Canvas gestures are orbit only (rotate / pan / pinch-zoom). Chat & voice stay on host UI buttons.
 * Touch stick + jump (see #game-pad) drive real-time third-person locomotion via CompanionStage.control.
 *
 * Universal control hook: any loaded VRM is registerActor()'d and can be possess()'d.
 * Locomotion is root transform + humanoid bone walk cycle — not mesh-specific — so custom VRMs work.
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
    // Soft ragdoll: hands ease home when free (low spring = no snap).
    // Keep spring low — high values read as a hard reset after gestures.
    gravity: 2.2,
    spring: 2.15,
    damp: 5.4,
    /** Until this idleTime, use extra-soft return spring (post-gesture / VRMA). */
    settleUntil: 0,
  };
  /**
   * Walk-cycle desired wrists (hips-local). Physics springs toward
   * rest ↔ these, blended by gaitWeight — never hard-swap bone ownership.
   */
  var walkWrist = {
    left: null,
    right: null,
  };
  /** Last chosen elbow bend sign (+1 / −1) for IK temporal continuity. */
  var ikElbowSign = { left: 0, right: 0 };
  /** After VRM install, remeasure hangs once world matrices settle. */
  var restRecalibLeft = 0;
  /**
   * 0→1 ease after load/recalibrate so arms drop into hang instead of popping.
   * Multiplies how aggressively wrists seek full geometric hang.
   */
  var hangEase = 1;
  /** Second pass after camera fit (viewer-forward becomes valid). */
  var postFitRecalib = false;
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
   * In-app Companion World (godot/companion-world maps shipped in APK assets).
   * Replaces the default disc floor with loadable maps + colliders.
   */
  var worldMapRoot = null;
  var worldColliders = [];
  var currentMapId = "stage";
  var worldMapGen = 0;
  /**
   * Universal controllable actors (any VRM humanoid).
   * Companion ships as id "companion"; a future player body is another registerActor + possess.
   * Root motion lives on each actor's root Group; bones stay hips-local for IK/VRMA.
   */
  var actors = {};
  var possessedId = null;
  /** Real-time third-person locomotion (shared physics; applied to possessed actor root). */
  var loco = {
    enabled: true,
    inputX: 0,
    inputZ: 0,
    jumpEdge: false,
    jumpHeld: false,
    vx: 0,
    vy: 0,
    vz: 0,
    yaw: 0,
    yawVel: 0,
    grounded: true,
    walkPhase: 0,
    airTime: 0,
    /**
     * 0..1 continuous gait blend. Ramps up when walking/jumping and eases down
     * so idle never hard-snaps from the walk cycle (the old "glitch to hang").
     */
    gaitWeight: 0,
    moveSpeed: 2.35,
    accel: 16,
    friction: 12,
    jumpSpeed: 4.6,
    gravity: 16.5,
    worldHalf: 9,
    camFollow: true,
    /** Last orbit-target used for camera-relative stick basis. */
    _prevTx: 0,
    _prevTy: 0,
    _prevTz: 0,
    _hasPrevTarget: false,
  };
  /**
   * Per-arm two-bone IK meta (bind quats, bone axes, lengths).
   * Arms are ragdolled: free hand points spring under gravity; bones IK to wrists.
   */
  var armMeta = { left: null, right: null };
  /** Reused THREE scratch for IK (allocated lazily). */
  var ikScratch = null;
  /**
   * Self-collision: hips-local body proxies (spheres + capsules) so wrists/elbows
   * do not sink through the torso, head, or clothing bulk. Rebuilt on calibrate.
   * Not true skinned-mesh collision (too heavy for mobile WebView) — wireframe
   * proxies approximate the body + clothes envelope for IK avoidance.
   */
  var bodyCollisionOn = true;
  /** @type {Array<{type:string,name:string,r?:number,x?:number,y?:number,z?:number,ax?:number,ay?:number,az?:number,bx?:number,by?:number,bz?:number,rx?:number,ry?:number,rz?:number}>|null} */
  var bodyColliders = null;
  /** Debug wire meshes for body colliders (child of debugGizmoRoot). */
  var debugColliderRoot = null;
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
    debugColliderRoot = null;
    disposeJointLabelDom();
    if (debugHudEl) {
      try {
        debugHudEl.style.display = "none";
        debugHudEl.textContent = "";
      } catch (_) {}
    }
  }

  /**
   * Wireframe body/clothes collision proxies (debug skeleton mode).
   * Parent under debugGizmoRoot; positions updated each frame in hips space.
   */
  function rebuildDebugColliders() {
    if (!debugSkeletonOn || !debugGizmoRoot) {
      debugColliderRoot = null;
      return;
    }
    var L = getLibs();
    if (!L || !L.THREE) return;
    var THREE = L.THREE;

    // Remove previous collider wire group if present.
    if (debugColliderRoot) {
      try {
        debugGizmoRoot.remove(debugColliderRoot);
        debugColliderRoot.traverse(function (obj) {
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
      debugColliderRoot = null;
    }
    if (!bodyColliders || !bodyColliders.length) return;

    debugColliderRoot = new THREE.Group();
    debugColliderRoot.name = "companion-body-colliders";
    debugColliderRoot.renderOrder = 998;

    var wireMat = new THREE.MeshBasicMaterial({
      color: 0xff9f43,
      wireframe: true,
      depthTest: false,
      depthWrite: false,
      transparent: true,
      opacity: 0.55,
    });
    var clothesMat = new THREE.MeshBasicMaterial({
      color: 0xa78bfa,
      wireframe: true,
      depthTest: false,
      depthWrite: false,
      transparent: true,
      opacity: 0.4,
    });

    for (var i = 0; i < bodyColliders.length; i++) {
      var c = bodyColliders[i];
      if (!c) continue;
      try {
        var mesh = null;
        var mat = c.name === "clothes" ? clothesMat : wireMat;
        if (c.type === "sphere") {
          mesh = new THREE.Mesh(
            new THREE.SphereGeometry(c.r || 0.08, 10, 8),
            mat
          );
          mesh.userData.colliderIndex = i;
          mesh.userData.colliderType = "sphere";
        } else if (c.type === "ellipsoid") {
          mesh = new THREE.Mesh(new THREE.SphereGeometry(1, 12, 10), mat);
          mesh.scale.set(c.rx || 0.1, c.ry || 0.1, c.rz || 0.1);
          mesh.userData.colliderIndex = i;
          mesh.userData.colliderType = "ellipsoid";
        } else if (c.type === "capsule") {
          var dx = (c.bx || 0) - (c.ax || 0);
          var dy = (c.by || 0) - (c.ay || 0);
          var dz = (c.bz || 0) - (c.az || 0);
          var len = Math.sqrt(dx * dx + dy * dy + dz * dz);
          var r = c.r || 0.1;
          // Cylinder body + end caps (CapsuleGeometry may exist in three 0.170).
          if (typeof THREE.CapsuleGeometry === "function") {
            mesh = new THREE.Mesh(
              new THREE.CapsuleGeometry(r, Math.max(0.001, len), 4, 8),
              mat
            );
          } else {
            mesh = new THREE.Mesh(
              new THREE.CylinderGeometry(r, r, Math.max(0.001, len), 8, 1, true),
              mat
            );
          }
          mesh.userData.colliderIndex = i;
          mesh.userData.colliderType = "capsule";
          mesh.userData.capLen = len;
        }
        if (mesh) {
          mesh.name = "body-col-" + (c.name || i);
          mesh.userData.pickable = false;
          mesh.renderOrder = 998;
          debugColliderRoot.add(mesh);
        }
      } catch (_) {}
    }
    debugGizmoRoot.add(debugColliderRoot);
    updateDebugColliderTransforms();
  }

  /** Place collider wire meshes at current hips-local proxies (world). */
  function updateDebugColliderTransforms() {
    if (!debugColliderRoot || !bodyColliders || !bodyColliders.length) return;
    var L = getLibs();
    if (!L || !L.THREE || !restBones || !restBones.hips) return;
    var THREE = L.THREE;
    var tmp = new THREE.Vector3();
    var tmpB = new THREE.Vector3();
    var up = new THREE.Vector3(0, 1, 0);
    var dir = new THREE.Vector3();
    var quat = new THREE.Quaternion();
    try {
      restBones.hips.updateWorldMatrix(true, false);
    } catch (_) {}

    debugColliderRoot.children.forEach(function (mesh) {
      var idx = mesh.userData.colliderIndex;
      var c = bodyColliders[idx];
      if (!c) {
        mesh.visible = false;
        return;
      }
      mesh.visible = true;
      try {
        if (c.type === "sphere" || c.type === "ellipsoid") {
          tmp.set(c.x || 0, c.y || 0, c.z || 0);
          tmp.applyMatrix4(restBones.hips.matrixWorld);
          mesh.position.copy(tmp);
          mesh.quaternion.identity();
          if (c.type === "ellipsoid") {
            mesh.scale.set(c.rx || 0.1, c.ry || 0.1, c.rz || 0.1);
          }
        } else if (c.type === "capsule") {
          tmp.set(c.ax || 0, c.ay || 0, c.az || 0);
          tmpB.set(c.bx || 0, c.by || 0, c.bz || 0);
          tmp.applyMatrix4(restBones.hips.matrixWorld);
          tmpB.applyMatrix4(restBones.hips.matrixWorld);
          mesh.position.copy(tmp).add(tmpB).multiplyScalar(0.5);
          dir.copy(tmpB).sub(tmp);
          if (dir.lengthSq() > 1e-10) {
            dir.normalize();
            quat.setFromUnitVectors(up, dir);
            mesh.quaternion.copy(quat);
          }
        }
      } catch (_) {
        mesh.visible = false;
      }
    });
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
    // Body / clothes collision wire proxies (if colliders already measured).
    try {
      if (!bodyColliders || !bodyColliders.length) rebuildBodyColliders();
      else rebuildDebugColliders();
    } catch (_) {}
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

    try {
      updateDebugColliderTransforms();
    } catch (_) {}

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
    // Keep actor registry entry / world pose; clear VRM handle until reload.
    if (actors.companion) {
      actors.companion.vrm = null;
    }
    restBones = null;
    armMeta.left = null;
    armMeta.right = null;
    ikElbowSign.left = 0;
    ikElbowSign.right = 0;
    walkWrist.left = null;
    walkWrist.right = null;
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
    // Fallback for older three-vrm builds.
    try {
      if (typeof vrm.humanoid.getBoneNode === "function") {
        return vrm.humanoid.getBoneNode(name) || null;
      }
    } catch (_) {}
    return null;
  }

  /**
   * Default hips-local body axes (VRM humanoid: +Z face-out, +X right, +Y up).
   * Custom / mis-authored VRMs often invert face-forward or spine pitch — we
   * overwrite these in measureBodyAxes() right after load.
   */
  function defaultBodyAxes() {
    return {
      forward: { x: 0, y: 0, z: 1 },
      right: { x: 1, y: 0, z: 0 },
      spinePitchSign: 1,
      headPitchSign: 1,
    };
  }

  function bodyAxes() {
    return (restBones && restBones.axes) || defaultBodyAxes();
  }

  /** +1 when +X euler on spine/chest bows toward face-forward; −1 if inverted. */
  function spinePitchSign() {
    var a = bodyAxes();
    return a.spinePitchSign < 0 ? -1 : 1;
  }

  function headPitchSign() {
    var a = bodyAxes();
    return a.headPitchSign < 0 ? -1 : 1;
  }

  /** Hips-local unit face-forward (mostly XZ). */
  function faceForward() {
    var f = bodyAxes().forward;
    if (f && typeof f.z === "number") return f;
    return { x: 0, y: 0, z: 1 };
  }

  /** Projection of a hips-local XZ delta onto face-forward (positive = toward face). */
  function alongFace(dx, dz) {
    var f = faceForward();
    return (dx || 0) * (f.x || 0) + (dz || 0) * (f.z || 0);
  }

  /**
   * How far a hips-local point sits *behind* a reference along face-forward
   * (positive = more behind the body — natural elbow fold side).
   */
  function behindAlongFace(point, ref) {
    if (!point || !ref) return 0;
    return alongFace((ref.x || 0) - (point.x || 0), (ref.z || 0) - (point.z || 0));
  }

  /**
   * Transform a bone-local direction into hips-local XZ unit (or null).
   * Used to read the authored face axis off head/chest instead of guessing +Z.
   */
  function hipsLocalDirFromBone(boneNode, lx, ly, lz) {
    var L = getLibs();
    if (!L || !L.THREE || !restBones || !restBones.hips || !boneNode) return null;
    try {
      restBones.hips.updateWorldMatrix(true, false);
      boneNode.updateWorldMatrix(true, false);
      var THREE = L.THREE;
      var world = new THREE.Vector3(lx, ly, lz);
      world.transformDirection(boneNode.matrixWorld);
      var qH = new THREE.Quaternion();
      restBones.hips.getWorldQuaternion(qH);
      world.applyQuaternion(qH.invert());
      world.y = 0;
      if (world.lengthSq() < 1e-8) return null;
      world.normalize();
      return { x: world.x, z: world.z };
    } catch (_) {
      return null;
    }
  }

  /**
   * Measure face-forward / body-right + spine/head pitch signs.
   * Call at bind (T-pose) before hang eulers are applied.
   *
   * Shoulder cross alone always yields +Z when arms are on ±X — it cannot
   * detect a face-inverted avatar. We disambiguate with head/chest local axes
   * and a tiny head-vs-hips offset, then probe pitch polarity.
   */
  function measureBodyAxes() {
    var axes = defaultBodyAxes();
    if (!restBones || !restBones.hips) return axes;
    try {
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
    } catch (_) {}

    var lSh = hipsLocalOf(restBones.leftUpperArm);
    var rSh = hipsLocalOf(restBones.rightUpperArm);
    var shFwd = null;
    if (lSh && rSh) {
      var rx = rSh.x - lSh.x;
      var ry = rSh.y - lSh.y;
      var rz = rSh.z - lSh.z;
      var rlen = Math.sqrt(rx * rx + ry * ry + rz * rz);
      if (rlen > 1e-4) {
        axes.right = { x: rx / rlen, y: ry / rlen, z: rz / rlen };
        // forward candidate = right × up → (−right.z, 0, right.x)
        var fx = -axes.right.z;
        var fz = axes.right.x;
        var flen = Math.sqrt(fx * fx + fz * fz);
        if (flen > 1e-4) {
          shFwd = { x: fx / flen, z: fz / flen };
        }
      }
    }

    // Score a unit XZ forward: head should sit slightly along it, and head-bone
    // local +Z (VRM face) should align with it when available.
    function scoreForward(fwd) {
      if (!fwd) return -1e9;
      var score = 0;
      var head = hipsLocalOf(restBones.head);
      var chest = hipsLocalOf(restBones.chest || restBones.upperChest || restBones.spine);
      if (head) {
        // Head slightly in front of hips origin along face (many VRMs ~+Z).
        score += (head.x * fwd.x + head.z * fwd.z) * 2.5;
      }
      if (head && chest) {
        score +=
          ((head.x - chest.x) * fwd.x + (head.z - chest.z) * fwd.z) * 3.5;
      }
      // Head bone local axes — VRM face is usually local +Z.
      var candidates = [
        hipsLocalDirFromBone(restBones.head, 0, 0, 1),
        hipsLocalDirFromBone(restBones.head, 0, 0, -1),
        hipsLocalDirFromBone(restBones.head, 0, 1, 0),
        hipsLocalDirFromBone(restBones.chest || restBones.spine, 0, 0, 1),
        hipsLocalDirFromBone(restBones.chest || restBones.spine, 0, 0, -1),
      ];
      var ci;
      for (ci = 0; ci < candidates.length; ci++) {
        var c = candidates[ci];
        if (!c) continue;
        var al = c.x * fwd.x + c.z * fwd.z;
        if (al > score) {
          /* keep best axis alignment as bonus */
        }
        score += Math.max(0, al) * 1.8;
      }
      // Soft prior: viewer / camera when already framed.
      try {
        var cam = cameraHipsLocal();
        if (cam) {
          var cdx = cam.x;
          var cdz = cam.z;
          var cl = Math.sqrt(cdx * cdx + cdz * cdz);
          if (cl > 0.15) {
            score += ((cdx / cl) * fwd.x + (cdz / cl) * fwd.z) * 1.1;
          }
        }
      } catch (_) {}
      return score;
    }

    var bestFwd = shFwd || { x: 0, z: 1 };
    var bestScore = scoreForward(bestFwd);
    var alt = { x: -bestFwd.x, z: -bestFwd.z };
    var altScore = scoreForward(alt);
    // Also try pure ±Z / ±X in case shoulders were mislabeled.
    var extras = [
      { x: 0, z: 1 },
      { x: 0, z: -1 },
      { x: 1, z: 0 },
      { x: -1, z: 0 },
    ];
    var ei;
    for (ei = 0; ei < extras.length; ei++) {
      var es = scoreForward(extras[ei]);
      if (es > bestScore) {
        bestScore = es;
        bestFwd = extras[ei];
      }
    }
    if (altScore > bestScore + 0.05) {
      bestFwd = alt;
      bestScore = altScore;
    }
    axes.forward = { x: bestFwd.x, y: 0, z: bestFwd.z };
    // Re-derive right = up × forward so handedness matches face.
    // (0,1,0) × (fx,0,fz) = (fz, 0, -fx)
    axes.right = {
      x: axes.forward.z,
      y: 0,
      z: -axes.forward.x,
    };

    function probePitchSign(boneKey, amount) {
      var node = restBones[boneKey];
      var b = restBones.bindEuler && restBones.bindEuler[boneKey];
      var tip = restBones.head || restBones.chest || restBones.spine;
      if (!node || !b || !tip) return 1;
      var before = hipsLocalOf(tip);
      if (!before) return 1;
      setEuler(node, b.x + amount, b.y, b.z);
      try {
        if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
      } catch (_) {}
      var after = hipsLocalOf(tip);
      if (restBones.bindQ && restBones.bindQ[boneKey]) {
        node.quaternion.copy(restBones.bindQ[boneKey]);
      } else {
        setEuler(node, b.x, b.y, b.z);
      }
      try {
        if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
      } catch (_) {}
      if (!after) return 1;
      var along = alongFace(after.x - before.x, after.z - before.z);
      var drop = before.y - after.y;
      var score = along * 2.2 + drop * 0.8;
      return score >= 0 ? 1 : -1;
    }

    try {
      restBones.axes = axes;
      axes.spinePitchSign = probePitchSign("spine", 0.4);
      if (restBones.chest && restBones.bindEuler && restBones.bindEuler.chest) {
        var chestSign = probePitchSign("chest", 0.35);
        if (chestSign !== axes.spinePitchSign) {
          // Chest is closer to the head tip — prefer it when they disagree.
          axes.spinePitchSign = chestSign;
        }
      }
      axes.headPitchSign = probePitchSign("head", 0.35);
      if (!axes.headPitchSign) axes.headPitchSign = axes.spinePitchSign;
    } catch (_) {}

    try {
      console.log(
        "[CompanionStage] body axes",
        "fwd=" + axes.forward.x.toFixed(2) + "," + axes.forward.z.toFixed(2),
        "spinePitch=" + axes.spinePitchSign,
        "headPitch=" + axes.headPitchSign,
        "score=" + (typeof bestScore === "number" ? bestScore.toFixed(2) : "?")
      );
    } catch (_) {}
    return axes;
  }

  /** Flip hang torso X deltas when spine +X is not a forward bow. */
  function applyPitchSignToHangDeltas(deltas) {
    if (!deltas) return;
    var ps = spinePitchSign();
    if (ps >= 0) return;
    ["hips", "spine", "chest", "upperChest", "neck", "head"].forEach(function (k) {
      if (deltas[k] && deltas[k].length >= 1) {
        deltas[k][0] *= ps;
      }
    });
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
      leftShoulder: bone("leftShoulder"),
      rightShoulder: bone("rightShoulder"),
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
      leftFoot: bone("leftFoot"),
      rightFoot: bone("rightFoot"),
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

    // Face-forward + spine pitch polarity for THIS avatar (before hang mutates bones).
    restBones.axes = measureBodyAxes();

    // Per-avatar hang deltas (which local axis drops the hand) + apply.
    restBones.hangDeltas = solveHangDeltas();
    applyPitchSignToHangDeltas(restBones.hangDeltas);
    // Knee/elbow hinge + swing axes so walk/idle never hyperextend on any VRM.
    restBones.hinge = solveLimbHinges();
    // Idle hang elbows/knees must use the same flex direction as walk (fixes
    // "idle elbows bend backwards" on custom VRMs that flex on −X).
    rewriteHangDeltasWithHinges(restBones.hangDeltas, restBones.hinge);
    applySoftHangEulers();

    // Base eulers = soft hang (torso / idle fallback uses addEuler from these).
    restBones.base = {};
    Object.keys(restBones).forEach(function (k) {
      if (
        k === "base" ||
        k === "bindQ" ||
        k === "bindEuler" ||
        k === "hangDeltas" ||
        k === "hinge" ||
        k === "axes"
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
    try {
      seedElbowSignFromHang("left");
      seedElbowSignFromHang("right");
    } catch (_) {}

    statePose = emptyPose();
    statePoseTarget = emptyPose();
    poseVariant = 0;
    poseVariantT = 0;
    poseBlend = 0;
    nodPulse = 0;
    nodNextT = 2.5 + Math.random() * 2;
    activeGesture = null;
    // Remeasure after a few frames once the scene graph world matrices settle.
    restRecalibLeft = 10;
    postFitRecalib = true;
    // Ease arms into hang (start slightly raised so load never pops into a lock).
    hangEase = 0;
    try {
      var rL = armReachLen("left");
      var rR = armReachLen("right");
      if (!vr.left.locked) {
        vr.left.x = vr.restLeft.x;
        vr.left.y = vr.restLeft.y + rL * 0.16;
        vr.left.z = vr.restLeft.z;
        vr.left.vx = vr.left.vy = vr.left.vz = 0;
      }
      if (!vr.right.locked) {
        vr.right.x = vr.restRight.x;
        vr.right.y = vr.restRight.y + rR * 0.16;
        vr.right.z = vr.restRight.z;
        vr.right.vx = vr.right.vy = vr.right.vz = 0;
      }
      vr.settleUntil = Math.max(vr.settleUntil || 0, idleTime + 1.45);
    } catch (_) {}
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
            // Prefer hands slightly face-forward of the shoulder (not behind back).
            var handFwd = alongFace(pos.x - sh.x, pos.z - sh.z);
            // Penalize crossing deep through midline.
            var cross =
              side === "left"
                ? Math.max(0, pos.x - sh.x * 0.15)
                : Math.max(0, sh.x * 0.15 - pos.x);
            score =
              drop * 1.15 +
              Math.max(0, Math.min(out, 0.14)) * 0.45 +
              Math.max(-0.05, Math.min(handFwd, 0.12)) * 0.9 -
              cross * 0.8 -
              Math.max(0, -handFwd - 0.04) * 1.6;
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
   * Default limb hinges (VRM-ish): knees flex with -X, elbows with +X, hips/arms
   * swing on +X. solveLimbHinges() overwrites from live bone trials per avatar.
   */
  function defaultLimbHinges() {
    return {
      leftKnee: [-1, 0, 0],
      rightKnee: [-1, 0, 0],
      leftElbow: [1, 0, 0],
      rightElbow: [1, 0, 0],
      leftHip: [1, 0, 0],
      rightHip: [1, 0, 0],
      leftArm: [1, 0, 0],
      rightArm: [1, 0, 0],
    };
  }

  /**
   * Probe which local euler axis+sign on a hinge bone moves the tip as intended.
   * Used so procedural walk never bends knees/elbows backwards on odd skeletons.
   *
   * Elbow note: from a straight T-pose, +axis and −axis both shorten hand↔shoulder
   * almost equally (symmetric isosceles). Pure distance is a coin-flip and was
   * the root of "elbows bend backwards after walk→idle". Elbow scoring must
   * break that symmetry (hand toward midline + elbow slightly behind).
   */
  function solveLimbHinges() {
    var out = defaultLimbHinges();
    if (!restBones || !restBones.bindEuler || !restBones.bindQ) return out;

    function restoreNode(key) {
      var n = restBones[key];
      if (!n) return;
      if (restBones.bindQ[key]) n.quaternion.copy(restBones.bindQ[key]);
      else if (restBones.bindEuler[key]) {
        var b = restBones.bindEuler[key];
        setEuler(n, b.x, b.y, b.z);
      }
    }

    function worldPos(node, target) {
      if (!node) return null;
      try {
        node.updateWorldMatrix(true, false);
        if (!target) target = new (getLibs().THREE.Vector3)();
        node.getWorldPosition(target);
        return target;
      } catch (_) {
        return null;
      }
    }

    function trialHinge(hingeKey, tipKey, axis, sign, amount) {
      restoreNode(hingeKey);
      restoreNode(tipKey);
      var hinge = restBones[hingeKey];
      var b = restBones.bindEuler[hingeKey];
      if (!hinge || !b) return null;
      var dx = axis === 0 ? sign * amount : 0;
      var dy = axis === 1 ? sign * amount : 0;
      var dz = axis === 2 ? sign * amount : 0;
      setEuler(hinge, b.x + dx, b.y + dy, b.z + dz);
      try {
        if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
      } catch (_) {}
      return worldPos(restBones[tipKey]);
    }

    /**
     * Knee: bend should raise the foot and pull it toward the hip (not hyperextend).
     * Elbow: shorten hand↔shoulder AND hand toward midline AND elbow behind
     * (hips −Z). Distance alone is ±symmetric on a straight T-pose arm.
     */
    function solveHinge(hingeKey, tipKey, anchorKey, mode) {
      var tip0 = worldPos(restBones[tipKey]);
      var anc0 = worldPos(restBones[anchorKey]);
      if (!tip0 || !anc0) return null;
      var best = null;
      var axes = [0, 1, 2];
      var signs = [1, -1];
      var amount = mode === "elbow" ? 0.9 : 0.75;
      var i, j, tip, score, dist0, dist, lift;
      var THREE, inv, tipLocal0, tipLocal1, elbLocal0, elbLocal1, elb0, elb1;
      dist0 = tip0.distanceTo(anc0);
      try {
        THREE = getLibs().THREE;
        inv = new THREE.Matrix4().copy(restBones.hips.matrixWorld).invert();
        tipLocal0 = tip0.clone().applyMatrix4(inv);
        // Elbow joint ≈ lower-arm bone origin (hinge bone itself for arms).
        elb0 = worldPos(restBones[hingeKey]);
        elbLocal0 = elb0 ? elb0.clone().applyMatrix4(inv) : null;
      } catch (_) {
        inv = null;
      }
      for (i = 0; i < axes.length; i++) {
        for (j = 0; j < signs.length; j++) {
          tip = trialHinge(hingeKey, tipKey, axes[i], signs[j], amount);
          if (!tip) continue;
          dist = tip.distanceTo(anc0);
          lift = tip.y - tip0.y;
          if (mode === "knee") {
            // Prefer shorter tip↔hip + foot lifts off floor slightly.
            score = dist0 - dist + Math.max(0, lift) * 0.8 - Math.max(0, -lift) * 1.5;
          } else {
            // Elbow: break ± symmetry. Pure shorten is ~equal for both signs.
            score = (dist0 - dist) * 1.2;
            if (inv && THREE) {
              tipLocal1 = tip.clone().applyMatrix4(inv);
              // Hand moves toward body midline (T-pose hands are far out).
              score += (Math.abs(tipLocal0.x) - Math.abs(tipLocal1.x)) * 2.2;
              // Mild preference for hand dropping (natural flex from T).
              score += (tipLocal0.y - tipLocal1.y) * 0.55;
              // Hand a bit more face-forward when flexing (not hips +Z assumption).
              score +=
                alongFace(tipLocal1.x - tipLocal0.x, tipLocal1.z - tipLocal0.z) * 0.35;
              elb1 = worldPos(restBones[hingeKey]);
              if (elb1 && elbLocal0) {
                elbLocal1 = elb1.clone().applyMatrix4(inv);
                // Elbow goes slightly behind face-forward — natural arm fold.
                score += behindAlongFace(elbLocal1, elbLocal0) * 1.6;
                // Keep elbow from diving deep through the torso (prefer stay out).
                score += (Math.abs(elbLocal1.x) - Math.abs(elbLocal0.x) * 0.4) * 0.25;
              }
            }
          }
          if (!best || score > best.score) {
            best = {
              score: score,
              axis: axes[i],
              sign: signs[j],
            };
          }
        }
      }
      restoreNode(hingeKey);
      restoreNode(tipKey);
      if (!best || best.score < 0.01) return null;
      var v = [0, 0, 0];
      v[best.axis] = best.sign;
      return v;
    }

    /**
     * Swing: positive amount should move tip forward in hips space (+Z local after
     * hips, or largest horizontal travel). Used for upper leg / upper arm gait.
     */
    function solveSwing(hingeKey, tipKey) {
      var hips = restBones.hips;
      if (!hips) return null;
      var tip0 = worldPos(restBones[tipKey]);
      if (!tip0) return null;
      var best = null;
      var amount = 0.55;
      var axes = [0, 1, 2];
      var signs = [1, -1];
      var i, j, tip, score, inv, local0, local1, THREE;
      try {
        THREE = getLibs().THREE;
        inv = new THREE.Matrix4().copy(hips.matrixWorld).invert();
        local0 = tip0.clone().applyMatrix4(inv);
      } catch (_) {
        return null;
      }
      for (i = 0; i < axes.length; i++) {
        for (j = 0; j < signs.length; j++) {
          tip = trialHinge(hingeKey, tipKey, axes[i], signs[j], amount);
          if (!tip) continue;
          local1 = tip.clone().applyMatrix4(inv);
          // Forward = measured face-forward (not always hips +Z).
          score = alongFace(local1.x - local0.x, local1.z - local0.z);
          // Arms hang at sides: also accept largest |horizontal| if forward weak.
          if (Math.abs(score) < 0.02) {
            score =
              alongFace(local1.x - local0.x, local1.z - local0.z) * 2 +
              Math.abs(local1.x - local0.x) * 0.2;
          }
          if (!best || score > best.score) {
            best = { score: score, axis: axes[i], sign: signs[j] };
          }
        }
      }
      restoreNode(hingeKey);
      restoreNode(tipKey);
      if (!best) return null;
      var v = [0, 0, 0];
      v[best.axis] = best.sign;
      return v;
    }

    try {
      var lk = solveHinge("leftLowerLeg", "leftFoot", "leftUpperLeg", "knee");
      // tip may be leftFoot missing — fall back to lowerLeg child / measure via lowerLeg end
      if (!lk) lk = solveHinge("leftLowerLeg", "leftLowerLeg", "leftUpperLeg", "knee");
      var rk = solveHinge("rightLowerLeg", "rightFoot", "rightUpperLeg", "knee");
      if (!rk) rk = solveHinge("rightLowerLeg", "rightLowerLeg", "rightUpperLeg", "knee");
      var le = solveHinge("leftLowerArm", "leftHand", "leftUpperArm", "elbow");
      var re = solveHinge("rightLowerArm", "rightHand", "rightUpperArm", "elbow");
      var lh = solveSwing("leftUpperLeg", "leftLowerLeg");
      var rh = solveSwing("rightUpperLeg", "rightLowerLeg");
      var la = solveSwing("leftUpperArm", "leftHand");
      var ra = solveSwing("rightUpperArm", "rightHand");
      if (lk) out.leftKnee = lk;
      if (rk) out.rightKnee = rk;
      if (le) out.leftElbow = le;
      if (re) out.rightElbow = re;
      if (lh) out.leftHip = lh;
      if (rh) out.rightHip = rh;
      if (la) out.leftArm = la;
      if (ra) out.rightArm = ra;
      // Second pass: confirm elbow sign from a soft-hang upper arm (not T-pose).
      // Walk→idle hands off to IK using the same hinge; wrong sign = backwards elbows.
      try {
        validateElbowHingeSign(out, "left");
        validateElbowHingeSign(out, "right");
      } catch (_) {}
      try {
        console.log(
          "[CompanionStage] limb hinges",
          "kneeL=" + out.leftKnee,
          "kneeR=" + out.rightKnee,
          "elbL=" + out.leftElbow,
          "elbR=" + out.rightElbow
        );
      } catch (_) {}
    } catch (e) {
      try {
        console.warn("[CompanionStage] limb hinge solve failed", (e && e.message) || e);
      } catch (_) {}
    }

    // Restore full soft hang after probing from bind.
    try {
      applySoftHangEulers();
    } catch (_) {}
    return out;
  }

  /**
   * From soft-hang upper arms, try both elbow signs and keep the one where the
   * elbow joint sits more behind the shoulder→hand chord (natural, not hyper).
   */
  function validateElbowHingeSign(hinges, side) {
    if (!hinges || !restBones || !restBones.bindEuler || !restBones.hangDeltas) return;
    var isLeft = side === "left";
    var upperKey = isLeft ? "leftUpperArm" : "rightUpperArm";
    var lowerKey = isLeft ? "leftLowerArm" : "rightLowerArm";
    var handKey = isLeft ? "leftHand" : "rightHand";
    var elbowKey = isLeft ? "leftElbow" : "rightElbow";
    var upper = restBones[upperKey];
    var lower = restBones[lowerKey];
    var hand = restBones[handKey];
    var bU = restBones.bindEuler[upperKey];
    var bL = restBones.bindEuler[lowerKey];
    var bH = restBones.bindEuler[handKey];
    var dU = restBones.hangDeltas[upperKey];
    if (!upper || !lower || !hand || !bU || !bL || !dU) return;

    var L = getLibs();
    if (!L || !L.THREE || !restBones.hips) return;
    var THREE = L.THREE;
    var amount = 0.55;
    var cur = hinges[elbowKey] || [1, 0, 0];

    function scoreFlex(hingeVec) {
      // Hang upper arm, flex lower along hingeVec from bind.
      setEuler(upper, bU.x + dU[0], bU.y + dU[1], bU.z + dU[2]);
      setEuler(
        lower,
        bL.x + (hingeVec[0] || 0) * amount,
        bL.y + (hingeVec[1] || 0) * amount,
        bL.z + (hingeVec[2] || 0) * amount
      );
      if (bH) setEuler(hand, bH.x, bH.y, bH.z);
      try {
        restBones.hips.updateWorldMatrix(true, true);
      } catch (_) {
        return -1e9;
      }
      var sh = new THREE.Vector3();
      var el = new THREE.Vector3();
      var hd = new THREE.Vector3();
      try {
        upper.getWorldPosition(sh);
        lower.getWorldPosition(el);
        hand.getWorldPosition(hd);
      } catch (_) {
        return -1e9;
      }
      var inv = new THREE.Matrix4().copy(restBones.hips.matrixWorld).invert();
      var shL = sh.clone().applyMatrix4(inv);
      var elL = el.clone().applyMatrix4(inv);
      var hdL = hd.clone().applyMatrix4(inv);
      // Chord mid; elbow should sit behind face-forward (natural fold).
      var mid = {
        x: (shL.x + hdL.x) * 0.5,
        y: (shL.y + hdL.y) * 0.5,
        z: (shL.z + hdL.z) * 0.5,
      };
      var behind = behindAlongFace(elL, mid);
      var torsoBehind = behindAlongFace(elL, { x: 0, y: 0, z: 0 });
      // Prefer elbow slightly out from midline.
      var brH = bodyAxes().right || { x: 1, y: 0, z: 0 };
      var elSide =
        (elL.x - shL.x) * (brH.x || 0) + (elL.z - shL.z) * (brH.z || 0);
      var elbowOut = isLeft ? -elSide : elSide;
      if (!isFinite(elbowOut)) {
        elbowOut = isLeft ? shL.x - elL.x : elL.x - shL.x;
      }
      var reach = sh.distanceTo(hd);
      return (
        behind * 3.4 +
        torsoBehind * 1.2 +
        Math.max(0, elbowOut) * 0.9 -
        reach * 0.15 -
        Math.max(0, -behind) * 1.8
      );
    }

    var flipped = [-(cur[0] || 0), -(cur[1] || 0), -(cur[2] || 0)];
    var sCur = scoreFlex(cur);
    var sFlip = scoreFlex(flipped);
    if (sFlip > sCur + 0.02) {
      hinges[elbowKey] = flipped;
      try {
        console.log(
          "[CompanionStage] elbow hinge flipped for natural bend",
          side,
          flipped
        );
      } catch (_) {}
    }
    // Restore arm bind so later hang apply is clean.
    if (restBones.bindQ[upperKey]) upper.quaternion.copy(restBones.bindQ[upperKey]);
    if (restBones.bindQ[lowerKey]) lower.quaternion.copy(restBones.bindQ[lowerKey]);
    if (restBones.bindQ[handKey]) hand.quaternion.copy(restBones.bindQ[handKey]);
  }

  /** Apply amount along a unit hinge vector onto a bone (from soft-hang base). */
  function addHinge(node, key, hingeVec, amount) {
    if (!hingeVec || !node) return;
    addEuler(
      node,
      key,
      (hingeVec[0] || 0) * amount,
      (hingeVec[1] || 0) * amount,
      (hingeVec[2] || 0) * amount
    );
  }

  /**
   * After hinge probes, rewrite lower-arm / lower-leg hang deltas so idle soft
   * hang flexes the same way as walk (never bends elbows/knees "backwards").
   */
  function rewriteHangDeltasWithHinges(deltas, H) {
    if (!deltas || !H) return;
    function along(hinge, amount, yBias, zBias) {
      if (!hinge) return null;
      return [
        (hinge[0] || 0) * amount,
        (hinge[1] || 0) * amount + (yBias || 0),
        (hinge[2] || 0) * amount + (zBias || 0),
      ];
    }
    // ~22° natural elbow hang flex along probed axis.
    var le = along(H.leftElbow, 0.38, 0.03, 0.04);
    var re = along(H.rightElbow, 0.38, -0.03, -0.04);
    if (le) deltas.leftLowerArm = le;
    if (re) deltas.rightLowerArm = re;
    // Soft knee micro-flex (idle weight) — same flex direction as plant.
    var lk = along(H.leftKnee, 0.07, 0, 0);
    var rk = along(H.rightKnee, 0.055, 0, 0);
    if (lk) deltas.leftLowerLeg = lk;
    if (rk) deltas.rightLowerLeg = rk;
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
        k === "hangDeltas" ||
        k === "hinge" ||
        k === "axes"
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
   * Stops mixer influence, reapplies hang eulers. Wrists ease home via spring
   * unless opts.snapHands (hard reset) — avoids the idle "glitch pop".
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
      // Capture live wrists BEFORE hang eulers overwrite bones (VRMA end pose).
      if (!opts.snapHands) {
        try {
          sampleWristsFromBones();
        } catch (_) {}
      }
      // Keep hinge-aware elbow/knee flex on every hang restore.
      if (restBones.hinge && restBones.hangDeltas) {
        rewriteHangDeltasWithHinges(restBones.hangDeltas, restBones.hinge);
      }
      applySoftHangEulers();
      if (opts.recaptureBase) captureBaseFromBones();
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
      if (opts.recalibrate !== false) {
        // Shoulders from current hang; never teleport free wrists on soft restore.
        calibrateVrRestsFromBones({ preserveHands: !opts.snapHands });
      }
      // Ease free hands toward rest — never teleport (snapHands opt-in only).
      if (opts.snapHands) {
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
      } else {
        // Soft settle window: extra-low spring until hands drift home.
        vr.settleUntil = Math.max(vr.settleUntil || 0, idleTime + 1.55);
        if (!vr.left.locked) {
          vr.left.vx *= 0.4;
          vr.left.vy *= 0.4;
          vr.left.vz *= 0.4;
        }
        if (!vr.right.locked) {
          vr.right.vx *= 0.4;
          vr.right.vy *= 0.4;
          vr.right.vz *= 0.4;
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
      elbowAlt: new T.Vector3(),
      target: new T.Vector3(),
      toTarget: new T.Vector3(),
      forward: new T.Vector3(),
      bend: new T.Vector3(),
      bendAlt: new T.Vector3(),
      pole: new T.Vector3(),
      poleRef: new T.Vector3(),
      side: new T.Vector3(),
      axis: new T.Vector3(),
      dir: new T.Vector3(),
      q: new T.Quaternion(),
      qParent: new T.Quaternion(),
      qInv: new T.Quaternion(),
      invHips: new T.Matrix4(),
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
   * Human-ish shoulder cone — distance clamp alone still allows camera-chasing
   * targets that wrap across the chest or deep behind the back (inhuman bends).
   * Limits: own-side bias, mild cross only, not deep behind, height band.
   * @param {{allowCross?:boolean, allowBack?:boolean}} opts
   *   allowCross — clap / crossed arms may sit past midline
   *   allowBack — hands-on-hips may sit slightly behind
   */
  function clampToHumanArmWorkspace(side, x, y, z, maxFrac, opts) {
    opts = opts || {};
    maxFrac = typeof maxFrac === "number" ? maxFrac : 0.88;
    var isLeft = side === "left";
    var sh = isLeft ? vr.shoulderLeft : vr.shoulderRight;
    if (!sh) return { x: x, y: y, z: z };
    var reach = armReachLen(side);
    if (!(reach > 1e-4)) return clampToArmReach(side, x, y, z, maxFrac);

    var face = faceForward();
    var fx = face.x || 0;
    var fz = typeof face.z === "number" ? face.z : 1;
    var fl = Math.sqrt(fx * fx + fz * fz) || 1;
    fx /= fl;
    fz /= fl;
    var br = bodyAxes().right || { x: 1, y: 0, z: 0 };
    var rx = br.x || 1;
    var rz = br.z || 0;
    var rl = Math.sqrt(rx * rx + rz * rz) || 1;
    rx /= rl;
    rz /= rl;

    // Height band first (head / full hang).
    var yMax = sh.y + reach * 0.92;
    var yMin = sh.y - reach * 1.08;
    y = clamp(y, yMin, yMax);

    var ox = x - sh.x;
    var oz = z - sh.z;
    var sideAmt = ox * rx + oz * rz; // + = body-right
    var ownSide = isLeft ? -sideAmt : sideAmt;
    // Allow small midline cross (clap/think) but never full wrap to other hip.
    var maxCross = reach * (opts.allowCross ? 0.42 : 0.2);
    var maxOut = reach * 0.9;
    if (ownSide < -maxCross) {
      var targetSideAmt = isLeft ? maxCross : -maxCross;
      var dSide = targetSideAmt - sideAmt;
      x += rx * dSide;
      z += rz * dSide;
      ox = x - sh.x;
      oz = z - sh.z;
      sideAmt = ox * rx + oz * rz;
      ownSide = isLeft ? -sideAmt : sideAmt;
    }
    if (ownSide > maxOut) {
      var targetOutAmt = isLeft ? -maxOut : maxOut;
      var dOut = targetOutAmt - sideAmt;
      x += rx * dOut;
      z += rz * dOut;
      ox = x - sh.x;
      oz = z - sh.z;
    }

    // Front/back along face: allow slight back (hips pose) but not behind spine.
    var frontAmt = ox * fx + oz * fz;
    var minFront = -reach * (opts.allowBack ? 0.32 : 0.18);
    var maxFront = reach * 0.78;
    if (frontAmt < minFront) {
      var dBack = minFront - frontAmt;
      x += fx * dBack;
      z += fz * dBack;
    } else if (frontAmt > maxFront) {
      var dFwd = maxFront - frontAmt;
      x += fx * dFwd;
      z += fz * dFwd;
    }

    return clampToArmReach(side, x, y, z, maxFrac);
  }

  /**
   * Measure hips-local sphere/capsule proxies for torso, head, hips, and shoulder
   * bulk (clothes envelope). Cheap — safe to call every frame before arm IK.
   */
  function measureBodyColliders() {
    bodyColliders = [];
    var lSh = vr.shoulderLeft || { x: -0.16, y: 1.22, z: 0 };
    var rSh = vr.shoulderRight || { x: 0.16, y: 1.22, z: 0 };
    // Prefer live shoulder samples when available (breath/sway).
    var liveL = hipsLocalOf(restBones && restBones.leftUpperArm);
    var liveR = hipsLocalOf(restBones && restBones.rightUpperArm);
    if (liveL) lSh = liveL;
    if (liveR) rSh = liveR;
    var head =
      hipsLocalOf(restBones && restBones.head) ||
      vr.restHead || { x: 0, y: 1.45, z: 0.05 };
    var hips =
      hipsLocalOf(restBones && restBones.hips) || { x: 0, y: 0, z: 0 };
    var chest =
      hipsLocalOf(restBones && (restBones.upperChest || restBones.chest)) || {
        x: 0,
        y: (lSh.y + rSh.y) * 0.5 - 0.08,
        z: 0,
      };
    var midChest =
      hipsLocalOf(restBones && restBones.chest) || {
        x: (chest.x + hips.x) * 0.5,
        y: (chest.y + hips.y) * 0.55,
        z: (chest.z + hips.z) * 0.5,
      };
    var neck =
      hipsLocalOf(restBones && restBones.neck) || {
        x: head.x,
        y: (head.y + chest.y) * 0.5,
        z: head.z,
      };

    var shoulderSpan = Math.hypot(rSh.x - lSh.x, rSh.z - lSh.z) || 0.32;
    var halfW = shoulderSpan * 0.5;
    // Clothes pad: inflate torso so arms avoid mesh bulk, not just skeleton.
    var cloth = 1.14;
    var torsoR = Math.max(0.1, halfW * 0.52 * cloth);
    var hipR = Math.max(0.11, halfW * 0.48 * cloth);
    var chestR = Math.max(0.1, halfW * 0.5 * cloth);
    var headR = Math.max(0.1, halfW * 0.42);
    var shoulderR = Math.max(0.06, halfW * 0.28);

    // Pelvis / hip bulk
    bodyColliders.push({
      type: "sphere",
      name: "hips",
      x: hips.x,
      y: hips.y + hipR * 0.15,
      z: hips.z,
      r: hipR,
    });
    // Main torso capsule hips → upper chest (primary body + clothing wire)
    bodyColliders.push({
      type: "capsule",
      name: "torso",
      ax: hips.x,
      ay: hips.y + hipR * 0.35,
      az: hips.z,
      bx: chest.x,
      by: chest.y,
      bz: chest.z,
      r: torsoR,
    });
    // Front/back clothing ellipsoid (slightly deeper on Z for coats/shirts)
    bodyColliders.push({
      type: "ellipsoid",
      name: "clothes",
      x: midChest.x,
      y: midChest.y,
      z: midChest.z + torsoR * 0.05,
      rx: torsoR * 0.95,
      ry: Math.max(0.14, (chest.y - hips.y) * 0.42),
      rz: torsoR * 1.12,
    });
    bodyColliders.push({
      type: "sphere",
      name: "chest",
      x: chest.x,
      y: chest.y,
      z: chest.z,
      r: chestR,
    });
    bodyColliders.push({
      type: "sphere",
      name: "neck",
      x: neck.x,
      y: neck.y,
      z: neck.z,
      r: headR * 0.55,
    });
    bodyColliders.push({
      type: "sphere",
      name: "head",
      x: head.x,
      y: head.y,
      z: head.z,
      r: headR,
    });
    // Shoulder caps — keep upper arms from clipping into clavicle/clothes
    bodyColliders.push({
      type: "sphere",
      name: "shoulderL",
      x: lSh.x * 0.72,
      y: lSh.y - shoulderR * 0.15,
      z: lSh.z,
      r: shoulderR,
    });
    bodyColliders.push({
      type: "sphere",
      name: "shoulderR",
      x: rSh.x * 0.72,
      y: rSh.y - shoulderR * 0.15,
      z: rSh.z,
      r: shoulderR,
    });
  }

  /** Full rebuild: measure proxies + refresh debug wire meshes if skeleton debug is on. */
  function rebuildBodyColliders() {
    measureBodyColliders();
    if (debugSkeletonOn) {
      try {
        rebuildDebugColliders();
      } catch (_) {}
    }
  }

  /** Push a hips-local point out of one sphere collider. */
  function resolveSphereCollider(px, py, pz, cx, cy, cz, r) {
    if (!(r > 0)) return { x: px, y: py, z: pz, hit: false };
    var dx = px - cx;
    var dy = py - cy;
    var dz = pz - cz;
    var d2 = dx * dx + dy * dy + dz * dz;
    var r2 = r * r;
    if (d2 >= r2) return { x: px, y: py, z: pz, hit: false };
    if (d2 < 1e-12) {
      // Degenerate: push outward along +X (or body side preference later).
      return { x: cx + r, y: cy, z: cz, hit: true };
    }
    var d = Math.sqrt(d2);
    var s = r / d;
    return { x: cx + dx * s, y: cy + dy * s, z: cz + dz * s, hit: true };
  }

  /** Capsule = sphere swept along segment a→b. */
  function resolveCapsuleCollider(px, py, pz, ax, ay, az, bx, by, bz, r) {
    var abx = bx - ax;
    var aby = by - ay;
    var abz = bz - az;
    var apx = px - ax;
    var apy = py - ay;
    var apz = pz - az;
    var ab2 = abx * abx + aby * aby + abz * abz;
    var t = ab2 > 1e-12 ? (apx * abx + apy * aby + apz * abz) / ab2 : 0;
    t = clamp(t, 0, 1);
    return resolveSphereCollider(
      px,
      py,
      pz,
      ax + abx * t,
      ay + aby * t,
      az + abz * t,
      r
    );
  }

  /** Axis-aligned ellipsoid (good clothes bulk without full mesh). */
  function resolveEllipsoidCollider(px, py, pz, cx, cy, cz, rx, ry, rz) {
    if (!(rx > 1e-6) || !(ry > 1e-6) || !(rz > 1e-6)) {
      return { x: px, y: py, z: pz, hit: false };
    }
    var dx = (px - cx) / rx;
    var dy = (py - cy) / ry;
    var dz = (pz - cz) / rz;
    var d2 = dx * dx + dy * dy + dz * dz;
    if (d2 >= 1 || d2 < 1e-12) {
      if (d2 >= 1) return { x: px, y: py, z: pz, hit: false };
      return { x: cx + rx, y: cy, z: cz, hit: true };
    }
    var d = Math.sqrt(d2);
    var s = 1 / d;
    return {
      x: cx + dx * s * rx,
      y: cy + dy * s * ry,
      z: cz + dz * s * rz,
      hit: true,
    };
  }

  /**
   * Resolve a hips-local point against all body colliders.
   * @param pad extra radius (m) — wrists get a bit more than elbows
   * @param preferSide "left"|"right"|null — bias push direction for near-center hits
   */
  function resolvePointAgainstBody(px, py, pz, pad, preferSide) {
    if (!bodyCollisionOn) return { x: px, y: py, z: pz };
    if (!bodyColliders || !bodyColliders.length) return { x: px, y: py, z: pz };
    pad = typeof pad === "number" ? pad : 0.02;
    var x = px;
    var y = py;
    var z = pz;
    // Iterate a few times so stacked colliders settle.
    for (var pass = 0; pass < 3; pass++) {
      var any = false;
      for (var i = 0; i < bodyColliders.length; i++) {
        var c = bodyColliders[i];
        if (!c) continue;
        var out = null;
        if (c.type === "sphere") {
          out = resolveSphereCollider(x, y, z, c.x, c.y, c.z, (c.r || 0) + pad);
        } else if (c.type === "capsule") {
          out = resolveCapsuleCollider(
            x,
            y,
            z,
            c.ax,
            c.ay,
            c.az,
            c.bx,
            c.by,
            c.bz,
            (c.r || 0) + pad
          );
        } else if (c.type === "ellipsoid") {
          out = resolveEllipsoidCollider(
            x,
            y,
            z,
            c.x,
            c.y,
            c.z,
            (c.rx || 0) + pad,
            (c.ry || 0) + pad,
            (c.rz || 0) + pad
          );
        }
        if (out && out.hit) {
          // If still near center, bias outward for the owning arm.
          if (preferSide && Math.abs(out.x) < 0.02) {
            out.x += preferSide === "left" ? -0.03 : 0.03;
          }
          x = out.x;
          y = out.y;
          z = out.z;
          any = true;
        }
      }
      if (!any) break;
    }
    return { x: x, y: y, z: z };
  }

  /** Soft separation so L/R wrists do not occupy the same point. */
  function separateHands(minDist) {
    if (!vr || !vr.left || !vr.right) return;
    minDist = typeof minDist === "number" ? minDist : 0.07;
    var dx = vr.right.x - vr.left.x;
    var dy = vr.right.y - vr.left.y;
    var dz = vr.right.z - vr.left.z;
    var d = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (d >= minDist || d < 1e-8) {
      if (d < 1e-8) {
        vr.left.x -= minDist * 0.5;
        vr.right.x += minDist * 0.5;
      }
      return;
    }
    var push = (minDist - d) * 0.5;
    var inv = 1 / d;
    var nx = dx * inv;
    var ny = dy * inv;
    var nz = dz * inv;
    // Locked hands move less so held poses stay stable.
    var wL = vr.left.locked ? 0.25 : 0.5;
    var wR = vr.right.locked ? 0.25 : 0.5;
    var sum = wL + wR || 1;
    wL /= sum;
    wR /= sum;
    vr.left.x -= nx * push * wL * 2;
    vr.left.y -= ny * push * wL * 2;
    vr.left.z -= nz * push * wL * 2;
    vr.right.x += nx * push * wR * 2;
    vr.right.y += ny * push * wR * 2;
    vr.right.z += nz * push * wR * 2;
  }

  /**
   * Clamp + body avoid for a wrist target (hips-local).
   * Call after any tool/physics write to the controller.
   */
  function sanitizeWristTarget(side, x, y, z, locked) {
    var frac = locked ? 0.86 : 0.93;
    var opts = {};
    if (locked && activeGesture && activeGesture.kind) {
      var gk = activeGesture.kind;
      if (gk === "clap" || gk === "crossed_arms") opts.allowCross = true;
      if (gk === "hands_on_hips") opts.allowBack = true;
    }
    // Human cone first (stops camera-wrap targets), then body proxies, re-clamp.
    var c = clampToHumanArmWorkspace(side, x, y, z, frac, opts);
    // Locked poses (think near face) use thinner pad so intentional near-body holds work.
    var pad = locked ? 0.012 : 0.028;
    var r = resolvePointAgainstBody(c.x, c.y, c.z, pad, side);
    return clampToHumanArmWorkspace(side, r.x, r.y, r.z, frac, opts);
  }

  /** Apply body avoidance to both free/locked wrists + hand-hand separation. */
  function applyBodyCollisionToHands() {
    if (!bodyCollisionOn || !vr) return;
    if (!bodyColliders || !bodyColliders.length) return;
    var L = sanitizeWristTarget("left", vr.left.x, vr.left.y, vr.left.z, vr.left.locked);
    var R = sanitizeWristTarget(
      "right",
      vr.right.x,
      vr.right.y,
      vr.right.z,
      vr.right.locked
    );
    vr.left.x = L.x;
    vr.left.y = L.y;
    vr.left.z = L.z;
    vr.right.x = R.x;
    vr.right.y = R.y;
    vr.right.z = R.z;
    separateHands(Math.max(0.06, Math.min(armReachLen("left"), armReachLen("right")) * 0.12));
  }

  /**
   * Copy live hand bone positions into VR wrist controllers (hips-local).
   * Used when leaving VRMA / scripted poses so the spring starts from the
   * current pose instead of teleporting to rest.
   */
  function sampleWristsFromBones() {
    if (!restBones) return;
    try {
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
    } catch (_) {}
    var l = hipsLocalOf(restBones.leftHand);
    var r = hipsLocalOf(restBones.rightHand);
    if (l && typeof l.x === "number") {
      vr.left.x = l.x;
      vr.left.y = l.y;
      vr.left.z = l.z;
      if (!vr.left.locked) {
        vr.left.vx *= 0.15;
        vr.left.vy *= 0.15;
        vr.left.vz *= 0.15;
      }
    }
    if (r && typeof r.x === "number") {
      vr.right.x = r.x;
      vr.right.y = r.y;
      vr.right.z = r.z;
      if (!vr.right.locked) {
        vr.right.vx *= 0.15;
        vr.right.vy *= 0.15;
        vr.right.vz *= 0.15;
      }
    }
  }

  /**
   * Rest wrist/head targets in hips-local space.
   *
   * Prefer *geometric* hang: shoulder + down/out/forward scaled by arm reach.
   * Measuring from live bones alone re-encoded shallow euler hang as a permanent
   * Y-pose rest (start + post-gesture). Live hand samples only win when they
   * clearly hang below the geometric target.
   *
   * @param {{preserveHands?:boolean}} opts  preserveHands=true keeps live wrist
   *   controllers (needed after VRMA / soft unlock so we spring home).
   */
  function calibrateVrRestsFromBones(opts) {
    opts = opts || {};
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

    // Character face-forward is authoritative for hang (stable across camera fit).
    // Soft-blend a little viewer bias only when camera clearly sits in front.
    var face = faceForward();
    var faceXZ = { x: face.x || 0, z: face.z || 1 };
    var fl = Math.sqrt(faceXZ.x * faceXZ.x + faceXZ.z * faceXZ.z) || 1;
    faceXZ.x /= fl;
    faceXZ.z /= fl;
    var viewL = viewerForwardXZ(lSh);
    var viewR = viewerForwardXZ(rSh);
    function blendFwd(view) {
      var align =
        (view.x || 0) * faceXZ.x + (view.z || 0) * faceXZ.z;
      // Only trust camera when it agrees with face (viewer in front of avatar).
      var w = align > 0.25 ? Math.min(0.35, (align - 0.25) * 0.7) : 0;
      var x = faceXZ.x * (1 - w) + (view.x || 0) * w;
      var z = faceXZ.z * (1 - w) + (view.z || 0) * w;
      var len = Math.sqrt(x * x + z * z) || 1;
      return { x: x / len, z: z / len };
    }
    var fwdL = blendFwd(viewL);
    var fwdR = blendFwd(viewR);
    // Body-right from measured axes (matches face handedness), not camera cross.
    var br = bodyAxes().right || { x: 1, z: 0 };
    var rightLdir = { x: br.x || 1, z: br.z || 0 };
    var rightRdir = { x: br.x || 1, z: br.z || 0 };
    var rl = Math.sqrt(rightLdir.x * rightLdir.x + rightLdir.z * rightLdir.z) || 1;
    rightLdir.x /= rl;
    rightLdir.z /= rl;
    rightRdir.x = rightLdir.x;
    rightRdir.z = rightLdir.z;

    // Geometric soft hang — primary rest (arms down, slight A-pose, soft elbow room).
    function geometricHang(sh, reach, fwd, rightDir, isLeft) {
      // Slightly less than full hang so elbows stay softly bent (not stick-straight).
      var down = reach * 0.7;
      var out = reach * 0.18;
      // Mild face-forward so wrists sit in front of the hip plane, not behind back.
      var fwdAmt = reach * 0.085;
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
    // Snap free hands home only on hard calibrate — never during soft settle /
    // mid-gesture (that was the "not gradual" reset after poses).
    var settling = vr.settleUntil > 0 && idleTime < vr.settleUntil;
    var preserve =
      opts.preserveHands === true ||
      settling ||
      !!(activeGesture && activeGesture.kind);
    if (!preserve && !vr.left.locked) {
      vr.left.x = leftL.x;
      vr.left.y = leftL.y;
      vr.left.z = leftL.z;
      vr.left.vx = vr.left.vy = vr.left.vz = 0;
    }
    if (!preserve && !vr.right.locked) {
      vr.right.x = rightL.x;
      vr.right.y = rightL.y;
      vr.right.z = rightL.z;
      vr.right.vx = vr.right.vy = vr.right.vz = 0;
    }

    // Body / clothes proxies for self-collision (IK avoidance).
    try {
      rebuildBodyColliders();
      applyBodyCollisionToHands();
    } catch (_) {}
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
        return { x: dx / len, z: dz / len, cam: cam, align: null };
      }
    }
    // Last resort: character face-forward (not raw +Z — custom VRMs invert).
    var ff = faceForward();
    var fx = ff.x || 0;
    var fz = typeof ff.z === "number" ? ff.z : 1;
    var fl = Math.sqrt(fx * fx + fz * fz) || 1;
    return { x: fx / fl, z: fz / fl, cam: cam, align: 1 };
  }

  /**
   * Gesture aim direction: character face-forward is primary.
   * Soft-bias toward the camera ONLY when the viewer sits in the front
   * hemisphere. Following the camera around the body was wrapping arms into
   * inhuman shoulder poses (side/behind orbits).
   */
  function gestureForwardXZ(origin) {
    var face = faceForward();
    var faceX = face.x || 0;
    var faceZ = typeof face.z === "number" ? face.z : 1;
    var fl = Math.sqrt(faceX * faceX + faceZ * faceZ) || 1;
    faceX /= fl;
    faceZ /= fl;
    var view = viewerForwardXZ(origin);
    var align =
      (view.x || 0) * faceX + (view.z || 0) * faceZ;
    // Front cone only: align>0.2 → up to ~0.5 camera weight; side/behind → 0.
    var w = 0;
    if (align > 0.2) {
      w = Math.min(0.5, (align - 0.2) * 0.9);
    }
    var x = faceX * (1 - w) + (view.x || 0) * w;
    var z = faceZ * (1 - w) + (view.z || 0) * w;
    var len = Math.sqrt(x * x + z * z) || 1;
    return {
      x: x / len,
      z: z / len,
      cam: view.cam,
      align: align,
      camWeight: w,
    };
  }

  /**
   * Body-right unit in hips XZ given a viewer-forward vector (up × forward).
   */
  function bodyRightXZ(fwd) {
    // (0,1,0) × (fx,0,fz) = (fz, 0, -fx)
    return { x: fwd.z, z: -fwd.x };
  }

  /**
   * Wave / point peaks relative to this avatar.
   * Face-forward primary; mild camera bias only when viewer is in front.
   * Offsets use measured arm reach so short VRMs stay inside the workspace.
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
    // Face-primary aim (not pure camera chase).
    var fwd = gestureForwardXZ(sh);
    // Lateral "out" from measured body-right so L/R stay on own sides.
    var br = bodyAxes().right || { x: 1, z: 0 };
    var right = { x: br.x || 1, z: br.z || 0 };
    var rl = Math.sqrt(right.x * right.x + right.z * right.z) || 1;
    right.x /= rl;
    right.z /= rl;
    // Outward from torso for this hand (left = −body-right).
    var outX = isLeft ? -right.x : right.x;
    var outZ = isLeft ? -right.z : right.z;
    var peak = { x: sh.x, y: sh.y, z: sh.z };

    if (kind === "wave") {
      // Classic "hi": near ear height, out, slightly in front — not stretched
      // at the camera (that locked elbows and hyperextended shoulders).
      peak.y = sh.y + reach * (0.42 + 0.08 * inten);
      peak.y = Math.min(peak.y, headY + reach * 0.04);
      peak.y = Math.max(peak.y, sh.y + reach * 0.28);
      peak.x =
        sh.x +
        outX * reach * (0.34 + 0.06 * inten) +
        fwd.x * reach * (0.28 + 0.06 * inten);
      peak.z =
        sh.z +
        outZ * reach * (0.34 + 0.06 * inten) +
        fwd.z * reach * (0.28 + 0.06 * inten);
    } else if (kind === "point") {
      // Point: forward of own shoulder, mild reach — not full-arm lock at cam.
      peak.y = sh.y + reach * 0.06;
      peak.x =
        sh.x +
        outX * reach * (0.16 + 0.04 * inten) +
        fwd.x * reach * (0.48 + 0.08 * inten);
      peak.z =
        sh.z +
        outZ * reach * (0.16 + 0.04 * inten) +
        fwd.z * reach * (0.48 + 0.08 * inten);
    } else if (kind === "cheer") {
      peak.y = Math.min(headY + reach * 0.12, sh.y + reach * 0.72);
      peak.x =
        sh.x + outX * reach * 0.24 + fwd.x * reach * 0.22;
      peak.z =
        sh.z + outZ * reach * 0.24 + fwd.z * reach * 0.22;
    } else {
      peak.y = sh.y + reach * 0.2;
      peak.x =
        sh.x + outX * reach * 0.26 + fwd.x * reach * 0.24;
      peak.z =
        sh.z + outZ * reach * 0.26 + fwd.z * reach * 0.24;
    }

    return clampToHumanArmWorkspace(side, peak.x, peak.y, peak.z, 0.84);
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

  /** Lateral flap offset in face/gesture plane (for wave) — not pure camera. */
  function waveFlapOffset(side, sign, inten) {
    var isLeft = side === "left";
    var sh = isLeft ? vr.shoulderLeft : vr.shoulderRight;
    if (!sh) sh = { x: 0, y: 1, z: 0 };
    var reach = armReachLen(side);
    var fwd = gestureForwardXZ(sh);
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
          lH = clampToHumanArmWorkspace("left", lH.x, lH.y, lH.z, 0.84);
          lH = { x: round3lib(lH.x), y: round3lib(lH.y), z: round3lib(lH.z) };
        }
        if (raiseR) {
          var fR = waveFlapOffset("right", sign, inten);
          rH = {
            x: round3lib(raiseR.x + fR.x),
            y: raiseR.y,
            z: round3lib(raiseR.z + fR.z),
          };
          rH = clampToHumanArmWorkspace("right", rH.x, rH.y, rH.z, 0.84);
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
      var fwdL = gestureForwardXZ(shL);
      var fwdR = gestureForwardXZ(shR);
      var brSh = bodyAxes().right || { x: 1, z: 0 };
      var leftUp = clampToHumanArmWorkspace(
        "left",
        shL.x - (brSh.x || 1) * rL * 0.2 + fwdL.x * rL * 0.12,
        shL.y + rL * 0.06,
        shL.z - (brSh.z || 0) * rL * 0.2 + fwdL.z * rL * 0.12,
        0.82
      );
      var rightUp = clampToHumanArmWorkspace(
        "right",
        shR.x + (brSh.x || 1) * rR * 0.2 + fwdR.x * rR * 0.12,
        shR.y + rR * 0.06,
        shR.z + (brSh.z || 0) * rR * 0.2 + fwdR.z * rR * 0.12,
        0.82
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
      var fwdT = gestureForwardXZ(shRt);
      var brT = bodyAxes().right || { x: 1, z: 0 };
      var thinkPt = clampToHumanArmWorkspace(
        "right",
        shRt.x + (brT.x || 1) * rr * 0.12 + fwdT.x * rr * 0.28,
        Math.min(headY - rr * 0.08, shRt.y + rr * 0.3),
        shRt.z + (brT.z || 0) * rr * 0.12 + fwdT.z * rr * 0.28,
        0.82
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
      var fwdCl = gestureForwardXZ({
        x: (shCl.x + shCr.x) * 0.5,
        z: (shCl.z + shCr.z) * 0.5,
      });
      var midY = (shCl.y + shCr.y) * 0.5 - rCl * 0.25;
      var midX = (shCl.x + shCr.x) * 0.5 + fwdCl.x * rCl * 0.28;
      var midZ = (shCl.z + shCr.z) * 0.5 + fwdCl.z * rCl * 0.28;
      var cl = clampToHumanArmWorkspace("left", midX - 0.04, midY, midZ, 0.8);
      var cr = clampToHumanArmWorkspace("right", midX + 0.04, midY, midZ, 0.8);
      addFrame(
        0,
        { x: round3lib(cl.x), y: round3lib(cl.y), z: round3lib(cl.z) },
        { x: round3lib(cr.x), y: round3lib(cr.y), z: round3lib(cr.z) },
        0.25,
        look
      );
      var cl2 = clampToHumanArmWorkspace("left", midX - 0.02, midY, midZ, 0.8);
      var cr2 = clampToHumanArmWorkspace("right", midX + 0.02, midY, midZ, 0.8);
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
      var hl = clampToHumanArmWorkspace(
        "left",
        shLH.x - rLH * 0.18,
        hipsY + rLH * 0.05,
        shLH.z - rLH * 0.04,
        0.75,
        { allowBack: true }
      );
      var hr = clampToHumanArmWorkspace(
        "right",
        shRH.x + rRH * 0.18,
        hipsY + rRH * 0.05,
        shRH.z - rRH * 0.04,
        0.75,
        { allowBack: true }
      );
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
      var fwdCx = gestureForwardXZ({
        x: (shLC.x + shRC.x) * 0.5,
        z: (shLC.z + shRC.z) * 0.5,
      });
      var cla = clampToHumanArmWorkspace(
        "left",
        0.04 + fwdCx.x * rLC * 0.12,
        cy,
        shLC.z * 0.3 + fwdCx.z * rLC * 0.22,
        0.8,
        { allowCross: true }
      );
      var cra = clampToHumanArmWorkspace(
        "right",
        -0.04 + fwdCx.x * rRC * 0.12,
        cy - 0.02,
        shRC.z * 0.3 + fwdCx.z * rRC * 0.18,
        0.8,
        { allowCross: true }
      );
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
   * Play a named template. Arm/body poses prefer camera-relative scripted IK
   * (works on any custom VRM). Bundled VRMA clips are authored for a reference
   * skeleton and often look inverted/mirrored on downloaded models — only use
   * them for pure emotion clips, or when opts.preferVrma is set.
   */
  function playTemplate(name, opts) {
    opts = opts || {};
    var n = String(name || "")
      .toLowerCase()
      .replace(/[\s-]+/g, "_");
    // Always scripted IK (measured shoulders + viewer-forward). Never VRMA.
    var SCRIPT_BODY = {
      bow: 1,
      lean_in: 1,
      hands_on_hips: 1,
      crossed_arms: 1,
      point: 1,
      point_left: 1,
      point_right: 1,
      wave: 1,
      hello: 1,
      clap: 1,
      cheer: 1,
      celebrate: 1,
      shrug: 1,
      think: 1,
      nod: 1,
      yes: 1,
      shake_head: 1,
      no: 1,
    };
    // Emotion / stance clips that are mostly torso/face — safer on custom VRMs.
    var EMOTION_VRMA = {
      angry: 1,
      mad: 1,
      sad: 1,
      sleepy: 1,
      sleep: 1,
      surprised: 1,
      surprise: 1,
      blush: 1,
      shy: 1,
      lookaround: 1,
      look_around: 1,
      relax: 1,
      jump: 1,
    };
    // Scripted body path first for arm gestures (fixes inverted custom VRMs).
    if (SCRIPT_BODY[n] && !opts.preferVrma) {
      if (startScriptedGesture(n, opts)) return true;
    }
    // VRMA for emotions, or when caller forces preferVrma / no scripted path.
    var vrmaId =
      SCRIPT_BODY[n] && !opts.preferVrma
        ? null
        : resolveVrmaId(n, opts);
    if (vrmaId && canPlayVrma() && (opts.preferVrma || EMOTION_VRMA[n] || !SCRIPT_BODY[n])) {
      playVrma(vrmaId, {
        loop: !!opts.loop,
        intensity: opts.intensity,
        fallback: n,
        side: opts.side,
      });
      return true;
    }
    // Scripted gestures fallback.
    if (startScriptedGesture(n, opts)) {
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
    // Clip id is set as soon as play is requested (before async load finishes)
    // so body IK/walk never fights the mixer mid-load.
    return !!vrmaClipId;
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
        // Unlock + sample end pose → spring home (no hard hand teleport).
        vr.left.locked = false;
        vr.right.locked = false;
        vr.head.locked = false;
        activeGesture = null;
        vr.settleUntil = Math.max(vr.settleUntil || 0, idleTime + 1.6);
        restoreHangPose({ recalibrate: true, recaptureBase: false, snapHands: false });
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
          // Prefer scripted IK fallback (camera-relative) over joint-XYZ look frames.
          if (opts.fallback && startScriptedGesture(opts.fallback, opts)) {
            /* ok */
          } else if (opts.fallback) {
            var p = buildTemplatePlan(opts.fallback, opts);
            if (p) playAiMotion(p);
          } else if (nameOrId && nameOrId !== id) {
            if (!startScriptedGesture(nameOrId, opts)) {
              var p2 = buildTemplatePlan(nameOrId, opts);
              if (p2) playAiMotion(p2);
            }
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
      // Soft exit: sample wrists from end pose, then spring to hang (no pop).
      try {
        sampleWristsFromBones();
      } catch (_) {}
      try {
        if (vrmaAction) {
          vrmaAction.fadeOut(0.35);
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
        vr.settleUntil = Math.max(vr.settleUntil || 0, idleTime + 1.7);
        // Re-apply hang bones; preserve sampled wrists so IK eases home.
        restoreHangPose({ recalibrate: true, recaptureBase: false, snapHands: false });
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
    var ps = spinePitchSign();
    var hs = headPitchSign();
    if (state === STATE_LISTENING) {
      p.spine = [0.012 * ps, 0, 0];
      p.chest = [0.01 * ps, 0, 0];
      p.neck = [0.002 * hs, 0, 0];
      p.head = [0.001 * hs, 0, 0];
    } else if (state === STATE_THINKING) {
      // Subtle torso only — no forced hand-to-chin (was glitchy between turns).
      p.hips = [0.004 * ps, -0.008, 0.006];
      p.spine = [0.008 * ps, 0.012, 0.006];
      p.chest = [0.008 * ps, 0.008, 0.004];
      p.neck = [0.006 * hs, 0.015, 0.006];
      p.head = [0.004 * hs, 0.02, 0.006];
    } else if (state === STATE_SPEAKING) {
      p.hips = [0.004 * ps, 0, 0];
      p.spine = [0.01 * ps, 0, 0];
      p.chest = [0.012 * ps, 0, 0];
      p.neck = [0.001 * hs, 0, 0];
      p.head = [0.001 * hs, 0, 0];
    } else {
      p.spine = [0.006 * ps, 0, 0];
    }
    statePoseTarget = p;
    // Hands: do NOT auto-pose on listen/think/speak. Soft hang + tools/templates only.
  }

  function setHandTarget(side, x, y, z, holdSec, locked) {
    var h = side === "left" ? vr.left : vr.right;
    if (!h) return;
    // Clamp to arm workspace + push out of body/clothes proxies.
    var c = sanitizeWristTarget(side, x, y, z, !!locked);
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
    // Slow ease so bow/lean/state changes never pop back to neutral.
    var rate = Math.min(1, dt * 1.05);
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
   * One-shot: at hang rest wrists, pick the elbow bend sign that places the
   * elbow behind the shoulder→hand chord. Seeds ikElbowSign so the first
   * idle frame is not a coin-flip.
   */
  function seedElbowSignFromHang(side) {
    var L = getLibs();
    if (!L || !L.THREE || !restBones || !restBones.hips || !armMeta) return;
    var meta = armMeta[side];
    if (!meta || !(meta.upperLen > 0)) return;
    var isLeft = side === "left";
    var upper = restBones[isLeft ? "leftUpperArm" : "rightUpperArm"];
    var lower = restBones[isLeft ? "leftLowerArm" : "rightLowerArm"];
    var hand = restBones[isLeft ? "leftHand" : "rightHand"];
    var rest = isLeft ? vr.restLeft : vr.restRight;
    if (!upper || !lower || !hand || !rest) return;
    var THREE = L.THREE;
    var a = meta.upperLen;
    var b = meta.lowerLen;
    // Temporarily pose from bind and compute both elbows for hang wrist.
    upper.quaternion.copy(meta.bindUpper);
    lower.quaternion.copy(meta.bindLower);
    hand.quaternion.copy(meta.bindHand);
    try {
      restBones.hips.updateWorldMatrix(true, true);
      upper.updateWorldMatrix(true, true);
    } catch (_) {
      return;
    }
    var sh = new THREE.Vector3();
    var tg = new THREE.Vector3(rest.x, rest.y, rest.z);
    try {
      upper.getWorldPosition(sh);
      tg.applyMatrix4(restBones.hips.matrixWorld);
    } catch (_) {
      return;
    }
    var toT = tg.clone().sub(sh);
    var dist = toT.length();
    if (dist < 1e-4) return;
    var maxR = (a + b) * 0.995;
    if (dist > maxR) {
      toT.multiplyScalar(maxR / dist);
      dist = maxR;
      tg.copy(sh).add(toT);
    }
    var cosU = clamp((a * a + dist * dist - b * b) / (2 * a * dist), -1, 1);
    var ang = Math.acos(cosU);
    var fwd = toT.clone().multiplyScalar(1 / dist);
    // Face-forward pole (not raw hips +Z) — matches applyHandIk.
    var ff = faceForward();
    var pole = new THREE.Vector3(ff.x || 0, 0, ff.z || 1)
      .transformDirection(restBones.hips.matrixWorld)
      .normalize()
      .multiplyScalar(-1);
    pole.y += 0.12;
    var br = bodyAxes().right || { x: 1, y: 0, z: 0 };
    var sideV = new THREE.Vector3(
      (br.x || 1) * (isLeft ? -1 : 1),
      0,
      (br.z || 0) * (isLeft ? -1 : 1)
    ).transformDirection(restBones.hips.matrixWorld);
    if (sideV.lengthSq() > 1e-8) pole.addScaledVector(sideV.normalize(), 0.85);
    var bend = pole.clone().addScaledVector(fwd, -pole.dot(fwd));
    if (bend.lengthSq() < 1e-8) bend.set(isLeft ? -1 : 1, 0, 0);
    bend.normalize();
    var inv = new THREE.Matrix4().copy(restBones.hips.matrixWorld).invert();

    function score(bendDir) {
      var el = sh
        .clone()
        .addScaledVector(fwd, Math.cos(ang) * a)
        .addScaledVector(bendDir, Math.sin(ang) * a);
      var elL = el.clone().applyMatrix4(inv);
      var shL = sh.clone().applyMatrix4(inv);
      var tgL = tg.clone().applyMatrix4(inv);
      var mid = {
        x: (shL.x + tgL.x) * 0.5,
        y: (shL.y + tgL.y) * 0.5,
        z: (shL.z + tgL.z) * 0.5,
      };
      var behind = behindAlongFace(elL, mid);
      var torsoBehind = behindAlongFace(elL, { x: 0, y: 0, z: 0 });
      var elSide =
        (elL.x - shL.x) * (br.x || 0) + (elL.z - shL.z) * (br.z || 0);
      var outAmt = isLeft ? -elSide : elSide;
      if (!isFinite(outAmt)) {
        outAmt = isLeft ? shL.x - elL.x : elL.x - shL.x;
      }
      return (
        behind * 3.8 +
        torsoBehind * 1.3 +
        Math.max(-0.04, outAmt) * 1.5 -
        Math.max(0, -behind) * 2.0
      );
    }
    var sA = score(bend);
    var sB = score(bend.clone().multiplyScalar(-1));
    ikElbowSign[side] = sB > sA + 0.04 ? -1 : 1;
    try {
      console.log(
        "[CompanionStage] elbow seed",
        side,
        "sign=" + ikElbowSign[side],
        "sA=" + sA.toFixed(3),
        "sB=" + sB.toFixed(3)
      );
    } catch (_) {}
  }

  /**
   * Soft-unlock hands after a scripted gesture so they spring home instead of
   * staying frozen (locked=true with holdUntil=0 was permanent).
   */
  function unlockHandsSoft() {
    function unlock(h) {
      if (!h) return;
      h.locked = false;
      h.holdUntil = 0;
      // Keep some residual velocity for natural follow-through (not a dead stop).
      h.vx *= 0.45;
      h.vy *= 0.45;
      h.vz *= 0.45;
    }
    unlock(vr.left);
    unlock(vr.right);
    // Extra-soft spring window so return from peak never reads as a hard reset.
    vr.settleUntil = Math.max(vr.settleUntil || 0, idleTime + 1.65);
  }

  /**
   * Slow independent arm "life" offsets so idle hang is never a frozen mannequin.
   * Blended only when free hands + low gait + no active gesture.
   */
  function idleLifeOffset(side) {
    var ph = side === "left" ? 0.0 : 2.15;
    var t = idleTime;
    var breath = Math.sin(t * 1.05) * 0.006;
    return {
      x:
        Math.sin(t * 0.47 + ph) * 0.016 +
        Math.sin(t * 0.19 + ph * 0.6) * 0.009,
      y:
        breath +
        Math.sin(t * 0.82 + ph * 0.7) * 0.011 +
        Math.sin(t * 0.29) * 0.004,
      z:
        Math.sin(t * 0.36 + ph * 0.5) * 0.014 +
        Math.sin(t * 0.13 + ph) * 0.006,
    };
  }

  /**
   * Spring + gravity on free VR hands.
   * Target = hang rest blended with walkWrist by gaitWeight (one continuous
   * path — never hard-swap bone ownership between walk eulers and IK).
   */
  function updateVrPhysics(dt) {
    var gw = loco && typeof loco.gaitWeight === "number" ? loco.gaitWeight : 0;
    var settling = vr.settleUntil > 0 && idleTime < vr.settleUntil;
    var gestBusy = !!(activeGesture && activeGesture.kind);
    function blendTarget(rest, walkT, side) {
      var base = rest;
      // Load ease: wrists drop from a soft A-pose toward full hang (no pop).
      if (hangEase < 0.999 && rest) {
        var shE = side === "left" ? vr.shoulderLeft : vr.shoulderRight;
        if (shE) {
          var he = hangEase * hangEase * (3 - 2 * hangEase);
          var startY = rest.y + armReachLen(side) * 0.16;
          var startX = shE.x + (rest.x - shE.x) * 0.55;
          var startZ = shE.z + (rest.z - shE.z) * 0.55;
          base = {
            x: startX + (rest.x - startX) * he,
            y: startY + (rest.y - startY) * he,
            z: startZ + (rest.z - startZ) * he,
          };
        }
      }
      // Idle life: gentle wrist drift so hang isn't rigid.
      if (!gestBusy && gw < 0.12 && base) {
        var life = idleLifeOffset(side);
        var lifeW = hangEase > 0.85 ? 1 : hangEase;
        base = {
          x: base.x + life.x * lifeW,
          y: base.y + life.y * lifeW,
          z: base.z + life.z * lifeW,
        };
      }
      if (!walkT || gw < 0.01) return base;
      var w = clamp(gw, 0, 1);
      // Ease gait influence so start/stop isn't linear snappy.
      w = w * w * (3 - 2 * w);
      return {
        x: base.x + (walkT.x - base.x) * w,
        y: base.y + (walkT.y - base.y) * w,
        z: base.z + (walkT.z - base.z) * w,
      };
    }
    function stepHand(h, rest, walkT, side) {
      if (h.holdUntil > 0 && idleTime >= h.holdUntil) {
        h.holdUntil = 0;
        h.locked = false;
        // Hold expired → soft settle home (was an abrupt unlock freeze).
        vr.settleUntil = Math.max(vr.settleUntil || 0, idleTime + 1.4);
      }
      if (h.locked) {
        h.vx = h.vy = h.vz = 0;
        return;
      }
      var tgt = blendTarget(rest, walkT, side);
      // Soft settle after gestures; slightly firmer during walk.
      var springMul = settling ? 0.42 : gw > 0.05 ? 0.55 + gw * 0.55 : 1;
      var dampMul = settling ? 1.35 : gw > 0.05 ? 1.2 : 1.05;
      var springK = vr.spring * springMul;
      var dampK = vr.damp * dampMul;
      // Light gravity only when above rest (hang), not a constant pull down.
      var grav =
        h.y > tgt.y + 0.02 ? vr.gravity * 0.18 : vr.gravity * 0.04;
      var ax = (tgt.x - h.x) * springK - h.vx * dampK;
      var ay = (tgt.y - h.y) * springK - h.vy * dampK - grav;
      var az = (tgt.z - h.z) * springK - h.vz * dampK;
      h.vx += ax * dt;
      h.vy += ay * dt;
      h.vz += az * dt;
      // Cap velocity so hands never whip across the body in one frame.
      var vmax = settling ? 0.95 : 1.45 + gw * 0.9;
      var spd = Math.sqrt(h.vx * h.vx + h.vy * h.vy + h.vz * h.vz);
      if (spd > vmax && spd > 1e-6) {
        var s = vmax / spd;
        h.vx *= s;
        h.vy *= s;
        h.vz *= s;
      }
      h.x += h.vx * dt;
      h.y += h.vy * dt;
      h.z += h.vz * dt;
      var shL = vr.shoulderLeft || { x: -0.16, y: 0.3, z: 0 };
      var shR = vr.shoulderRight || { x: 0.16, y: 0.3, z: 0 };
      var shY = Math.min(shL.y, shR.y);
      var maxR = Math.max(armReachLen("left"), armReachLen("right"));
      h.x = clamp(h.x, -maxR * 1.55, maxR * 1.55);
      h.y = clamp(h.y, shY - maxR * 1.25, shY + maxR * 0.95);
      h.z = clamp(h.z, -maxR * 1.35, maxR * 1.35);
    }
    stepHand(vr.left, vr.restLeft, walkWrist.left, "left");
    stepHand(vr.right, vr.restRight, walkWrist.right, "right");
    applyBodyCollisionToHands();
    if (!vr.head.locked) {
      var headRate = settling ? 1.6 : 2.4;
      vr.head.x += (vr.restHead.x - vr.head.x) * Math.min(1, dt * headRate);
      vr.head.y += (vr.restHead.y - vr.head.y) * Math.min(1, dt * headRate);
      vr.head.z += (vr.restHead.z - vr.head.z) * Math.min(1, dt * headRate);
    }
  }

  /**
   * Aim a bone so its bind-time child axis points at a world-space target.
   * Optional poleWorld (point or direction tip) re-twists the bone so the
   * elbow crease follows the bend plane — setFromUnitVectors alone drops roll
   * and is the classic "elbows bend backwards" look on custom VRMs.
   */
  function aimBoneToward(boneNode, worldTarget, localAxis, S, poleWorld) {
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

    // Twist correction toward pole so mesh elbow crease matches bend plane.
    if (poleWorld) {
      try {
        // Pole direction in parent space (from bone origin toward pole point).
        S.pole.copy(poleWorld).sub(S.shoulder);
        S.pole.applyQuaternion(S.qInv);
        // Project onto plane ⊥ aim dir.
        S.pole.addScaledVector(S.dir, -S.pole.dot(S.dir));
        if (S.pole.lengthSq() > 1e-10) {
          S.pole.normalize();
          // Secondary bone-local axis ⊥ primary child axis.
          S.side.set(0, 1, 0);
          if (Math.abs(S.axis.dot(S.side)) > 0.85) S.side.set(1, 0, 0);
          S.bend.copy(S.axis).cross(S.side).normalize();
          // Secondary after pure aim, in parent space.
          S.side.copy(S.bend).applyQuaternion(S.q);
          S.side.addScaledVector(S.dir, -S.side.dot(S.dir));
          if (S.side.lengthSq() > 1e-10) {
            S.side.normalize();
            var cosT = clamp(S.side.dot(S.pole), -1, 1);
            S.bend.copy(S.side).cross(S.pole);
            var sinT = S.bend.length();
            var signed =
              Math.atan2(S.bend.dot(S.dir) >= 0 ? sinT : -sinT, cosT) || 0;
            if (Math.abs(signed) > 1e-5) {
              // Twist around aim axis in parent space, then apply aim.
              S.qInv.setFromAxisAngle(S.dir, signed);
              S.q.premultiply(S.qInv);
            }
          }
        }
      } catch (_) {}
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
    // Elbow: only adjust along the probed flex hinge (never hard-code +X which
    // bends many custom VRMs backwards when idle).
    var H = (restBones && restBones.hinge) || defaultLimbHinges();
    var eH = isLeft ? H.leftElbow : H.rightElbow;
    // Raise unbends slightly; at hang rest base already has flex — stay near 0 delta.
    var elbowExtra = (1 - raise) * 0.02 - raise * 0.12;
    if (eH && restBones[lowerKey]) {
      addHinge(restBones[lowerKey], lowerKey, eH, elbowExtra);
    } else {
      addEuler(restBones[lowerKey], lowerKey, raise * 0.05, 0, -sign * raise * 0.03);
    }
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

    // Elbow pole from measured face-forward (not raw hips +Z — custom VRMs invert).
    // Hang: elbow slightly *behind* + out. Raised gestures: elbow *down + out*
    // (forcing "behind" on a high wave produces broken shoulder/elbow folds).
    var raiseAmt = clamp((h.y - (rest ? rest.y : 0.7)) / 0.45, 0, 1.4);
    // How far the wrist sits in front of the shoulder (face axis) — high when
    // pointing/waving; lower behind preference when the arm is already forward.
    var wristFront = 0;
    try {
      var shLocH = isLeft ? vr.shoulderLeft : vr.shoulderRight;
      if (shLocH) {
        wristFront = alongFace(h.x - shLocH.x, h.z - shLocH.z);
      }
    } catch (_) {}
    try {
      var ff = faceForward();
      S.pole.set(ff.x || 0, 0, ff.z || 1).transformDirection(restBones.hips.matrixWorld);
      if (S.pole.lengthSq() < 1e-8) S.pole.set(0, 0, 1);
      S.pole.normalize();
      // Behind-body weight fades as the arm rises / reaches forward.
      var behindW = clamp(1 - raiseAmt * 0.85 - Math.max(0, wristFront) * 1.2, 0.05, 1);
      S.pole.multiplyScalar(-behindW);
      // Raised: pull elbow down (natural under the upper arm), not up into head.
      if (raiseAmt > 0.35) {
        S.pole.y -= 0.35 + raiseAmt * 0.55;
      } else {
        S.pole.y += 0.05;
        if (raiseAmt < 0.2) S.pole.y -= 0.06;
      }
      var br = bodyAxes().right || { x: 1, y: 0, z: 0 };
      S.side
        .set((br.x || 1) * (isLeft ? -1 : 1), 0, (br.z || 0) * (isLeft ? -1 : 1))
        .transformDirection(restBones.hips.matrixWorld);
      if (S.side.lengthSq() < 1e-8) S.side.set(isLeft ? -1 : 1, 0, 0);
      S.side.normalize();
      // Stronger lateral as raise grows (wave elbow points out, not into ribs).
      S.pole.addScaledVector(S.side, 0.9 + raiseAmt * 0.55);
    } catch (_) {
      S.pole.set(isLeft ? -0.6 : 0.6, raiseAmt > 0.4 ? -0.5 : 0.15, -1);
    }
    // Project pole onto plane ⊥ shoulder→hand.
    S.bend.copy(S.pole).addScaledVector(S.forward, -S.pole.dot(S.forward));
    if (S.bend.lengthSq() < 1e-8) {
      S.bend.set(0, 1, 0).addScaledVector(S.forward, -S.forward.y);
    }
    if (S.bend.lengthSq() < 1e-8) S.bend.set(isLeft ? -1 : 1, 0, 0);
    S.bend.normalize();

    function placeElbow(bendDir, out) {
      out
        .copy(S.shoulder)
        .addScaledVector(S.forward, Math.cos(upperAngle) * a)
        .addScaledVector(bendDir, Math.sin(upperAngle) * a);
      return out;
    }
    placeElbow(S.bend, S.elbow);
    try {
      if (restBones.hips) {
        if (!S.invHips) S.invHips = new L.THREE.Matrix4();
        S.invHips.copy(restBones.hips.matrixWorld).invert();
        if (!S.elbowAlt) S.elbowAlt = new L.THREE.Vector3();
        if (!S.bendAlt) S.bendAlt = new L.THREE.Vector3();
        S.bendAlt.copy(S.bend).multiplyScalar(-1);
        placeElbow(S.bendAlt, S.elbowAlt);

        function scoreElbowCandidate(elWorld) {
          var elLoc = elWorld.clone().applyMatrix4(S.invHips);
          var shLoc = S.shoulder.clone().applyMatrix4(S.invHips);
          var tgLoc = S.target.clone().applyMatrix4(S.invHips);
          var midY = (shLoc.y + tgLoc.y) * 0.5;
          var mid = {
            x: (shLoc.x + tgLoc.x) * 0.5,
            y: midY,
            z: (shLoc.z + tgLoc.z) * 0.5,
          };
          // Behind shoulder→hand chord along face-forward.
          var behind = behindAlongFace(elLoc, mid);
          // Also behind the torso frontal plane (hips origin).
          var torsoBehind = behindAlongFace(elLoc, { x: 0, y: 0, z: 0 });
          // Prefer out from midline (body-right aware when available).
          var br2 = bodyAxes().right || { x: 1, y: 0, z: 0 };
          var elSide = (elLoc.x - shLoc.x) * (br2.x || 0) + (elLoc.z - shLoc.z) * (br2.z || 0);
          var outAmt = isLeft ? -elSide : elSide;
          if (!isFinite(outAmt)) {
            outAmt = isLeft ? shLoc.x - elLoc.x : elLoc.x - shLoc.x;
          }
          var drop = midY - elLoc.y;
          var raiseW = clamp(raiseAmt, 0, 1.2);
          // Raised / forward wrists: care about out+down, not "behind body"
          // (that score preferred hyper-extension on wave/point peaks).
          var behindW = 3.4 - raiseW * 2.6;
          var torsoW = 1.2 - raiseW * 0.95;
          var outW = 1.7 + raiseW * 1.1;
          var dropW = 0.45 + raiseW * 1.35;
          var frontPen = Math.max(0, -behind) * (2.0 - raiseW * 1.2);
          return (
            behind * Math.max(0.35, behindW) +
            torsoBehind * Math.max(0.15, torsoW) +
            Math.max(-0.05, outAmt) * outW +
            drop * dropW -
            frontPen
          );
        }

        var sA = scoreElbowCandidate(S.elbow);
        var sB = scoreElbowCandidate(S.elbowAlt);
        // Temporal continuity: only flip when the other side clearly wins.
        // Stops per-frame coin-flips that look like backwards elbows / jitter.
        var prev = ikElbowSign[side] || 0;
        var pickAlt = false;
        if (prev === 0) {
          // First frame: require a clearer win so we don't lock the wrong side.
          pickAlt = sB > sA + 0.04;
        } else if (prev < 0) {
          pickAlt = !(sA > sB + 0.16);
        } else {
          pickAlt = sB > sA + 0.16;
        }
        if (pickAlt) {
          S.bend.copy(S.bendAlt);
          S.elbow.copy(S.elbowAlt);
          ikElbowSign[side] = -1;
        } else {
          ikElbowSign[side] = 1;
        }
      }
    } catch (_) {}

    // Elbow self-collision: push out of torso/clothes so the forearm does not
    // tunnel through the chest when the wrist crosses the midline.
    if (bodyCollisionOn && bodyColliders && bodyColliders.length && restBones.hips) {
      try {
        if (!S.invHips) {
          S.invHips = new (getLibs().THREE.Matrix4)();
        }
        S.invHips.copy(restBones.hips.matrixWorld).invert();
        S.dir.copy(S.elbow).applyMatrix4(S.invHips);
        var elR = resolvePointAgainstBody(S.dir.x, S.dir.y, S.dir.z, 0.035, side);
        // Prefer elbows slightly out from the midline (less clothing penetration).
        var shLocal = isLeft ? vr.shoulderLeft : vr.shoulderRight;
        if (shLocal) {
          var outSign = isLeft ? -1 : 1;
          if (
            (isLeft && elR.x > shLocal.x * 0.3) ||
            (!isLeft && elR.x < shLocal.x * 0.3)
          ) {
            elR.x += outSign * 0.02;
          }
        }
        S.elbow.set(elR.x, elR.y, elR.z).applyMatrix4(restBones.hips.matrixWorld);
        // Keep upper length: project elbow onto sphere around shoulder.
        S.toTarget.copy(S.elbow).sub(S.shoulder);
        var eLen = S.toTarget.length();
        if (eLen > 1e-5) {
          S.elbow.copy(S.shoulder).addScaledVector(S.toTarget.normalize(), a);
        }
        // Recompute bend from final elbow (for twist aim).
        S.toTarget.copy(S.elbow).sub(S.shoulder);
        S.bend.copy(S.toTarget).addScaledVector(S.forward, -S.toTarget.dot(S.forward));
        if (S.bend.lengthSq() > 1e-10) S.bend.normalize();
      } catch (_) {}
    }

    // Twist reference: point off the bone along bend plane (not the elbow —
    // elbow lies on the aim axis so it cannot define roll).
    if (!S.poleRef) S.poleRef = new L.THREE.Vector3();
    S.poleRef.copy(S.shoulder).addScaledVector(S.bend, Math.max(0.08, a * 0.45));
    aimBoneToward(upper, S.elbow, meta.upperAxis, S, S.poleRef);
    try {
      upper.updateWorldMatrix(true, false);
    } catch (_) {}
    // Lower arm: same bend-plane twist so the elbow crease stays consistent.
    S.poleRef.copy(S.elbow).addScaledVector(S.bend, Math.max(0.06, b * 0.35));
    aimBoneToward(lower, S.target, meta.lowerAxis, S, S.poleRef);
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
        // Longer ease in/out (~28%) so raise/return never snaps.
        if (u < 0.22) {
          var k0 = easeInOut(u / 0.22);
          setHandTarget(
            side,
            rest.x + (peakX - rest.x) * k0,
            rest.y + (peakY - rest.y) * k0,
            rest.z + (peakZ - rest.z) * k0,
            0,
            true
          );
        } else if (u < 0.72) {
          // Lateral flap in the face-forward plane (gestureForward, not pure cam).
          var waveReach = armReachLen(side);
          var shW = side === "left" ? vr.shoulderLeft : vr.shoulderRight;
          if (!shW) shW = { x: 0, y: 1.2, z: 0 };
          var gFwd = gestureForwardXZ(shW);
          var gRight = bodyRightXZ(gFwd);
          var flap =
            Math.sin((g.t - g.duration * 0.22) * 10.5) *
            waveReach *
            0.18 *
            inten;
          // Oscillate along body-right (viewer-facing tangent when cam is front).
          var fx = peakX + gRight.x * flap;
          var fz = peakZ + gRight.z * flap;
          // Keep hand in front of shoulder along face — never wrap to camera behind.
          var handOffX = fx - shW.x;
          var handOffZ = fz - shW.z;
          var frontDot =
            handOffX * (gFwd.x || 0) + handOffZ * (gFwd.z || 0);
          if (frontDot < waveReach * 0.08) {
            fx = shW.x + (gFwd.x || 0) * waveReach * 0.28 + gRight.x * flap * 0.5;
            fz = shW.z + (gFwd.z || 0) * waveReach * 0.28 + gRight.z * flap * 0.5;
          }
          setHandTarget(side, fx, peakY + Math.abs(flap) * 0.05, fz, 0, true);
        } else {
          var k1 = easeInOut((u - 0.72) / 0.28);
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
      if (g.t > g.duration) {
        unlockHandsSoft();
        activeGesture = null;
      }
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
      var pointSides =
        g.side === "both"
          ? ["left", "right"]
          : [g.side === "left" ? "left" : "right"];
      lookTowardCamera(0.35);
      // Ease in/hold/out so point doesn't teleport wrists.
      var pBlend =
        u < 0.24 ? easeInOut(u / 0.24) : u > 0.72 ? easeInOut((1 - u) / 0.28) : 1;
      for (var pi = 0; pi < pointSides.length; pi++) {
        var ps = pointSides[pi];
        var pSign = ps === "left" ? -1 : 1;
        var pp = gesturePeak("point", ps, inten);
        var prest = ps === "left" ? vr.restLeft : vr.restRight;
        setHandTarget(
          ps,
          prest.x + (pp.x - prest.x) * pBlend,
          prest.y + (pp.y - prest.y) * pBlend,
          prest.z + (pp.z - prest.z) * pBlend,
          0,
          true
        );
        if (pointSides.length === 1) {
          lookTarget.x = clamp(lookTarget.x + pSign * 0.2 * inten * pBlend, -1, 1);
        }
      }
      if (g.t > g.duration) {
        unlockHandsSoft();
        activeGesture = null;
      }
      return true;
    }
    if (g.kind === "shrug") {
      var ls = vr.shoulderLeft || { x: -0.16, y: 1.2, z: 0 };
      var rs = vr.shoulderRight || { x: 0.16, y: 1.2, z: 0 };
      var shBlend =
        u < 0.22 ? easeInOut(u / 0.22) : u > 0.72 ? easeInOut((1 - u) / 0.28) : 1;
      var lRest = vr.restLeft;
      var rRest = vr.restRight;
      // Face-primary (soft cam bias only in front) — out + slightly forward.
      var shFwdL = gestureForwardXZ(ls);
      var shFwdR = gestureForwardXZ(rs);
      var brSg = bodyAxes().right || { x: 1, z: 0 };
      var shRightL = { x: brSg.x || 1, z: brSg.z || 0 };
      var shRightR = { x: brSg.x || 1, z: brSg.z || 0 };
      var rShg = Math.min(armReachLen("left"), armReachLen("right")) || 0.55;
      var lTx = ls.x - shRightL.x * rShg * 0.18 + shFwdL.x * rShg * 0.12;
      var lTy = ls.y - 0.08 * (1 - 0.3 * inten);
      var lTz = ls.z - shRightL.z * rShg * 0.18 + shFwdL.z * rShg * 0.12;
      var rTx = rs.x + shRightR.x * rShg * 0.18 + shFwdR.x * rShg * 0.12;
      var rTy = rs.y - 0.08 * (1 - 0.3 * inten);
      var rTz = rs.z + shRightR.z * rShg * 0.18 + shFwdR.z * rShg * 0.12;
      setHandTarget(
        "left",
        lRest.x + (lTx - lRest.x) * shBlend,
        lRest.y + (lTy - lRest.y) * shBlend,
        lRest.z + (lTz - lRest.z) * shBlend,
        0,
        true
      );
      setHandTarget(
        "right",
        rRest.x + (rTx - rRest.x) * shBlend,
        rRest.y + (rTy - rRest.y) * shBlend,
        rRest.z + (rTz - rRest.z) * shBlend,
        0,
        true
      );
      if (g.t > g.duration) {
        unlockHandsSoft();
        activeGesture = null;
      }
      return true;
    }
    if (g.kind === "think") {
      var shT = vr.shoulderRight || { x: 0.16, y: 0.3, z: 0 };
      var hy = (vr.restHead && vr.restHead.y) || shT.y + 0.12;
      var rT = armReachLen("right");
      var thBlend =
        u < 0.24 ? easeInOut(u / 0.24) : u > 0.72 ? easeInOut((1 - u) / 0.28) : 1;
      var thFwd = gestureForwardXZ(shT);
      var brTh = bodyAxes().right || { x: 1, z: 0 };
      var thRight = { x: brTh.x || 1, z: brTh.z || 0 };
      var thx = shT.x + thRight.x * rT * 0.12 + thFwd.x * rT * 0.3;
      var thy = Math.min(hy - rT * 0.12, shT.y + rT * 0.28);
      var thz = shT.z + thRight.z * rT * 0.12 + thFwd.z * rT * 0.3;
      setHandTarget(
        "right",
        vr.restRight.x + (thx - vr.restRight.x) * thBlend,
        vr.restRight.y + (thy - vr.restRight.y) * thBlend,
        vr.restRight.z + (thz - vr.restRight.z) * thBlend,
        0,
        true
      );
      lookTarget.x = 0.28 * thBlend;
      lookTarget.y = 0.14 * thBlend;
      lookHoldUntil = idleTime + 0.25;
      if (g.t > g.duration) {
        unlockHandsSoft();
        activeGesture = null;
      }
      return true;
    }
    if (g.kind === "clap") {
      var c = Math.abs(Math.sin(g.t * 12));
      var shCl = vr.shoulderLeft || { x: -0.16, y: 0.3, z: 0 };
      var shCr = vr.shoulderRight || { x: 0.16, y: 0.3, z: 0 };
      var rCl = Math.min(armReachLen("left"), armReachLen("right"));
      var cy = (shCl.y + shCr.y) * 0.5 - rCl * 0.12;
      var clBlend =
        u < 0.2 ? easeInOut(u / 0.2) : u > 0.75 ? easeInOut((1 - u) / 0.25) : 1;
      // Meet in front of chest (face-primary; soft cam only when in front).
      var mid = {
        x: (shCl.x + shCr.x) * 0.5,
        y: cy,
        z: (shCl.z + shCr.z) * 0.5,
      };
      var clFwd = gestureForwardXZ(mid);
      var brCl = bodyAxes().right || { x: 1, z: 0 };
      var clRight = { x: brCl.x || 1, z: brCl.z || 0 };
      var meetZ = mid.z + clFwd.z * rCl * 0.32;
      var meetX = mid.x + clFwd.x * rCl * 0.32;
      setHandTarget(
        "left",
        vr.restLeft.x +
          (meetX - clRight.x * rCl * (0.1 + c * 0.05) - vr.restLeft.x) * clBlend,
        vr.restLeft.y + (cy - vr.restLeft.y) * clBlend,
        vr.restLeft.z +
          (meetZ - clRight.z * rCl * (0.1 + c * 0.05) - vr.restLeft.z) * clBlend,
        0,
        true
      );
      setHandTarget(
        "right",
        vr.restRight.x +
          (meetX + clRight.x * rCl * (0.1 + c * 0.05) - vr.restRight.x) * clBlend,
        vr.restRight.y + (cy - vr.restRight.y) * clBlend,
        vr.restRight.z +
          (meetZ + clRight.z * rCl * (0.1 + c * 0.05) - vr.restRight.z) * clBlend,
        0,
        true
      );
      if (g.t > g.duration) {
        unlockHandsSoft();
        activeGesture = null;
      }
      return true;
    }
    if (g.kind === "cheer") {
      var cl = gesturePeak("cheer", "left", inten);
      var cr = gesturePeak("cheer", "right", inten);
      var lift = ease * armReachLen("right") * 0.12 * inten;
      var chBlend =
        u < 0.22 ? easeInOut(u / 0.22) : u > 0.72 ? easeInOut((1 - u) / 0.28) : 1;
      setHandTarget(
        "left",
        vr.restLeft.x + (cl.x - vr.restLeft.x) * chBlend,
        vr.restLeft.y + (cl.y + lift - vr.restLeft.y) * chBlend,
        vr.restLeft.z + (cl.z - vr.restLeft.z) * chBlend,
        0,
        true
      );
      setHandTarget(
        "right",
        vr.restRight.x + (cr.x - vr.restRight.x) * chBlend,
        vr.restRight.y + (cr.y + lift - vr.restRight.y) * chBlend,
        vr.restRight.z + (cr.z - vr.restRight.z) * chBlend,
        0,
        true
      );
      if (g.t > g.duration) {
        unlockHandsSoft();
        activeGesture = null;
      }
      return true;
    }
    if (g.kind === "bow") {
      // Sin rise/return on targets; blendStatePose eases bones (no hard zero).
      // spinePitchSign flips +X when this VRM's spine +X leans back (common custom).
      var bowAmt = Math.sin(clamp(u, 0, 1) * Math.PI) * inten;
      var bowPs = spinePitchSign();
      var bowHs = headPitchSign();
      if (statePoseTarget) {
        statePoseTarget.spine = [0.42 * bowAmt * bowPs, 0, 0];
        statePoseTarget.chest = [0.28 * bowAmt * bowPs, 0, 0];
        statePoseTarget.head = [0.22 * bowAmt * bowHs, 0, 0];
        statePoseTarget.hips = [0.08 * bowAmt * bowPs, 0, 0];
      }
      lookTarget.y = -0.35 * bowAmt;
      lookHoldUntil = idleTime + 0.2;
      if (g.t > g.duration) {
        if (statePoseTarget) {
          statePoseTarget.spine = [0, 0, 0];
          statePoseTarget.chest = [0, 0, 0];
          statePoseTarget.head = [0, 0, 0];
          statePoseTarget.hips = [0, 0, 0];
        }
        activeGesture = null;
      }
      return false;
    }
    if (g.kind === "lean_in") {
      var leanAmt = Math.sin(clamp(u, 0, 1) * Math.PI) * inten;
      var leanPs = spinePitchSign();
      if (statePoseTarget) {
        statePoseTarget.spine = [0.14 * leanAmt * leanPs, 0, 0];
        statePoseTarget.chest = [0.12 * leanAmt * leanPs, 0, 0];
      }
      if (g.t > g.duration) {
        if (statePoseTarget) {
          statePoseTarget.spine = [0, 0, 0];
          statePoseTarget.chest = [0, 0, 0];
        }
        activeGesture = null;
      }
      return false;
    }
    if (g.kind === "hands_on_hips") {
      var rHips = Math.min(armReachLen("left"), armReachLen("right")) || 0.55;
      var shHL = vr.shoulderLeft || { x: -0.16, y: 1.2, z: 0 };
      var shHR = vr.shoulderRight || { x: 0.16, y: 1.2, z: 0 };
      var hipsY = Math.min(
        (vr.restLeft && vr.restLeft.y) || shHL.y - rHips * 0.72,
        (vr.restRight && vr.restRight.y) || shHR.y - rHips * 0.72
      );
      var hhBlend =
        u < 0.25 ? easeInOut(u / 0.25) : u > 0.7 ? easeInOut((1 - u) / 0.3) : 1;
      var hfL = gestureForwardXZ(shHL);
      var hfR = gestureForwardXZ(shHR);
      var brH = bodyAxes().right || { x: 1, z: 0 };
      var hrL = { x: brH.x || 1, z: brH.z || 0 };
      var hrR = { x: brH.x || 1, z: brH.z || 0 };
      // Wrists near hip bones: out + slightly back from face (not camera chase).
      var hlx = shHL.x - hrL.x * rHips * 0.2 - hfL.x * rHips * 0.06;
      var hly = hipsY + rHips * 0.08;
      var hlz = shHL.z - hrL.z * rHips * 0.2 - hfL.z * rHips * 0.06;
      var hrx = shHR.x + hrR.x * rHips * 0.2 - hfR.x * rHips * 0.06;
      var hry = hipsY + rHips * 0.08;
      var hrz = shHR.z + hrR.z * rHips * 0.2 - hfR.z * rHips * 0.06;
      setHandTarget(
        "left",
        vr.restLeft.x + (hlx - vr.restLeft.x) * hhBlend,
        vr.restLeft.y + (hly - vr.restLeft.y) * hhBlend,
        vr.restLeft.z + (hlz - vr.restLeft.z) * hhBlend,
        0,
        true
      );
      setHandTarget(
        "right",
        vr.restRight.x + (hrx - vr.restRight.x) * hhBlend,
        vr.restRight.y + (hry - vr.restRight.y) * hhBlend,
        vr.restRight.z + (hrz - vr.restRight.z) * hhBlend,
        0,
        true
      );
      if (g.t > g.duration) {
        unlockHandsSoft();
        activeGesture = null;
      }
      return true;
    }
    if (g.kind === "crossed_arms") {
      var shCx = vr.shoulderLeft || { x: -0.16, y: 1.15, z: 0 };
      var shCxR = vr.shoulderRight || { x: 0.16, y: 1.15, z: 0 };
      var rCx = Math.min(armReachLen("left"), armReachLen("right")) || 0.55;
      var csy = (shCx.y + shCxR.y) * 0.5 - rCx * 0.32;
      var cxMid = {
        x: (shCx.x + shCxR.x) * 0.5,
        y: csy,
        z: (shCx.z + shCxR.z) * 0.5,
      };
      var cxFwd = gestureForwardXZ(cxMid);
      var brCx = bodyAxes().right || { x: 1, z: 0 };
      var cxRight = { x: brCx.x || 1, z: brCx.z || 0 };
      // Cross in front of chest (face-primary).
      var csz = cxMid.z + cxFwd.z * rCx * 0.24;
      var csx = cxMid.x + cxFwd.x * rCx * 0.24;
      var cxBlend =
        u < 0.26 ? easeInOut(u / 0.26) : u > 0.7 ? easeInOut((1 - u) / 0.3) : 1;
      setHandTarget(
        "left",
        vr.restLeft.x +
          (csx + cxRight.x * rCx * 0.14 - vr.restLeft.x) * cxBlend,
        vr.restLeft.y + (csy - vr.restLeft.y) * cxBlend,
        vr.restLeft.z +
          (csz + cxRight.z * rCx * 0.14 - vr.restLeft.z) * cxBlend,
        0,
        true
      );
      setHandTarget(
        "right",
        vr.restRight.x +
          (csx - cxRight.x * rCx * 0.14 - vr.restRight.x) * cxBlend,
        vr.restRight.y + (csy - rCx * 0.03 - vr.restRight.y) * cxBlend,
        vr.restRight.z +
          (csz - cxRight.z * rCx * 0.14 - vr.restRight.z) * cxBlend,
        0,
        true
      );
      if (g.t > g.duration) {
        unlockHandsSoft();
        activeGesture = null;
      }
      return true;
    }
    if (g.kind === "reset") {
      resetBodyInternal();
      activeGesture = null;
      return false;
    }
    // Unknown — soft unlock then drop.
    unlockHandsSoft();
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
    // Soft return to hang — sample current wrists, spring home (no teleport).
    try {
      sampleWristsFromBones();
    } catch (_) {}
    vr.settleUntil = Math.max(vr.settleUntil || 0, idleTime + 1.5);
    try {
      restoreHangPose({ recalibrate: true, recaptureBase: false, snapHands: false });
    } catch (_) {
      // Keep live positions; physics will ease toward rest.
    }
    // Head eases via updateVrPhysics (not snapped).
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
    // Smooth load: arms ease into full hang instead of snapping.
    if (hangEase < 1) {
      hangEase = Math.min(1, hangEase + dt / 0.9);
    }
    // After load: remeasure bind arm meta, re-solve hang, geometric rest wrists.
    if (restRecalibLeft > 0) {
      restRecalibLeft -= 1;
      if (restRecalibLeft === 0 && !activeGesture && !isVrmaPlaying()) {
        try {
          // Full re-calibrate once world matrices settle (custom VRMs often wrong on frame 0).
          restoreArmBindPose();
          // Restore torso/legs to bind so axis probes see true T-pose, not hang.
          try {
            Object.keys(restBones.bindQ || {}).forEach(function (k) {
              var n = restBones[k];
              var q = restBones.bindQ[k];
              if (n && q) n.quaternion.copy(q);
            });
          } catch (_) {}
          if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
          measureArmMeta("left");
          measureArmMeta("right");
          restBones.axes = measureBodyAxes();
          restBones.hangDeltas = solveHangDeltas();
          applyPitchSignToHangDeltas(restBones.hangDeltas);
          restBones.hinge = solveLimbHinges();
          rewriteHangDeltasWithHinges(restBones.hangDeltas, restBones.hinge);
          applySoftHangEulers();
          captureBaseFromBones();
          if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
          // Keep live wrists (hangEase) — only refresh rest targets + shoulders.
          calibrateVrRestsFromBones({ preserveHands: true });
          ikElbowSign.left = 0;
          ikElbowSign.right = 0;
          // Seed elbow polarity from hang wrists so idle never starts flipped.
          try {
            seedElbowSignFromHang("left");
            seedElbowSignFromHang("right");
          } catch (_) {}
          postFitRecalib = false;
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

    var locoBusy = isLocoMoving();
    // While player-steering, mute turn-based idle sway so voice phase changes
    // don't fight walk/jump (the old "reset glitch" between listen/think/speak).
    var locoMul = locoBusy ? 0.18 : 1;

    // Idle "alive" — deeper breath, visible weight shift, soft shoulder roll.
    // Previous amplitudes were ~½ of human micro-motion and read as a mannequin.
    var breath = Math.sin(t * 1.05) * 0.022 + Math.sin(t * 0.31) * 0.008;
    var sway =
      (Math.sin(t * 0.36) * 0.032 +
        Math.sin(t * 0.78) * 0.012 +
        Math.sin(t * 0.15) * 0.01) *
      locoMul;
    var weight =
      ((poseBlend - 0.5) * 0.055 +
        Math.sin(t * 0.22) * 0.02 +
        Math.sin(t * 0.11) * 0.012) *
      locoMul;
    var shoulder =
      Math.sin(t * 0.48) * 0.022 +
      Math.sin(t * 1.15) * 0.007 +
      Math.sin(t * 0.2) * 0.01;
    // Single clean nod arc — small amplitude (was stacking with lookAt → weird bob).
    var nodAmt = nodPulse > 0 && !locoBusy ? Math.sin((1 - nodPulse) * Math.PI) * 0.028 : 0;

    if (currentState === STATE_THINKING) {
      sway = (Math.sin(t * 0.32) * 0.02 + Math.sin(t * 0.55) * 0.008) * locoMul;
      breath = Math.sin(t * 0.95) * 0.018;
      weight = (Math.sin(t * 0.18) * 0.016 + 0.01) * locoMul;
    } else if (currentState === STATE_SPEAKING) {
      sway = (Math.sin(t * 0.55) * 0.026 + Math.sin(t * 1.05) * 0.01) * locoMul;
      breath = Math.sin(t * 1.35) * 0.024 + mouthValue * 0.01;
      shoulder += mouthValue * 0.012;
    } else if (currentState === STATE_LISTENING) {
      sway = (Math.sin(t * 0.34) * 0.024 + Math.sin(t * 0.7) * 0.008) * locoMul;
      breath = Math.sin(t * 1.0) * 0.018;
    }

    // 1) Pose torso / legs / head first so shoulders are in the right place.
    // Pitch (X) scaled by measured spinePitchSign so breath/lean never arches backward.
    var psIdle = spinePitchSign();
    var hsIdle = headPitchSign();
    addEuler(
      restBones.hips,
      "hips",
      (0.012 + weight * 0.45 + breath * 0.15) * psIdle,
      sway * 0.42 + weight * 0.7,
      weight * 0.28 + sway * 0.08
    );
    addEuler(
      restBones.spine,
      "spine",
      (0.014 + breath * 0.85) * psIdle,
      sway * 0.42 - weight * 0.28,
      sway * 0.12 + shoulder * 0.28
    );
    if (restBones.chest) {
      addEuler(
        restBones.chest,
        "chest",
        breath * 1.05 * psIdle,
        sway * 0.28,
        shoulder * 0.4
      );
    }
    if (restBones.upperChest) {
      addEuler(
        restBones.upperChest,
        "upperChest",
        breath * 0.55 * psIdle,
        sway * 0.14,
        shoulder * 0.16
      );
    }
    // Soft shoulder shrug-cycle (reads as relaxed, not T-pose mannequin).
    if (restBones.leftShoulder) {
      addEuler(
        restBones.leftShoulder,
        "leftShoulder",
        breath * 0.08,
        -0.01 - shoulder * 0.12,
        0.02 + Math.sin(t * 0.4) * 0.015
      );
    }
    if (restBones.rightShoulder) {
      addEuler(
        restBones.rightShoulder,
        "rightShoulder",
        breath * 0.08,
        0.01 + shoulder * 0.12,
        -0.02 - Math.sin(t * 0.4 + 0.8) * 0.015
      );
    }

    // Virtual HMD look: tool look_at x=-1..1 must read as a clear head turn
    // (old gain 0.18 rad total ≈ 10° — invisible). Neck+head stack ~±45° at full.
    var lookYaw = lookSmooth.x * 0.55;
    // look y>0 = look up → negative pitch on standard bones; flip with headPitchSign.
    var lookPitch = -lookSmooth.y * 0.28 * hsIdle;
    var lookRoll = lookSmooth.x * 0.06;
    var headDx = (vr.head.x - vr.restHead.x) * 0.25;
    addEuler(
      restBones.neck,
      "neck",
      (nodAmt * 0.4 + breath * 0.04) * hsIdle + lookPitch * 0.35,
      lookYaw * 0.48 + sway * 0.05 + headDx * 0.3,
      lookRoll * 0.35 + shoulder * 0.04
    );
    addEuler(
      restBones.head,
      "head",
      nodAmt * 0.6 * hsIdle + lookPitch * 0.55,
      lookYaw * 0.62 + sway * 0.04 + headDx * 0.4,
      lookRoll * 0.45
    );

    // Idle weight-shift on legs only when not player-walking (hinge-correct).
    // Larger hip rock + soft knee bounce so stance isn't a locked stick figure.
    if (!locoBusy) {
      var Hi = restBones.hinge || defaultLimbHinges();
      var kneeBounce =
        (0.045 + Math.abs(weight) * 0.55 + Math.sin(t * 0.9) * 0.012) * locoMul;
      // Combine hinge + lateral into one write (addEuler overwrites).
      function hipDelta(hingeVec, hingeAmt, yExtra, zExtra) {
        var h = hingeVec || [1, 0, 0];
        return [
          (h[0] || 0) * hingeAmt,
          (h[1] || 0) * hingeAmt + yExtra,
          (h[2] || 0) * hingeAmt + zExtra,
        ];
      }
      if (restBones.leftUpperLeg) {
        var ld = hipDelta(Hi.leftHip, weight * 0.65, weight * 0.08, weight * 0.12);
        addEuler(restBones.leftUpperLeg, "leftUpperLeg", ld[0], ld[1], ld[2]);
      }
      if (restBones.rightUpperLeg) {
        var rd = hipDelta(Hi.rightHip, -weight * 0.65, -weight * 0.08, -weight * 0.12);
        addEuler(restBones.rightUpperLeg, "rightUpperLeg", rd[0], rd[1], rd[2]);
      }
      if (restBones.leftLowerLeg) {
        addHinge(
          restBones.leftLowerLeg,
          "leftLowerLeg",
          Hi.leftKnee,
          kneeBounce * (weight >= 0 ? 1.15 : 0.55)
        );
      }
      if (restBones.rightLowerLeg) {
        addHinge(
          restBones.rightLowerLeg,
          "rightLowerLeg",
          Hi.rightKnee,
          kneeBounce * (weight < 0 ? 1.15 : 0.55)
        );
      }
    }

    // 2) Walk cycle sets leg eulers + walkWrist targets (same frame).
    applyLocomotionPose(dt);
    // 3) Spring free wrists toward rest↔walk blend (after targets are current).
    updateVrPhysics(dt);

    // 4) Always two-bone IK from virtual wrists — single arm path forever.
    if (bodyCollisionOn) {
      try {
        measureBodyColliders();
        applyBodyCollisionToHands();
      } catch (_) {}
    }
    applyHandIk("left");
    applyHandIk("right");
  }

  /**
   * Start a frame-driven activeGesture (no VRMA). Used by playTemplate when
   * look-only / VRMA paths would appear dead (bow, hips, cross, …).
   */
  function startScriptedGesture(name, opts) {
    opts = opts || {};
    var n = String(name || "")
      .toLowerCase()
      .replace(/[\s-]+/g, "_");
    var intensity =
      typeof opts.intensity === "number" ? clamp(opts.intensity, 0.2, 1.5) : 1;
    var sideRaw =
      opts.side == null || opts.side === ""
        ? ""
        : String(opts.side).toLowerCase().trim();
    if (sideRaw === "l") sideRaw = "left";
    if (sideRaw === "r") sideRaw = "right";
    if (sideRaw === "all") sideRaw = "both";
    var side = sideRaw;
    if (n === "point_left") {
      n = "point";
      side = "left";
    }
    if (n === "point_right") {
      n = "point";
      side = "right";
    }
    if (side !== "left" && side !== "right" && side !== "both") {
      side = "right";
    }
    if (n === "reset" || n === "reset_body" || n === "idle" || n === "rest") {
      resetBodyInternal();
      return true;
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
      hands_on_hips: { kind: "hands_on_hips", duration: 2.4 },
      crossed_arms: { kind: "crossed_arms", duration: 2.4 },
      celebrate: { kind: "cheer", duration: 1.5 },
      hello: { kind: "wave", duration: 1.8, side: side },
      yes: { kind: "nod", duration: 0.9 },
    };
    var spec = table[n];
    if (!spec) return false;
    try {
      stopVrmaInternal(false);
      clearAiMotionTimers();
      aiMotionGen++;
    } catch (_) {}
    activeGesture = {
      kind: spec.kind,
      t: 0,
      duration: spec.duration,
      intensity: intensity,
      side: spec.side || side,
    };
    try {
      console.log(
        "[CompanionStage] scripted gesture",
        n,
        "side=" + activeGesture.side,
        "intensity=" + intensity
      );
    } catch (_) {}
    return true;
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

    // Force scripted body path when requested (pose wheel / custom VRM safety).
    if (opts.forceJoint || opts.scripted || !opts.preferVrma) {
      // Prefer measured IK gestures — VRMA often inverts on downloaded models.
      var scriptedOk = startScriptedGesture(
        n,
        Object.assign({}, opts, { side: side, intensity: intensity })
      );
      if (scriptedOk) return true;
    }

    // Portable VRMA only when no scripted path or preferVrma (emotions etc.).
    var vrmaId = resolveVrmaId(n, opts);
    if (vrmaId && canPlayVrma()) {
      return playVrma(vrmaId, {
        intensity: intensity,
        side: side,
        fallback: n,
        loop: !!opts.loop,
      });
    }

    return startScriptedGesture(n, Object.assign({}, opts, { side: side, intensity: intensity }));
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

  // ─── Universal actor control + real-time locomotion ───────────────────────

  function getPossessedActor() {
    if (!possessedId) return null;
    return actors[possessedId] || null;
  }

  function isControlActive() {
    return !!(loco && loco.enabled && getPossessedActor() && getPossessedActor().root);
  }

  function isLocoMoving() {
    if (!isControlActive()) return false;
    // Treat gait settle as "moving" so voice state pose doesn't fight the blend.
    if ((loco.gaitWeight || 0) > 0.12) return true;
    var sp = Math.sqrt(loco.vx * loco.vx + loco.vz * loco.vz);
    return sp > 0.12 || Math.abs(loco.inputX) > 0.08 || Math.abs(loco.inputZ) > 0.08 || !loco.grounded;
  }

  /**
   * Register a VRM (or any humanoid scene root) as a controllable actor.
   * Root receives world XZ/yaw/jump; bone layers stay avatar-local.
   */
  function registerActor(id, opts) {
    opts = opts || {};
    var aid = String(id || "").trim();
    if (!aid) return false;
    var root = opts.root || null;
    var actorVrm = opts.vrm || null;
    if (!root && actorVrm && actorVrm.scene) root = actorVrm.scene.parent || actorVrm.scene;
    if (!root) return false;
    actors[aid] = {
      id: aid,
      kind: opts.kind || "vrm",
      root: root,
      vrm: actorVrm,
      label: opts.label || aid,
    };
    if (!possessedId) possessedId = aid;
    return true;
  }

  function unregisterActor(id) {
    var aid = String(id || "").trim();
    if (!aid || !actors[aid]) return false;
    delete actors[aid];
    if (possessedId === aid) {
      possessedId = null;
      var keys = Object.keys(actors);
      if (keys.length) possessedId = keys[0];
    }
    return true;
  }

  /** Possess an actor for stick/jump. Defaults to "companion". */
  function possess(id) {
    var aid = String(id || "companion").trim();
    if (!actors[aid]) return false;
    possessedId = aid;
    // Snap loco yaw to current root rotation.
    try {
      loco.yaw = actors[aid].root.rotation.y || 0;
    } catch (_) {}
    return true;
  }

  function setControlEnabled(on) {
    loco.enabled = !!on;
    if (!loco.enabled) {
      loco.inputX = 0;
      loco.inputZ = 0;
      loco.jumpEdge = false;
      loco.jumpHeld = false;
      loco.vx = 0;
      loco.vz = 0;
    }
    return loco.enabled;
  }

  /**
   * Drive locomotion from HUD / host.
   * moveX: -1..1 strafe (right+), moveY: -1..1 stick vertical (up = forward),
   * jump: boolean edge or held (edge fired when true after false).
   */
  function setControlInput(input) {
    if (!input || typeof input !== "object") return false;
    var mx =
      typeof input.moveX === "number"
        ? input.moveX
        : typeof input.x === "number"
          ? input.x
          : loco.inputX;
    var my =
      typeof input.moveY === "number"
        ? input.moveY
        : typeof input.y === "number"
          ? input.y
          : typeof input.moveZ === "number"
            ? input.moveZ
            : loco.inputZ;
    loco.inputX = clamp(mx, -1, 1);
    // Stick up → positive forward in camera space.
    loco.inputZ = clamp(my, -1, 1);
    var j =
      input.jump === true ||
      input.jumpPressed === true ||
      input.jumpEdge === true;
    if (j && !loco.jumpHeld) {
      loco.jumpEdge = true;
    }
    if (typeof input.jump === "boolean" || typeof input.jumpPressed === "boolean") {
      loco.jumpHeld = !!(input.jump || input.jumpPressed);
    } else if (input.jumpEdge === true) {
      loco.jumpHeld = true;
    }
    if (input.jump === false || input.jumpPressed === false) {
      loco.jumpHeld = false;
    }
    return true;
  }

  function getControlState() {
    var act = getPossessedActor();
    return {
      enabled: !!loco.enabled,
      possessed: possessedId,
      actors: Object.keys(actors),
      grounded: !!loco.grounded,
      moving: isLocoMoving(),
      input: { x: loco.inputX, y: loco.inputZ },
      velocity: { x: loco.vx, y: loco.vy, z: loco.vz },
      yaw: loco.yaw,
      position: act && act.root
        ? {
            x: act.root.position.x,
            y: act.root.position.y,
            z: act.root.position.z,
          }
        : null,
    };
  }

  /** Camera-relative wish direction on XZ (third-person stick). */
  function locoWishDir() {
    var ix = loco.inputX;
    var iz = loco.inputZ;
    var mag = Math.sqrt(ix * ix + iz * iz);
    if (mag < 0.04) return { x: 0, z: 0, mag: 0 };
    if (mag > 1) {
      ix /= mag;
      iz /= mag;
      mag = 1;
    }
    var lookX = 0;
    var lookZ = 1;
    if (camera && controls && controls.target) {
      lookX = controls.target.x - camera.position.x;
      lookZ = controls.target.z - camera.position.z;
    } else if (camera) {
      lookX = -camera.position.x;
      lookZ = -camera.position.z;
    }
    var llen = Math.sqrt(lookX * lookX + lookZ * lookZ);
    if (llen < 1e-4) {
      lookX = 0;
      lookZ = 1;
      llen = 1;
    }
    lookX /= llen;
    lookZ /= llen;
    // Camera right on XZ (Y-up cross: forward × up → right)
    var rightX = -lookZ;
    var rightZ = lookX;
    return {
      x: rightX * ix + lookX * iz,
      z: rightZ * ix + lookZ * iz,
      mag: mag,
    };
  }

  function shortestAngle(from, to) {
    var d = to - from;
    while (d > Math.PI) d -= Math.PI * 2;
    while (d < -Math.PI) d += Math.PI * 2;
    return d;
  }

  /**
   * Real-time root locomotion for possessed actor. Continuous — never reset by
   * voice idle/listen/think/speak phase changes.
   */
  function updateLocomotion(dt) {
    if (!loco.enabled) return;
    var act = getPossessedActor();
    if (!act || !act.root) return;
    var root = act.root;
    var wish = locoWishDir();
    var targetSp = loco.moveSpeed * wish.mag;
    var wishX = wish.x;
    var wishZ = wish.z;
    var wlen = Math.sqrt(wishX * wishX + wishZ * wishZ);
    if (wlen > 1e-4) {
      wishX /= wlen;
      wishZ /= wlen;
    }

    // Accelerate / friction on XZ
    if (wish.mag > 0.04 && loco.grounded) {
      var tvx = wishX * targetSp;
      var tvz = wishZ * targetSp;
      var ax = (tvx - loco.vx) * Math.min(1, dt * loco.accel);
      var az = (tvz - loco.vz) * Math.min(1, dt * loco.accel);
      loco.vx += ax;
      loco.vz += az;
      // Face move direction (smooth).
      var targetYaw = Math.atan2(wishX, wishZ);
      var dyaw = shortestAngle(loco.yaw, targetYaw);
      loco.yaw += dyaw * Math.min(1, dt * 10);
    } else {
      var damp = Math.exp(-loco.friction * dt);
      if (!loco.grounded) damp = Math.exp(-loco.friction * 0.35 * dt);
      loco.vx *= damp;
      loco.vz *= damp;
      if (Math.abs(loco.vx) < 0.01) loco.vx = 0;
      if (Math.abs(loco.vz) < 0.01) loco.vz = 0;
    }

    // Jump
    if (loco.jumpEdge && loco.grounded) {
      loco.vy = loco.jumpSpeed;
      loco.grounded = false;
      loco.airTime = 0;
    }
    loco.jumpEdge = false;

    if (!loco.grounded) {
      loco.vy -= loco.gravity * dt;
      loco.airTime += dt;
    } else {
      loco.airTime = 0;
      if (loco.vy < 0) loco.vy = 0;
    }

    var half = loco.worldHalf;
    var nx = clamp(root.position.x + loco.vx * dt, -half, half);
    var nz = clamp(root.position.z + loco.vz * dt, -half, half);
    // Wall / platform AABB resolve (from CompanionWorldMaps when a map is loaded).
    try {
      var WM = window.CompanionWorldMaps;
      if (WM && typeof WM.resolveWalls === "function" && worldColliders.length) {
        var resolved = WM.resolveWalls(worldColliders, nx, nz, root.position.y, 0.3);
        if (resolved) {
          nx = resolved.x;
          nz = resolved.z;
        }
      }
    } catch (_) {}
    root.position.x = nx;
    root.position.z = nz;
    root.position.y += loco.vy * dt;

    var groundY = 0;
    try {
      var WMg = window.CompanionWorldMaps;
      if (WMg && typeof WMg.sampleGroundY === "function" && worldColliders.length) {
        groundY = WMg.sampleGroundY(
          worldColliders,
          root.position.x,
          root.position.z,
          root.position.y,
          0.45,
        );
      }
    } catch (_) {}
    if (root.position.y <= groundY) {
      root.position.y = groundY;
      loco.vy = 0;
      loco.grounded = true;
    } else if (loco.grounded && root.position.y > groundY + 0.08) {
      // Walked off a ledge
      loco.grounded = false;
    }
    root.rotation.y = loco.yaw;

    // Foot ring follows actor
    if (floorRing) {
      floorRing.position.x = root.position.x;
      floorRing.position.z = root.position.z;
      floorRing.position.y = groundY + 0.006;
    }

    updateCameraFollow(dt, root);
  }

  /** Keep third-person orbit target on the possessed character; preserve camera offset. */
  function updateCameraFollow(dt, root) {
    if (!loco.camFollow || !camera || !root) return;
    var lookY = 1.15;
    try {
      if (vrm && vrm.scene) {
        var L = getLibs();
        if (L && L.THREE) {
          var box = new L.THREE.Box3().setFromObject(vrm.scene);
          if (isFinite(box.min.y) && isFinite(box.max.y)) {
            lookY = (box.max.y - box.min.y) * 0.55;
          }
        }
      }
    } catch (_) {}
    var tx = root.position.x;
    var ty = root.position.y + lookY;
    var tz = root.position.z;
    if (controls && controls.target) {
      var k = Math.min(1, dt * 12);
      var ptx = controls.target.x;
      var pty = controls.target.y;
      var ptz = controls.target.z;
      // Translate camera by the same delta as the target so walk doesn't leave cam behind.
      var ntx = ptx + (tx - ptx) * k;
      var nty = pty + (ty - pty) * k;
      var ntz = ptz + (tz - ptz) * k;
      var dx = ntx - ptx;
      var dy = nty - pty;
      var dz = ntz - ptz;
      controls.target.set(ntx, nty, ntz);
      camera.position.x += dx;
      camera.position.y += dy;
      camera.position.z += dz;
    } else {
      // No orbit controls — soft look-at.
      try {
        camera.lookAt(tx, ty, tz);
      } catch (_) {}
    }
  }

  /**
   * Procedural walk / hop on humanoid legs + wrist-target arm swing (any VRM).
   * Arms are NEVER posed by bone eulers here — only walkWrist targets, which
   * physics blends and two-bone IK always solves. That removes walk→idle snaps.
   */
  function applyLocomotionPose(dt) {
    // Clear walk wrist desires each frame; refilled when gait is active.
    walkWrist.left = null;
    walkWrist.right = null;

    if (!isControlActive() || !restBones || isVrmaPlaying()) {
      if (loco) {
        loco.gaitWeight = Math.max(
          0,
          (loco.gaitWeight || 0) * Math.max(0, 1 - dt * 2.4)
        );
      }
      return;
    }
    var H = restBones.hinge || defaultLimbHinges();
    var sp = Math.sqrt(loco.vx * loco.vx + loco.vz * loco.vz);
    var stickOn =
      Math.abs(loco.inputX) > 0.06 || Math.abs(loco.inputZ) > 0.06;
    var moving = sp > 0.08 && loco.grounded;
    var airborne = !loco.grounded && loco.airTime > 0.02;

    var wantGait = 0;
    if (airborne) {
      wantGait = 0.85;
    } else if (moving || stickOn) {
      wantGait = clamp(Math.max(sp / loco.moveSpeed, stickOn ? 0.4 : 0), 0.25, 1);
    }
    // Fast engage, slow release so stop-walking eases into hang (not a pop).
    var rate = wantGait > loco.gaitWeight ? 7.0 : 2.1;
    loco.gaitWeight += (wantGait - loco.gaitWeight) * Math.min(1, dt * rate);
    if (loco.gaitWeight < 0.006) loco.gaitWeight = 0;

    if (moving) {
      loco.walkPhase += dt * (2.6 + sp * 2.4);
    } else if (loco.grounded) {
      loco.walkPhase *= Math.max(0, 1 - dt * 1.6);
    }

    var gw = loco.gaitWeight;
    if (gw < 0.01 && !airborne) return;

    var swing = Math.sin(loco.walkPhase);
    var swingOpp = Math.sin(loco.walkPhase + Math.PI);
    var amp = clamp(sp / loco.moveSpeed, 0.12, 1) * 0.82 * gw;
    if (!moving && !airborne) {
      amp = 0.14 * gw * Math.min(1, Math.abs(swing) + 0.2);
    }
    var plantL = Math.max(0, -swing);
    var plantR = Math.max(0, -swingOpp);

    if (loco.grounded && gw > 0) {
      if (restBones.leftUpperLeg) {
        addHinge(restBones.leftUpperLeg, "leftUpperLeg", H.leftHip, swing * amp);
      }
      if (restBones.rightUpperLeg) {
        addHinge(restBones.rightUpperLeg, "rightUpperLeg", H.rightHip, swingOpp * amp);
      }
      if (restBones.leftLowerLeg) {
        addHinge(restBones.leftLowerLeg, "leftLowerLeg", H.leftKnee, plantL * amp * 1.15);
      }
      if (restBones.rightLowerLeg) {
        addHinge(restBones.rightLowerLeg, "rightLowerLeg", H.rightKnee, plantR * amp * 1.15);
      }

      // Counter-arm swing via wrist targets only (IK owns bones every frame).
      if (!activeGesture && gw > 0.04) {
        var reachL = armReachLen("left") || 0.55;
        var reachR = armReachLen("right") || 0.55;
        // Character face-forward for swing (soft cam only if viewer is in front).
        var face = gestureForwardXZ(vr.restLeft || { x: 0, z: 0 });
        var travelL = reachL * 0.2 * amp;
        var travelR = reachR * 0.2 * amp;
        walkWrist.left = {
          x: vr.restLeft.x + face.x * swingOpp * travelL,
          y: vr.restLeft.y + Math.abs(swingOpp) * reachL * 0.025 * amp,
          z: vr.restLeft.z + face.z * swingOpp * travelL + reachL * 0.02 * amp,
        };
        walkWrist.right = {
          x: vr.restRight.x + face.x * swing * travelR,
          y: vr.restRight.y + Math.abs(swing) * reachR * 0.025 * amp,
          z: vr.restRight.z + face.z * swing * travelR + reachR * 0.02 * amp,
        };
      }
    }

    // Air pose: legs tuck via hinges; wrists open slightly for balance (IK).
    if (airborne) {
      var tuck = clamp(loco.airTime * 2.5, 0, 0.55) * gw;
      if (restBones.leftUpperLeg) {
        addHinge(restBones.leftUpperLeg, "leftUpperLeg", H.leftHip, tuck * 0.45);
      }
      if (restBones.rightUpperLeg) {
        addHinge(restBones.rightUpperLeg, "rightUpperLeg", H.rightHip, tuck * 0.4);
      }
      if (restBones.leftLowerLeg) {
        addHinge(restBones.leftLowerLeg, "leftLowerLeg", H.leftKnee, tuck * 0.7);
      }
      if (restBones.rightLowerLeg) {
        addHinge(restBones.rightLowerLeg, "rightLowerLeg", H.rightKnee, tuck * 0.65);
      }
      if (!activeGesture) {
        var rAL = armReachLen("left") || 0.55;
        var rAR = armReachLen("right") || 0.55;
        walkWrist.left = {
          x: vr.restLeft.x - rAL * 0.08 * tuck,
          y: vr.restLeft.y + rAL * 0.1 * tuck,
          z: vr.restLeft.z + rAL * 0.06 * tuck,
        };
        walkWrist.right = {
          x: vr.restRight.x + rAR * 0.08 * tuck,
          y: vr.restRight.y + rAR * 0.1 * tuck,
          z: vr.restRight.z + rAR * 0.06 * tuck,
        };
      }
    }
  }

  function ensureFloor(THREE) {
    if (floorMesh) return;
    try {
      // Larger playable pad for third-person walk (was a tiny stage disc).
      var geo = new THREE.CircleGeometry(12, 64);
      var mat = new THREE.MeshStandardMaterial({
        color: 0x121826,
        roughness: 0.94,
        metalness: 0.05,
        transparent: true,
        opacity: 0.92,
      });
      floorMesh = new THREE.Mesh(geo, mat);
      floorMesh.rotation.x = -Math.PI / 2;
      floorMesh.position.y = 0;
      floorMesh.name = "companion-floor";
      scene.add(floorMesh);

      // Soft grid rings for game-space depth.
      try {
        var grid = new THREE.RingGeometry(3.8, 3.95, 64);
        var gridMat = new THREE.MeshBasicMaterial({
          color: 0x3d5a80,
          transparent: true,
          opacity: 0.22,
          side: THREE.DoubleSide,
        });
        var gridMesh = new THREE.Mesh(grid, gridMat);
        gridMesh.rotation.x = -Math.PI / 2;
        gridMesh.position.y = 0.003;
        scene.add(gridMesh);
        var grid2 = new THREE.RingGeometry(7.5, 7.65, 64);
        var gridMesh2 = new THREE.Mesh(grid2, gridMat.clone());
        gridMesh2.rotation.x = -Math.PI / 2;
        gridMesh2.position.y = 0.003;
        scene.add(gridMesh2);
      } catch (_) {}

      var ringGeo = new THREE.RingGeometry(0.32, 0.4, 48);
      var ringMat = new THREE.MeshBasicMaterial({
        color: 0x6ea8ff,
        transparent: true,
        opacity: 0.4,
        side: THREE.DoubleSide,
      });
      floorRing = new THREE.Mesh(ringGeo, ringMat);
      floorRing.rotation.x = -Math.PI / 2;
      floorRing.position.y = 0.005;
      scene.add(floorRing);
    } catch (_) {}
  }

  function placeFloorUnderAvatar() {
    if (!vrm || !vrm.scene) return;
    try {
      var L = getLibs();
      if (!L || !L.THREE) return;
      // Keep floor as a fixed world plane; only snap avatar feet to y=0 on the root.
      var box = new L.THREE.Box3().setFromObject(vrm.scene);
      if (!isFinite(box.min.y)) return;
      // If VRM floats/sinks, offset the scene child (not world floor) once on calibrate.
      var footY = box.min.y;
      if (Math.abs(footY) > 0.002 && Math.abs(footY) < 2.5 && vrm.scene) {
        vrm.scene.position.y -= footY;
      }
      if (floorMesh) floorMesh.position.y = 0;
      if (floorRing) {
        floorRing.position.y = 0.006;
        if (modelRoot) {
          floorRing.position.x = modelRoot.position.x;
          floorRing.position.z = modelRoot.position.z;
        }
      }
    } catch (_) {}
  }

  function ensureWorldMapRoot(THREE) {
    if (worldMapRoot) return worldMapRoot;
    worldMapRoot = new THREE.Group();
    worldMapRoot.name = "companion-world-maps";
    scene.add(worldMapRoot);
    return worldMapRoot;
  }

  function clearWorldMap() {
    if (!worldMapRoot) {
      worldColliders = [];
      return;
    }
    while (worldMapRoot.children.length) {
      var ch = worldMapRoot.children[0];
      worldMapRoot.remove(ch);
      try {
        ch.traverse(function (o) {
          if (o.geometry) o.geometry.dispose();
          if (o.material) {
            if (Array.isArray(o.material)) {
              o.material.forEach(function (m) {
                if (m && m.dispose) m.dispose();
              });
            } else if (o.material.dispose) o.material.dispose();
          }
        });
      } catch (_) {}
    }
    worldColliders = [];
  }

  function setDefaultFloorVisible(on) {
    if (floorMesh) floorMesh.visible = !!on;
    // Keep foot ring always — helpful in maps too.
  }

  /**
   * Load a Companion World map (same ids as Godot WorldBridge).
   * Maps ship in assets/companion/world/ — no separate Godot APK.
   * @returns {boolean} true if load started / completed synchronously
   */
  function loadMap(mapId) {
    var id = String(mapId || "").trim();
    if (!id) return false;
    var L = getLibs();
    if (!L || !L.THREE || !scene) return false;
    var WM = window.CompanionWorldMaps;
    if (!WM || typeof WM.buildMap !== "function") {
      setHud("World maps module missing");
      return false;
    }
    var known = WM.listMaps ? WM.listMaps() : WM.MAP_IDS || [];
    if (known.indexOf(id) < 0) {
      setHud("Unknown map: " + id);
      return false;
    }
    var token = ++worldMapGen;
    clearWorldMap();
    setDefaultFloorVisible(false);
    var root = ensureWorldMapRoot(L.THREE);
    var colliders = [];
    setHud("Loading " + id + "…");
    Promise.resolve(WM.buildMap(id, L.THREE, L, root, colliders))
      .then(function (meta) {
        if (token !== worldMapGen) return;
        worldColliders = colliders;
        currentMapId = id;
        if (meta && typeof meta.worldHalf === "number") {
          loco.worldHalf = meta.worldHalf;
        }
        var spawn = (meta && meta.spawn) || { x: 0, y: 0, z: 0 };
        var act = getPossessedActor();
        var r = act && act.root ? act.root : modelRoot;
        if (r) {
          r.position.x = spawn.x;
          r.position.y = spawn.y;
          r.position.z = spawn.z;
          loco.vx = 0;
          loco.vy = 0;
          loco.vz = 0;
          loco.grounded = true;
        }
        setHud("Map: " + id);
        setTimeout(function () {
          if (token === worldMapGen) setHud("");
        }, 2200);
        try {
          if (typeof window.GrokifyCompanion !== "undefined" && window.GrokifyCompanion.onDebugLog) {
            window.GrokifyCompanion.onDebugLog("map_loaded " + id);
          }
        } catch (_) {}
      })
      .catch(function (e) {
        if (token !== worldMapGen) return;
        setDefaultFloorVisible(true);
        currentMapId = "stage";
        setHud("Map failed: " + ((e && e.message) || String(e)).slice(0, 80));
      });
    return true;
  }

  function listMaps() {
    var WM = window.CompanionWorldMaps;
    if (WM && typeof WM.listMaps === "function") return WM.listMaps();
    return ["proto_arena", "kenney_plaza", "courtyard", "mini_dungeon"];
  }

  function getCurrentMap() {
    return currentMapId;
  }

  function nextMap() {
    var maps = listMaps();
    if (!maps.length) return currentMapId;
    var idx = maps.indexOf(currentMapId);
    if (idx < 0) idx = -1;
    var nid = maps[(idx + 1) % maps.length];
    loadMap(nid);
    return nid;
  }

  /** Enter in-app world mode: ensure control on + load default / given map. */
  function enterWorld(mapId) {
    setControlEnabled(true);
    loco.camFollow = true;
    var id = mapId && String(mapId).trim() ? String(mapId).trim() : "proto_arena";
    if (currentMapId === id && worldColliders.length) {
      setHud("Map: " + id);
      return true;
    }
    return loadMap(id);
  }

  function leaveWorld() {
    clearWorldMap();
    setDefaultFloorVisible(true);
    currentMapId = "stage";
    loco.worldHalf = 9;
    var act = getPossessedActor();
    var r = act && act.root ? act.root : modelRoot;
    if (r) {
      r.position.x = 0;
      r.position.z = 0;
      r.position.y = 0;
      loco.vx = loco.vy = loco.vz = 0;
      loco.grounded = true;
    }
    setHud("");
    return true;
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
   * Lip-sync from host amplitude envelope (TTS PCM).
   * Hot drive + syllable-phase chatter so the jaw visibly tracks speech energy.
   */
  function applyMouth(v) {
    var open = Math.max(0, Math.min(1, Number(v) || 0));
    mouthValue = open;
    if (usingFallback) {
      setFallbackMouth(open);
      return;
    }
    if (!vrm) return;

    if (open < 0.008) {
      clearTalkExpressions();
      return;
    }

    var vel = Math.max(0, Math.min(1, Math.abs(mouthVelocity) * 3.4));
    // Boost mid-range so quiet TTS still opens lips; onset velocity adds punch.
    var drive = Math.min(1, open * 1.38 + vel * 0.14 + (open > 0.08 ? 0.06 : 0));
    // Syllable-ish chatter from speechPhase (host envelope is open amount only).
    var ph = speechPhase || 0;
    var c1 = 0.5 + 0.5 * Math.sin(ph * 9.7);
    var c2 = 0.5 + 0.5 * Math.sin(ph * 14.3 + 1.1);
    var c3 = 0.5 + 0.5 * Math.sin(ph * 6.2 + 0.4);
    var wAa = drive * (0.55 + 0.35 * c1 + vel * 0.12);
    var wOh = drive * (0.22 + 0.28 * (1 - c1) + (drive > 0.45 ? 0.12 : 0));
    var wOu = drive * (0.14 + 0.22 * c2 * (drive < 0.55 ? 1 : 0.45));
    var wIh = drive * (0.12 + 0.22 * c3) * (0.35 + vel);
    var wEe = drive * (0.08 + 0.18 * c2) * (0.25 + vel * 1.2);

    // Near-instant follow on attack; snappy close so consonants read.
    var s = drive > visemeSmooth.aa ? 0.96 : 0.82;
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
    setExpression("jawOpen", Math.min(1, drive * 1.05 + vel * 0.1));

    if (restBones && restBones.jaw && restBones.base && restBones.base.jaw) {
      var jb = restBones.base.jaw;
      setEuler(
        restBones.jaw,
        jb.x + drive * 0.48 + vel * 0.08,
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

    camera = new THREE.PerspectiveCamera(32, 1, 0.1, 80);
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
      controls.maxDistance = 18;
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

      // Track host envelope tightly — attack almost locks, release still snappy.
      var prevMouth = mouthValue;
      var mouthDelta = targetMouth - mouthValue;
      var mouthRate = mouthDelta > 0 ? 72 : 36;
      if (Math.abs(mouthDelta) > 0.0002) {
        mouthValue += mouthDelta * Math.min(1, dt * mouthRate);
      }
      if (currentState === STATE_SPEAKING || targetMouth > 0.02) {
        speechPhase += dt * (5.5 + targetMouth * 4.2 + Math.abs(mouthVelocity) * 0.8);
      } else {
        speechPhase *= 0.82;
      }
      mouthVelocity = (mouthValue - prevMouth) / Math.max(dt, 0.001);
      if (!usingFallback && (currentState === STATE_SPEAKING || targetMouth > 0.01 || mouthValue > 0.01)) {
        applyMouth(mouthValue);
      } else if (!usingFallback && mouthValue <= 0.01 && targetMouth <= 0.01) {
        if (visemeSmooth.aa > 0.001) clearTalkExpressions();
      }

      // Continuous world motion (stick / jump) — independent of voice turn state.
      try {
        updateLocomotion(dt);
      } catch (_) {}

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

    // Universal control hook: this VRM is the "companion" actor (any humanoid).
    // Future player body = registerActor("player", { vrm, root }) + possess("player").
    try {
      registerActor("companion", {
        vrm: vrm,
        root: modelRoot,
        kind: "vrm",
        label: label || "companion",
      });
      possess("companion");
      // Preserve world pose across model swaps; only zero velocity.
      loco.vx = 0;
      loco.vz = 0;
      loco.vy = 0;
      loco.grounded = modelRoot.position.y <= 0.001;
      loco.yaw = modelRoot.rotation.y || loco.yaw || 0;
    } catch (_) {}

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
    // Camera fit BEFORE a second axis pass so viewer-forward is valid.
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
    // Quick post-fit face-axis refresh (camera now valid). Must restore hang
    // eulers after bind probes so we don't leave spine at T-pose mid-frame.
    try {
      if (restBones && restBones.bindQ) {
        var savedHang = hangEase;
        var savedQ = {};
        Object.keys(restBones.bindQ).forEach(function (k) {
          var n = restBones[k];
          if (n && n.quaternion) savedQ[k] = n.quaternion.clone();
        });
        try {
          Object.keys(restBones.bindQ).forEach(function (k) {
            var n = restBones[k];
            var q = restBones.bindQ[k];
            if (n && q) n.quaternion.copy(q);
          });
          if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
          restBones.axes = measureBodyAxes();
          applyPitchSignToHangDeltas(restBones.hangDeltas);
        } finally {
          Object.keys(savedQ).forEach(function (k) {
            if (restBones[k] && restBones[k].quaternion) {
              restBones[k].quaternion.copy(savedQ[k]);
            }
          });
        }
        if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
        calibrateVrRestsFromBones({ preserveHands: true });
        ikElbowSign.left = 0;
        ikElbowSign.right = 0;
        seedElbowSignFromHang("left");
        seedElbowSignFromHang("right");
        hangEase = savedHang;
        postFitRecalib = true;
      }
    } catch (_) {}
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
    // Voice turn phases must NOT teleport, re-root, or hard-reset locomotion.
    // Only soft torso expression targets — continuous play keeps walking/jumping.
    if (prev !== s) {
      if (!isLocoMoving()) {
        pickStatePoseTarget(s);
      }
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
      mouthValue = mouthValue * 0.12 + targetMouth * 0.88;
      setFallbackMouth(mouthValue);
      return;
    }
    // Snap hard on onsets/closures so lips land with the audio peak.
    var d = targetMouth - mouthValue;
    if (Math.abs(d) > 0.04) {
      mouthValue = mouthValue + d * (d > 0 ? 0.92 : 0.8);
    } else if (Math.abs(d) > 0.01) {
      mouthValue = mouthValue + d * 0.65;
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
      calibration: (function () {
        var ax = bodyAxes();
        var H = (restBones && restBones.hinge) || null;
        return {
          face_forward: ax.forward
            ? { x: round3(ax.forward.x), y: round3(ax.forward.y || 0), z: round3(ax.forward.z) }
            : null,
          body_right: ax.right
            ? { x: round3(ax.right.x), y: round3(ax.right.y || 0), z: round3(ax.right.z) }
            : null,
          spine_pitch_sign: ax.spinePitchSign < 0 ? -1 : 1,
          head_pitch_sign: ax.headPitchSign < 0 ? -1 : 1,
          elbow_hinge: H
            ? { left: H.leftElbow, right: H.rightElbow }
            : null,
          knee_hinge: H
            ? { left: H.leftKnee, right: H.rightKnee }
            : null,
          note:
            "Measured after VRM load (and once matrices settle). Bow/lean use spine_pitch_sign; " +
            "elbow IK uses face_forward for behind-fold polarity.",
        };
      })(),
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
      control: getControlState(),
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

  /**
   * Re-run full post-load calibration (face axes, hinges, soft hang).
   * Useful after swapping assets or if a model looked inverted on first frame.
   */
  function recalibrateAvatar() {
    if (!vrm || !restBones) {
      return { ok: false, error: "no VRM loaded" };
    }
    try {
      try {
        Object.keys(restBones.bindQ || {}).forEach(function (k) {
          var n = restBones[k];
          var q = restBones.bindQ[k];
          if (n && q) n.quaternion.copy(q);
        });
      } catch (_) {}
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
      measureArmMeta("left");
      measureArmMeta("right");
      restBones.axes = measureBodyAxes();
      restBones.hangDeltas = solveHangDeltas();
      applyPitchSignToHangDeltas(restBones.hangDeltas);
      restBones.hinge = solveLimbHinges();
      rewriteHangDeltasWithHinges(restBones.hangDeltas, restBones.hinge);
      applySoftHangEulers();
      captureBaseFromBones();
      if (restBones.hips) restBones.hips.updateWorldMatrix(true, true);
      calibrateVrRestsFromBones({ preserveHands: false });
      ikElbowSign.left = 0;
      ikElbowSign.right = 0;
      hangEase = 0;
      try {
        var rL2 = armReachLen("left");
        var rR2 = armReachLen("right");
        if (!vr.left.locked) {
          vr.left.x = vr.restLeft.x;
          vr.left.y = vr.restLeft.y + rL2 * 0.14;
          vr.left.z = vr.restLeft.z;
          vr.left.vx = vr.left.vy = vr.left.vz = 0;
        }
        if (!vr.right.locked) {
          vr.right.x = vr.restRight.x;
          vr.right.y = vr.restRight.y + rR2 * 0.14;
          vr.right.z = vr.restRight.z;
          vr.right.vx = vr.right.vy = vr.right.vz = 0;
        }
        vr.settleUntil = Math.max(vr.settleUntil || 0, idleTime + 1.25);
        seedElbowSignFromHang("left");
        seedElbowSignFromHang("right");
      } catch (_) {}
      pickStatePoseTarget(currentState || STATE_IDLE);
      return {
        ok: true,
        axes: bodyAxes(),
        hinge: restBones.hinge,
        elbowSign: { left: ikElbowSign.left, right: ikElbowSign.right },
      };
    } catch (e) {
      return { ok: false, error: (e && e.message) || String(e) };
    }
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
    recalibrateAvatar: recalibrateAvatar,
    resetCamera: resetCamera,
    setOrbit: setOrbit,
    getOrbit: getOrbit,
    exportBodyState: exportBodyState,
    setDebugSkeleton: setDebugSkeleton,
    getDebugSkeleton: function () {
      return !!debugSkeletonOn;
    },
    /** Enable/disable body self-collision (wrist/elbow vs torso/clothes proxies). */
    setBodyCollision: function (on) {
      bodyCollisionOn = !!on;
      if (bodyCollisionOn) {
        try {
          if (!bodyColliders || !bodyColliders.length) rebuildBodyColliders();
          applyBodyCollisionToHands();
        } catch (_) {}
      }
    },
    getBodyCollision: function () {
      return !!bodyCollisionOn;
    },
    rebuildBodyColliders: rebuildBodyColliders,
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
    /**
     * Universal real-time control (any VRM):
     *   registerActor / possess / setControlInput / setControlEnabled
     * Companion is auto-registered as "companion" on loadModel.
     */
    control: {
      setEnabled: setControlEnabled,
      setInput: setControlInput,
      getState: getControlState,
      possess: possess,
      registerActor: registerActor,
      unregisterActor: unregisterActor,
      isMoving: isLocoMoving,
    },
    setControlEnabled: setControlEnabled,
    setControlInput: setControlInput,
    getControlState: getControlState,
    possess: possess,
    registerActor: registerActor,
    /** In-app Godot-parity maps (assets/companion/world/) — no side package. */
    loadMap: loadMap,
    nextMap: nextMap,
    listMaps: listMaps,
    getCurrentMap: getCurrentMap,
    enterWorld: enterWorld,
    leaveWorld: leaveWorld,
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
