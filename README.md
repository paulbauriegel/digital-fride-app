# My Fridge

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

## GLM-OCR setup

ML Kit remains the default and works without setup. To use GLM-OCR, open **More → Text
recognition**, import both matching files from
[`ggml-org/GLM-OCR-GGUF`](https://huggingface.co/ggml-org/GLM-OCR-GGUF), then select GLM-OCR:

- `GLM-OCR-Q8_0.gguf`
- `mmproj-GLM-OCR-Q8_0.gguf`

The files are copied to app-private storage and inference stays offline. The official Q8 pair uses
about 1.43 GB of device storage. The app packages an arm64 Vulkan runtime built from llama.cpp
release `b10167`. GLM-OCR first offloads the decoder and multimodal projector to Vulkan, then
automatically retries on CPU if the device has no compatible Vulkan backend or GPU initialization
fails. ML Kit remains available on every supported device. GLM-OCR returns plain text without
positioned line boxes, so receipt highlighting is unavailable for GLM-OCR output.

The experimental LiteRT engine currently executes the vision signatures and cached decoder on CPU.
An instrumented `litert_gpu` benchmark path uses LiteRT's OpenCL `CompiledModel` backend, but it is
not selected by the app because Pixel 7 Pro parity runs produced materially different vision
embeddings even when only one transformer block was delegated. GPU initialization or delegated-op
counts alone are therefore not treated as successful OCR.

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
