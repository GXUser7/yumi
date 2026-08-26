# Hysteria 2 в Xray — что выяснено по исходникам

Проверено на XTLS/Xray-core, тег `v26.7.28` (коммит `5ca6f4b`). Публичная документация по этим
местам отстаёт: поддержка появилась в январе 2026 коммитом «Proxy: Add Hysteria outbound &
transport (version 2, udphop) and Salamander udpmask», и часть полей уже успела переехать.

## Это два объекта, а не один

В sing-box Hysteria2 — один outbound. В Xray он расщеплён надвое, и заполнять нужно оба:

| Что | Где | Поля |
| --- | --- | --- |
| Протокол | `outbounds[].settings` — `infra/conf/hysteria.go:14-16,34-42` | `version` (обязано быть `2`), `address`, `port`, `users[].auth` |
| Транспорт | `outbounds[].streamSettings.hysteriaSettings` — `infra/conf/transport_method.go:761-772` | `version` (снова `2`), `auth`, `udpIdleTimeout`, `masquerade` |

`Build()` отвергает всё, где `version != 2` (`transport_method.go:776-778`), так что первая версия
Hysteria не поддерживается вовсе — что для нас неважно, но объясняет, почему поле вообще есть.

`udpIdleTimeout` принимает `0` (тогда ядро ставит 60) либо значение от 2 до 600; всё остальное
отвергается (`transport_method.go:784-786`).

## Привычные ручки объявлены устаревшими

`congestion`, `up`, `down`, `udphop` в структуре есть, но при их появлении ядро печатает
предупреждение «congestion & up & down & udphop move to finalmask/quicParams»
(`transport_method.go:780-782`) и дальше их не читает. Переносить `upMbps`/`downMbps` из нашей
модели в эти поля бессмысленно: они молча ничего не сделают.

## Salamander — это не `obfs`

Самое опасное несовпадение имён. В ссылке Hysteria2 обфускация записана как
`obfs=salamander&obfs-password=...`, и в sing-box так же. В Xray поля `obfs` нет ни в протоколе, ни
в транспорте: Salamander вынесен в отдельный подключаемый слой `finalmask`, где загрузчик
`udpmaskLoader` знает его под ключом `"salamander"` (`infra/conf/transport_finalmask.go:77-81`,
реализация — `transport/internet/finalmask/salamander/`).

Практический вывод для генератора конфига: `ProxySettings.Hysteria2.obfsType` нельзя перекладывать
в одноимённое поле, его надо разворачивать в конструкцию `finalmask`. Пока не выяснено, как именно
она вкладывается в `streamSettings` — это первое, что нужно дочитать перед реализацией Hysteria2.

## Что ещё не выяснено

- Точная форма `finalmask`/`udpmask` в JSON и куда она крепится.
- Куда переехали пропускные способности (`quicParams`?) и нужны ли они клиенту вообще.
- Где для Hysteria2 живут `sni`/`alpn`/`allowInsecure` — в `tlsSettings` или в настройках протокола.
