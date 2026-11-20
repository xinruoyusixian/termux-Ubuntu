# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# ===================================================================
# Termux:X11（lorie）—— 原模块 proguard-rules.pro 的规则，并入 app 后必须保留
# libXlorie.so 的 JNI_OnLoad 会用 FindClass("com/termux/x11/...") 找这些类，
# 一旦被 R8 重命名就会 java_class == null → SIGABRT。
# ===================================================================
-keepattributes SourceFile,LineNumberTable

-keep class com.termux.x11.LorieView {
    native <methods>;
    void resetIme();
}

-keep class com.termux.x11.LorieView$CriticalNativeProbe {
    native <methods>;
}

-keep class com.termux.x11.CmdEntryPoint {
    public static void main(java.lang.String[]);
}

# 整个 x11 包保留类名（JNI + 反射 + 广播接收器都会按名字查找）
-keep class com.termux.x11.** { *; }

# CmdEntryPoint 通过反射访问的隐藏框架类
-dontwarn android.app.ActivityThread
-dontwarn android.app.ContextImpl
-dontwarn android.app.IActivityManager
-dontwarn android.content.IIntentReceiver
-dontwarn android.content.IIntentReceiver$Stub
-dontwarn android.content.IIntentSender
-dontwarn android.content.pm.IPackageManager

-keepclassmembers class com.termux.x11.** {
    void performReceive(android.content.Intent, int, java.lang.String, android.os.Bundle, boolean, boolean, int);
}

-keepclassmembers class * extends android.preference.Preference {
    void onSetInitialValue(boolean, java.lang.Object);
}
