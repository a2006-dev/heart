# Add project specific ProGuard rules here.

# WebView JS 接口
-keepclassmembers class com.xinji.heartbeat.HomeFragment { *; }

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# 保留所有 Activity/Fragment/Service
-keep class com.xinji.heartbeat.** { *; }