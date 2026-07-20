/**
 * Companion VRM stage — offline Three.js + @pixiv/three-vrm.
 * Host bridge: window.GrokifyCompanion.{onReady,onModelLoaded,onError,onAvatarTapped,openVrm,readVrmBase64,closeVrm}
 * Stage API:   window.CompanionStage.{loadModel,setState,setMouth,playMotion}
 *
 * Android WebView cannot reliably fetch() file:// VRMs ("Failed to fetch").
 * Bytes are streamed from Kotlin via openVrm/readVrmBase64, then GLTFLoader.parse.
 *
 * Vendored libs expose window.CompanionVrmLibs = {
 *   THREE, GLTFLoader, VRMLoaderPlugin, VRMUtils, VRMExpressionPresetName
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
  var clock = null;
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
  var loadToken = 0;

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

  function destroyVrm() {
    if (!vrm) return;
    try {
      if (scene) scene.remove(vrm.scene);
    } catch (_) {}
    try {
      var L = getLibs();
      if (L && L.VRMUtils && typeof L.VRMUtils.deepDispose === "function") {
        L.VRMUtils.deepDispose(vrm.scene);
      }
    } catch (_) {}
    vrm = null;
  }

  function fitCamera() {
    if (!camera || !renderer) return;
    var w = window.innerWidth || 1;
    var h = window.innerHeight || 1;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h, false);

    if (vrm && vrm.scene && getLibs() && getLibs().THREE) {
      try {
        var THREE = getLibs().THREE;
        var box = new THREE.Box3().setFromObject(vrm.scene);
        if (isFinite(box.min.x) && isFinite(box.max.y) && box.getSize) {
          var size = new THREE.Vector3();
          var center = new THREE.Vector3();
          box.getSize(size);
          box.getCenter(center);
          if (size.y > 0.01) {
            var lookY = center.y + size.y * 0.18;
            var dist = Math.max(size.y * 0.95, size.x * 1.4, 1.1);
            if (w >= h) dist *= 1.15;
            camera.position.set(center.x, lookY, center.z + dist);
            camera.lookAt(center.x, lookY, center.z);
            return;
          }
        }
      } catch (_) {}
    }

    if (w < h) {
      camera.position.set(0, 1.35, 1.55);
      camera.lookAt(0, 1.25, 0);
    } else {
      camera.position.set(0, 1.3, 1.85);
      camera.lookAt(0, 1.2, 0);
    }
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

  function onCanvasPointer() {
    hostCall("onAvatarTapped");
    if (vrm && currentState === STATE_IDLE) {
      setExpression("happy", 0.55);
      setTimeout(function () {
        if (currentState === STATE_IDLE) applyStateExpressions(STATE_IDLE);
      }, 600);
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

    camera = new THREE.PerspectiveCamera(30, 1, 0.1, 20);
    clock = new THREE.Clock();

    var amb = new THREE.AmbientLight(0xffffff, 0.65);
    scene.add(amb);
    var key = new THREE.DirectionalLight(0xffffff, 1.1);
    key.position.set(1.2, 1.8, 1.5);
    scene.add(key);
    var fill = new THREE.DirectionalLight(0xa8c0ff, 0.45);
    fill.position.set(-1.4, 1.0, -0.6);
    scene.add(fill);
    var rim = new THREE.DirectionalLight(0xffe0c0, 0.25);
    rim.position.set(0, 1.2, -1.5);
    scene.add(rim);

    canvas.addEventListener("pointerdown", onCanvasPointer, { passive: true });
    window.addEventListener("resize", fitCamera);
    fitCamera();

    function tick() {
      animFrame = requestAnimationFrame(tick);
      var dt = clock ? clock.getDelta() : 0.016;
      idleTime += dt;

      var mouthDelta = targetMouth - mouthValue;
      if (Math.abs(mouthDelta) > 0.001) {
        mouthValue += mouthDelta * Math.min(1, dt * 18);
        if (!usingFallback) applyMouth(mouthValue);
      }

      if (vrm && !usingFallback) {
        var sway =
          currentState === STATE_THINKING
            ? Math.sin(idleTime * 1.4) * 0.04
            : Math.sin(idleTime * 0.9) * 0.02;
        var breath = 1 + Math.sin(idleTime * 2.2) * 0.008;
        try {
          if (vrm.scene) {
            vrm.scene.rotation.y = sway;
            vrm.scene.scale.setScalar(breath);
          }
        } catch (_) {}

        lookSmooth.x += (lookTarget.x - lookSmooth.x) * Math.min(1, dt * 3);
        lookSmooth.y += (lookTarget.y - lookSmooth.y) * Math.min(1, dt * 3);
        if (vrm.lookAt) {
          try {
            if (typeof vrm.lookAt.lookAt === "function" && camera) {
              var THREE2 = getLibs().THREE;
              var target = new THREE2.Vector3(
                lookSmooth.x * 0.6,
                1.35 + lookSmooth.y * 0.4,
                1.2
              );
              vrm.lookAt.lookAt(target);
            }
          } catch (_) {}
        }

        blinkNext -= dt;
        if (blinkT >= 0) {
          blinkT += dt;
          var b = blinkT < 0.06 ? blinkT / 0.06 : blinkT < 0.12 ? 1 : 1 - (blinkT - 0.12) / 0.08;
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

      if (renderer && scene && camera) {
        renderer.render(scene, camera);
      }
    }
    tick();
  }

  function installVrmFromGltf(gltf, label, L) {
    var loaded = gltf.userData.vrm;
    if (!loaded) {
      throw new Error("No VRM data in file (need a .vrm avatar, not plain GLB)");
    }
    try {
      if (L.VRMUtils && typeof L.VRMUtils.rotateVRM0 === "function") {
        L.VRMUtils.rotateVRM0(loaded);
      }
    } catch (_) {}
    try {
      loaded.scene.traverse(function (obj) {
        if (obj.isMesh || obj.isSkinnedMesh) {
          obj.frustumCulled = false;
        }
      });
    } catch (_) {}

    vrm = loaded;
    scene.add(vrm.scene);
    currentState = STATE_IDLE;
    applyStateExpressions(STATE_IDLE);
    applyMouth(0);
    usingFallback = false;
    showFallback(false);
    fitCamera();
    requestAnimationFrame(function () {
      fitCamera();
    });
    return label || "VRM";
  }

  function parseVrmBuffer(arrayBuffer, label) {
    var L = getLibs();
    if (!L) return Promise.reject(new Error("CompanionVrmLibs not loaded"));
    ensureScene();
    destroyVrm();
    showFallback(false);

    if (!arrayBuffer || arrayBuffer.byteLength < 12) {
      return Promise.reject(new Error("VRM buffer empty"));
    }
    // glTF binary magic "glTF"
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
      return new L.VRMLoaderPlugin(parser);
    });

    return new Promise(function (resolve, reject) {
      try {
        loader.parse(
          arrayBuffer,
          "",
          function (gltf) {
            try {
              resolve(installVrmFromGltf(gltf, label, L));
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
   * Yields to the event loop between chunks so the UI can paint progress.
   */
  function loadVrmFromBridge(path) {
    var bridge = hostBridge();
    if (!bridge || typeof bridge.openVrm !== "function") {
      return Promise.reject(new Error("Native VRM bridge missing"));
    }
    if (typeof bridge.readVrmBase64 !== "function") {
      return Promise.reject(new Error("Native VRM read bridge missing"));
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

    function readNext() {
      if (offset >= size) {
        try {
          if (typeof bridge.closeVrm === "function") bridge.closeVrm();
        } catch (_) {}
        setHud("Parsing " + label + "…");
        return parseVrmBuffer(bytes.buffer, label);
      }
      var n = Math.min(BRIDGE_CHUNK, size - offset);
      var b64;
      try {
        b64 = bridge.readVrmBase64(offset, n);
      } catch (e) {
        try {
          if (typeof bridge.closeVrm === "function") bridge.closeVrm();
        } catch (_) {}
        return Promise.reject(
          new Error("readVrmBase64 threw at " + offset + ": " + ((e && e.message) || e))
        );
      }
      if (!b64) {
        try {
          if (typeof bridge.closeVrm === "function") bridge.closeVrm();
        } catch (_) {}
        return Promise.reject(new Error("Empty VRM chunk at offset " + offset));
      }
      var bin;
      try {
        bin = atob(b64);
      } catch (e) {
        try {
          if (typeof bridge.closeVrm === "function") bridge.closeVrm();
        } catch (_) {}
        return Promise.reject(new Error("Base64 decode failed at " + offset));
      }
      for (var i = 0; i < bin.length; i++) {
        bytes[offset + i] = bin.charCodeAt(i);
      }
      offset += bin.length;
      var pct = Math.min(99, Math.floor((offset / size) * 100));
      setHud("Loading " + label + "… " + pct + "%");

      // Yield so WebView can paint + not hit ANR-ish long blocks.
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

      setHud("Loading VRM…");
      var label = await loadVrmFromBridge(bridgePath);
      if (token !== loadToken) {
        // Superseded by a newer loadModel call.
        return false;
      }
      setHud(label + " (VRM)");
      setTimeout(function () {
        if (!usingFallback && token === loadToken) setHud("");
      }, 4000);
      notifyModelLoaded(label);
      return true;
    } catch (e) {
      if (token !== loadToken) return false;
      var msg = (e && e.message) || String(e);
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
    currentState = s;
    setFallbackState(s);
    if (usingFallback || !vrm) return;
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

  window.CompanionStage = {
    loadModel: loadModel,
    setState: setState,
    setMouth: setMouth,
    playMotion: playMotion,
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
    // Host onReady → pushLoadModel with absolute path; do not fetch file:// here.
    notifyReady();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
