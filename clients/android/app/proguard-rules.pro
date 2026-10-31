# App-specific R8 rules. The rules for JNA and the UniFFI bindings travel with :core:ffi
# (core/ffi/consumer-rules.pro).

# ---- Phase A7: release rules ---------------------------------------------------------------------
# JNA and the UniFFI bindings are kept by core/ffi/consumer-rules.pro. The app itself uses no reflection,
# no serialisation library and no native callbacks of its own, so nothing else has to be kept.

# Readable stack traces from the mapping file, without the original file names in the APK.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Debug and verbose logging has no place in a release (needs proguard-android-optimize.txt, which is used).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
