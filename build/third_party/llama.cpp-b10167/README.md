# llama.cpp b10167 Android Vulkan source

This directory preserves the source used for the GroceriesApp GLM-OCR Android runtime.

- Upstream: `https://github.com/ggml-org/llama.cpp`
- Release: `b10167`
- Commit: `ee3d1b54c1e47fce4cea9d63a60a1e45795eaf27`
- Source archive SHA-256: `0fadcfa409a74b9c07a37c5870af368e72653cd66408e3589a64702eafa33a6a`
- Android NDK: `27.3.13750724`
- Android API: `26`
- ABI: `arm64-v8a`

`source/` is the extracted archive with `vulkan-api26.patch` already applied. The patch:

1. Routes three Vulkan 1.1 feature queries through llama.cpp's existing dynamic dispatcher. This
   avoids linking directly to an API 28 Vulkan symbol and keeps Android API 26 compatibility.
2. Restores the pinned release number and commit in builds made from the source archive, which has
   no `.git` metadata.

Run `./build-android-vulkan.sh` to create stripped runtime libraries in
`out/android-arm64-vulkan/staged`. The app packages those files from
`app/src/main/jniLibs/arm64-v8a`.

The large intermediate `out/` directory is intentionally ignored. The pinned archive, extracted
patched source, patch, and build recipe are retained.
