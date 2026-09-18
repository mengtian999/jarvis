# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# [T-im-merge] Keep Flutter embedding + all Flutter plugin classes.
# Without this, R8 (proguard-android-optimize.txt) renames/removes classes
# that GeneratedPluginRegistrant instantiates reflectively at runtime.
# Seen symptom on first IM launch: shared_preferences channel 'getAll' fails
# with PlatformException(channel-error) because the LegacySharedPreferencesPlugin
# class was stripped, and IM's main() never reaches runApp().
-keep class io.flutter.plugins.** { *; }
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.plugin.common.** { *; }
-keep class io.flutter.view.** { *; }
# Flutter plugins that ship Java classes used through MethodChannel/Pigeon:
-keep class io.flutter.plugins.sharedpreferences.** { *; }
-keep class dev.fluttercommunity.plus.** { *; }
-keep class com.llfbandit.** { *; }
-keep class com.mr.flutter.** { *; }
-keep class com.ryanheise.** { *; }
-keep class com.flungo.** { *; }
-keep class com.fintasys.** { *; }
-keep class one.mixin.** { *; }
-keep class io.material.** { *; }
-keep class com.bugsnag.** { *; }
-dontwarn io.flutter.**
-dontwarn dev.fluttercommunity.**
-dontwarn com.llfbandit.**

# [T-android-vad-jni-keep / GH#250] RealTimeCutVAD's C++ layer calls back into
# VADWrapper BY NAME through JNI (GetMethodID "onVoiceStart" / "onVoiceEnd" /
# "onVoiceDidContinue"). Those three are PRIVATE Java methods with no Java-side
# caller, so R8 sees them as dead and renames them — verified in the shipped
# 1.12 release dex, where they had become b()V / c()V / d()V and the `callback`
# field was removed outright. The native lookup then fails with
#
#   java.lang.NoSuchMethodError: no non-static method
#   "Lio/codeconcept/realtimecutvadlibrary/VADWrapper;.onVoiceStart()V"
#
# thrown from processAudio (a native method), which is exactly the reported
# crash: every release user who tapped the mic got "Voice detection failed"
# before any STT provider was even contacted.
#
# proguard-android-optimize.txt keeps `native` method DECLARATIONS and @Keep,
# but nothing protects an ordinary Java method that only native code invokes.
#
# Kept whole rather than member-scoped: the library is five classes, so the
# size cost is negligible, whereas a narrow rule that missed a member the
# native side also touches (the `vadInstance` handle, for one) would degrade
# into a subtler runtime failure.
#
# NOTE FOR VERIFICATION: debug builds don't minify, so this bug is invisible
# there. Any change here must be checked against an assembleRelease APK.
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }
