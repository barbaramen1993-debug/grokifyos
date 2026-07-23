/**
 * Companion touch controller — virtual stick + jump + gesture/pose wheel.
 * Feeds window.CompanionStage.setControlInput every frame while active.
 * Pointer events stay on the HUD so OrbitControls (canvas) is not stolen.
 */
(function () {
  "use strict";

  var stickZone = null;
  var stickBase = null;
  var stickKnob = null;
  var jumpBtn = null;
  var mapBtn = null;
  var gestureBtn = null;
  var gestureWheel = null;
  var gestureRing = null;
  var gestureClose = null;
  var pad = null;

  var stickId = null;
  var stickX = 0;
  var stickY = 0;
  var jumpDown = false;
  var wheelOpen = false;
  var dead = 0.12;
  var maxRadius = 48;
  var raf = 0;

  /**
   * Radial menu entries — ids map to CompanionStage.playTemplate / playGesture
   * (VRMA-first catalog + joint-XYZ fallbacks).
   */
  var GESTURE_ITEMS = [
    { id: "wave", label: "wave", icon: "👋" },
    { id: "clap", label: "clap", icon: "👏" },
    { id: "think", label: "think", icon: "🤔" },
    { id: "cheer", label: "cheer", icon: "🎉" },
    { id: "bow", label: "bow", icon: "🙇" },
    { id: "shrug", label: "relax", icon: "😌" },
    { id: "point", label: "point", icon: "👉" },
    { id: "hands_on_hips", label: "hips", icon: " entr" },
    { id: "crossed_arms", label: "cross", icon: "💪" },
    { id: "lookaround", label: "look", icon: "👀" },
    { id: "surprised", label: "wow", icon: "😮" },
    { id: "sad", label: "sad", icon: "😢" },
    { id: "angry", label: "mad", icon: "😠" },
    { id: "sleepy", label: "sleep", icon: "😴" },
    { id: "blush", label: "shy", icon: "😊" },
    { id: "rest", label: "rest", icon: "⏹" },
  ];

  function stage() {
    return window.CompanionStage || null;
  }

  function pushInput(extra) {
    var S = stage();
    if (!S || typeof S.setControlInput !== "function") return;
    var payload = {
      moveX: stickX,
      moveY: stickY,
      jump: jumpDown,
    };
    if (extra && extra.jumpEdge) payload.jumpEdge = true;
    try {
      S.setControlInput(payload);
    } catch (_) {}
  }

  function setKnob(nx, ny) {
    if (!stickKnob) return;
    stickKnob.style.transform =
      "translate(calc(-50% + " + nx.toFixed(1) + "px), calc(-50% + " + ny.toFixed(1) + "px))";
  }

  function normFromEvent(ev, baseEl) {
    var rect = baseEl.getBoundingClientRect();
    var cx = rect.left + rect.width * 0.5;
    var cy = rect.top + rect.height * 0.5;
    var dx = (ev.clientX || 0) - cx;
    var dy = (ev.clientY || 0) - cy;
    var len = Math.sqrt(dx * dx + dy * dy) || 0;
    var r = Math.min(maxRadius, rect.width * 0.42);
    if (len > r && len > 0) {
      dx = (dx / len) * r;
      dy = (dy / len) * r;
      len = r;
    }
    var nx = r > 0 ? dx / r : 0;
    var ny = r > 0 ? dy / r : 0;
    // Screen: up is negative Y → stick forward = -ny
    var mx = nx;
    var my = -ny;
    var mag = Math.sqrt(mx * mx + my * my);
    if (mag < dead) {
      mx = 0;
      my = 0;
    } else if (mag > 1e-6) {
      // Remap so dead zone doesn't leave a jump in response.
      var adj = (mag - dead) / (1 - dead);
      mx = (mx / mag) * Math.min(1, adj);
      my = (my / mag) * Math.min(1, adj);
    }
    return { dx: dx, dy: dy, mx: mx, my: my };
  }

  function onStickDown(ev) {
    if (wheelOpen) return;
    if (stickId != null) return;
    stickId = ev.pointerId;
    try {
      stickZone.setPointerCapture(ev.pointerId);
    } catch (_) {}
    var n = normFromEvent(ev, stickBase || stickZone);
    stickX = n.mx;
    stickY = n.my;
    setKnob(n.dx, n.dy);
    if (stickBase) stickBase.classList.add("active");
    pushInput();
    if (ev.cancelable) ev.preventDefault();
  }

  function onStickMove(ev) {
    if (stickId !== ev.pointerId) return;
    var n = normFromEvent(ev, stickBase || stickZone);
    stickX = n.mx;
    stickY = n.my;
    setKnob(n.dx, n.dy);
    pushInput();
    if (ev.cancelable) ev.preventDefault();
  }

  function onStickUp(ev) {
    if (stickId !== ev.pointerId) return;
    stickId = null;
    stickX = 0;
    stickY = 0;
    setKnob(0, 0);
    if (stickBase) stickBase.classList.remove("active");
    try {
      stickZone.releasePointerCapture(ev.pointerId);
    } catch (_) {}
    pushInput();
  }

  function onJumpDown(ev) {
    if (wheelOpen) return;
    jumpDown = true;
    if (jumpBtn) jumpBtn.classList.add("active");
    pushInput({ jumpEdge: true });
    try {
      jumpBtn.setPointerCapture(ev.pointerId);
    } catch (_) {}
    if (ev.cancelable) ev.preventDefault();
  }

  function onJumpUp(ev) {
    jumpDown = false;
    if (jumpBtn) jumpBtn.classList.remove("active");
    pushInput();
  }

  function playGestureId(id) {
    var S = stage();
    if (!S) return false;
    // Body poses that only work as scripted activeGesture (not VRMA/look-only).
    var SCRIPT_FIRST = {
      bow: 1,
      lean_in: 1,
      hands_on_hips: 1,
      crossed_arms: 1,
      point: 1,
    };
    try {
      if (id === "rest" || id === "idle" || id === "reset") {
        if (typeof S.stopVrma === "function") S.stopVrma();
        if (typeof S.playGesture === "function") return !!S.playGesture("reset");
        if (typeof S.playTemplate === "function") return !!S.playTemplate("rest");
        return false;
      }
      if (SCRIPT_FIRST[id] && typeof S.playGesture === "function") {
        var g = S.playGesture(id, { intensity: 1, side: "right", forceJoint: true });
        if (g) return true;
      }
      // Prefer template (VRMA-first catalog), fall back to scripted gesture.
      if (typeof S.playTemplate === "function") {
        var ok = S.playTemplate(id, { intensity: 1 });
        if (ok) return true;
      }
      if (typeof S.playGesture === "function") {
        return !!S.playGesture(id, { intensity: 1, side: "right" });
      }
    } catch (e) {
      try {
        console.warn("[CompanionControl] play failed", id, e);
      } catch (_) {}
    }
    return false;
  }

  function layoutWheel() {
    if (!gestureRing) return;
    var slots = gestureRing.querySelectorAll(".gesture-slot");
    var n = slots.length;
    if (!n) return;
    var rect = gestureRing.getBoundingClientRect();
    var radius = Math.min(rect.width, rect.height) * 0.38;
    for (var i = 0; i < n; i++) {
      // Start at top (-90°) and go clockwise.
      var ang = -Math.PI / 2 + (i / n) * Math.PI * 2;
      var x = Math.cos(ang) * radius;
      var y = Math.sin(ang) * radius;
      slots[i].style.setProperty("--gx", x.toFixed(1) + "px");
      slots[i].style.setProperty("--gy", y.toFixed(1) + "px");
    }
  }

  function buildWheel() {
    if (!gestureRing) return;
    gestureRing.innerHTML = "";
    for (var i = 0; i < GESTURE_ITEMS.length; i++) {
      (function (item) {
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "gesture-slot";
        btn.setAttribute("role", "menuitem");
        btn.setAttribute("data-gesture", item.id);
        btn.setAttribute("aria-label", item.label);
        btn.innerHTML =
          '<span class="g-icon">' +
          item.icon +
          '</span><span class="g-label">' +
          item.label +
          "</span>";
        btn.addEventListener("pointerdown", function (ev) {
          btn.classList.add("pressed");
          if (ev.cancelable) ev.preventDefault();
          ev.stopPropagation();
        });
        btn.addEventListener("pointerup", function (ev) {
          btn.classList.remove("pressed");
          ev.stopPropagation();
          playGestureId(item.id);
          closeWheel();
        });
        btn.addEventListener("pointercancel", function () {
          btn.classList.remove("pressed");
        });
        gestureRing.appendChild(btn);
      })(GESTURE_ITEMS[i]);
    }
  }

  function openWheel() {
    if (!gestureWheel) return;
    wheelOpen = true;
    gestureWheel.classList.remove("hidden");
    gestureWheel.setAttribute("aria-hidden", "false");
    if (gestureBtn) {
      gestureBtn.classList.add("active");
      gestureBtn.setAttribute("aria-expanded", "true");
    }
    // Zero stick while choosing a pose.
    stickX = 0;
    stickY = 0;
    setKnob(0, 0);
    pushInput();
    layoutWheel();
  }

  function closeWheel() {
    if (!gestureWheel) return;
    wheelOpen = false;
    gestureWheel.classList.add("hidden");
    gestureWheel.setAttribute("aria-hidden", "true");
    if (gestureBtn) {
      gestureBtn.classList.remove("active");
      gestureBtn.setAttribute("aria-expanded", "false");
    }
  }

  function toggleWheel(ev) {
    if (ev) {
      if (ev.cancelable) ev.preventDefault();
      ev.stopPropagation();
    }
    if (wheelOpen) closeWheel();
    else openWheel();
  }

  function bind() {
    pad = document.getElementById("game-pad");
    stickZone = document.getElementById("stick-zone");
    stickBase = document.getElementById("stick-base");
    stickKnob = document.getElementById("stick-knob");
    jumpBtn = document.getElementById("jump-btn");
    mapBtn = document.getElementById("map-btn");
    gestureBtn = document.getElementById("gesture-btn");
    gestureWheel = document.getElementById("gesture-wheel");
    gestureRing = document.getElementById("gesture-wheel-ring");
    gestureClose = document.getElementById("gesture-wheel-close");
    if (!stickZone || !jumpBtn) return;

    // Block WebView parent / canvas from eating these touches.
    function stop(ev) {
      ev.stopPropagation();
    }
    if (pad) {
      pad.addEventListener("pointerdown", stop);
      pad.addEventListener("pointermove", stop);
      pad.addEventListener("pointerup", stop);
      pad.addEventListener("touchstart", stop, { passive: true });
    }

    stickZone.addEventListener("pointerdown", onStickDown);
    stickZone.addEventListener("pointermove", onStickMove);
    stickZone.addEventListener("pointerup", onStickUp);
    stickZone.addEventListener("pointercancel", onStickUp);

    jumpBtn.addEventListener("pointerdown", onJumpDown);
    jumpBtn.addEventListener("pointerup", onJumpUp);
    jumpBtn.addEventListener("pointercancel", onJumpUp);
    jumpBtn.addEventListener("pointerleave", function (ev) {
      if (jumpDown) onJumpUp(ev);
    });

    if (mapBtn) {
      mapBtn.addEventListener("pointerdown", function (ev) {
        if (ev.cancelable) ev.preventDefault();
        ev.stopPropagation();
        mapBtn.classList.add("active");
      });
      mapBtn.addEventListener("pointerup", function (ev) {
        if (ev.cancelable) ev.preventDefault();
        ev.stopPropagation();
        mapBtn.classList.remove("active");
        var S = stage();
        if (!S) return;
        try {
          // First press enters world (proto); further presses cycle maps.
          if (typeof S.getCurrentMap === "function" && S.getCurrentMap() === "stage") {
            if (typeof S.enterWorld === "function") S.enterWorld("proto_arena");
            else if (typeof S.loadMap === "function") S.loadMap("proto_arena");
          } else if (typeof S.nextMap === "function") {
            S.nextMap();
          } else if (typeof S.loadMap === "function") {
            S.loadMap("proto_arena");
          }
        } catch (_) {}
      });
      mapBtn.addEventListener("pointercancel", function () {
        mapBtn.classList.remove("active");
      });
    }

    if (gestureBtn) {
      gestureBtn.addEventListener("pointerdown", function (ev) {
        // Use pointerup for toggle so it feels like a button press.
        if (ev.cancelable) ev.preventDefault();
        ev.stopPropagation();
      });
      gestureBtn.addEventListener("pointerup", toggleWheel);
    }
    if (gestureClose) {
      gestureClose.addEventListener("pointerup", function (ev) {
        if (ev.cancelable) ev.preventDefault();
        ev.stopPropagation();
        closeWheel();
      });
    }
    if (gestureWheel) {
      // Tap dimmed backdrop to close.
      gestureWheel.addEventListener("pointerup", function (ev) {
        if (ev.target === gestureWheel) closeWheel();
      });
    }

    buildWheel();
    window.addEventListener("resize", function () {
      if (wheelOpen) layoutWheel();
    });

    // Keep input latched while held (in case a frame missed pointermove).
    function pulse() {
      raf = requestAnimationFrame(pulse);
      if (stickId != null || jumpDown) pushInput();
    }
    raf = requestAnimationFrame(pulse);

    if (pad) pad.classList.add("ready");
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", bind);
  } else {
    bind();
  }
})();
