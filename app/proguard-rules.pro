-keep class coil.** { *; }
-dontwarn coil.**

-keep class android.graphics.drawable.AnimatedImageDrawable { *; }
-keep class android.graphics.ImageDecoder { *; }
-dontwarn android.graphics.drawable.AnimatedImageDrawable
-dontwarn android.graphics.ImageDecoder

-keep class androidx.core.app.CoreComponentFactory { *; }
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Dialog

-keep class com.example.honoroverlay.** { *; }
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}
