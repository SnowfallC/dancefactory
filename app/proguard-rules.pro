# Add project specific ProGuard rules here.
-keep class com.formationtool.app.model.** { *; }
-keepclassmembers class com.formationtool.app.model.** { *; }
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
