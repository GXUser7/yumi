#!/usr/bin/env python3
"""
Local mock server implementing Open Subscription Billing (OSB v0.1)
compatible with Remnawave-style subscription headers and Yumi VPN.

Standard library only (Python 3.9+). Bind to 127.0.0.1 by default.
"""

from __future__ import annotations

import argparse
import base64
import html
import json
import re
import secrets
import threading
import time
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, quote, urlencode, urlparse, urlunparse


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8787
TOTAL_DEFAULT_BYTES = 300 * 1024 * 1024 * 1024
HOST_HEADER_RE = re.compile(r"^[A-Za-z0-9.\-:]+$")


@dataclass
class DemoState:
    expire_ts: int = field(default_factory=lambda: int(time.time()) + 14 * 86400)
    upload_bytes: int = 4 * 1024 * 1024 * 1024
    download_bytes: int = 38 * 1024 * 1024 * 1024
    total_bytes: int = TOTAL_DEFAULT_BYTES
    current_plan: str = "Pro 1M"
    devices_used: int = 2
    device_limit: int = 5
    orders: dict[str, dict[str, Any]] = field(default_factory=dict)


STATE_LOCK = threading.Lock()
DEMO_STATE = DemoState()

PLANS: list[dict[str, Any]] = [
    {
        "id": "pro-1m",
        "title": "1 месяц Pro",
        "price": 69000,
        "currency": "RUB",
        "currency_decimals": 2,
        "duration_days": 30,
        "traffic_gb": 200,
        "devices": 5,
        "features": ["Все локации 10 Gbps", "До 5 устройств", "Без рекламы"],
    },
    {
        "id": "pro-3m",
        "title": "3 месяца Pro",
        "badge": "Популярный",
        "price": 189000,
        "old_price": 207000,
        "currency": "RUB",
        "currency_decimals": 2,
        "duration_days": 90,
        "traffic_gb": 300,
        "devices": 5,
        "features": ["Все локации 10 Gbps", "300 ГБ/мес", "Скидка 10% по YUMI10"],
    },
    {
        "id": "pro-12m",
        "title": "12 месяцев Pro",
        "badge": "−34%",
        "price": 549000,
        "old_price": 828000,
        "currency": "RUB",
        "currency_decimals": 2,
        "duration_days": 365,
        "traffic_gb": 500,
        "devices": 8,
        "features": ["Все локации 10 Gbps", "До 8 устройств", "Приоритетная поддержка"],
    },
]

ADDONS: list[dict[str, Any]] = [
    {
        "id": "extra-traffic-100",
        "title": "+100 ГБ трафика",
        "description": "Добавляет 100 ГБ к текущему периоду",
        "price": 19900,
        "currency": "RUB",
        "currency_decimals": 2,
    },
    {
        "id": "extra-devices-3",
        "title": "+3 устройства",
        "description": "Расширяет лимит HWID на 3 слота",
        "badge": "HWID",
        "price": 24900,
        "currency": "RUB",
        "currency_decimals": 2,
    },
]

PAYMENT_METHODS: list[dict[str, Any]] = [
    {"id": "sbp", "title": "СБП", "type": "sbp"},
    {"id": "card_ru", "title": "Карта РФ", "type": "card"},
    {"id": "stars", "title": "Telegram Stars", "type": "stars"},
]


def format_rub(minor: int) -> str:
    rub = minor // 100
    kopecks = minor % 100
    grouped = f"{rub:,}".replace(",", " ")
    if kopecks:
        return f"{grouped},{kopecks:02d} ₽"
    return f"{grouped} ₽"


def sanitize_return_url(raw: str | None, order_id: str) -> str:
    fallback = f"/portal/demo?paid=1&order_id={quote(order_id, safe='')}"
    if not raw:
        return fallback
    cleaned = raw.strip()
    if not cleaned or any(ch in cleaned for ch in ("\r", "\n", "\x00")):
        return fallback
    parsed = urlparse(cleaned)
    scheme = parsed.scheme.lower()
    if scheme in ("mydrop", "yumi"):
        if parsed.netloc.lower() != "billing":
            return fallback
        path = parsed.path or "/return"
        if path != "/return":
            return fallback
        query = parse_qs(parsed.query, keep_blank_values=False)
        flat_query: dict[str, str] = {k: v[0] for k, v in query.items() if v}
        flat_query["order_id"] = order_id
        return urlunparse((scheme, "billing", "/return", "", urlencode(flat_query), ""))
    return fallback


def reset_demo_state() -> None:
    global DEMO_STATE
    with STATE_LOCK:
        DEMO_STATE = DemoState()


def apply_order_payment(order_id: str) -> dict[str, Any] | None:
    now = int(time.time())
    with STATE_LOCK:
        order = DEMO_STATE.orders.get(order_id)
        if order is None:
            return None
        if not order.get("paid"):
            order["paid"] = True
            order["paid_at"] = now
            kind = order.get("kind")
            item_id = order.get("item_id")
            if kind == "plan":
                plan = next((p for p in PLANS if p["id"] == item_id), None)
                if plan is not None:
                    base_ts = max(now, DEMO_STATE.expire_ts)
                    DEMO_STATE.expire_ts = base_ts + int(plan["duration_days"]) * 86400
                    DEMO_STATE.current_plan = str(plan["title"])
                    traffic_gb = plan.get("traffic_gb")
                    if traffic_gb is not None:
                        DEMO_STATE.total_bytes = int(traffic_gb) * 1024 * 1024 * 1024
                    devices = plan.get("devices")
                    if devices is not None:
                        DEMO_STATE.device_limit = int(devices)
            elif kind == "addon":
                if item_id == "extra-traffic-100":
                    DEMO_STATE.total_bytes += 100 * 1024 * 1024 * 1024
                elif item_id == "extra-devices-3":
                    DEMO_STATE.device_limit += 3
        return dict(order)


class MockBillingHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_host: str = DEFAULT_HOST
    server_port: int = DEFAULT_PORT

    def log_message(self, format: str, *args: Any) -> None:
        return

    def _base_url(self) -> str:
        raw_host = self.headers.get("Host", "").strip()
        if raw_host and HOST_HEADER_RE.match(raw_host):
            return f"http://{raw_host}"
        return f"http://{self.server_host}:{self.server_port}"

    def _send_bytes(
        self,
        status: int,
        body: bytes,
        content_type: str,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if extra_headers:
            for key, value in extra_headers.items():
                self.send_header(key, value)
        self.end_headers()
        self.wfile.write(body)

    def _send_json(
        self,
        status: int,
        payload: Any,
        extra_headers: dict[str, str] | None = None,
    ) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._send_bytes(status, body, "application/json; charset=utf-8", extra_headers)

    def _send_html(self, status: int, markup: str) -> None:
        self._send_bytes(status, markup.encode("utf-8"), "text/html; charset=utf-8")

    def _read_json_body(self, max_bytes: int = 64 * 1024) -> dict[str, Any] | None:
        raw_len = self.headers.get("Content-Length", "").strip()
        try:
            length = int(raw_len)
        except ValueError:
            return None
        if length <= 0 or length > max_bytes:
            return None
        raw = self.rfile.read(length)
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return None
        return parsed if isinstance(parsed, dict) else None

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        if path == "/sub/demo":
            self._handle_subscription(query)
        elif path == "/api/billing/demo":
            self._handle_manifest()
        elif path.startswith("/pay/"):
            order_id = path[len("/pay/") :].strip("/")
            self._handle_pay_page(order_id, query)
        elif path == "/portal/demo":
            self._handle_portal(query)
        elif path == "/healthz":
            self._send_json(HTTPStatus.OK, {"ok": True})
        else:
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found", "message": "Unknown endpoint"})

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/billing/demo/checkout":
            self._handle_checkout()
        elif path == "/api/billing/demo/reset":
            reset_demo_state()
            self._send_json(HTTPStatus.OK, {"ok": True})
        elif path.startswith("/pay/") and path.endswith("/confirm"):
            order_id = path[len("/pay/") : -len("/confirm")].strip("/")
            self._handle_pay_confirm(order_id)
        else:
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "not_found", "message": "Unknown endpoint"})

    def _handle_subscription(self, query: dict[str, list[str]]) -> None:
        if query.get("reset") == ["1"]:
            reset_demo_state()

        mode = (query.get("mode", ["active"])[0] or "active").strip().lower()
        now = int(time.time())
        base = self._base_url()

        with STATE_LOCK:
            expire_ts = DEMO_STATE.expire_ts
            upload_bytes = DEMO_STATE.upload_bytes
            download_bytes = DEMO_STATE.download_bytes
            total_bytes = DEMO_STATE.total_bytes
            device_limit = DEMO_STATE.device_limit

        if mode == "urgent":
            expire_ts = now + 2 * 86400
        elif mode == "expired":
            expire_ts = now - 3600

        lines = [
            f"vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443?security=tls&type=tcp#{quote('DE #1 Frankfurt')}",
            f"vless://22222222-2222-2222-2222-222222222222@203.0.113.20:443?security=tls&type=tcp#{quote('NL #2 Amsterdam')}",
            f"vless://33333333-3333-3333-3333-333333333333@203.0.113.30:443?security=tls&type=tcp#{quote('FI #3 Helsinki')}",
        ]
        if mode == "expired":
            lines = [
                f"vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?security=none#{quote('Subscription Expired')}",
            ]
        elif mode == "hwid":
            lines = [
                f"vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?security=none#{quote('Device Limit Reached')}",
            ]

        headers: dict[str, str] = {
            "profile-title": "base64:" + base64.b64encode("Test".encode("utf-8")).decode("ascii"),
            "profile-update-interval": "12",
            "subscription-userinfo": (
                f"upload={upload_bytes}; download={download_bytes}; total={total_bytes}; expire={expire_ts}"
            ),
            "profile-web-page-url": f"{base}/portal/demo",
            "support-url": f"{base}/portal/demo#support",
            "billing-manifest-url": f"{base}/api/billing/demo",
            "payment-url": f"{base}/portal/demo",
            "sub-expire-button-link": f"{base}/portal/demo",
            "x-hwid-limit": str(device_limit),
        }
        if mode == "hwid":
            headers["x-hwid-max-devices-reached"] = "true"

        encoded_body = base64.b64encode("\n".join(lines).encode("utf-8"))
        self._send_bytes(HTTPStatus.OK, encoded_body, "text/plain; charset=utf-8", headers)

    def _handle_manifest(self) -> None:
        base = self._base_url()
        with STATE_LOCK:
            expires_iso = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(DEMO_STATE.expire_ts))
            account = {
                "status": "expired" if DEMO_STATE.expire_ts <= int(time.time()) else "active",
                "current_plan": DEMO_STATE.current_plan,
                "expires_at": expires_iso,
                "devices_used": DEMO_STATE.devices_used,
                "device_limit": DEMO_STATE.device_limit,
            }

        manifest = {
            "version": 1,
            "provider": {
                "name": "Test",
                "support_url": f"{base}/portal/demo#support",
            },
            "account": account,
            "routing_hint": "direct_ru",
            "promo_supported": True,
            "fallback_web_url": f"{base}/portal/demo",
            "payment_methods": PAYMENT_METHODS,
            "plans": PLANS,
            "addons": ADDONS,
            "checkout": {
                "endpoint": f"{base}/api/billing/demo/checkout",
                "method": "POST",
            },
        }
        self._send_json(HTTPStatus.OK, manifest)

    def _handle_checkout(self) -> None:
        req = self._read_json_body()
        if req is None:
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_request", "message": "Malformed JSON body"},
            )
            return

        plan_id = req.get("plan_id")
        addon_id = req.get("addon_id")
        method_id = req.get("payment_method")
        promo_code = req.get("promo_code")
        return_url = req.get("return_url")

        if bool(plan_id) == bool(addon_id):
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_selection", "message": "Specify either plan_id or addon_id"},
            )
            return

        if not isinstance(method_id, str) or not any(m["id"] == method_id for m in PAYMENT_METHODS):
            self._send_json(
                HTTPStatus.BAD_REQUEST,
                {"error": "invalid_payment_method", "message": "Unsupported payment method"},
            )
            return

        kind = "plan" if plan_id else "addon"
        item_id = plan_id if plan_id else addon_id
        catalog = PLANS if kind == "plan" else ADDONS
        item = next((entry for entry in catalog if entry["id"] == item_id), None)
        if item is None:
            self._send_json(
                HTTPStatus.NOT_FOUND,
                {"error": "item_not_found", "message": "Selected tariff or add-on was not found"},
            )
            return

        amount = int(item["price"])
        promo_applied = False
        if isinstance(promo_code, str) and promo_code.strip():
            normalized_promo = promo_code.strip().upper()
            if normalized_promo == "YUMI10":
                amount = (amount * 90) // 100
                promo_applied = True
            else:
                self._send_json(
                    HTTPStatus.BAD_REQUEST,
                    {"error": "invalid_promo", "message": "Промокод не найден или истёк"},
                )
                return

        order_id = "ord_" + secrets.token_hex(6)
        safe_return = sanitize_return_url(
            return_url if isinstance(return_url, str) else None,
            order_id,
        )
        now = int(time.time())
        expires_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now + 900))
        with STATE_LOCK:
            DEMO_STATE.orders[order_id] = {
                "order_id": order_id,
                "kind": kind,
                "item_id": item_id,
                "title": item["title"],
                "amount": amount,
                "currency": item["currency"],
                "payment_method": method_id,
                "promo_applied": promo_applied,
                "return_url": safe_return,
                "paid": False,
                "created_at": now,
            }

        base = self._base_url()
        self._send_json(
            HTTPStatus.OK,
            {
                "order_id": order_id,
                "amount": amount,
                "currency": item["currency"],
                "payment_url": f"{base}/pay/{order_id}",
                "routing_hint": "direct_ru",
                "expires_at": expires_at,
            },
        )

    def _handle_pay_page(self, order_id: str, query: dict[str, list[str]]) -> None:
        if order_id == "quick":
            self._handle_portal(query)
            return

        with STATE_LOCK:
            order = dict(DEMO_STATE.orders.get(order_id) or {})

        if not order:
            self._send_html(
                HTTPStatus.NOT_FOUND,
                "<!doctype html><html><body><h1>Order not found</h1></body></html>",
            )
            return

        title = html.escape(str(order["title"]))
        oid = html.escape(order_id)
        amount_text = html.escape(format_rub(int(order["amount"])))
        method = html.escape(str(order["payment_method"]).upper())
        promo_note = " (скидка 10% применена)" if order.get("promo_applied") else ""
        paid_banner = (
            '<div class="badge">Уже оплачено</div>' if order.get("paid") else ""
        )

        page = f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Оплата заказа {oid}</title>
<style>
  body {{ margin:0; font-family:system-ui,-apple-system,sans-serif; background:#090B0F; color:#EEF2F9; display:flex; min-height:100vh; align-items:center; justify-content:center; padding:20px; }}
  .card {{ width:100%; max-width:420px; background:#0F131A; border:1px solid #262C38; border-radius:20px; padding:24px; }}
  .muted {{ color:#9AA3B5; font-size:14px; margin:6px 0 0; }}
  .price {{ font-size:32px; font-weight:700; color:#A8C7FF; margin:18px 0 4px; }}
  .btn {{ width:100%; border:0; border-radius:14px; padding:14px 18px; font-size:16px; font-weight:600; cursor:pointer; background:#A8C7FF; color:#091426; margin-top:20px; }}
  .link {{ display:block; text-align:center; margin-top:14px; color:#9AA3B5; text-decoration:none; font-size:14px; }}
  .badge {{ display:inline-block; padding:4px 10px; border-radius:999px; background:#1D3B2A; color:#86EFAC; font-size:12px; margin-bottom:10px; }}
</style>
</head>
<body>
  <div class="card">
    {paid_banner}
    <h1 style="margin:0;font-size:20px;">Test Checkout</h1>
    <p class="muted">Заказ {oid} · Способ: {method}</p>
    <div class="price">{amount_text}</div>
    <p class="muted">{title}{html.escape(promo_note)}</p>
    <form method="post" action="/pay/{oid}/confirm">
      <button class="btn" type="submit">Оплатить и вернуться в Yumi</button>
    </form>
    <a class="link" href="/portal/demo">Отменить и перейти в кабинет</a>
  </div>
</body>
</html>"""
        self._send_html(HTTPStatus.OK, page)

    def _handle_pay_confirm(self, order_id: str) -> None:
        order = apply_order_payment(order_id)
        if order is None:
            self._send_json(HTTPStatus.NOT_FOUND, {"error": "order_not_found", "message": "Unknown order"})
            return
        target = str(order["return_url"])
        self.send_response(HTTPStatus.SEE_OTHER)
        self.send_header("Location", target)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _handle_portal(self, query: dict[str, list[str]]) -> None:
        with STATE_LOCK:
            days_left = max(0, (DEMO_STATE.expire_ts - int(time.time())) // 86400)
            plan = html.escape(DEMO_STATE.current_plan)
            used = DEMO_STATE.devices_used
            limit = DEMO_STATE.device_limit
            total_gb = DEMO_STATE.total_bytes // (1024 * 1024 * 1024)

        paid = query.get("paid") == ["1"]
        status_note = "<p style='color:#86EFAC'>Оплата успешно проведена, подписка обновлена.</p>" if paid else ""
        page = f"""<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Test Portal (Mock)</title>
<style>
  body {{ margin:0; font-family:system-ui,-apple-system,sans-serif; background:#090B0F; color:#EEF2F9; padding:24px; }}
  .wrap {{ max-width:520px; margin:0 auto; background:#0F131A; border:1px solid #262C38; border-radius:20px; padding:24px; }}
  .btn {{ display:inline-block; margin-top:16px; padding:12px 18px; border-radius:14px; background:#A8C7FF; color:#091426; text-decoration:none; font-weight:600; }}
</style>
</head>
<body>
  <div class="wrap">
    <h1 style="margin-top:0;">Личный кабинет Test</h1>
    {status_note}
    <p>Текущий тариф: <strong>{plan}</strong></p>
    <p>Осталось дней: <strong>{days_left}</strong> · Лимит трафика: <strong>{total_gb} ГБ</strong></p>
    <p>Устройства: <strong>{used} / {limit}</strong></p>
    <a class="btn" href="mydrop://billing/return">Вернуться в Yumi</a>
  </div>
</body>
</html>"""
        self._send_html(HTTPStatus.OK, page)


def run_server(host: str, port: int) -> None:
    MockBillingHandler.server_host = host
    MockBillingHandler.server_port = port
    httpd = ThreadingHTTPServer((host, port), MockBillingHandler)
    print(f"Yumi mock OSB server listening on http://{host}:{port}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Local OSB v0.1 mock billing server for Yumi VPN")
    parser.add_argument("--host", default=DEFAULT_HOST, help="Bind host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Bind port (default: 8787)")
    args = parser.parse_args()
    run_server(args.host, args.port)


if __name__ == "__main__":
    main()
