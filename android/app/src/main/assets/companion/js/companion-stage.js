/**
 * Companion Live2D stage — offline PixiJS + pixi-live2d-display (Cubism 4).
 * Host bridge: window.GrokifyCompanion.{onReady,onModelLoaded,onError,onAvatarTapped}
 * Stage API:   window.CompanionStage.{loadModel,setState,setMouth,playMotion}
 */
(function () {
  "use strict";

  var BUNDLED_MODEL_CANDIDATES = [
    "models/default/Wanko.model3.json",
    "models/default/default.model3.json",
  ];

  var MOUTH_PARAM_CANDIDATES = [
    "ParamMouthOpenY",
    "PARAM_MOUTH_OPEN_Y",
    "ParamMouthOpen",
    "PARAM_MOUTH_OPEN",
  ];

  var STATE_IDLE = "idle";
  var STATE_LISTENING = "listening";
  var STATE_THINKING = "thinking";
  var STATE_SPEAKING = "speaking";

  var app = null;
  var model = null;
  var currentState = STATE_IDLE;
  var mouthValue = 0;
  var mouthParamId = null;
  var usingFallback = false;
  var resizeObserver = null;
  var idleMotionTimer = null;
  var readyNotified = false;

  function hostCall(method) {
    try {
      var bridge = window.GrokifyCompanion;
      if (!bridge || typeof bridge[method] !== "function") return;
      var args = Array.prototype.slice.call(arguments, 1);
      bridge[method].apply(bridge, args);
    } catch (e) {
      // Host may not be ready yet; ignore.
    }
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

  function getLive2DModelClass() {
    if (window.PIXI && PIXI.live2d && PIXI.live2d.Live2DModel) {
      return PIXI.live2d.Live2DModel;
    }
    return null;
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
    // Closed ~0.15 scaleY, open ~1.0
    mouth.style.transform = "scaleY(" + (0.15 + open * 0.85).toFixed(3) + ")";
  }

  function destroyModel() {
    if (idleMotionTimer) {
      clearTimeout(idleMotionTimer);
      idleMotionTimer = null;
    }
    if (model && app) {
      try {
        app.stage.removeChild(model);
      } catch (_) {}
      try {
        if (typeof model.destroy === "function") model.destroy({ children: true });
      } catch (_) {}
    }
    model = null;
    mouthParamId = null;
  }

  function fitModel() {
    if (!model || !app) return;
    var w = app.renderer.width;
    var h = app.renderer.height;
    if (w < 2 || h < 2) return;

    // Prefer fitting height; keep character lower-center like a companion avatar.
    var scale = Math.min(w / model.width, h / model.height) * 0.95;
    if (!isFinite(scale) || scale <= 0) scale = 0.2;
    model.scale.set(scale);
    model.anchor.set(0.5, 0.9);
    model.x = w / 2;
    model.y = h * 0.98;
  }

  function resolveMouthParam(m) {
    if (!m || !m.internalModel || !m.internalModel.coreModel) return null;
    var core = m.internalModel.coreModel;
    var i;
    for (i = 0; i < MOUTH_PARAM_CANDIDATES.length; i++) {
      var id = MOUTH_PARAM_CANDIDATES[i];
      try {
        if (typeof core.getParameterIndex === "function") {
          var idx = core.getParameterIndex(id);
          if (idx != null && idx >= 0) return id;
        }
      } catch (_) {}
    }
    // Scan parameter ids if available
    try {
      var count =
        typeof core.getParameterCount === "function" ? core.getParameterCount() : 0;
      for (i = 0; i < count; i++) {
        var pid =
          typeof core.getParameterId === "function" ? core.getParameterId(i) : null;
        if (!pid) continue;
        var name = String(pid);
        if (/mouth.*open/i.test(name) || /open.*mouth/i.test(name)) {
          return name;
        }
      }
    } catch (_) {}
    return MOUTH_PARAM_CANDIDATES[0];
  }

  function applyMouthToModel(v) {
    if (!model || !model.internalModel || !model.internalModel.coreModel) return;
    var core = model.internalModel.coreModel;
    var id = mouthParamId || resolveMouthParam(model);
    mouthParamId = id;
    if (!id) return;
    var open = Math.max(0, Math.min(1, Number(v) || 0));
    try {
      if (typeof core.setParameterValueById === "function") {
        core.setParameterValueById(id, open);
      } else if (typeof core.setParameterValueByIndex === "function") {
        var idx = core.getParameterIndex(id);
        if (idx >= 0) core.setParameterValueByIndex(idx, open);
      }
    } catch (_) {}
  }

  function pickMotionGroup(preferred) {
    if (!model || !model.internalModel) return null;
    var defs =
      (model.internalModel.motionManager &&
        model.internalModel.motionManager.definitions) ||
      {};
    var keys = Object.keys(defs);
    if (!keys.length) return null;
    var i;
    for (i = 0; i < preferred.length; i++) {
      var p = preferred[i];
      if (defs[p] && defs[p].length) return p;
      // case-insensitive
      for (var j = 0; j < keys.length; j++) {
        if (keys[j].toLowerCase() === p.toLowerCase() && defs[keys[j]].length) {
          return keys[j];
        }
      }
    }
    return keys[0];
  }

  function playGroupMotion(groupName, index) {
    if (!model || typeof model.motion !== "function") return Promise.resolve(false);
    try {
      var p = model.motion(groupName, index == null ? undefined : index);
      if (p && typeof p.then === "function") return p.then(function () { return true; });
      return Promise.resolve(true);
    } catch (e) {
      return Promise.resolve(false);
    }
  }

  function scheduleIdleLoop() {
    if (idleMotionTimer) {
      clearTimeout(idleMotionTimer);
      idleMotionTimer = null;
    }
    if (!model || currentState !== STATE_IDLE) return;
    var group = pickMotionGroup(["Idle", "idle", "Idle1"]);
    if (!group) return;
    playGroupMotion(group).finally(function () {
      if (currentState !== STATE_IDLE || !model) return;
      idleMotionTimer = setTimeout(scheduleIdleLoop, 2500 + Math.random() * 2500);
    });
  }

  function mapStateToMotion(state) {
    switch (state) {
      case STATE_LISTENING:
        return playGroupMotion(pickMotionGroup(["TapBody", "tap_body", "Touch", "Idle"]) || "Idle");
      case STATE_THINKING:
        return playGroupMotion(pickMotionGroup(["Shake", "shake", "Flick", "Idle"]) || "Idle");
      case STATE_SPEAKING:
        return playGroupMotion(pickMotionGroup(["TapBody", "Talk", "Speak", "Idle"]) || "Idle");
      case STATE_IDLE:
      default:
        scheduleIdleLoop();
        return Promise.resolve(true);
    }
  }

  function onCanvasPointer() {
    hostCall("onAvatarTapped");
    if (model && currentState === STATE_IDLE) {
      var group = pickMotionGroup(["TapBody", "tap_body", "Touch", "Idle"]);
      if (group) playGroupMotion(group);
    }
  }

  function ensureApp() {
    if (app) return app;
    var canvas = document.getElementById("live2d-canvas");
    if (!canvas || !window.PIXI) {
      throw new Error("PixiJS or canvas not available");
    }

    // Expose for pixi-live2d-display ticker integration
    window.PIXI = PIXI;

    app = new PIXI.Application({
      view: canvas,
      resizeTo: window,
      backgroundAlpha: 0,
      antialias: true,
      autoDensity: true,
      resolution: Math.min(window.devicePixelRatio || 1, 2),
      powerPreference: "high-performance",
    });

    // Keep transparent stage
    app.renderer.background.alpha = 0;
    if (app.renderer.background) {
      try {
        app.renderer.background.color = 0x000000;
      } catch (_) {}
    }

    canvas.addEventListener("pointerdown", onCanvasPointer, { passive: true });
    window.addEventListener("resize", function () {
      fitModel();
    });

    // Apply mouth after Live2D internal update each frame
    app.ticker.add(function () {
      if (model && !usingFallback) {
        applyMouthToModel(mouthValue);
      }
    });

    return app;
  }

  function findBundledModelUrl() {
    // Prefer known default, else first model3.json via static candidates.
    return BUNDLED_MODEL_CANDIDATES[0];
  }

  /**
   * Probe whether a relative/absolute URL is fetchable (best-effort).
   * Prefer XHR: Android WebView file:///android_asset often blocks fetch().
   */
  function probeUrl(url) {
    return new Promise(function (resolve, reject) {
      try {
        var xhr = new XMLHttpRequest();
        xhr.open("GET", url, true);
        xhr.onload = function () {
          if (xhr.status === 0 || (xhr.status >= 200 && xhr.status < 300)) {
            resolve(url);
          } else {
            reject(new Error("HTTP " + xhr.status));
          }
        };
        xhr.onerror = function () {
          reject(new Error("probe failed: " + url));
        };
        xhr.send();
      } catch (e) {
        reject(e);
      }
    });
  }

  async function resolveBundledModelPath() {
    var i;
    for (i = 0; i < BUNDLED_MODEL_CANDIDATES.length; i++) {
      try {
        await probeUrl(BUNDLED_MODEL_CANDIDATES[i]);
        return BUNDLED_MODEL_CANDIDATES[i];
      } catch (_) {}
    }
    // Default entry even if probe failed — Live2D loader will surface the real error.
    return BUNDLED_MODEL_CANDIDATES[0];
  }

  function normalizeUserPath(path) {
    if (!path) return "";
    var p = String(path).trim();
    // Android may pass absolute filesystem path without file://
    if (/^https?:\/\//i.test(p) || /^file:\/\//i.test(p) || /^content:\/\//i.test(p)) {
      return p;
    }
    if (p.charAt(0) === "/") {
      return "file://" + p;
    }
    return p;
  }

  async function loadLive2DFromUrl(url) {
    var Live2DModel = getLive2DModelClass();
    if (!Live2DModel) {
      throw new Error("pixi-live2d-display Cubism4 not loaded");
    }

    // Ensure Cubism core is present
    if (!window.Live2DCubismCore) {
      throw new Error("live2dcubismcore not loaded");
    }

    ensureApp();
    destroyModel();
    showFallback(false);

    var m = await Live2DModel.from(url, {
      autoInteract: false,
    });

    // Disable built-in lip-sync so host-driven setMouth wins
    try {
      if (m.internalModel) {
        m.internalModel.lipSync = false;
      }
    } catch (_) {}

    model = m;
    mouthParamId = resolveMouthParam(m);
    app.stage.addChild(model);
    fitModel();

    // Interactive hit / tap
    try {
      model.interactive = true;
      model.buttonMode = true;
      model.on("pointertap", onCanvasPointer);
      model.on("hit", function () {
        onCanvasPointer();
      });
    } catch (_) {}

    currentState = STATE_IDLE;
    scheduleIdleLoop();
    usingFallback = false;
    return url;
  }

  function activateFallback(reason) {
    destroyModel();
    showFallback(true);
    setFallbackState(currentState || STATE_IDLE);
    setFallbackMouth(mouthValue);
    usingFallback = true;
    notifyError(reason || "model load failed; using fallback avatar");
  }

  /**
   * @param {'bundled'|'user'|string} source
   * @param {string} [path] user model path / URL
   */
  async function loadModel(source, path) {
    var src = (source || "bundled").toString().toLowerCase();
    try {
      ensureApp();
      var url;
      if (src === "user") {
        url = normalizeUserPath(path);
        if (!url) throw new Error("user model path is empty");
      } else {
        url = await resolveBundledModelPath();
      }

      await loadLive2DFromUrl(url);
      notifyModelLoaded(url);
      return true;
    } catch (e) {
      var msg = (e && e.message) || String(e);
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

    if (usingFallback || !model) return;
    if (idleMotionTimer) {
      clearTimeout(idleMotionTimer);
      idleMotionTimer = null;
    }
    mapStateToMotion(s);
  }

  function setMouth(v) {
    mouthValue = Math.max(0, Math.min(1, Number(v) || 0));
    if (usingFallback) {
      setFallbackMouth(mouthValue);
      return;
    }
    applyMouthToModel(mouthValue);
  }

  function playMotion(name) {
    if (!name) return;
    if (usingFallback || !model) return;
    var n = String(name);
    // Accept "Group" or "Group:index"
    var parts = n.split(":");
    var group = parts[0];
    var index = parts.length > 1 ? parseInt(parts[1], 10) : undefined;
    if (!pickMotionGroup([group])) {
      // Try as raw group name even if not in preferred list
      playGroupMotion(group, isNaN(index) ? undefined : index);
      return;
    }
    playGroupMotion(
      pickMotionGroup([group]) || group,
      isNaN(index) ? undefined : index
    );
  }

  // Public API
  window.CompanionStage = {
    loadModel: loadModel,
    setState: setState,
    setMouth: setMouth,
    playMotion: playMotion,
    /** @internal debug helpers */
    getState: function () {
      return currentState;
    },
    isFallback: function () {
      return usingFallback;
    },
  };

  function boot() {
    try {
      ensureApp();
    } catch (e) {
      showFallback(true);
      notifyError((e && e.message) || String(e));
    }
    notifyReady();
    // Auto-load bundled model
    loadModel("bundled").catch(function (e) {
      activateFallback((e && e.message) || String(e));
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
})();
