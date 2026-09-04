# Правила R8 для всего, что лежит в :shared. Раздаются потребителям автоматически через
# consumerProguardFiles, и это здесь главное: телефон и телевизор — два разных приложения с
# двумя разными файлами правил, и когда они собирались порознь, у :tv оказались только две
# строки из нужных. Релизная сборка телевизора при этом собиралась и подписывалась, а падала бы
# уже на устройстве — на первом же чтении settings.json.
#
# Всё, что ниже, нужно коду из :shared, а не какому-то одному из приложений. Значит, и жить
# оно должно рядом с этим кодом.

# ── Ядро Xray ────────────────────────────────────────────────────────────────────────────────
# Привязка сделана gomobile: со стороны Go классы, поля и колбэки ищутся по имени через JNI,
# поэтому всё, что R8 переименует или выбросит, превращается в UnsatisfiedLinkError, который
# никаким анализом котлиновской стороны не предсказывается.
-keep class com.mydrop.vpn.xray.** { *; }
-keep class go.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Наши реализации интерфейсов ядра создаются со стороны Go, а не из Kotlin.
-keep class com.mydrop.vpn.vpn.** { *; }

# ── kotlinx.serialization ────────────────────────────────────────────────────────────────────
# Сгенерированные сериализаторы достаются рефлексией через companion-объект. Без этих правил
# R8 их вырезает, и каждый decode падает в рантайме: настройки, профили, конфигурация ядра,
# протокол сопряжения — всё хранится и передаётся через них.
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
