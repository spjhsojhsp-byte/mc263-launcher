#!/bin/bash
# Builds libltw.so for arm64-v8a from source into app_pojavlauncher/src/main/jniLibs, so the APK packages it.
#
# libltw.so is the Large Thin Wrapper (LTW, LGPL-3.0, https://github.com/MojoLauncher/LTW). It runs Minecraft's
# OpenGL backend on the device's OpenGL ES 3 driver, the way MojoLauncher does for Minecraft 26.x. The commit and
# the NDK are pinned, so every build of this repository ships the same library.
#
# SDL3 loads its EGL functions from libltw.so by name. scripts/ltw_egl_forward.c adds the ones LTW does not implement,
# and the check at the end fails the build when one of them is not exported.
#
# Needs ANDROID_SDK_ROOT (or ANDROID_HOME), git and cmake. Installs the NDK into the SDK when it is missing.
set -eu

LTW_REPO="https://github.com/MojoLauncher/LTW.git"
LTW_COMMIT="3f1f4df6c80c925ea34067b1d943a84acce0184d"
NDK_VERSION="28.2.13676358"   # the NDK the LTW project builds with
ANDROID_PLATFORM="android-21" # minSdk of the LTW project

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/app_pojavlauncher/src/main/jniLibs/arm64-v8a"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
    echo "ANDROID_SDK_ROOT is not set" >&2
    exit 1
fi

NDK_DIR="$SDK/ndk/$NDK_VERSION"
if [ ! -d "$NDK_DIR" ]; then
    SDKMANAGER="$(ls "$SDK"/cmdline-tools/*/bin/sdkmanager | head -n 1)"
    # Answer the licence prompts, in case the runner has not accepted them yet
    printf 'y\n%.0s' $(seq 1 20) | "$SDKMANAGER" --sdk_root="$SDK" "ndk;$NDK_VERSION" > /dev/null
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone --quiet "$LTW_REPO" "$WORK/ltw-src"
git -C "$WORK/ltw-src" checkout --quiet "$LTW_COMMIT"

LTW_DIR="$WORK/ltw-src/ltw/src/main/tinywrapper"
cp "$ROOT/scripts/ltw_egl_forward.c" "$LTW_DIR/egl_forward.c"
echo 'target_sources(ltw PRIVATE egl_forward.c)' >> "$LTW_DIR/CMakeLists.txt"
# The desktop OpenGL hook in ltw_egl_forward.c replaces this pointer, so it must still be a plain global in egl.c
grep -q '^EGLContext (\*host_eglCreateContext)(' "$LTW_DIR/egl.c" || { echo "LTW egl.c no longer defines host_eglCreateContext, update scripts/ltw_egl_forward.c" >&2; exit 1; }

cmake -S "$WORK/ltw-src/ltw/src/main/tinywrapper" -B "$WORK/build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release
cmake --build "$WORK/build" --parallel

mkdir -p "$OUT_DIR"
cp "$WORK/build/libltw.so" "$OUT_DIR/libltw.so"

# Show what the library is and what it needs at run time, so a bad build is visible in the CI log
READELF="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
"$READELF" -h "$OUT_DIR/libltw.so" | grep -E "Class|Machine" || true
"$READELF" -d "$OUT_DIR/libltw.so" | grep NEEDED || true

# SDL3 finds every EGL name in the forwarder list by dlsym, so the library must export each one of them
EGL_NAMES="$(grep -o 'X(egl[A-Za-z0-9]*)' "$ROOT/scripts/ltw_egl_forward.c" | sed 's/^X(//; s/)$//')"
[ -n "$EGL_NAMES" ] || { echo "No EGL names found in scripts/ltw_egl_forward.c" >&2; exit 1; }
for name in $EGL_NAMES; do
    "$READELF" --dyn-syms "$OUT_DIR/libltw.so" | grep -q " $name\$" || { echo "libltw.so does not export $name" >&2; exit 1; }
done
echo "Built libltw.so from $LTW_REPO at $LTW_COMMIT into $OUT_DIR"
