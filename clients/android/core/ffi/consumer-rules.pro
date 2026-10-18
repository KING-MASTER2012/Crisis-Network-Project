# Rules that every consumer of :core:ffi inherits.

# JNA looks up its native peers and callback interfaces by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**

# The UniFFI bindings are called back from native code through JNA.
-keep class uniffi.** { *; }
