# Vendored AARs

## sherpa-onnx-2.0.0.aar

Offline speech recognition runtime for hold-to-talk voice input, from the
official [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) GitHub
releases (ASL-2.0).

- Source: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.0
- Size: 49236482 bytes
- SHA-256: `f0f8716982d27cb4ce695e3934018b1342d9d3d6fdb6ef1f1658fe8bad50b4fc`

Only the JNI API is used (`libsherpa-onnx-jni.so` + `libonnxruntime.so`);
the C/C++ API libraries are excluded from the APK in `build.gradle.kts`,
and only `arm64-v8a` / `armeabi-v7a` are packaged.
