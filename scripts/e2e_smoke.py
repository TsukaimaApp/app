"""Tsukaima e2e smoke: login, REST contract (the app's exact GETs), WS session, one prompt,
cron create/list/delete, session cleanup."""
import asyncio, http.cookiejar, json, os, sys, time, urllib.error, urllib.request

BASE = os.environ["HERMES_TEST_URL"].rstrip("/")
USER, PASSWORD = os.environ["HERMES_TEST_USER"], os.environ["HERMES_TEST_PASSWORD"]
TIMEOUT = 120


def fail(msg):
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def step(msg):
    print(f"ok  {msg}", flush=True)


cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def http_json(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    with opener.open(req, timeout=30) as r:
        raw = r.read()
    return json.loads(raw) if raw.strip() else None


def login():
    http_json("POST", "/auth/password-login", {"provider": "basic", "username": USER, "password": PASSWORD})
    at = next((c.value for c in cj if c.name == "hermes_session_at"), None)
    if not at:
        fail("no hermes_session_at cookie after login")
    return at


async def ws_part(token):
    import websockets
    ticket = http_json("POST", "/api/auth/ws-ticket", token=token)["ticket"]
    url = BASE.replace("http", "ws", 1) + "/api/ws?ticket=" + ticket
    pending, complete = {}, asyncio.get_running_loop().create_future()
    n = 0
    async with websockets.connect(url, max_size=None) as ws:
        async def reader():
            async for raw in ws:
                for line in raw.split("\n"):
                    if not line.strip():
                        continue
                    m = json.loads(line)
                    if "id" in m and m["id"] in pending and "method" not in m:
                        pending.pop(m["id"]).set_result(m)
                    elif m.get("method") == "event":
                        p = m["params"]
                        if p.get("type") == "message.complete" and not complete.done():
                            complete.set_result(p.get("payload", {}).get("text", ""))
                    elif "method" in m and "id" in m:  # server request: deny anything unexpected
                        await ws.send(json.dumps({"jsonrpc": "2.0", "id": m["id"], "result": {"choice": "deny"}}))

        async def rpc(method, params=None):
            nonlocal n
            n += 1
            rid = f"s{n}"
            fut = asyncio.get_running_loop().create_future()
            pending[rid] = fut
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}))
            m = await asyncio.wait_for(fut, 60)
            if "error" in m:
                fail(f"{method}: {m['error']}")
            return m["result"]

        rt = asyncio.create_task(reader())
        sid = stored = None
        try:
            await rpc("client.capabilities", {"server_requests": True})
            step("client.capabilities")
            r = await rpc("session.create", {"source": "tsukaima-smoke"})
            sid, stored = r["session_id"], r["stored_session_id"]
            step(f"session.create ({sid})")
            await rpc("prompt.submit", {"session_id": sid, "text": "Reply with exactly: pong"})
            text = await asyncio.wait_for(complete, TIMEOUT)
            if "pong" not in text.lower():
                fail(f"reply lacks 'pong': {text!r}")
            step(f"message.complete: {text.strip()[:60]!r}")
        finally:
            if stored:  # session.delete takes the stored id, not the live one
                try:
                    await rpc("session.close", {"session_id": sid})
                    await rpc("session.delete", {"session_id": stored})
                    step("session.delete")
                except (Exception, SystemExit) as e:  # noqa: BLE001
                    print(f"warn: session.delete failed: {e!r}", file=sys.stderr)
            rt.cancel()


# Every REST read the app makes, with the app's exact query params (HermesApi.kt). A server-side cap or
# param change (e.g. /api/sessions `limit` le=100) makes one of these non-2xx and fails the smoke.
REST_CONTRACT = [
    "/api/sessions?limit=100&archived=include&exclude_sources=cron",  # drawer
    "/api/sessions?limit=10",  # status: last session
    "/api/model/options",
    "/api/chat/workspaces",
    "/api/cron/jobs",
    "/api/cron/delivery-targets",
    "/api/pairing",
    "/api/skills",
    "/api/tools/toolsets",
    "/api/profiles",
    "/api/profiles/active",
    "/api/logs?file=gateway&lines=300",
    "/api/logs?file=agent&lines=300&level=WARNING",
    "/api/analytics/usage?days=30",
    "/api/system/stats",
    "/api/auth/me",
]


def rest_contract_part(token):
    for path in REST_CONTRACT:
        try:
            http_json("GET", path, token=token)
        except urllib.error.HTTPError as e:
            fail(f"GET {path} -> HTTP {e.code}: {e.read()[:200]!r}")
    step(f"REST contract ({len(REST_CONTRACT)} GETs with the app's params, all 2xx)")


def cron_part(token):
    name = f"tsukaima-smoke-{int(time.time())}"
    job = http_json("POST", "/api/cron/jobs", {"name": name, "schedule": "0 4 * * *",
                    "prompt": "smoke", "deliver": "local"}, token)
    jid = job["id"]
    try:
        jobs = http_json("GET", "/api/cron/jobs", token=token)
        if not any(j["id"] == jid for j in jobs):
            fail("created cron job missing from list")
        step(f"cron create+list ({jid})")
    finally:
        http_json("DELETE", f"/api/cron/jobs/{jid}", token=token)
    if any(j["id"] == jid for j in http_json("GET", "/api/cron/jobs", token=token)):
        fail("cron job still listed after delete")
    step("cron delete")


def main():
    token = login()
    step("login")
    rest_contract_part(token)
    asyncio.run(ws_part(token))
    cron_part(token)
    print("PASS")


try:
    main()
except SystemExit:
    raise
except Exception as e:  # noqa: BLE001
    fail(repr(e))
