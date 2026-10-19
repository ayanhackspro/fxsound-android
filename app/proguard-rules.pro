# Proguard / R8 optimization rules for Android FxSound

# Preserve JNI native method signatures and bridge
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.fxsound.audio.nativebridge.** { *; }
-keep class com.fxsound.audio.model.** { *; }

# Compose and AndroidX
-keepattributes *Annotation*
-dontwarn java.lang.instrument.ClassFileTransformer
