-keep public class juloo.cdict.* {
  public protected private *;
}
# sherpa-onnx: native code calls back into these classes by name
-keep class com.k2fsa.sherpa.onnx.** {
  public protected private *;
}
