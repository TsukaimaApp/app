"""Tsukaima e2e smoke: login, REST contract (the app's exact GETs), WS session, one prompt,
config.set model round trip (same model, session scope), session detail `archived` flag,
file.attach + /api/files/download round trip, cron create/list/delete, session cleanup."""
import asyncio, base64, http.cookiejar, json, os, sys, time, urllib.error, urllib.parse, urllib.request

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
    infos = []  # session.info payloads, in order
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
                        elif p.get("type") == "session.info":
                            infos.append(p.get("payload", {}))
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
            await model_switch_part(rpc, sid, infos)
            detail = http_json("GET", f"/api/sessions/{stored}", token=token)
            if "archived" not in detail:
                fail(f"GET /api/sessions/{{id}} lacks 'archived': {sorted(detail)[:20]}")
            step(f"GET /api/sessions/{{id}} (archived={detail['archived']!r})")
            await download_part(rpc, sid, token)
        finally:
            if stored:  # session.delete takes the stored id, not the live one
                try:
                    await rpc("session.close", {"session_id": sid})
                    await rpc("session.delete", {"session_id": stored})
                    step("session.delete")
                except (Exception, SystemExit) as e:  # noqa: BLE001
                    print(f"warn: session.delete failed: {e!r}", file=sys.stderr)
            rt.cancel()


async def model_switch_part(rpc, sid, infos):
    """The chat's live model switch: `config.set key=model` with the model the session already runs (so nothing
    changes), `--provider` and `--session` exactly as ChatViewModel sends it; the reply is session-scoped and a
    new `session.info` (one that arrived after the call, within 10 s) still names that model."""
    current = next((i for i in reversed(infos) if i.get("model") and i.get("provider")), None)
    if current is None:
        opts = await rpc("model.options", {"session_id": sid})
        current = {"model": opts.get("model"), "provider": opts.get("provider")}
    model, provider = current["model"], current["provider"]
    seen = len(infos)
    r = await rpc("config.set", {"session_id": sid, "key": "model", "value": f"{model} --provider {provider} --session"})
    if r.get("value") != model or r.get("scope") != "session" or r.get("confirm_required"):
        fail(f"config.set model: unexpected result {r!r}")
    for _ in range(100):  # up to 10 s for the session.info the switch emits
        if len(infos) > seen:
            break
        await asyncio.sleep(0.1)
    after = infos[seen:]
    if not after:
        fail("no new session.info within 10 s of config.set model")
    if after[-1].get("model") != model:
        fail(f"session.info after config.set does not name {model!r}: {after[-1]!r}")
    step(f"config.set model round trip ({model} via {provider}, scope=session)")


async def download_part(rpc, sid, token):
    """`MEDIA:` cards download through GET /api/files/download (bearer): upload a tiny file into the session's
    workspace with file.attach, then fetch it back by the returned path."""
    name = f"tsukaima-smoke-{int(time.time())}.txt"
    payload = b"tsukaima smoke\nline 2\n"
    data_url = "data:text/plain;base64," + base64.b64encode(payload).decode()
    r = await rpc("file.attach", {"session_id": sid, "data_url": data_url, "name": name})
    path = r.get("path")
    if not path:
        fail(f"file.attach returned no path: {r!r}")
    url = BASE + "/api/files/download?" + urllib.parse.urlencode({"path": path})
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    try:
        with opener.open(req, timeout=30) as resp:
            body, disposition = resp.read(), resp.headers.get("Content-Disposition", "")
    except urllib.error.HTTPError as e:
        fail(f"GET /api/files/download -> HTTP {e.code}: {e.read()[:200]!r}")
    if body != payload:
        fail(f"download bytes differ: {body!r}")
    expected = os.path.basename(path)
    if f'filename="{expected}"' not in disposition and urllib.parse.quote(expected) not in disposition:
        fail(f"Content-Disposition lacks {expected!r}: {disposition!r}")
    step(f"file.attach + GET /api/files/download ({len(body)} B, {disposition})")


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
    "/api/sessions/search?q=tsukaima&limit=20",  # drawer search
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
