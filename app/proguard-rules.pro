# Keep kotlinx.serialization generated classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tumorrecorder.app.**$$serializer { *; }
-keepclassmembers class com.tumorrecorder.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.tumorrecorder.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep JavaMail / activation providers
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn java.awt.**
