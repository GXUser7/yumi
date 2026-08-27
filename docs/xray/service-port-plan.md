План переноса Android VPN-сервиса с `sing-box` (libbox) на `Xray-core` (обвязка `yumi`).

---

### 1. ЧТО ИСЧЕЗАЕТ

Сервис `MyDropVpnService` больше не реализует интерфейсы `PlatformInterface` и `CommandServerHandler`. Методы удаляются полностью:

*   `openTun(options: TunOptions)` — Xray не строит туннель через обратный вызов, приложение создаёт его самостоятельно до вызова ядра.
*   `usePlatformAutoDetectInterfaceControl()`, `autoDetectInterfaceControl(fd: Int)` — сокеты защищаются через `yumi.Protector.protect(fd)`, платформенный автодетект libbox не используется.
*   `getInterfaces()` — Xray не опрашивает Android о списке сетевых интерфейсов через колбэк.
*   `startDefaultInterfaceMonitor(listener)`, `closeDefaultInterfaceMonitor(listener)` — Xray не требует от приложения подписки на смену интерфейсов через структуры libbox.
*   `findConnectionOwner(...)`, `useProcFS()` — Xray не запрашивает у приложения UID владельца сокета/пакета.
*   `localDNSTransport()`, `readWIFIState()`, `clearDNSCache()` — специфичные DNS/Wi-Fi колбэки sing-box, отсутствующие в архитектуре Xray.
*   `includeAllNetworks()`, `underNetworkExtension()`, `registerMyInterface()` — платформенные флаги libbox, не имеющие аналогов в Xray.
*   `startNeighborMonitor()`, `closeNeighborMonitor()`, `tailscaleHostname()`, `usePlatformBridge()`, `usePlatformShell()`, `connectSSHAgent()`, `triggerNativeCrash()` — вспомогательные возможности sing-box, не поддерживаемые ядром Xray.
*   `createCommandServer()`, `serviceStop()`, `serviceReload()`, `getSystemProxyStatus()`, `setSystemProxyEnabled()` — управление ядром происходит напрямую через методы `Yumi.start()` / `Yumi.stop()`, командный сокет libbox не нужен.
*   `subscribeToStatus()`, `connected()`, `disconnected()`, `writeStatus()`, `writeLogs()`, `clearLogs()`, `initializeClashMode()`, `setDefaultLogLevel()`, `updateClashMode()`, `writeConnectionEvents()`, `writeGroups()`, `writeOutbounds()` — система событий, групп аутбаундов и Clash-режимов libbox в Xray отсутствует.
*   `sendNotification()`, `cancelNotification()`, `coreNotificationId()` — Xray не делегирует приложению показ системных уведомлений.

---

### 2. ЧТО ОСТАЁТСЯ И МЕНЯЕТСЯ

#### Жизненный цикл и привязка к Protector
`MyDropVpnService` теперь наследует только `VpnService()` и реализует интерфейс `yumi.Protector`:

```kotlin
class MyDropVpnService : VpnService(), yumi.Protector {

    override fun protect(fd: Long): Boolean {
        // gomobile передает целые числа как Long, VpnService.protect ожидает Int
        return protect(fd.toInt())
    }
    
    // ...
}
```

#### Построение туннеля через `VpnService.Builder`
Раньше параметры приходили в объекте `TunOptions`. Теперь приложение задаёт их самостоятельно:

```kotlin
private fun establishVpn(): ParcelFileDescriptor {
    val builder = Builder()
    builder.setSession(nodeName.ifEmpty { "MyDrop" })
    
    // 1. MTU: Стандартное значение для gVisor TUN стека
    builder.setMtu(1500)

    // 2. IP-адреса интерфейса:
    // Назначаем локальные адреса туннеля (gVisor обрабатывает их внутри)
    builder.addAddress("172.19.0.1", 30)
    builder.addAddress("fdfe:dcba:9876::1", 126)

    // 3. Маршруты: Перехват всего IPv4 и IPv6 трафика
    builder.addRoute("0.0.0.0", 0)
    builder.addRoute("::", 0)

    // 4. DNS: Локальный адрес туннеля или внешний резолвер
    builder.addDnsServer("1.1.1.1")
    builder.addDnsServer("8.8.8.8")
    // НЕ ХВАТАЕТ ДАННЫХ: Точный IP-адрес DNS зависит от входящего TUN/DNS-inbound в JSON-конфиге Xray. 
    // Нужно посмотреть секцию "inbounds" (тип tun) в генерируемом конфигурационном файле.

    // 5. Раздельное туннелирование (Per-App VPN):
    // Обязательно исключаем само приложение, чтобы предотвратить зацикливание трафика
    runCatching { builder.addDisallowedApplication(packageName) }
    
    // Применяем пользовательские правила из настроек приложения
    includedPackages.forEach { runCatching { builder.addAllowedApplication(it) } }
    excludedPackages.forEach { runCatching { builder.addDisallowedApplication(it) } }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        builder.setMetered(false)
    }

    return builder.establish() ?: throw IllegalStateException("Failed to establish VpnService")
}
```

#### Запуск и остановка туннеля
```kotlin
private fun startTunnel(configJson: String) {
    // Указываем директорию с geoip.dat и geosite.dat перед стартом
    Yumi.setAssetPath(filesDir.absolutePath)

    if (tunDescriptor == null) {
        tunDescriptor = establishVpn()
    }

    val fd = tunDescriptor!!.fd.toLong()
    Yumi.start(configJson, fd, this)
}

private fun stopTunnel() {
    runCatching { Yumi.stop() }
    runCatching { tunDescriptor?.close() }
    tunDescriptor = null
}
```

---

### 3. ВЛАДЕНИЕ ДЕСКРИПТОРОМ TUN

*   **Разница моделей:** `sing-box` вызывал системный `dup()` дескриптора, поэтому приложение закрывало свой `ParcelFileDescriptor` сразу при повторном `openTun`. Ядро `Xray` **не дублирует дескриптор** (`AndroidTun.Close()` возвращает `nil`, `gVisor` при сбросе не закрывает переданные дескрипторы).
*   **Владелец:** Дескриптором владеет **только Android-приложение** (`MyDropVpnService`).
*   **Правило закрытия:** `ParcelFileDescriptor` закрывается **только после** `Yumi.stop()` или при полном уничтожении сервиса.

#### Последовательность при переключении сервера (без смены правил маршрутизации):
1. `Yumi.stop()` — останавливает старый `core.Instance`, отцепляет gVisor-стек; системный `tunFd` остаётся открытым и валидным в ОС.
2. Подготавливается новый `configJson`.
3. `Yumi.start(newConfigJson, tunDescriptor.fd.toLong(), this)` — новое ядро переиспользует тот же дескриптор.
4. **Результат:** Системный VPN-интерфейс Android не пересоздаётся, значок VPN в шторке не мигает.

#### Последовательность при переключении сервера (с изменением правил / Per-App списка):
1. `Yumi.stop()`.
2. `tunDescriptor?.close()`.
3. `tunDescriptor = establishVpn()`.
4. `Yumi.start(newConfigJson, tunDescriptor.fd.toLong(), this)`.

---

### 4. СМЕНА СЕТИ (Wi-Fi ↔ Мобильная сеть)

У Xray отсутствует API сброса сети (`ResetNetwork`). При этом остаются:
*   Кэш DNS в `app/dns/cache.go`.
*   Пул TLS-сессий `globalSessionCache`.
*   Глобальный пул соединений Hysteria QUIC (`clientManager`).

#### Ранжированные варианты обработки:

1.  **Ранг 1 (Рекомендуется): Мягкий перезапуск инстанса Xray на том же дескрипторе TUN**
    *   *Реализация:* При срабатывании сетевого колбэка (`onAvailable` / смена дефолтной сети) выполняем:
        ```kotlin
        if (Yumi.isRunning()) {
            Yumi.stop()
            Yumi.start(currentConfigJson, tunDescriptor!!.fd.toLong(), this)
        }
        ```
    *   *Плюсы:* Мгновенно (< 20 мс), полностью очищает gVisor стек, соединения и DNS-кэш инстанса; не пересоздаёт системный `VpnService` (нет лагов и мигания UI).
    *   *Минусы:* Глобальный `clientManager` Hysteria не инвалидируется без правок Go-кода; сбрасываются все текущие TCP-сессии.

2.  **Ранг 2: Точечный сброс через расширение Go-обвязки**
    *   *Реализация:* Дописать в Go-слой методы принудительной инвалидации `dns.Client` и разрыва сокетов.
    *   *Плюсы:* Нулевое время переключения, сохранение состояния ядра.
    *   *Минусы:* Требует модификации исходного кода Xray-core или использования рефлексии.

3.  **Ранг 3: Полный перезапуск VpnService**
    *   *Реализация:* `Yumi.stop()` $\to$ `tunDescriptor.close()` $\to$ `builder.establish()` $\to$ `Yumi.start()`.
    *   *Плюсы:* 100% чистый сетевой интерфейс на уровне ОС.
    *   *Минусы:* Пауза 1–2 секунды, пересоздание системного туннеля, раздражающая анимация VPN-ключа в строке состояния.

---

### 5. ЖУРНАЛ И СЧЁТЧИКИ

Текущий `yumi` не экспортирует логи и статистику. Для проброса в Kotlin через `gomobile` требуются доработки Go-обвязки.

#### Логирование ядра (Журнал)
В Go реализуем `log.Handler` и экспортируем регистратор слушателя с простыми типами:

```go
package yumi

import (
	"github.com/xtls/xray-core/common/log"
)

type LogListener interface {
	OnLog(level int32, message string)
}

type appLogHandler struct {
	listener LogListener
}

func (h *appLogHandler) Handle(msg log.Message) {
	if h.listener == nil {
		return
	}
	switch m := msg.(type) {
	case *log.GeneralMessage:
		h.listener.OnLog(int32(m.Severity), m.String())
	default:
		h.listener.OnLog(int32(log.Severity_Info), msg.String())
	}
}

func SetLogListener(l LogListener) {
	log.RegisterHandler(&appLogHandler{listener: l})
}
```

*Маппинг уровней важности (`log.Severity` $\to$ Android Log):*
*   `0 (Severity_Unknown)` $\to$ `Log.VERBOSE`
*   `1 (Severity_Error)` $\to$ `Log.ERROR`
*   `2 (Severity_Warning)` $\to$ `Log.WARN`
*   `3 (Severity_Info)` $\to$ `Log.INFO`
*   `4 (Severity_Debug)` $\to$ `Log.DEBUG`

#### Счётчики трафика (Статистика)
В JSON-конфиг добавляются секции:
```json
{
  "stats": {},
  "policy": {
    "system": {
      "statsOutboundUplink": true,
      "statsOutboundDownlink": true
    }
  }
}
```

> [!WARNING]
> **НЕ ХВАТАЕТ ДАННЫХ**: В предоставленных материалах отсутствует конкретный Go API для чтения данных из `app/stats` без gRPC (например, вызов через `instance.GetFeature(stats.ManagerType())`).
> **Что нужно посмотреть:** Исходный код пакета `github.com/xtls/xray-core/app/stats` и реализацию интерфейса `stats.Manager` в Xray, чтобы дописать в `yumi` функцию:
> ```go
> func QueryStats(tag string, direct string) int64
> ```

---

### 6. ПОРЯДОК РАБОТ

Каждый шаг формирует компилируемый билд для промежуточного тестирования:

1.  **Подключение AAR и зачистка зависимостей:**
    *   Добавить скомпилированный `yumi.aar` в `app/libs/` и `build.gradle.kts`.
    *   Удалить зависимость `io.nekohasekai.libbox`.
2.  **Рефакторинг `MyDropVpnService` (Очистка от libbox):**
    *   Удалить наследование `PlatformInterface`, `CommandServerHandler` и все их методы (раздел 1).
    *   Добавить реализацию `yumi.Protector`.
    *   *Проверка:* Проект успешно компилируется без ошибок отсутствующих символов.
3.  **Реализация нового пайплайна туннеля:**
    *   Написать метод `establishVpn()` с ручным заполнением `Builder` (раздел 2).
    *   Внедрить `Yumi.setAssetPath(...)`, `Yumi.start(...)` и `Yumi.stop()`.
    *   Скопировать файлы `geoip.dat` и `geosite.dat` в `filesDir` приложения.
    *   *Проверка:* Успешное поднятие туннеля на реальном устройстве, прохождение трафика (проверка через браузер).
4.  **Реализация логики переключения серверов и смены сети:**
    *   Реализовать мягкий перезапуск ядра по рангу 1 при переключении сервера и в событиях `ConnectivityManager.NetworkCallback`.
    *   *Проверка:* Переключение между Wi-Fi и LTE без падения сервиса и без утечки файловых дескрипторов.
5.  **Расширение Go-обвязки (Логи и статистика) и UI:**
    *   Дописать в `yumi` функции `SetLogListener` и опрос счётчиков `stats`.
    *   Собрать обновленный `.aar` через `gomobile bind`.
    *   Подключить вывод логов в логгер приложения и отображение байтов в UI/уведомлении.
    *   *Проверка:* Корректное отображение логов и расхода трафика в приложении.
