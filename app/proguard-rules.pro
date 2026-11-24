# MPAndroidChart 通过 XML 属性与反射构建, 保留
-keep class com.github.mikephil.charting.** { *; }

# Android 组件由 Manifest 引用, 保留入口
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends androidx.fragment.app.Fragment
