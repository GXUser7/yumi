#!/usr/bin/env python3
"""Build a signed release, publish it to GitHub Releases, announce it in Telegram.

Why the APK goes to GitHub and only a link goes to Telegram: the cloud Bot API caps
`sendDocument` at 50 MB, and Yumi's release APK is around 79 MB — the sing-box native library
dominates it and will not shrink under that line. A link also gives friends a stable URL and a
version history instead of a file buried in chat scrollback.

Credentials live in ~/.mydrop-signing/publish.env, outside the repository:

    GITHUB_TOKEN=github_pat_...        # needs Contents: write, and Administration: write to
                                       # create the repository on the first run
    GITHUB_REPO=owner/yumi             # optional; defaults to <your login>/yumi and is created
                                       # PUBLIC, because assets in a private repo need auth
    TELEGRAM_BOT_TOKEN=123456:AA...    # from @BotFather
    TELEGRAM_CHAT_ID=-1001234567890    # channel id; run --chats to find it

Usage:
    tools/release.py                          # build, publish, announce
    tools/release.py --skip-build             # publish the APKs already in build/outputs
    tools/release.py --dry-run                # build and print, publish nothing
    tools/release.py --chats                  # list chats the bot can see, to get the id
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD_GRADLE = ROOT / "app/build.gradle.kts"
APK_DIR = ROOT / "app/build/outputs/apk/release"

# Same override the Gradle build honours, and for the same reason: the default is a guess about
# the machine rather than about the project, and the credentials do not have to live on C:.
#
#     YUMI_SIGNING_DIR=E:/.mydrop-signing tools/release.py
SIGNING_DIR = Path(os.environ.get("YUMI_SIGNING_DIR") or (Path.home() / ".mydrop-signing"))
ENV_FILE = SIGNING_DIR / "publish.env"
KEYSTORE_PROPS = SIGNING_DIR / "keystore.properties"

# Fallbacks for a release published without `--notes` / `--announce`. Deliberately says nothing
# about what changed: a default that describes one particular version turns into a lie the next
# time someone runs this without arguments, and the announcement goes out to a channel.
RELEASE_NOTES = """## Yumi {version}

Android-клиент VPN на Material 3 Expressive с ядром sing-box.
"""

# Appended to every release body. The APKs are installed from "unknown sources" with nothing
# between the download and the phone, so the checksums are the only way for somebody to tell the
# file they got from a file somebody else got. They were computed and printed to the terminal
# before, which helps nobody downloading from the releases page.
CHECKSUMS_SECTION = """

### sha256

```
{lines}
```
"""

ANNOUNCEMENT = """<b>Yumi {version}</b>

Новая сборка Android-клиента."""


# This script prints arrows and Russian, and on a Windows console the default encoding is cp1251,
# which cannot represent either. Without this the run dies on its own progress message — and it
# does so partway through publishing, which is the worst possible moment for it.
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")


def die(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def load_env() -> dict[str, str]:
    if not ENV_FILE.is_file():
        die(f"{ENV_FILE} not found — see the docstring at the top of this file")
    env: dict[str, str] = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip()
    return env


def read_version() -> tuple[str, int]:
    text = BUILD_GRADLE.read_text(encoding="utf-8")
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    if not name or not code:
        die("could not read versionName/versionCode from app/build.gradle.kts")
    return name.group(1), int(code.group(1))


def resolve_repo(token: str, configured: str | None) -> str:
    """The repository to publish into, created on first use.

    Public on purpose: a release asset in a private repository is behind authentication, and the
    whole point is a link a friend can open.
    """
    if configured:
        repo = configured
    else:
        login = api("https://api.github.com/user", token)["login"]
        repo = f"{login}/yumi"

    try:
        api(f"https://api.github.com/repos/{repo}", token)
        return repo
    except urllib.error.HTTPError as error:
        if error.code != 404:
            die(f"GitHub said {error.code} for {repo}: {error.read().decode()[:300]}")

    print(f"→ creating public repository {repo}")
    try:
        api(
            "https://api.github.com/user/repos",
            token,
            method="POST",
            payload={
                "name": repo.split("/", 1)[1],
                "description": "Yumi — VPN-клиент для Android на Material 3 Expressive, ядро sing-box",
                "private": False,
                "has_wiki": False,
                # A release needs a commit to hang its tag on, and this makes one.
                "auto_init": True,
            },
        )
    except urllib.error.HTTPError as error:
        body = error.read().decode()[:300]
        die(
            f"could not create {repo} ({error.code}: {body}).\n"
            f"Создай репозиторий вручную на https://github.com/new — публичный, имя "
            f"{repo.split('/', 1)[1]}, с галочкой Add a README — и запусти снова."
        )
    return repo


def build() -> list[Path]:
    if not KEYSTORE_PROPS.is_file():
        die(f"{KEYSTORE_PROPS} not found — the release would be unsigned and refuse to install")

    env = dict(os.environ)
    # No default: the path that used to be here existed on exactly one machine, and anywhere else
    # it sent the build at a JDK that was not there. Gradle finds its own if JAVA_HOME is unset;
    # this only says what the build needs when it cannot.
    if "JAVA_HOME" not in env:
        print("note: JAVA_HOME is unset; Gradle will use whatever java is on PATH (needs 21)")
    env.setdefault("YUMI_SIGNING_DIR", str(SIGNING_DIR))
    print("→ building signed release…")
    # `gradlew` is a shell script and Windows cannot execute one: CreateProcess refuses it with
    # "not a Win32 application". The wrapper ships both, so pick by platform rather than by hope.
    wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
    subprocess.run([str(ROOT / wrapper), ":app:assembleRelease"], cwd=ROOT, env=env, check=True)

    apks = sorted(APK_DIR.glob("*-release.apk"))
    if not apks:
        die(f"no APKs in {APK_DIR}; did the build actually produce a signed release?")
    # An unsigned artifact is the one failure mode that only shows up on the friend's phone.
    for apk in apks:
        if "unsigned" in apk.name:
            die(f"{apk.name} is unsigned — check ~/.mydrop-signing/keystore.properties")
    return apks


def existing_apks() -> list[Path]:
    apks = sorted(p for p in APK_DIR.glob("*.apk") if "unsigned" not in p.name)
    if not apks:
        die(f"no APKs in {APK_DIR} — drop --skip-build to build them")
    return apks


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def api(url: str, token: str, method: str = "GET", payload: dict | None = None) -> dict:
    data = json.dumps(payload).encode() if payload is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data:
        request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read() or b"{}")


def ensure_release(repo: str, token: str, tag: str, notes: str) -> dict:
    try:
        existing = api(f"https://api.github.com/repos/{repo}/releases/tags/{tag}", token)
        print(f"→ reusing existing release {tag}")
        return existing
    except urllib.error.HTTPError as error:
        if error.code != 404:
            die(f"GitHub said {error.code}: {error.read().decode()[:300]}")

    print(f"→ creating release {tag}")
    return api(
        f"https://api.github.com/repos/{repo}/releases",
        token,
        method="POST",
        payload={"tag_name": tag, "name": tag, "body": notes, "draft": False, "prerelease": False},
    )


def upload_asset(release: dict, token: str, apk: Path) -> str:
    # Replace an asset of the same name, so re-running after a fix does not fail on a conflict.
    for asset in release.get("assets", []):
        if asset["name"] == apk.name:
            print(f"→ replacing existing asset {apk.name}")
            api(asset["url"], token, method="DELETE")

    upload_url = release["upload_url"].split("{")[0]
    url = f"{upload_url}?{urllib.parse.urlencode({'name': apk.name})}"
    request = urllib.request.Request(url, data=apk.read_bytes(), method="POST")
    request.add_header("Authorization", f"Bearer {token}")
    request.add_header("Content-Type", "application/vnd.android.package-archive")
    print(f"→ uploading {apk.name} ({apk.stat().st_size / 1048576:.1f} MB)…")
    with urllib.request.urlopen(request, timeout=900) as response:
        return json.loads(response.read())["browser_download_url"]


def telegram(method: str, token: str, payload: dict) -> dict:
    data = urllib.parse.urlencode(payload).encode()
    url = f"https://api.telegram.org/bot{token}/{method}"
    with urllib.request.urlopen(urllib.request.Request(url, data=data), timeout=60) as response:
        body = json.loads(response.read())
    if not body.get("ok"):
        die(f"Telegram said: {body}")
    return body


def list_chats(token: str) -> None:
    """Telegram never tells a bot its channel id directly; getUpdates is the way to find it."""
    body = telegram("getUpdates", token, {})
    seen: dict[int, str] = {}
    for update in body.get("result", []):
        for key in ("message", "channel_post", "my_chat_member"):
            chat = update.get(key, {}).get("chat")
            if chat:
                seen[chat["id"]] = f"{chat.get('title') or chat.get('username') or ''} ({chat['type']})"
    if not seen:
        print("Ничего не видно. Добавь бота в канал администратором и отправь туда любое сообщение,")
        print("потом запусти снова. Если у бота включён privacy mode, выключи его у @BotFather.")
        return
    for chat_id, title in seen.items():
        print(f"{chat_id}\t{title}")


def announce_only(args, env) -> None:
    """Send the channel post for a release that is already published.

    Exists because the post and the release are two different things that can fail apart: a typo,
    a forgotten argument, a deleted message. Re-uploading eighty megabytes of APK to fix a sentence
    is not a reasonable price.
    """
    token = env["GITHUB_TOKEN"]
    repo = resolve_repo(token, env.get("GITHUB_REPO"))
    version, _ = read_version()
    tag = f"v{version}"

    try:
        release = api(f"https://api.github.com/repos/{repo}/releases/tags/{tag}", token)
    except urllib.error.HTTPError as error:
        die(f"релиза {tag} нет на GitHub ({error.code}) — сначала опубликуй его")

    links = {
        asset["name"]: asset["browser_download_url"]
        for asset in release.get("assets", [])
        if asset["name"].endswith(".apk")
    }
    if not links:
        die(f"у релиза {tag} нет ни одного APK — постить нечего")

    post(args, env, version, links)


def post(args, env, version: str, links: dict) -> None:
    announcement = args.announce or ANNOUNCEMENT.format(version=version)
    lines = [announcement.strip(), ""]
    for name, url in sorted(links.items()):
        abi = "arm64" if "arm64" in name else "arm 32-бит"
        lines.append(f'<a href="{url}">Скачать · {abi}</a>')
    lines += ["", "<i>Почти всем нужен arm64 — 32-битная сборка только для очень старых телефонов.</i>"]

    sent = telegram(
        "sendMessage",
        env["TELEGRAM_BOT_TOKEN"],
        {
            "chat_id": env["TELEGRAM_CHAT_ID"],
            "text": "\n".join(lines),
            "parse_mode": "HTML",
            "disable_web_page_preview": "true",
        },
    )
    # Kept so a post can be edited later instead of deleted and re-sent, which in a channel leaves
    # a hole where the old message was and re-notifies everyone.
    message_id = sent.get("result", {}).get("message_id")
    if message_id:
        (ROOT / "build" / "last-announcement.txt").parent.mkdir(parents=True, exist_ok=True)
        (ROOT / "build" / "last-announcement.txt").write_text(
            f"{env['TELEGRAM_CHAT_ID']} {message_id}\n", encoding="utf-8",
        )
        print(f"→ пост отправлен (message_id {message_id})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--notes", default="", help="что нового в этой версии (текст релиза)")
    parser.add_argument(
        "--announce",
        default="",
        help="текст поста в канал, HTML; ссылки на сборки дописываются сами",
    )
    parser.add_argument("--dry-run", action="store_true", help="собрать, но ничего не публиковать")
    parser.add_argument(
        "--skip-build",
        action="store_true",
        help="взять APK, которые уже лежат в app/build/outputs/apk/release",
    )
    parser.add_argument("--chats", action="store_true", help="показать чаты, доступные боту")
    parser.add_argument(
        "--announce-only",
        action="store_true",
        help="ничего не собирать и не загружать: взять уже опубликованный релиз и отправить пост",
    )
    parser.add_argument(
        "--allow-default-announce",
        action="store_true",
        help="разрешить пост-заглушку без --announce",
    )
    args = parser.parse_args()

    # `@path` reads the text from a UTF-8 file. Release notes and channel posts are paragraphs of
    # Russian with newlines in them, and passing those through a Windows command line is a good
    # way to publish mojibake to a few hundred people.
    for field in ("notes", "announce"):
        value = getattr(args, field)
        if value.startswith("@"):
            setattr(args, field, Path(value[1:]).read_text(encoding="utf-8"))

    if args.chats:
        list_chats(load_env()["TELEGRAM_BOT_TOKEN"])
        return

    # The trap this closes, having sprung once: `--notes` goes to the GitHub release and
    # `--announce` goes to the channel, and passing only the first publishes a release with a full
    # changelog next to a post that says "новая сборка" and nothing else. Nobody sees that until
    # they read the channel, by which point a few hundred people have seen it too.
    if not args.announce and not args.allow_default_announce and not args.dry_run:
        die(
            "нет --announce: в канал ушёл бы текст-заглушка, который ничего не говорит о версии.\n"
            "Передай --announce @файл.txt, или --allow-default-announce, если это правда нужно.",
        )

    if args.announce_only:
        announce_only(args, load_env())
        return

    # --dry-run stays usable before any credentials exist: it is the way to check that the build
    # signs and names itself correctly without needing a bot or a repo yet.
    env = {} if args.dry_run else load_env()

    version, code = read_version()
    tag = f"v{version}"
    apks = existing_apks() if args.skip_build else build()

    renamed: list[Path] = []
    for apk in apks:
        abi = re.sub(r"^app-|^yumi-[\d.]+-|-release\.apk$|\.apk$", "", apk.name)
        target = apk.with_name(f"yumi-{version}-{abi}.apk")
        apk.replace(target)
        renamed.append(target)

    print(f"\n{tag} (versionCode {code})")
    for apk in renamed:
        print(f"  {apk.name}  {apk.stat().st_size / 1048576:.1f} MB  sha256:{sha256(apk)[:16]}…")

    if args.dry_run:
        print("\n--dry-run: ничего не опубликовано")
        return

    token = env["GITHUB_TOKEN"]
    repo = resolve_repo(token, env.get("GITHUB_REPO"))
    notes = args.notes or RELEASE_NOTES.format(version=version)
    notes += CHECKSUMS_SECTION.format(
        lines=chr(10).join(f"{sha256(apk)}  {apk.name}" for apk in renamed),
    )
    release = ensure_release(repo, token, tag, notes)
    links = {apk.name: upload_asset(release, token, apk) for apk in renamed}

    post(args, env, version, links)
    print("\n→ опубликовано и отправлено в канал")


if __name__ == "__main__":
    main()
