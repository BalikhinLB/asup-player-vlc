# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep stack trace info for crash reports
-keepattributes SourceFile,LineNumberTable

# MPV native library: libplayer.so calls back into these Java classes/methods
# by name via JNI (FindClass / GetStaticMethodID). R8 must not rename them.
-keep class is.xyz.mpv.MPVLib {
    native <methods>;
    public static <methods>;
}
-keep class is.xyz.mpv.MPVLib$* { *; }
-keep class is.xyz.mpv.BaseMPVView { *; }
