#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_dir="$script_dir/source"
output_dir="${1:-$script_dir/out/android-arm64-vulkan}"

: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"

ndk_version="${LLAMA_ANDROID_NDK_VERSION:-27.3.13750724}"
cmake_version="${LLAMA_ANDROID_CMAKE_VERSION:-3.22.1}"
ndk_dir="$ANDROID_HOME/ndk/$ndk_version"
cmake_bin="$ANDROID_HOME/cmake/$cmake_version/bin/cmake"
ninja_dir="$ANDROID_HOME/cmake/$cmake_version/bin"
toolchain="$ndk_dir/build/cmake/android.toolchain.cmake"
sysroot="$ndk_dir/toolchains/llvm/prebuilt/darwin-x86_64/sysroot"
strip_bin="$ndk_dir/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"

shaderc_prefix="$(brew --prefix shaderc)"
spirv_prefix="$(brew --prefix spirv-headers)"
vulkan_headers_prefix="$(brew --prefix vulkan-headers)"

sdk_include="$output_dir/vulkan-sdk-include"
build_dir="$output_dir/build"
staged_dir="$output_dir/staged"

mkdir -p "$sdk_include" "$build_dir" "$staged_dir"
ln -sfn "$vulkan_headers_prefix/include/vulkan" "$sdk_include/vulkan"
ln -sfn "$vulkan_headers_prefix/include/vk_video" "$sdk_include/vk_video"
ln -sfn "$spirv_prefix/include/spirv" "$sdk_include/spirv"

PATH="$ninja_dir:/opt/homebrew/bin:/usr/bin:/bin" "$cmake_bin" \
    -S "$source_dir" \
    -B "$build_dir" \
    -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$ninja_dir/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_VULKAN=ON \
    -DGGML_BACKEND_DL=ON \
    -DGGML_CPU_ALL_VARIANTS=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_TOOLS=ON \
    -DLLAMA_BUILD_COMMON=ON \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_SUBPROCESS=OFF \
    -DMTMD_VIDEO=OFF \
    -DVulkan_GLSLC_EXECUTABLE="$shaderc_prefix/bin/glslc" \
    -DVulkan_INCLUDE_DIR="$sdk_include" \
    -DVulkan_LIBRARY="$sysroot/usr/lib/aarch64-linux-android/26/libvulkan.so" \
    -DSPIRV-Headers_DIR="$spirv_prefix/share/cmake/SPIRV-Headers"

PATH="$ninja_dir:/opt/homebrew/bin:/usr/bin:/bin" "$cmake_bin" \
    --build "$build_dir" \
    --target llama-mtmd-cli \
    -j "${LLAMA_BUILD_JOBS:-8}"

cp "$build_dir/bin/llama-mtmd-cli" "$staged_dir/libllama_mtmd_cli.so"
cp "$build_dir"/bin/libggml*.so "$staged_dir/"
cp "$build_dir"/bin/libllama*.so "$staged_dir/"
cp "$build_dir/bin/libmtmd.so" "$staged_dir/"
"$strip_bin" --strip-unneeded "$staged_dir"/*.so

printf 'Stripped Android runtime: %s\n' "$staged_dir"
