# Подача Yumi и Yumi TV в IzzyOnDroid и каталог Obtainium

> Начиная с релиза **v2.3.3** (`base versionCode = 50` → `501` для `armeabi-v7a` и `502` для `arm64-v8a`), релизные APK собираются без `DEPENDENCY_INFO_BLOCK` и полностью готовы к индексации в IzzyOnDroid.

---

## 1. IzzyOnDroid (трекер заявок на Codeberg)

Открывать issue здесь: `https://codeberg.org/IzzyOnDroid/repo/issues` (шаблон **App Inclusion**).
Лучше завести две отдельные заявки (или уточнить в первой, можно ли добавить обе): для `com.mydrop.vpn` и `com.mydrop.vpn.tv`.

### Заявка 1: Yumi (телефон, `com.mydrop.vpn`)

```markdown
- **App Name:** Yumi
- **Package Name:** `com.mydrop.vpn`
- **Source Code:** https://github.com/GXUser7/yumi
- **Releases:** https://github.com/GXUser7/yumi/releases
- **License:** GPL-3.0-or-later
- **Category:** Connectivity / Security
- **APK filter (GitHub Releases):** `yumi-[0-9].*\.apk` (distinct per-ABI `versionCode`: `armeabi-v7a` = `base*10+1`, `arm64-v8a` = `base*10+2`)
- **Fastlane metadata:** https://github.com/GXUser7/yumi/tree/main/fastlane/metadata/android (`en-US`, `ru-RU`, icon, featureGraphic, phoneScreenshots)
- **Non-FOSS / Proprietary blobs:** None (no GMS, no Firebase, no analytics; QR scanning uses CameraX + pure-Java ZXing; `dependenciesInfo` disabled; Xray core is built from `core-xray/` via `gomobile`).

### Notes on sensitive permissions
- `REQUEST_INSTALL_PACKAGES`: Used only for an optional, transparent update check against official GitHub Releases (`github.com/GXUser7/yumi/releases`). Nothing is installed silently — downloads are verified for matching package ID and signing certificate and handed to Android's system package installer only when the user explicitly confirms.
- `ACCESS_FINE_LOCATION` / `ACCESS_BACKGROUND_LOCATION`: Optional and disabled by default; used strictly on-device for local cellular allow-list geo-zones (automatic switch to user's cellular server list). Coordinates never leave the device.
- `QUERY_ALL_PACKAGES`: Used solely to list installed apps (including background apps without launcher icons) for per-app split tunneling.
```

### Заявка 2: Yumi TV (Android TV, `com.mydrop.vpn.tv`)

```markdown
- **App Name:** Yumi TV
- **Package Name:** `com.mydrop.vpn.tv`
- **Source Code:** https://github.com/GXUser7/yumi
- **Releases:** https://github.com/GXUser7/yumi/releases
- **License:** GPL-3.0-or-later
- **Category:** Connectivity / Security
- **APK filter (GitHub Releases):** `yumi-tv-.*\.apk` (distinct per-ABI `versionCode`: `armeabi-v7a` = `base*10+1`, `arm64-v8a` = `base*10+2`)
- **Fastlane metadata:** https://github.com/GXUser7/yumi/tree/main/tv/fastlane/metadata/android (`en-US`, `ru-RU`, icon, featureGraphic, tvScreenshots)
- **Non-FOSS / Proprietary blobs:** None (`dependenciesInfo` disabled; no self-updater / no `REQUEST_INSTALL_PACKAGES` in TV app).
```

### Бейдж IzzyOnDroid (добавить в `README.md` / `README.en.md` после одобрения)

```markdown
[![IzzyOnDroid](https://img.shields.io/endpoint?url=https://apt.izzysoft.de/fdroid/api/v1/shield/com.mydrop.vpn&style=flat-square&labelColor=1E1612&color=F2B8A0)](https://apt.izzysoft.de/fdroid/index/apk/com.mydrop.vpn)
```

---

## 2. Каталог Obtainium (`apps.obtainium.imranr.dev`)

- Прямые кнопки установки через Obtainium в `README.md` и `README.en.md` **работают сразу** (без модерации и ограничений по звёздам).
- Для попадания в общий веб-каталог [`ImranR98/apps.obtainium.imranr.dev`](https://github.com/ImranR98/apps.obtainium.imranr.dev) их правила требуют **не менее 35 звёзд на GitHub** и возраст репозитория **от 4 месяцев**. Как только условия выполнены:
  1. Форкни `ImranR98/apps.obtainium.imranr.dev`.
  2. Скопируй `docs/distribution/obtainium-apps.json` в `public/data/apps/complex/com.mydrop.vpn.json`.
  3. Проверь валидацию локально: `npm install && npm run validate` (при необходимости сверь имя категории в `categories.json`).
  4. Открой Pull Request.
