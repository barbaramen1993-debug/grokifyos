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
    companion-vrm-libs.min.js   # three + three-vrm + three-vrm-animation (esbuild IIFE)
  models/default/
    Seed-san.vrm
    LICENSE
  animations/                   # portable VRMA clips (any VRM humanoid)
    goodbye.vrma, clapping.vrma, thinking.vrma, …
    LICENSE
  README.md
```

## Vendored JavaScript (pinned)

| File | Package / source | Version | License | Notes |
|------|------------------|---------|---------|-------|
| `js/vendor/companion-vrm-libs.min.js` | [three.js](https://github.com/mrdoob/three.js) + [@pixiv/three-vrm](https://github.com/pixiv/three-vrm) + [@pixiv/three-vrm-animation](https://github.com/pixiv/three-vrm) | **three 0.170.0**, **three-vrm 3.3.2**, **three-vrm-animation 3.3.2** | MIT | IIFE → `window.CompanionVrmLibs` |

### Refresh vendors

```bash
WORKDIR=/tmp/companion-vrm-vendor
mkdir -p "$WORKDIR" && cd "$WORKDIR"
npm init -y
npm install three@0.170.0 @pixiv/three-vrm@3.3.2 @pixiv/three-vrm-animation@3.3.2 esbuild
cat > entry.js << 'EOF'
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { VRMLoaderPlugin, VRMUtils, VRMExpressionPresetName } from '@pixiv/three-vrm';
import { VRMAnimationLoaderPlugin, createVRMAnimationClip } from '@pixiv/three-vrm-animation';
window.CompanionVrmLibs = {
  THREE, GLTFLoader, OrbitControls, VRMLoaderPlugin, VRMUtils, VRMExpressionPresetName,
  VRMAnimationLoaderPlugin, createVRMAnimationClip,
};
EOF
npx esbuild entry.js --bundle --format=iife --platform=browser --target=es2020 --minify \
  --outfile=companion-vrm-libs.min.js
cp companion-vrm-libs.min.js \
  /path/to/android/app/src/main/assets/companion/js/vendor/
```

## Bundled VRMA animations

Portable [VRM Animation](https://vrm.dev/en/vrma/) clips — **one file, any VRM 1.0 humanoid**.

| Clip | Gesture aliases | Source |
|------|-----------------|--------|
| `goodbye` | wave, hello, bye | [tk256ailab/vrm-viewer](https://github.com/tk256ailab/vrm-viewer) (MIT) |
| `clapping` | clap | same |
| `thinking` | think | same |
| `jump` | cheer, celebrate | same |
| `relax` | shrug, idle | same |
| `lookaround` | look_around | same |
| `angry` / `sad` / `sleepy` / `surprised` / `blush` | emotion poses | same |
| `test` | sample | [@pixiv/three-vrm-animation](https://github.com/pixiv/three-vrm) (MIT) |

Host materializes clips to app files; stage loads via bridge `openVrm("anim:goodbye")`.
While a VRMA plays, soft-hang IK is suspended so the mixer owns the skeleton.

**Expanding the pack:** drop more `.vrma` under `animations/`, add the id to
`CompanionModelAssets.BUNDLED_VRMA_IDS` and `VRMA_GESTURE_MAP` / `VRMA_CATALOG` in
`companion-stage.js`. Convert Mixamo/Quaternius FBX via [bvh2vrma](https://vrm-c.github.io/bvh2vrma/)
or [fbx2vrma-converter](https://github.com/tk256ailab/fbx2vrma-converter).

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
| `playMotion(name)` | Expression flash + VRMA when name maps to a clip. |
| `playGesture(name, opts?)` | Named move; prefers VRMA (`wave`→goodbye), else scripted IK. |
| `playTemplate(name, opts?)` | Same catalog; used by `body_pose` tools. |
| `playVrma(id, opts?)` | Play bundled `.vrma` by id on the current VRM. |
| `stopVrma()` | Stop mixer + return to soft hang. |
| `listVrma()` | Catalog of bundled clips + gesture map. |
| `setHands(json)` | Place L/R wrist targets (hips-local); arms two-bone IK + gravity. |
| `exportBodyState()` | Live snapshot: joints, VRMA catalog, motion library, hang rest, camera, control schema. |
| `setJointLabel(id, name)` | Custom display name for a bone/controller (persisted via host). Empty clears. |
| `setJointLabels(map)` | Bulk load custom labels. |
| `getJointLabels()` | Current custom label map. |
| `setDebugSkeleton(bool)` | SkeletonHelper wireframe + joint spheres + VR hand/HMD controller markers. |
| `setLook({x,y,direction?,hold_sec?})` | Virtual HMD gaze; `x=-1` left, `x=1` right. |
| `resetBody()` | Stop VRMA, unlock controllers, soft hang rest. |

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
