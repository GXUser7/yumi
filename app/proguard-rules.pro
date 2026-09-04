# Всё, что нужно коду из :shared — ядро Xray, нативные методы, сериализаторы — приходит из
# самого модуля через consumerProguardFiles (shared/consumer-rules.pro). Здесь только то, что
# принадлежит именно телефону.

# libbox — привязка прежнего ядра sing-box. В сборке его больше нет; правило оставлено до тех
# пор, пока в репозитории лежит shared/libs/libbox.aar, чтобы возврат к нему не начинался с
# отладки UnsatisfiedLinkError.
-keep class io.nekohasekai.libbox.** { *; }
