# Companion VRM stage (offline WebView assets)

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
    companion-vrm-libs.min.js   # three@0.170 + @pixiv/three-vrm@3.3.2 (esbuild IIFE)
  models/default/
    Seed-san.vrm
    LICENSE
  README.md
```

## Vendored JavaScript (pinned)

| File | Package / source | Version | License | Notes |
|------|------------------|---------|---------|-------|
| `js/vendor/companion-vrm-libs.min.js` | [three.js](https://github.com/mrdoob/three.js) + [@pixiv/three-vrm](https://github.com/pixiv/three-vrm) | **three 0.170.0**, **three-vrm 3.3.2** | MIT | Browser IIFE exposing `window.CompanionVrmLibs` |

### Refresh vendors

```bash
WORKDIR=/tmp/companion-vrm-vendor
mkdir -p "$WORKDIR" && cd "$WORKDIR"
npm init -y
npm install three@0.170.0 @pixiv/three-vrm@3.3.2 esbuild
cat > entry.js << 'EOF'
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils, VRMExpressionPresetName } from '@pixiv/three-vrm';
window.CompanionVrmLibs = { THREE, GLTFLoader, VRMLoaderPlugin, VRMUtils, VRMExpressionPresetName };
EOF
npx esbuild entry.js --bundle --format=iife --platform=browser --target=es2020 --minify \
  --outfile=companion-vrm-libs.min.js
cp companion-vrm-libs.min.js \
  /path/to/android/app/src/main/assets/companion/js/vendor/
```

## Default model

| Item | Value |
|------|--------|
| Character | **Seed-san** |
| Format | VRM 1.0 (`.vrm`) |
| Source | [vrm-c/vrm-specification samples](https://github.com/vrm-c/vrm-specification/tree/master/samples/Seed-san) |
| Entry | `models/default/Seed-san.vrm` |
| Size | ~11 MB |
| License | [VRM Public License 1.0](https://vrm.dev/en/licenses/1.0/index) — model by VirtualCast, Inc. |

Supports expressions (happy / relaxed / visemes), LookAt, spring bones, MToon + PBR.

### Replace / install another model

1. Export a VRM from [VRoid Studio](https://vroid.com/en/studio) or download from [VRoid Hub](https://hub.vroid.com/en/).
2. In Companion **Settings → Load .vrm file…**, pick the file (copied into app storage).
3. Or place a file at `models/default/Seed-san.vrm` (or `default.vrm`) in assets and rebuild.

Host call:

```js
CompanionStage.loadModel("user", "/absolute/or/file/url/MyAvatar.vrm");
```

## Stage bridge API

`window.CompanionStage`:

| Method | Description |
|--------|-------------|
| `loadModel(source, path?)` | `source`: `"bundled"` \| `"user"`. For `"user"`, `path` is a file URL or absolute path to a `.vrm`. |
| `setState(state)` | `"idle"` \| `"listening"` \| `"thinking"` \| `"speaking"` — mood expressions + look drift. |
| `setMouth(v)` | Mouth open `0..1` → multi-viseme blend (`aa`/`ih`/`ou`/`ee`/`oh` + `jawOpen`) with speech-phase modulation. |
| `playMotion(name)` | Soft expression flash for names containing happy/sad/angry/surprise. |
| `playGesture(name, opts?)` | VR wrist-controller gesture (`wave`, `nod`, `point`, …). |
| `setHands(json)` | Place L/R wrist targets (hips-local); arms two-bone IK + gravity. |
| `exportBodyState()` | Live VR snapshot: joints/chains, bones (id+name), joint_labels, named_joints, hang rest, camera, gesture peaks, control schema. |
| `setJointLabel(id, name)` | Custom display name for a bone/controller (persisted via host). Empty clears. |
| `setJointLabels(map)` | Bulk load custom labels. |
| `getJointLabels()` | Current custom label map. |
| `setDebugSkeleton(bool)` | SkeletonHelper wireframe + joint spheres + VR hand/HMD controller markers. |
| `setLook({x,y,direction?,hold_sec?})` | Virtual HMD gaze; `x=-1` left, `x=1` right. |
| `resetBody()` | Unlock controllers, clear gesture, soft hang rest. |

Host callbacks on `window.GrokifyCompanion` (Android `@JavascriptInterface`):

| Callback | When |
|----------|------|
| `onReady()` | Stage JS booted |
| `onModelLoaded(info)` | VRM loaded (`info` = URL/path string) |
| `onError(message)` | Load/runtime failure (also used when falling back) |
| `onAvatarTapped()` | Canvas / avatar pointer tap |

### Fallback avatar

If WebGL / three-vrm / the model fail, the stage shows a CSS silhouette face (blink + mouth driven by `setMouth`) so the UI remains usable. `onError` is still fired.

## Android integration notes

- WebView with JavaScript enabled; load the asset URL above.
- Inject `GrokifyCompanion` before or right after page load.
- Drive mouth from PCM amplitude on the Kotlin side via `evaluateJavascript("CompanionStage.setMouth(...)")`.
- Transparent WebView background recommended so Compose chrome can show through.
- Offline: `blockNetworkLoads = true` — do not rely on CDNs.

## Self-review checklist

- [x] No runtime CDN fetches for stage code or default model
- [x] Vendors pinned and documented
- [x] Default VRM + LICENSE present
- [x] CompanionStage API + host callbacks
- [x] Fallback when model fails
