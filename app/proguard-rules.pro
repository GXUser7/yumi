# libbox is a gomobile binding: the Go side looks its classes, fields and callbacks up by name
# through JNI, so anything R8 renames or strips becomes an UnsatisfiedLinkError at runtime that
# no amount of Kotlin-side analysis can predict.
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
# Our own implementations of libbox interfaces are instantiated from Go, never from Kotlin.
-keep class com.mydrop.vpn.vpn.** { *; }

# kotlinx.serialization keeps generated serializers reachable through reflection on the
# companion object; without this R8 strips them and every decode throws at runtime.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.mydrop.vpn.**$$serializer { *; }
-keepclassmembers class com.mydrop.vpn.** {
    *** Companion;
}
-keepclasseswithmembers class com.mydrop.vpn.** {
    kotlinx.serialization.KSerializer serializer(...);
}
