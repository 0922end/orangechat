# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# keep kotlinx serializable classes
-keep @kotlinx.serialization.Serializable class * {*;}

# keep jlatexmath
-keep class org.scilab.forge.jlatexmath.** {*;}

-dontwarn com.google.re2j.**
-dontobfuscate

# Ktor 鍦?Android 涓婂紩鐢ㄤ簡浠?JVM 鍙敤鐨?java.lang.management 绫伙紙IntellijIdeaDebugDetector锛?# Android 涓嶅寘鍚繖浜涚被锛岄渶瑕佸憡鐭?R8 蹇界暐
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# java.beans is not available on Android; Jackson references it only on JVM
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# auth0/jackson: TypeReference subclasses rely on runtime generic signatures.
# R8 strips Signature/InnerClasses/EnclosingMethod by default, and its class
# merging/inlining optimizations can destroy the anonymous class hierarchy that
# TypeReference.<init> depends on via getClass().getGenericSuperclass().
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class com.fasterxml.jackson.** { *; }
-keep class com.auth0.jwt.** { *; }
# JSch uses reflection to load its JCE providers - R8 would strip them without these rules  
-keep class com.jcraft.jsch.** { *; }  
-dontwarn com.jcraft.jsch.**  
