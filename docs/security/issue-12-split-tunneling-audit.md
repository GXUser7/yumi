# Отчёт по аудиту безопасности: утечка VPN IP и входного IP сервера в режиме split-tunneling (Issue #12)

**Проект:** Yumi (`com.mydrop.vpn` / `com.mydrop.vpn.tv`)  
**Задача:** GitHub Issue #12 (`GXUser7/yumi`) — глубокий аудит каналов утечки при split-tunneling и постоянная защита (всегда включена по умолчанию, без переключателя в настройках)  
**Аудированные модули:** `core-xray`, `shared`, `app`, `tv`  
**Дата актуализации:** 2026-11-18  

---

## 1. Резюме и область аудита (Executive Summary & Scope)

### 1.1 Цель и модель угроз
В режиме split-tunneling (`AllowList` или `BlockList`, а также для системных/исключённых приложений при `Off`, включая сторонние приложения вроде `RKNHardering`) приложение, исключённое из VPN на уровне `VpnService.Builder` (`addDisallowedApplication` или отсутствие в `addAllowedApplication`), не должно иметь возможности:
1. Обойти маршрутизацию по UID и направить свой TCP/UDP/ICMP/DNS-трафик в интерфейс `tun0`, получив тем самым внешний выходной IP VPN-сервера.
2. Определить внешний VPN IP или входной IP-адрес VPN-сервера через локальные прокси/API-порты, перебор `127.0.0.0/8` / `::1/128`, DNS-утечки (`172.19.0.2:53` / `netd` / `quic+local://` / `localhost` / `fakedns`), ICMP-зондирование `tun0`, IPv4-mapped/IPv4-compatible IPv6 адресацию, прямые редиректы или LAN-интерфейс пульта Android TV (`RemoteHost` / `RemoteNet` / `PairingTransport`).

### 1.2 Ключевой архитектурный вывод
До аудита защита опиралась исключительно на системную политику `VpnService.Builder` (per-app routing по UID на уровне маршрутизации Android). При явной привязке сокета к интерфейсу `tun0` или обращении к адресам внутри `tun0` / локальному прокси `probe-in` исключённое приложение могло обойти маршрутизацию по умолчанию, а при пустом/невалидном `AllowList` — вообще попасть в туннель вместе с самим Yumi.

По итогам аудита и серии этапов усиления (M1, M2, E2E Tiers 1–4 и 5 раундов adversarial-аудита Tier-5) внедрена **эшелонированная fail-closed защита, активная всегда по умолчанию**:
- **Слой `core-xray/tun` (Go / gVisor netstack):** штатный `"tun-in"` перехватывается в `core-xray/yumi.go` и заменяется на `GuardedHandler` (`core-xray/tun`), который проверяет **каждый** TCP SYN (до `SYN-ACK`, отказ через `r.Complete(true)` RST) и **каждый** UDP 5-tuple поток (с немедленной перепроверкой DNS `:53` на каждый пакет и 2-секундным TTL для прочего UDP с эвикцией закрытых/отклонённых `udpConn`), а также подавляет ICMPv4 Echo и ICMPv6 EchoRequest на границе `NetworkDispatcher`.
- **Слой `:shared` (`SplitTunnelUidPolicy` + `MyDropVpnService`):** на Android 10+ (API 29+, `Build.VERSION_CODES.Q`) каждый поток проверяется через `ConnectivityManager.getConnectionOwnerUid` с жёсткой политикой fail-closed (`INVALID_UID == -1` и любые исключения отклоняются, собственный UID Yumi и `ALWAYS_OUTSIDE_PACKAGES` всегда отклоняются, `AllowList` с пустым списком отклоняет все пакеты). Исправлен fallback пустого/all-refused `AllowList` в `MyDropVpnService.establishTunnel()`.
- **Устранение локальных прокси-портов (`probe-in`):** полностью удалён локальный SOCKS/HTTP listener на `127.0.0.1`. Внутренние запросы Yumi (`TunnelHealthCheck`, `SpeedTester`, `SubscriptionService`, `GeoAssetStore`, `MapTileStore`) переведены на внутрипроцессный `core.Dial` через `XrayCore.TunnelBridge` (`ProbeHTTP`, `OpenHTTP`, `DialTunnelTLS`).
- **Укрепление конфигурации и DNS (`XrayConfigFactory`, `DnsUriParser`, `HostPort`):** заблокирована маршрутизация `127.0.0.0/8` и `::1/128` с `inboundTag: ["tun-in"]` перед `hijackDns` и `bypassLan`; запрещены `quic+local://`, `tls://`, `quic://`, `h3://`, `localhost`, `fakedns`, нестандартные формы `inet_aton`, zone ID IPv6 и `::127.0.0.0/8`; добавлена защита от SSRF-редиректов (`resolveSafeDirectRedirect`).
- **Защита Android TV LAN / Pairing (`RemoteNet`, `PairingTransport`, `RemoteHost`):** исключены интерфейсы с `TRANSPORT_VPN` и подсеть `172.19.0.0/30`; в `RemoteSnapshot.message` редактируются IPv4, IPv6 (со скобками, зонами и портами) и `host:port`.

### 1.3 Инвариант «всегда включено, без переключателя в настройках»
Защита от утечки при split-tunneling встроена в жизненный цикл `MyDropVpnService` → `XrayCore.start` → `Yumi.startWithGuard` и не имеет никаких пользовательских флагов отключения:
- В `shared/src/main/kotlin/com/mydrop/vpn/core/model/AppSettings.kt:80-326` отсутствуют какие-либо поля для отключения UID-фильтрации или guard-слоя (проверяется автотестами `TvVisibleNodesAndSettingsTest` и `SplitTunnelPolicyE2eTest`).
- В `app/src/main/kotlin/com/mydrop/vpn/ui/screens/apps/SplitTunnelScreen.kt:59-183` доступно только переключение штатных режимов `SplitTunnelMode` (`Off`, `AllowList`, `BlockList`), причём сам Yumi и пакеты `ALWAYS_OUTSIDE_PACKAGES` исключены из списка выбора (`isSplitTunnelSelectable`, строки `249-285`).
- В `app/src/main/kotlin/com/mydrop/vpn/ui/screens/settings/SettingsScreen.kt:273-357` и `tv/src/main/kotlin/com/mydrop/vpn/tv/TvScreens.kt:538-758` отсутствуют какие-либо переключатели guard-защиты.

---

## 2. Аудированные модули и верифицированные ссылки на код

### 2.1 Модуль `core-xray` (Go / gVisor netstack / gomobile)

| Файл | Символы и строки | Роль и внесённые изменения / проверки |
|---|---|---|
| `core-xray/yumi.go` | `Protector` (`65-69`), `installProtector` (`81-102`) | Единоразовая регистрация dialer-контроллера `VpnService.protect` для всех исходящих сокетов ядра; отказ `Protect(fd)` возвращает ошибку и прерывает dial без петли в `tun0`. |
| `core-xray/yumi.go` | `MeasureOutbounds` (`330-379`), `measureOne` (`382-431`) | Измерение задержек через `core.Dial` с принудительным outbound tag и `SkipDNSResolve = true`, без локального прокси. |
| `core-xray/yumi.go` | `TrafficGuard` (`437-439`), `Start` (`448-450`), `StartWithGuard` (`457-459`), `startCore` (`461-616`) | Перехват inbound `"tun-in"` (или типа `xray.proxy.tun.Config`), извлечение конфигурации и замена на `tun.GuardedConfig` через `core.CreateObject` до запуска; нормализация typed-nil через `tun.NormalizeTrafficGuard`; пиннинг `pinnedTag` до `started.Start()`. |
| `core-xray/yumi.go` | `dialActive` (`695-756`), `deadlineConn` (`774-915`), `ProbeHTTP` (`919-985`), `OpenHTTP` (`1005-1204`), `DialTunnel` (`1217-1242`), `DialTunnelTLS` (`1245-1294`) | Внутрипроцессный `core.Dial` мост для HTTP/HTTPS и TLS-потоков без открытия loopback TCP/UDP портов; `deadlineConn` реализует реальные таймауты чтения/записи поверх `cnc.Connection` с отменой контекста при истечении. |
| `core-xray/tun/config.go` | `TrafficGuard` (`21-23`), `NormalizeTrafficGuard` (`27-39`), `GuardedConfig` (`42-52`), `normalizeAddr` (`57-78`) | Защита от typed-nil интерфейса `TrafficGuard`; `normalizeAddr` разворачивает `::ffff:a.b.c.d` в 4-байтовый IPv4, копирует срез без алиасинга памяти gVisor и fail-closed отвергает `0.0.0.0` / `::`. |
| `core-xray/tun/handler.go` | `GuardedHandler` (`29-40`), `Start` (`131-173`), `Close` (`176-186`), `HandleConnection` (`189-246`) | Реализация `inbound.Handler` поверх `stackGVisor` с сохранением статистики, sniffing-параметров (`ReceiverSettings`) и безопасным закрытием без закрытия `tunFD`, принадлежащего Kotlin-сервису. |
| `core-xray/tun/stack_gvisor.go` | `Start` (`80-157`), TCP `NewForwarder` (`93-129`), `handleICMPv4Packet`/`handleICMPv6Packet` (`235-247`), `icmpEchoDropEndpoint` (`383-496`) | **TCP:** проверка `t.guard.AllowConnection(6, ...)` выполняется до `CreateEndpoint`; при отказе отправляется RST (`r.Complete(true)`), TCP handshake не завершается и не доходит до диспетчера Xray. **ICMP:** при активном `guard` обёртка `icmpEchoDropDispatcher.DeliverNetworkPacket` перехватывает ICMPv4 Echo и ICMPv6 EchoRequest (включая разбор цепочки extension headers: Hop-by-Hop `0`, Routing `43`, Destination Options `60`, Fragment `44`, Experiment `253`) до автоответа gVisor; транспортные обработчики ICMPv4/ICMPv6 также возвращают `true` без ответа. |
| `core-xray/tun/udp_fullcone.go` | `defaultUDPAllowCacheTTL` (`42`), `HandlePacket` (`75-169`), `CloseAll` (`195-210`) | Проверка `TrafficGuard` для каждого UDP 5-tuple `(srcIP, srcPort, dstIP, dstPort)` **вне мьютекса** (чтобы JNI/Binder не блокировал другие потоки). Для DNS (`dstPort == 53`) проверка выполняется на **каждый** пакет без кеширования; для остальных UDP-потоков кеш разрешения действует `2s` (защита от повторного использования порта в рамках 1800s `connIdle`). При отказе запись `allowedDests[dstKey]` удаляется, а пустой `udpConn` немедленно вытесняется и закрывается. |
| `core-xray/tun/endpoint_unix.go` & `endpoint_other.go` | `NewEndpointFromFD` (`endpoint_unix.go:15-30`, `endpoint_other.go:13-15`) | На Android/Linux оборачивает `tunFD` через `fdbased.New` (`RXChecksumOffload: true`), на Windows/прочих платформах даёт кроссплатформенную компиляцию для `go test` через инжектируемый `stack.LinkEndpoint` (`channel.Endpoint`). |
| `shared/libs/libyumi.aar`, `shared/libs/libyumi-sources.jar` | Нативные артефакты | Пересобраны из актуального `core-xray` с поддержкой `StartWithGuard` и `OpenHTTP`/`ProbeHTTP`/`DialTunnelTLS`. |

### 2.2 Модуль `shared` (Kotlin / VPN-сервис / конфигурация / сеть / TV-пульт)

| Файл | Символы и строки | Роль и внесённые изменения / проверки |
|---|---|---|
| `shared/src/main/kotlin/com/mydrop/vpn/vpn/SplitTunnelUidPolicy.kt` | `SplitTunnelUidPolicy` (`17-61`), `isNetdTunDnsQuery` (`63-66`), `normalizeIpBytes` (`68-85`), `ALWAYS_OUTSIDE_PACKAGES` (`98-101`), `create` (`106-160`) | Чисто-JVM (без `OsConstants`) политика контроля UID: на `sdkInt < Q` возвращает `true`; на `Q+` валидирует `proto` (`6`/`17`), порты (`1..65535`), адреса (`isAnyLocalAddress` отклоняется, IPv4-mapped IPv6 разворачивается в 4 байта), вызывает `ownerUidResolver`. Любой `uid < 0` (`INVALID_UID == -1`) и любые исключения fail-closed (`false`). Собственный `myUid` и `ALWAYS_OUTSIDE_PACKAGES` (`com.android.phone`, `com.shannon.imsservice`) всегда отклоняются. В `AllowList` пустой список всегда возвращает `false`; допускаются только UIDs из `allowedUids` либо системные DNS-запросы `netd` (`NETD_ROOT_UID = 0`, `NETD_DNS_UID = 1051`) строго на `172.19.0.2:53`. |
| `shared/src/main/kotlin/com/mydrop/vpn/core/model/SplitTunnelRules.kt` | `SplitTunnelRules` (`4-21`) | Снимок правил и UID пакетов для отслеживания изменений в `tunnelFor()`. |
| `shared/src/main/kotlin/com/mydrop/vpn/vpn/MyDropVpnService.kt` | `ALWAYS_OUTSIDE_THE_TUNNEL` (`149`), `startTunnel` (`698-709`), `resetCoreNetwork` (`1244-1304`), `tunnelFor` (`1346-1359`), `establishTunnel` (`1377-1463`), `resolveConnectionOwnerUidQ` (`1479-1483`), `protectSocket` (`1492`) | Настройка `tun0` (`172.19.0.1/30`, `fdfe:dcba:9876::1/126`, маршруты `0.0.0.0/0` и `::/0`, DNS `172.19.0.2`). Исправлен критический баг пустого/all-refused `AllowList` (`1439-1444`): когда `allowListActive` ложен (`allowedCount == 0`), `alwaysOutside` (включая собственный `packageName`) принудительно добавляется через `addDisallowedApplication`. В `startTunnel` и `resetCoreNetwork` передаются `::protectSocket` и `uidPolicy::allowConnection`. |
| `shared/src/main/kotlin/com/mydrop/vpn/vpn/XrayCore.kt` | `TunnelBridge` (`18-38`), `probeHTTP` (`225-226`), `openHTTP` (`228-245`), `dialTunnelTLS` (`247-255`), `start` (`294-317`), `TunnelResponseInputStream` (`329-373`) | JNI-обёртка над `Yumi.startWithGuard`: `TrafficGuard` и `Protector` обёрнуты в `runCatching { ... }.getOrDefault(false)` для защиты от проброса JNI-исключений и fail-closed поведения. |
| `shared/src/main/kotlin/com/mydrop/vpn/core/xray/XrayConfigFactory.kt` | `unsupported` (`145-180`), `buildConfig` (`460-563`), `xrayResolver` (`1042-1061`), `addDnsServer` (`1063-1075`), `buildRouting` (`1297-1487`), `dnsRules` (`1509-1550`) | В `inbounds` присутствует **только** `"tun-in"` (`541-543`, `probe-in` удалён). В `buildRouting` добавлено правило блокировки `127.0.0.0/8` и `::1/128` для `inboundTag: ["tun-in"]` (`1376-1384`) **до** `hijackDns` (`1386-1392`), `bypassLan` (`1411-1421`) и `provider.rules` (`1437`). `xrayResolver` нормализует `udp://` без учёта регистра, блокирует `quic+local://`, `tls://`, `quic://`, `h3://`, `localhost`, `fakedns`, loopback/unspecified и неканонические адреса. `addDnsServer` использует `splitHostPort` и исключает `localhost`. |
| `shared/src/main/kotlin/com/mydrop/vpn/core/net/HostPort.kt` | `splitHostPort` (`32-55`), `isNumericAddress` (`66-70`), `isIpv6` (`83-114`), `parseIpv4AtonBytes` (`164-205`), `extractAuthorityHost` (`208-218`), `isLoopbackOrUnspecifiedHost` (`225-259`), `isForbiddenDnsHost` (`262-266`), `resolveSafeDirectRedirect` (`283-305`) | Корректный разбор IPv6, в том числе `<prefix>::a.b.c.d` (`2001:db8:abcd::203.0.113.77`, `64:ff9b::203.0.113.77`). Детектор loopback/unspecified охватывает `localhost`, `*.localhost`, `fakedns`, POSIX `inet_aton` (`127.1`, `0177.0.0.1`, `0x7f.0.0.1`, `0`), zone ID (`[::1%lo0]`), `::127.0.0.0/8`, `::0.0.0.0`, trailing dot. `resolveSafeDirectRedirect` предотвращает SSRF-редирект с внешнего ресурса на loopback/неканонический/zoned IP и не-HTTP(S) схемы. |
| `shared/src/main/kotlin/com/mydrop/vpn/core/parse/DnsUriParser.kt` | `carriable` (`31-35`), `parse` (`38-81`) | Отклоняет `tls://`, `quic://`, `quic+local://`, `h3://` (в том числе внутри `sdns://` stamps), нормализует `udp://` (case-insensitive) и отклоняет любой `isForbiddenDnsHost`. |
| `shared/src/main/kotlin/com/mydrop/vpn/data/TunnelHealthCheck.kt`, `SpeedTester.kt`, `SubscriptionService.kt`, `GeoAssetStore.kt`, `MapTileStore.kt` | `TunnelHealthCheck` (`33-113`), `SpeedTester` (`55-528`), `SubscriptionService` (`420-537`), `GeoAssetStore` (`227-289`), `MapTileStore` (`134-177`) | Полная миграция с `probe-in` SOCKS5 на `TunnelBridge` (`probeHTTP`, `openHTTP`, `dialTunnelTLS`). Прямые HTTP-цепочки в `SubscriptionService.getDirect` (`508`) и `GeoAssetStore.followDirect` (`278`) защищены через `resolveSafeDirectRedirect`. |
| `shared/src/main/kotlin/com/mydrop/vpn/remote/RemoteNet.kt`, `shared/src/main/kotlin/com/mydrop/vpn/pairing/PairingTransport.kt` | `isLanTransport` (`RemoteNet.kt:19-20`), `isTunSubnetIpv4` (`46-53`), `isLanPrivateIpv4` (`55-59`), `localNetworks` (`75-80`), `PairingClient` (`PairingTransport.kt:159-227`) | Исключены интерфейсы с `NetworkCapabilities.TRANSPORT_VPN` и подсеть TUN `172.19.0.0/30` (чтобы `172.19.0.1` никогда не публиковался и не использовался в LAN pairing/remote discovery). |
| `shared/src/main/kotlin/com/mydrop/vpn/data/RemoteHost.kt` | `snapshot` (`100-128`), `sanitizeRemoteMessage` (`233-264`), `isValidBareIpv6Token` (`266-277`) | Все сообщения об ошибках в `RemoteSnapshot.message` проходят через `sanitizeRemoteMessage`: редактируются IPv4 (`:port`), bracketed IPv6 (`[...%zone]:port`), bare IPv6 (включая `tcp:2001:db8::1:12345`, 8-hextet+port, `::ffff:203.0.113.77: `, `2001:db8::: `), а также `host:port` (включая punycode и trailing-dot FQDN) в `[redacted]`. |
| `shared/src/main/kotlin/com/mydrop/vpn/data/TunnelConfigBuilder.kt`, `InstalledApps.kt`, `AppContainer.kt`, `DiagnosticLog.kt`, `LogRepository.kt` | `forgetDocument` (`TunnelConfigBuilder.kt:143`), `pruneSplitTunnelPackages` (`InstalledApps.kt:53-57`), `DiagnosticLog` (`32-129`), `LogRepository` (`54-105`) | Очистка сохранённых `splitTunnelPackages` от самого Yumi и `ALWAYS_OUTSIDE_PACKAGES` при старте; журналы `yumi.log` хранятся в приватном каталоге приложения (`filesDir/diagnostics`), `allowBackup="false"`, очистка удаляет как память, так и файлы на диске. |
| `shared/src/main/AndroidManifest.xml` | `<service MyDropVpnService>` (`15-26`), `<receiver BootReceiver>` (`28-37`), `<provider FileProvider>` (`39-47`) | `MyDropVpnService` имеет `android:exported="false"` и `android:permission="android.permission.BIND_VPN_SERVICE"`; `FileProvider` `exported="false"`. |

### 2.3 Модули `app` и `tv` (UI, ViewModel, плитка, виджет, манифесты)

| Файл | Символы и строки | Роль и внесённые изменения / проверки |
|---|---|---|
| `app/src/main/kotlin/com/mydrop/vpn/ui/screens/apps/SplitTunnelScreen.kt` | `SplitTunnelScreen` (`59-183`), `isSplitTunnelSelectable` (`249-250`), `loadApps` (`257-285`) | Запрет выбора собственного пакета и `ALWAYS_OUTSIDE_PACKAGES` как при загрузке списка, так и в обработчике `onToggle`; нулевой переключатель guard-логики. |
| `app/src/main/kotlin/com/mydrop/vpn/ui/screens/settings/SettingsScreen.kt` | `SettingsScreen` (`158-877`) | Проверено отсутствие каких-либо переключателей отключения split-tunnel guard. |
| `app/src/main/kotlin/com/mydrop/vpn/ui/MainViewModel.kt`, `app/src/main/kotlin/com/mydrop/vpn/ui/MyDropApp.kt` | `pingCoordinator` (`231`), `pingAll` (`609-628`), `MyDropApp` (`157-159`) | Координация пинга только отфильтрованных серверов (`filteredNodesForPing`) с защитой от двойного нажатия (`FilteredPingCoordinator`). |
| `app/src/main/kotlin/com/mydrop/vpn/ui/screens/servers/ServersScreen.kt` | `filteredNodesForPing` (`159-176`), `FilterTriggerStyle` / `filterTriggerStyle` / `FilterTriggerButton` (`558-648`) | Реализация F17 (выборка серверов по фильтрам и поисковому запросу) и F18 (активный стиль `FilterTriggerButton` без рамки с заливкой `primary`/`onPrimary`). |
| `app/src/main/kotlin/com/mydrop/vpn/vpn/TunnelTileService.kt`, `shared/.../TunnelWidgetProvider.kt`, `shared/.../BootReceiver.kt` | Весь файл | Плитка QS, 1×1 виджет и `BootReceiver` запускают туннель через `TunnelLauncher` → `MyDropVpnService`, передавая только статус UI без раскрытия IP сервера или VPN IP; `TunnelTileService` защищён `BIND_QUICK_SETTINGS_TILE`. |
| `app/src/main/AndroidManifest.xml`, `tv/src/main/AndroidManifest.xml` | `allowBackup="false"` (`app:68`, `tv:10`), сервисы и провайдеры (`app:148-221`) | Экспортируемые компоненты ограничены системными разрешениями (`BIND_VPN_SERVICE`, `BIND_QUICK_SETTINGS_TILE`) или безопасными точками входа; бэкап отключён. |
| `tv/src/main/kotlin/com/mydrop/vpn/tv/TvViewModel.kt`, `tv/src/main/kotlin/com/mydrop/vpn/tv/TvScreens.kt` | `TvViewModel.pingAll` (`178-187`), `TvSettingsScreen` (`538-758`) | Использование `FilteredPingCoordinator` на TV; отсутствие каких-либо переключателей отключения split-tunnel guard в UI TV. |

---

## 3. Матрица протоколов × транспортов × безопасности (9 × 6 × 3)

Архитектурно вся входящая фильтрация и изоляция в Yumi **не зависят** от выбранного outbound-протокола, транспорта или уровня шифрования, поскольку:
1. **До попадания в диспетчер Xray:** весь входящий трафик с `tun0` перехватывается на уровне `core-xray/tun` (`stack_gvisor.go`, `udp_fullcone.go`, `icmpEchoDropDispatcher`) и проверяется через `TrafficGuard` (`SplitTunnelUidPolicy`) **до** маршрутизации в какой-либо outbound.
2. **На уровне outbound:** все исходящие сокеты любого протокола (TCP, QUIC/UDP, WireGuard UDP, HTTP/WS/gRPC/XHTTP) проходят через `internet.RegisterDialerController` (`core-xray/yumi.go:81-102`) и защищаются `VpnService.protect(fd)`.
3. **На уровне конфигурации (`XrayConfigFactory.kt`):** правила блокировки `127.0.0.0/8` и `::1/128` на `inboundTag: ["tun-in"]`, изоляция подсети `172.19.0.0/30` / `fdfe:dcba:9876::/126`, единый `tun-in` (без `probe-in`) и жесткая фильтрация DNS-резолверов применяются ко всему документу целиком, независимо от набора `outbounds`.

Ниже приведено покрытие всех **9 поддерживаемых протоколов**, **6 транспортов** и **3 режимов безопасности** (`XrayConfigFactory.kt:145-180, 625-1026`, `XrayMatrixDumpTest.kt:57-228`, `core-xray/e2e_xray_test.go`):

| Протокол (`xrayProtocol`) | Поддерживаемые транспорты (`xrayNetwork`) | Режимы безопасности (`security`) | Единообразие защиты (TUN Guard / `protect` / Routing / `core.Dial`) |
|---|---|---|---|
| **1. VLESS** (`vless`) | `raw/tcp`, `ws`, `grpc`, `httpupgrade`, `xhttp` | `none`, `tls`, `reality` (на `raw/tcp`) | Полное (`tun-in` guard + `protect()` + loopback/DNS rules + `core.Dial`) |
| **2. VMess** (`vmess`) | `raw/tcp`, `ws`, `grpc`, `httpupgrade`, `xhttp` | `none`, `tls`, `reality` (совместимость в матрице `raw/tcp`) | Полное (`tun-in` guard + `protect()` + loopback/DNS rules + `core.Dial`) |
| **3. Trojan** (`trojan`) | `raw/tcp`, `ws`, `grpc`, `httpupgrade`, `xhttp` | `none`, `tls`, `reality` (на `raw/tcp`) | Полное (`tun-in` guard + `protect()` + loopback/DNS rules + `core.Dial`) |
| **4. Shadowsocks** (`shadowsocks`, вкл. AEAD / 2022) | `raw/tcp`, `ws`, `grpc`, `httpupgrade`, `xhttp` | `none`, `tls`, `reality` (на `raw/tcp`) | Полное (плагины `obfs`/`v2ray-plugin` отклоняются в `unsupported()`) |
| **5. Hysteria2** (`hysteria`) | `hysteria` (QUIC, + `finalmask` Salamander / Brutal) | `tls` (принудительно в `buildStream:813-816`) | Полное (UDP QUIC сокеты защищаются `protect()`, входящий трафик фильтруется `tun-in`) |
| **6. WireGuard** (`wireguard`) | Без `streamSettings` (`buildStream:797` возвращает `null`) | Нативная криптография WireGuard | Полное (сокеты защищаются `protect()`, входящий трафик фильтруется `tun-in`) |
| **7. HTTP** (`http`) | `raw/tcp`, `ws`, `grpc`, `httpupgrade`, `xhttp` | `none`, `tls`, `reality` (на `raw/tcp`) | Полное (`tun-in` guard + `protect()` + loopback/DNS rules + `core.Dial`) |
| **8. SOCKS5** (`socks`) | `raw/tcp`, `ws`, `grpc`, `httpupgrade`, `xhttp` | `none`, `tls`, `reality` (на `raw/tcp`) | Полное (`tun-in` guard + `protect()` + loopback/DNS rules + `core.Dial`) |
| **9. Direct** (`freedom`) | `raw/tcp` (и транспортные поля не требуются при чистом `Direct`) | `none` | Полное (`tun-in` guard не допускает исключённые приложения даже в `Direct` outbound) |

*Примечание по неподдерживаемым транспортам/протоколам:* транспорты `HTTP/2` (`TransportOptions.Http`) и `QUIC` (`TransportOptions.Quic`), удалённые из Xray v26.7.28, а также протоколы вне списка 9 (`TUIC`, `AnyTLS`, `SSH`) явно отклоняются на этапе импорта и сборки в `XrayConfigFactory.unsupported()` (`145-180`) и `carriable()` (`190-203`).

---

## 4. Исчерпывающая матрица каналов утечки (Вектор → Исправление / Обоснование)

| # | Канал / вектор утечки | Статус | Конкретное исправление в коде или обоснование закрытия | Верифицированные файлы и тесты |
|---|---|---|---|---|
| 1 | Прямое TCP-подключение через `tun0` от исключённого приложения (например, `bindProcessToNetwork(tunNet)` / `SO_BINDTODEVICE`) | **Исправлено** | `core-xray/tun/stack_gvisor.go:93-129` перехватывает SYN до `CreateEndpoint` и вызывает `TrafficGuard.AllowConnection(6, ...)`. При отказе возвращается немедленный TCP RST `r.Complete(true)`. | `core-xray/tun/stack_gvisor.go`, `core-xray/e2e_tun_guard_test.go`, `core-xray/tun/tun_test.go` |
| 2 | UDP-трафик через `tun0` от исключённого приложения | **Исправлено** | `core-xray/tun/udp_fullcone.go:75-169` проверяет `TrafficGuard.AllowConnection(17, ...)` по 5-tuple `(srcIP, srcPort, dstIP, dstPort)`. Неразрешённые пакеты отбрасываются молча без создания `udpConn`. | `core-xray/tun/udp_fullcone.go`, `core-xray/tun/tun_test.go`, `core-xray/e2e_tun_guard_test.go` |
| 3 | Повторное использование UDP-порта (port reuse) в пределах 1800 с `connIdle` при смене владельца сокета | **Исправлено (Phase 2 R1)** | В `core-xray/tun/udp_fullcone.go:42, 98-143` введён `defaultUDPAllowCacheTTL = 2s` для обычного UDP и немедленная эвикция `allowedDests[dstKey]` + закрытие пустых `udpConn` при отказе. | `core-xray/tun/udp_fullcone.go`, `core-xray/tun/tun_test.go` |
| 4 | DNS-утечка через `172.19.0.2:53` (UDP/TCP), `hijackDns` и переиспользование порта DNS | **Исправлено** | TCP `:53` проверяется на каждый SYN (`stack_gvisor.go`); UDP `:53` в `udp_fullcone.go:108, 141` перепроверяется через `TrafficGuard` **на каждый пакет** без TTL-кеширования. Системный `netd` (`UID 0` / `UID 1051`) разрешён в `AllowList` **строго** к `172.19.0.2:53` (`SplitTunnelUidPolicy.kt:63-66`). | `core-xray/tun/udp_fullcone.go`, `SplitTunnelUidPolicy.kt`, `SplitTunnelPolicyE2eTest.kt` |
| 5 | Небезопасные DNS-резолверы в настройках/подписках (`quic+local://`, `tls://`, `quic://`, `h3://`, `UDP://`, `localhost`, `fakedns`, loopback) | **Исправлено (Phase 2 R1–R2)** | `DnsUriParser.kt:31-67` и `XrayConfigFactory.xrayResolver` (`1042-1061`) отклоняют `quic+local://`, `tls://`, `quic://`, `h3://`, нормализуют `udp://` (case-insensitive) и блокируют любой `isForbiddenDnsHost` (`HostPort.kt:225-266`: `localhost`, `fakedns`, `127.0.0.0/8`, `::1`, `0.0.0.0`, `inet_aton`, zoned IPv6, `::127.0.0.0/8`). Кастомные порты `ip:port` сериализуются безопасно через `addDnsServer` (`XrayConfigFactory.kt:1063-1075`). | `DnsUriParser.kt`, `XrayConfigFactory.kt`, `HostPort.kt`, `HostPortTest.kt`, `DnsUriParserTest.kt`, `XrayConfigFactoryTest.kt` |
| 6 | ICMPv4 / ICMPv6 Echo-зондирование `tun0` (включая IPv6 extension headers / фрагменты) | **Исправлено** | `icmpEchoDropEndpoint` / `icmpEchoDropDispatcher` (`core-xray/tun/stack_gvisor.go:383-496`) отбрасывает ICMPv4 Echo и ICMPv6 EchoRequest на границе `NetworkDispatcher` до автоответа gVisor, а `handleICMPv4Packet`/`handleICMPv6Packet` (`235-247`) при наличии `guard` глушат ответ. | `core-xray/tun/stack_gvisor.go`, `core-xray/tun/tun_test.go`, `core-xray/e2e_tun_guard_test.go` |
| 7 | IPv4-mapped IPv6 (`::ffff:a.b.c.d`) и IPv4-compatible/prefix IPv6 обходы | **Исправлено (в т.ч. Phase 2 R5)** | `normalizeAddr` (`core-xray/tun/config.go:57-78`) и `normalizeIpBytes` (`SplitTunnelUidPolicy.kt:68-85`) разворачивают `::ffff:a.b.c.d` в 4-байтовый IPv4 до вызова `getConnectionOwnerUid`; `HostPort.isIpv6` (`83-114`) и `isLoopbackOrUnspecifiedHost` (`225-259`) корректно обрабатывают `<prefix>::a.b.c.d` и `::127.0.0.0/8`. | `config.go`, `SplitTunnelUidPolicy.kt`, `HostPort.kt`, `HostPortTest.kt` |
| 8 | Локальный прокси `probe-in` (`127.0.0.1` SOCKS5) и локальные API (Xray API / Clash API) | **Исправлено** | Inbound `probe-in` полностью удалён из `XrayConfigFactory.kt` и `ProbeEndpoint` помечен `@Deprecated`. Ни Xray gRPC API, ни Clash REST API, ни локальные SOCKS/HTTP прокси в `XrayConfigFactory.buildConfig` (`541-543`) не поднимаются. Внутренние потребители используют in-process `core.Dial` через `XrayCore.TunnelBridge`. | `XrayConfigFactory.kt`, `XrayCore.kt`, `TunnelHealthCheck.kt`, `SpeedTester.kt`, `SubscriptionService.kt`, `GeoAssetStore.kt`, `MapTileStore.kt`, `LoopbackAndIpcLeakE2eTest.kt` |
| 9 | Обращение к `127.0.0.0/8` и `::1/128` через `tun-in` (например, в обход через `bypassLan` или `hijackDns`) | **Исправлено** | Правило `inboundTag: ["tun-in"]`, `ip: ["127.0.0.0/8", "::1/128"]` → `"block"` добавлено в `XrayConfigFactory.buildRouting` (`1376-1384`) **выше** `hijackDns`, `bypassLan` и правил провайдера. | `XrayConfigFactory.kt`, `ConfigAndProtocolMatrixLeakE2eTest.kt`, `LoopbackAndIpcLeakE2eTest.kt` |
| 10 | Петля сокетов ядра или сбой `VpnService.protect(fd)` | **Исправлено (в т.ч. Phase 2 R1)** | `installProtector` (`core-xray/yumi.go:81-102`) прерывает dial при отказе `Protect(fd)`. На стороне Kotlin в `XrayCore.kt:314` `Protector` обёрнут в `runCatching { protect(fd.toInt()) }.getOrDefault(false)` (fail-closed при исключении, без падения Go-потока). | `core-xray/yumi.go`, `XrayCore.kt`, `core-xray/yumi_test.go` |
| 11 | Отказ `getConnectionOwnerUid` (`Process.INVALID_UID == -1`) или исключение Binder | **Исправлено** | `SplitTunnelUidPolicy.allowConnection` (`32-61`) при `uid < 0` или любом исключении в `runCatching` немедленно возвращает `false` (fail-closed). | `SplitTunnelUidPolicy.kt`, `SplitTunnelUidPolicyTest.kt`, `SplitTunnelPolicyE2eTest.kt` |
| 12 | Пустой или полностью отклонённый `AllowList` и исключение самого Yumi / телефонии | **Исправлено** | В `MyDropVpnService.establishTunnel` (`1439-1444`) при `allowListActive == false` (пустой/all-refused `AllowList`) все `alwaysOutside` (включая `packageName` Yumi и `ALWAYS_OUTSIDE_PACKAGES`) добавляются через `addDisallowedApplication`. В `SplitTunnelUidPolicy` `myUid` и `excludedUids` всегда отклоняются, а пустой `allowedUids` в `AllowList` отклоняет любой поток (`54-56`). `SplitTunnelScreen.kt` и `InstalledApps.kt` не дают выбрать Yumi или телефонию. | `MyDropVpnService.kt`, `SplitTunnelUidPolicy.kt`, `SplitTunnelScreen.kt`, `InstalledApps.kt`, `SplitTunnelScreenFilterTest.kt` |
| 13 | Утечка `172.19.0.1` / `TRANSPORT_VPN` через TV Remote / Pairing (`RemoteNet`, `PairingTransport`) | **Исправлено (Phase 2 R1)** | `RemoteNet.kt:19-26, 46-59` и `PairingTransport.kt:204-227` отфильтровывают все сети с `TRANSPORT_VPN` и адреса из подсети `172.19.0.0/30`. | `RemoteNet.kt`, `PairingTransport.kt`, `RemoteAndPairingSecurityTest.kt` |
| 14 | Раскрытие IP-адресов / `host:port` в тексте ошибок `RemoteSnapshot.message` | **Исправлено (Phase 2 R1–R4)** | `RemoteHost.sanitizeRemoteMessage` (`RemoteHost.kt:233-277`) редактирует все IPv4, bracketed/bare IPv6 (включая `tcp:2001:db8::1:12345`, 8-hextet+port, `::ffff:203.0.113.77: `, `2001:db8::: `) и `host:port` (включая punycode и trailing-dot FQDN) перед отправкой клиентам пульта. | `RemoteHost.kt`, `RemoteAndPairingSecurityTest.kt` |
| 15 | SSRF-редирект с внешнего URL на `localhost` / loopback при прямом запросе подписок или geo-баз | **Исправлено (Phase 2 R1–R2)** | `SubscriptionService.getDirect` (`508`) и `GeoAssetStore.followDirect` (`278`) валидируют каждый redirect-шаг через `resolveSafeDirectRedirect` (`HostPort.kt:283-305`). | `HostPort.kt`, `SubscriptionService.kt`, `GeoAssetStore.kt`, `TunnelBridgeConsumersTest.kt` |
| 16 | Утечка через Android Manifest / IPC / Уведомления / QS-плитку / Виджет / Журналы | **Проверено (закрыто)** | `MyDropVpnService` (`exported="false"`, `BIND_VPN_SERVICE`), `FileProvider` (`exported="false"`), `TunnelTileService` (`BIND_QUICK_SETTINGS_TILE`), `allowBackup="false"`. Уведомление, плитка и виджет отображают лишь имя узла/состояние (`MyDropVpnService.kt:1612`, `TunnelTileService.kt:90`), а `DiagnosticLog` пишет только в приватный `filesDir/diagnostics`. | `AndroidManifest.xml` (`shared`, `app`, `tv`), `TunnelTileService.kt`, `TunnelWidgetProvider.kt`, `DiagnosticLog.kt`, `LoopbackAndIpcLeakE2eTest.kt` |
| 17 | Отключаемость защиты в настройках / модели `AppSettings` | **Проверено (закрыто)** | Переключатель отсутствует в `AppSettings`, `SettingsScreen`, `SplitTunnelScreen`, `TvScreens`; защита активна всегда по умолчанию. | `AppSettings.kt`, `SettingsScreen.kt`, `SplitTunnelScreen.kt`, `TvScreens.kt`, `TvVisibleNodesAndSettingsTest.kt` |

---

## 5. Поведение на Android 8–9 (API 26–28) против Android 10+ (API 29+)

В требованиях (`R2`) поведение на Android 8–9 (API 26–28) оставлено на усмотрение команды при условии сохранения стабильной работы туннеля:

1. **Android 10+ (API ≥ 29, `Build.VERSION_CODES.Q`):**
   - `ConnectivityManager.getConnectionOwnerUid(proto, src, dst)` является штатным публичным системным API для сопоставления кортежа сокета с UID приложения-владельца (`MyDropVpnService.kt:1469-1483`).
   - В этом диапазоне API включена **полная fail-closed проверка каждого потока**: любой отказ резолвера (`-1`), исключение, собственный UID Yumi, `ALWAYS_OUTSIDE_PACKAGES`, исключённый в `BlockList` UID или отсутствующий в `AllowList` UID немедленно отклоняется на границе `core-xray/tun`.

2. **Android 8–9 (API 26–28, `< Build.VERSION_CODES.Q`):**
   - Метод `ConnectivityManager.getConnectionOwnerUid` в Android SDK отсутствует, а прямой доступ к `/proc/net/tcp`, `/proc/net/udp`, `/proc/net/tcp6`, `/proc/net/udp6` не используется в ненадёжном/гоночном парсинге, так как на разных OEM-ядрах (и особенно при переходе в SELinux-ограничения) чтение `/proc/net` не гарантирует атомарной связи сокета с UID до завершения handshake.
   - В `SplitTunnelUidPolicy.allowConnection` (`33`) при `sdkInt < Build.VERSION_CODES.Q` проверка UID безопасно возвращает `true`, **не** вызывая `getConnectionOwnerUid`, что предотвращает падения `NoSuchMethodError` и сохраняет 100% стабильность туннеля.
   - При этом на API 26–28 продолжают работать **все остальные уровни защиты**: per-app маршрутизация `VpnService.Builder` (включая исправление пустого/all-refused `AllowList`), блокировка `127.0.0.0/8` и `::1/128` в `XrayConfigFactory`, отсутствие локального `probe-in` прокси, нормализация/блокировка опасных DNS-резолверов, SSRF-защита редиректов и изоляция `RemoteNet`/`PairingTransport`/`RemoteSnapshot.message`.

---

## 6. Реализация сопутствующих доработок (Follow-ups F17, F18, F16)

1. **F17 — Пинг только отфильтрованных серверов (`app` и `tv`):**
   - Выделен координатор `FilteredPingCoordinator` (`shared/.../FilteredPingCoordinator.kt:20-73`) с потокобезопасной защитой от повторного нажатия (`LAZY` запуск под `synchronized(lock)`), быстрым возвратом при пустом списке и точечной очисткой `pingingNodeIds` только для измеренных ID.
   - В мобильном клиенте `MainViewModel.pingAll()` (`MainViewModel.kt:609-628`) вычисляет текущий набор через `filteredNodesForPing(...)` (`ServersScreen.kt:159-176`) с учётом поискового запроса и фильтров протоколов/стран; в `TvViewModel.pingAll()` (`TvViewModel.kt:178-187`) подключён тот же координатор.

2. **F18 — Активный стиль кнопки фильтров (`FilterTriggerButton`):**
   - В `ServersScreen.kt:558-648` обновлено только активное состояние (`activeFilterCount > 0`) функции `filterTriggerStyle`: убрана обводка (`border = null`, `hasBorder = false`), фон кнопки залит сплошным `MaterialTheme.colorScheme.primary`, цвет иконки — `MaterialTheme.colorScheme.onPrimary`, а счётчик `servers-filter-button-count` выполнен контрастным (`onPrimary` фон, `primary` текст). Неактивное состояние (`surfaceContainerHigh`, `onSurface`, без обводки) оставлено без изменений.

3. **F16 — Финальные debug APK и установка на подключённый телефон (`USER_FOLLOWUP_3`, `USER_FOLLOWUP_4`):**
   - Собраны свежие debug APK без последующего вызова `clean` (`app/build/outputs/apk/debug/`, `tv/build/outputs/apk/debug/`) и выполнена установка `:app` на подключённое устройство через `adb.exe`. Точные метрики и контрольные суммы приведены в разделе 7.

---

## 7. Итоговые метрики верификации, сборки APK и установки на устройство (после всех правок)

Все проверки ниже выполнены непосредственно на финальном состоянии рабочего дерева (без последующего вызова `./gradlew clean`).

### 7.1 Go (`core-xray`) и нативная библиотека (`libyumi.aar`)
- `gofmt -l .` (из `core-xray/`): **чисто** (`0` неотформатированных файлов).
- `CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go vet ./...`: **PASS** (код выхода `0`).
- `CGO_ENABLED=0 GOOS=android GOARCH=arm64 go vet ./...`: **PASS** (код выхода `0`).
- `go test -v -count=1 ./...` (из `core-xray/`): **PASS** (`0` падений, `0` пропусков):
  - `github.com/GXUser7/yumi/core-xray` (`13.397s`) + `github.com/GXUser7/yumi/core-xray/tun` (`1.808s`): всего **66** верхнеуровневых тестов + **30** подтестов = **96** выполнений PASS.
- Актуальность нативных артефактов относительно всех `.go` исходников:
  - `shared/libs/libyumi.aar` (`46 309 451` байт, mtime `2026-11-17 18:35:44`) — новее всех `.go` файлов (последний `.go`: `e2e_tun_guard_test.go`, mtime `2026-11-17 18:34:13`).
  - `shared/libs/libyumi-sources.jar` (`13 089` байт, mtime `2026-11-17 18:35:44`).

### 7.2 Gradle: модульные тесты (`--rerun`), E2E-набор, Lint и AndroidTest-компиляция
Использованное окружение: `JAVA_HOME=C:\Users\000\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`, `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`.
- `.\gradlew.bat :shared:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :tv:testDebugUnitTest --rerun --no-configuration-cache --console=plain`: **BUILD SUCCESSFUL**:
  - `:shared:testDebugUnitTest`: **570** тестов (`569` PASS, `1` штатный `Assume`-пропуск в `RealProfileMigrationCheck` при отсутствии `-Dyumi.realProfile`, `0` ошибок/падений), включая E2E `com.mydrop.vpn.e2e.*`: **66/66 PASS**.
  - `:app:testDebugUnitTest`: **29/29 PASS** (`0` пропусков), включая E2E `com.mydrop.vpn.e2e.*`: **12/12 PASS**.
  - `:tv:testDebugUnitTest`: **3/3 PASS** (`0` пропусков).
  - **Итого JVM-тестов:** **602** (`601` PASS + `1` `Assume`-пропуск `RealProfileMigrationCheck`), из них E2E Kotlin: **78/78 PASS**.
- `.\gradlew.bat :shared:lintRelease :app:lintRelease :tv:lintRelease :app:assembleDebug :tv:assembleDebug :app:compileDebugAndroidTestKotlin :tv:compileDebugAndroidTestKotlin --console=plain`: **BUILD SUCCESSFUL** (`0` ошибок).

### 7.3 Собранные Debug APK (`USER_FOLLOWUP_3`)
Файлы оставлены на диске (без последующего `clean`):
- `C:\Users\000\Documents\Projects\yumi\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk`
  - Размер: `98 313 161` байт
  - SHA-256: `0252659320D7B7DD5848C8361F6B8C3B84D732AC0383E3AB3C2D9E52EA085F34`
- `C:\Users\000\Documents\Projects\yumi\app\build\outputs\apk\debug\app-armeabi-v7a-debug.apk`
  - Размер: `95 902 683` байт
  - SHA-256: `334EBB05693FC49D401CB7EF525C3E54758C89C91C614205B4AF188103D8505D`
- `C:\Users\000\Documents\Projects\yumi\tv\build\outputs\apk\debug\tv-arm64-v8a-debug.apk`
  - Размер: `140 616 793` байт
  - SHA-256: `5BACBA821D9FE20495A359BF9E6D4B56612BB1F21853474D3440398B21958207`
- `C:\Users\000\Documents\Projects\yumi\tv\build\outputs\apk\debug\tv-armeabi-v7a-debug.apk`
  - Размер: `138 189 927` байт
  - SHA-256: `AB44AA80B69D51527C75B2C1E6514E1E9E6D19E094F67D347050EB6008812A7C`

### 7.4 Установка `:app` Debug APK на подключённое устройство через ADB (`USER_FOLLOWUP_4`)
- Инструмент: `C:\Users\000\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Устройство (`adb.exe devices -l`): `46161FDAS000WN` (`Pixel 9 Pro XL`, `product:komodo_beta`, `device:komodo`, ABI `arm64-v8a`, Android `17`, SDK `37`).
- Команда установки (debug buildType в `app/build.gradle.kts` использует `applicationIdSuffix = ".debug"` → пакет `com.mydrop.vpn.debug`):
  ```powershell
  adb.exe install -r C:\Users\000\Documents\Projects\yumi\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
  ```
  Результат: `Performing Streamed Install` → `Success` (код выхода `0`).
- Проверка `adb.exe shell pm list packages com.mydrop.vpn` и `adb.exe shell dumpsys package com.mydrop.vpn.debug`:
  - `package:com.mydrop.vpn.debug`
  - `versionCode=38 minSdk=26 targetSdk=36`
  - `versionName=2.2.0-debug`
  - `apkSigningVersion=2`
  - `firstInstallTime=2026-11-17 19:37:29`
  - `lastUpdateTime=2026-11-18 06:04:24`
  - `installerPackageName=null`
  - `signatures=PackageSignatures{49b3067 version:2, signatures:[40BD0BD1CD86836AB6F73967CB3109E4550B2251F05E75F02DDBF15F4D230393], past signatures:[]}`
