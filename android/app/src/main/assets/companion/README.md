# Companion Live2D stage (offline WebView assets)

Loaded by the Android app as:

```text
file:///android_asset/companion/index.html
```

No CDN at runtime. All JS vendors and the default model are local under this tree.

## Layout

```text
companion/
  index.html
  css/stage.css
  js/companion-stage.js
  js/vendor/
    live2dcubismcore.min.js
    pixi.min.js
    cubism4.min.js
  models/default/
    Wanko.model3.json
    Wanko.moc3
    Wanko.physics3.json
    Wanko.cdi3.json
    Wanko.1024/texture_00.png
    motions/*.motion3.json
    LICENSE
  README.md
```

## Vendored JavaScript (pinned)

| File | Package / source | Version | License | Download URL used |
|------|------------------|---------|---------|-------------------|
| `js/vendor/pixi.min.js` | [pixi.js](https://github.com/pixijs/pixijs) browser build | **7.4.2** | MIT | `https://cdn.jsdelivr.net/npm/pixi.js@7.4.2/dist/pixi.min.js` |
| `js/vendor/cubism4.min.js` | [pixi-live2d-display](https://github.com/guansss/pixi-live2d-display) Cubism 4 bundle | **0.5.0-beta** | MIT | `https://cdn.jsdelivr.net/npm/pixi-live2d-display@0.5.0-beta/dist/cubism4.min.js` |
| `js/vendor/live2dcubismcore.min.js` | Live2D Cubism Core for Web (Redistributable Code) | as published on Live2D CDN | [Live2D Proprietary Software License](https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html) | `https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js` |

Notes:

- Pixi **7.x** is the peer dependency of `pixi-live2d-display@0.5.0-beta`.
- Only the Cubism 4 bundle is vendored (no Cubism 2).
- Cubism Core is Redistributable Code under Live2D’s proprietary license; agree to that license before shipping.

### Refresh vendors

```bash
cd android/app/src/main/assets/companion/js/vendor
curl -fsSL -o pixi.min.js \
  "https://cdn.jsdelivr.net/npm/pixi.js@7.4.2/dist/pixi.min.js"
curl -fsSL -o cubism4.min.js \
  "https://cdn.jsdelivr.net/npm/pixi-live2d-display@0.5.0-beta/dist/cubism4.min.js"
curl -fsSL -o live2dcubismcore.min.js \
  "https://cubism.live2d.com/sdk-web/cubismcore/live2dcubismcore.min.js"
```

## Default model

| Item | Value |
|------|--------|
| Character | **Wanko** (Wankoromochi) |
| Format | Cubism 3/4 (`.model3.json` + `.moc3`) |
| Source | [Live2D/CubismWebSamples](https://github.com/Live2D/CubismWebSamples) `Samples/Resources/Wanko/` |
| Entry | `models/default/Wanko.model3.json` |
| Size | ~0.7 MB total (1024 texture) |
| License | Free Material License + Sample Data Terms — see `models/default/LICENSE` |

### Why Wanko (not Hiyori)

Official Live2D Free Material samples (Hiyori, Haru, Mao, …) are **not open-source**. The Free Material License restricts raw **redistribution** of sample Material while allowing **use / distribute in derivative works** for General Users and Small-Scale Enterprises (see Live2D agreements). Wanko is the smallest Cubism sample already published in CubismWebSamples, with Idle / TapBody motions and a lip-sync parameter (`PARAM_MOUTH_OPEN_Y`), so it is a practical bundled default for the Companion stage.

If you replace the pack, keep a single `*.model3.json` under `models/default/` and update `BUNDLED_MODEL_CANDIDATES` in `js/companion-stage.js` if the filename changes.

### Replace / install another model

1. Export a Cubism 4 runtime pack (or copy a sample you are licensed to use).
2. Place files under `models/default/` so the entry JSON is reachable, e.g.:

   ```text
   models/default/MyModel.model3.json
   models/default/MyModel.moc3
   models/default/...textures / motions / expressions...
   ```

3. Point the stage at it by either renaming to match a candidate path, or calling from the host:

   ```js
   CompanionStage.loadModel("user", "/absolute/or/file/url/MyModel.model3.json");
   ```

4. Include any required copyright notices for that model.

### Copyright notice (Live2D samples)

When the bundled Live2D sample model is shown:

> This content uses sample data owned and copyrighted by Live2D Inc. The sample data are utilized in accordance with terms and conditions set by Live2D Inc. This content itself is created at the author’s sole discretion.

## Stage bridge API

`window.CompanionStage`:

| Method | Description |
|--------|-------------|
| `loadModel(source, path?)` | `source`: `"bundled"` \| `"user"`. For `"user"`, `path` is a file URL or absolute path. Returns a Promise\<boolean\>. |
| `setState(state)` | `"idle"` \| `"listening"` \| `"thinking"` \| `"speaking"` — maps to idle / touch / shake-style motions when available. |
| `setMouth(v)` | Mouth open amount `0..1` → `ParamMouthOpenY` / `PARAM_MOUTH_OPEN_Y` (or first matching mouth param). |
| `playMotion(name)` | Play motion group `name`, or `"Group:index"`. |

Host callbacks on `window.GrokifyCompanion` (Android `@JavascriptInterface`):

| Callback | When |
|----------|------|
| `onReady()` | Stage JS booted |
| `onModelLoaded(info)` | Live2D model loaded (`info` = URL/path string) |
| `onError(message)` | Load/runtime failure (also used when falling back) |
| `onAvatarTapped()` | Canvas / avatar pointer tap |

### Fallback avatar

If Live2D libs or the model fail to load, the stage shows a CSS silhouette face (blink + mouth driven by `setMouth`) so the UI remains usable. `onError` is still fired.

## Android integration notes

- Use a WebView with JavaScript enabled; load the asset URL above.
- Inject `GrokifyCompanion` before or right after page load.
- Drive mouth from PCM amplitude on the Kotlin side via `evaluateJavascript("CompanionStage.setMouth(...)")`.
- Transparent WebView background recommended so Compose chrome can show through.

## Self-review checklist

- [x] No runtime CDN fetches for stage code or default model
- [x] Vendors pinned and documented
- [x] Default model + LICENSE present
- [x] CompanionStage API + host callbacks
- [x] Fallback when model fails
