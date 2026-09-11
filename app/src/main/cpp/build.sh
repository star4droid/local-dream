set -e

if [ -z "$ANDROID_NDK_ROOT" ]; then
  if [ -n "$ANDROID_NDK_HOME" ]; then
    export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
  elif [ -n "$ANDROID_NDK" ]; then
    export ANDROID_NDK_ROOT="$ANDROID_NDK"
  elif [ -n "$ANDROID_SDK_ROOT" ] && [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
    export ANDROID_NDK_ROOT="$(ls -d $ANDROID_SDK_ROOT/ndk/* | tail -n 1)"
  fi
fi

if [ -z "$QNN_SDK_ROOT" ]; then
  QNN_SDK_ROOT="/data/qairt/2.39.0.250926"
fi

if [ ! -d "$QNN_SDK_ROOT" ]; then
  echo "::warning::Qualcomm QAIRT SDK directory '$QNN_SDK_ROOT' not found. Skipping native C++ QNN compilation."
  exit 0
fi

echo "Using ANDROID_NDK_ROOT: $ANDROID_NDK_ROOT"
echo "Using QNN_SDK_ROOT: $QNN_SDK_ROOT"

rm -rf build/android

cmake --preset android-release \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake"

cmake --build --preset android-release

mkdir -p lib
cp -r ./build/android/qnnlibs ../assets/
mkdir -p ../jniLibs/arm64-v8a/
cp ./build/android/bin/arm64-v8a/libstable_diffusion_core.so ../jniLibs/arm64-v8a/
