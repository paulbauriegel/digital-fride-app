# My Fridge

[![Android](https://github.com/paulbauriegel/digital-fride-app/actions/workflows/android.yml/badge.svg)](https://github.com/paulbauriegel/digital-fride-app/actions/workflows/android.yml)

An offline-first Android app that turns a freely recorded, AR-tracked view of a receipt into one
continuous digital grocery receipt, then lets the user review recognized groceries before adding
them to a local fridge inventory.

## What is implemented

- Jetpack Compose + Material 3 dark/light theme with compact bottom navigation and a rail on wider
  screens.
- Overview, inventory, receipt archive, settings/privacy, scanner, processing, and receipt-review
  screens.
- ARCore camera preview, world-pose tracking, autofocus, optional torch, and continuous CPU-frame
  delivery.
- Live partial-receipt boundary modeling from local contrast and paper geometry; the full receipt
  does not need to fit in the view.
- OpenCV ORB feature tracking and RANSAC homographies between receipt frames.
- Free recording with automatic sharp keyframes. Stationary or slow frames are ignored without a
  warning; the UI warns for blur-producing speed or lost registration.
- Crash-safe scan drafts restore selected source frames and the last visual anchor after process
  death.
- Planar panorama registration against several recent keyframes instead of a fixed downward scan.
- Receipt-mask-only composition with center-weighted hard seams written to `1000 × 2048`
  disk-backed tiles, avoiding a single very large in-memory bitmap.
- One continuous preview plus a seamless tiled review viewer with zoom and OCR-line highlighting.
- Selectable on-device OCR: bundled ML Kit Latin OCR with positioned lines, GLM-OCR through a
  pinned llama.cpp Android runtime and user-imported GGUF decoder/projector files, or an
  experimental GLM-OCR LiteRT 2.1.6 path using imported vision and cached-decoder models.
- Conservative German/European receipt parsing for merchant, date, labeled total, prices,
  quantities, units, and grocery categories.
- App-private JSON metadata and image storage with atomic index/draft writes; Android cloud backup
  and device-transfer extraction are disabled for receipt data.
- Explicit item selection before receipt items enter inventory. Expiry dates remain unknown rather
  than being guessed, and ambiguous OCR rows start unselected with a visible review warning.
- Confirmed receipt deletion and Android share-sheet export of the continuous preview image.

## Project layout

```text
app/src/main/java/com/example/groceriesapp/
├── data/                 Local models and atomic persistence
├── feature/
│   ├── inventory/
│   ├── more/
│   ├── overview/
│   ├── receipts/
│   ├── review/
│   └── scanner/
├── ui/                   Adaptive app shell, formatting, shared components, theme
└── vision/
    ├── capture/          ARCore session, paper boundary model, tracking, keyframe policy
    ├── ocr/              Tiled final OCR with global coordinates
    ├── parsing/          Conservative receipt parser
    └── stitching/        Planar homographies and disk-backed masked composition
```

## Build

The checked-in wrapper uses Gradle 8.13. The project targets Android API 36 and requires JDK 17 or
newer.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The installed app id is `de.bauriegel.digitalfridge`. The Kotlin namespace and package stay
`com.example.groceriesapp`. Builds include only the `arm64-v8a` ABI, because the bundled llama.cpp
runtime exists only for arm64.

### Private build values

`local.properties` (gitignored) can hold values that must not be in the public source:

```properties
biocompany.aesKey=<32 hex characters>
biocompany.aesIv=<32 hex characters>
```

They can also come from Gradle properties of the same name, or from the environment variables
`BIOCOMPANY_AES_KEY` and `BIOCOMPANY_AES_IV`; CI uses the latter. Without them, everything works
except the Bio Company card-points call, which then reports that it is unavailable.

### Release builds

Release and `bridge` builds are signed with the upload key named in `keystore.properties` (gitignored)
at the repo root:

```properties
storeFile=/path/to/upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Without that file the release APK stays unsigned. `-Pgroceries.versionCode=N` and
`-Pgroceries.versionName=X` override the version, which CI does.

## Continuous integration

`.github/workflows/android.yml` runs on every push, every pull request and by hand:

- It runs the unit tests and Android lint. Lint only reports for now and doesn't fail the build.
- It builds a debug APK and a release APK, plus an AAB when signing secrets are set.
- It uploads them as the `digital-fridge-apk-<run>` artifact, kept for 30 days.

Pushing a `v*` tag also creates a GitHub release with the APKs attached, using the tag as
`versionName`. `versionCode` is the workflow run number, so later CI builds can update earlier ones.

Repository secrets:

| Secret | Purpose |
|--------|---------|
| `ANDROID_KEYSTORE_BASE64` | `base64 -i upload.jks` |
| `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | upload key |
| `BIOCOMPANY_AES_KEY`, `BIOCOMPANY_AES_IV` | Bio Company card points (optional) |

## Moving data to a differently signed install

All data lives in app-private storage with no cloud backup, and Android lets `adb run-as` write
into it only on debuggable builds. To move receipts and inventory into an install that a signed
release will later update (for example from the old `com.example.groceriesapp` debug app to
`de.bauriegel.digitalfridge`):

1. **Back up** the source app:
   `adb exec-out run-as <old id> sh -c 'tar cf - files/groceries shared_prefs 2>/dev/null' > backup.tar`.
   Check the archive with `tar tf backup.tar`.
2. **Install the bridge build:** `./gradlew assembleBridge`, then
   `adb install -r app/build/outputs/apk/bridge/app-bridge.apk`. It is debuggable but signed with the
   release key.
3. **Restore before the first launch:**
   `adb exec-in run-as de.bauriegel.digitalfridge tar xf - < backup.tar`.
   - Encrypted preferences are bound to the old app's Keystore keys and can't be moved. Re-enter the
     OpenAI key, the REWE certificate and the store logins.
4. **Update to the signed release** with `adb install -r`. The release must have a versionCode at
   least as high as the bridge build's. Because the signing key is the same, the data stays.

## Models

ML Kit text recognition and the built-in receipt rules work without any download. The other engines
need model files. Download them from **Settings → Text recognition** or **Settings → Item
extraction**. Each model card has a Download button with progress, Cancel and Delete. Downloads:

- run in the background with a notification;
- wait for Wi-Fi unless "Download over Wi-Fi only" is switched off;
- resume after a dropped connection;
- are checked against their sha256 before they are used.

| Model | Size | Downloaded from | Licence |
|-------|------|-----------------|---------|
| GLM-OCR (llama.cpp): `GLM-OCR-Q8_0.gguf` + `mmproj-GLM-OCR-Q8_0.gguf` | 1.43 GB | [ggml-org/GLM-OCR-GGUF](https://huggingface.co/ggml-org/GLM-OCR-GGUF) | MIT |
| GLM-OCR LiteRT (experimental): vision encoder + cached decoder | 3.27 GB | [paulbauriegel/digital-fridge-models](https://huggingface.co/paulbauriegel/digital-fridge-models) (our conversion) | MIT |
| Granite 4.0 350M (LiteRT-LM) | 468 MB | [litert-community/granite-4.0-350m-litert-lm](https://huggingface.co/litert-community/granite-4.0-350m-litert-lm) | Apache-2.0 |

`app/src/main/assets/model_catalog.json` lists every file with a URL pinned to a commit, its sha256
and size, and where the app stores it (`filesDir/models/…`, the paths the model managers check). The
app refreshes the catalog from the model repo at most once a day. Model files that are already on
the device, for example copied there with `adb`, are recognised and not downloaded again.

The app packages an arm64 Vulkan runtime built from llama.cpp release `b10167`:

- GLM-OCR first offloads the decoder and the multimodal projector to Vulkan.
- If the device has no compatible Vulkan backend, or GPU initialisation fails, it retries on the CPU.
- GLM-OCR returns plain text without positioned line boxes, so receipt highlighting is unavailable
  for its output.

The experimental LiteRT engine currently executes the vision signatures and cached decoder on CPU.
An instrumented `litert_gpu` benchmark path uses LiteRT's OpenCL `CompiledModel` backend, but it is
not selected by the app because Pixel 7 Pro parity runs produced materially different vision
embeddings even when only one transformer block was delegated. GPU initialization or delegated-op
counts alone are therefore not treated as successful OCR.


## Publishing models

`tools/models/publish_models.py` (run with `uv run tools/models/publish_models.py`) does the
following:

- Checks the LiteRT conversion in `build/glm-ocr-litert/` against its conversion manifest.
- Uploads it to [paulbauriegel/digital-fridge-models](https://huggingface.co/paulbauriegel/digital-fridge-models)
  in one commit, together with the conversion script, the manifest, a licence and a model card.
- Writes `catalog.json` to that repo and to the app's bundled `model_catalog.json`.

The GGUF pair and Granite are not re-hosted; the catalog pins their upstream commits, and their
sha256 values come from the Hub's metadata. Re-running uploads nothing unless a file changed. To move
to a new upstream revision, bump `GGUF_REVISION` or `GRANITE_REVISION` in the script and rerun.
`--dry-run` only rewrites the bundled catalog. You need a Hugging Face write token
(`hf auth login`).

## Validation boundary

JVM tests cover the continuous keyframe policy, German receipt parsing, bounded preview sizing,
cross-tile OCR planning/deduplication, and OCR cancellation lifetime. Validation also includes
Android lint, a debug APK build, and physical-device ARCore capture checks when a supported phone is
connected.

ARCore capture requires a supported physical Android device with Google Play Services for AR.
Final tuning of receipt boundary confidence, autofocus, glare handling, movement thresholds, and
panorama quality still requires varied real receipts. Processing intentionally fails rather than
using a keyframe whose planar registration or overlap is unsafe.

## Deliberately deferred

Cloud accounts/sync, shared fridges, product databases, barcode lookup, voice entry, automatic
expiry estimates, meal planning, and waste analytics are not enabled. Those features need separate
data, privacy, and food-safety decisions; the receipt image remains the source of truth.
