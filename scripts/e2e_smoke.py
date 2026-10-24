"""Tsukaima e2e smoke: login, REST contract (the app's exact GETs), WS session, one prompt,
config.set model round trip (same model, session scope), session detail `archived` flag,
file.attach + /api/files/download round trip, cron create/list/delete, session cleanup, STT and TTS relays,
pdf.attach, todo.updated from the todo_list tool."""
import asyncio, base64, contextlib, http.cookiejar, json, os, sys, time, urllib.error, urllib.parse, urllib.request

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
    "/api/audio/voice-config",  # Settings: STT model / TTS provider labels (stt.provider/model/streaming, tts.mode/provider)
]


def rest_contract_part(token):
    for path in REST_CONTRACT:
        try:
            http_json("GET", path, token=token)
        except urllib.error.HTTPError as e:
            fail(f"GET {path} -> HTTP {e.code}: {e.read()[:200]!r}")
    step(f"REST contract ({len(REST_CONTRACT)} GETs with the app's params, all 2xx)")


def silence_wav(seconds=1, rate=16000):
    """16-bit mono PCM silence as a RIFF/WAVE file, built like the app's Pcm.wav (44-byte header)."""
    data = b"\x00\x00" * (rate * seconds)
    header = (b"RIFF" + (36 + len(data)).to_bytes(4, "little") + b"WAVEfmt "
              + (16).to_bytes(4, "little") + (1).to_bytes(2, "little") + (1).to_bytes(2, "little")
              + rate.to_bytes(4, "little") + (rate * 2).to_bytes(4, "little")
              + (2).to_bytes(2, "little") + (16).to_bytes(2, "little")
              + b"data" + len(data).to_bytes(4, "little"))
    return header + data


def transcribe_part(token):
    body = {"data_url": "data:audio/wav;base64," + base64.b64encode(silence_wav()).decode(), "mime_type": "audio/wav"}
    try:
        r = http_json("POST", "/api/audio/transcribe", body, token)
    except urllib.error.HTTPError as e:
        fail(f"POST /api/audio/transcribe -> HTTP {e.code}: {e.read()[:200]!r}")
    if r.get("ok") is not True:
        fail(f"transcribe not ok: {r!r}")
    if r.get("transcript") != "":
        fail(f"transcript of silence not empty ({len(r.get('transcript') or '')} chars)")
    step(f"POST /api/audio/transcribe (silence → empty transcript, provider={r.get('provider')})")


def speak_part(token):
    try:
        r = http_json("POST", "/api/audio/speak", {"text": "Tsukaima smoke test."}, token)
    except urllib.error.HTTPError as e:
        detail = e.read()[:200]
        if e.code == 400 and b"provider" in detail.lower():
            print(f"skip POST /api/audio/speak (no usable TTS provider: {detail!r})", flush=True)
            return
        fail(f"POST /api/audio/speak -> HTTP {e.code}: {detail!r}")
    if r.get("ok") is not True:
        fail(f"speak not ok: {sorted(r)!r}")
    data_url = r.get("data_url") or ""
    head, _, payload = data_url.partition(";base64,")
    if not head.startswith("data:audio/") or not payload:
        fail(f"speak data_url not a non-empty base64 audio data URL ({head[:40]!r}, {len(payload)} chars)")
    audio = base64.b64decode(payload, validate=True)
    step(f"POST /api/audio/speak ({r.get('mime_type')}, {len(audio)} bytes, provider={r.get('provider')})")


def voice_live_part(token):
    try:
        r = http_json("GET", "/api/audio/voice-live/status", token=token)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            step("voice-live status: route absent (older Hermes)")
            return
        fail(f"GET /api/audio/voice-live/status -> HTTP {e.code}: {e.read()[:200]!r}")
    if not isinstance(r, dict) or r.get("ok") is not True:
        fail(f"voice-live status not ok: {r!r}")
    mode = r.get("mode")
    step(f"GET /api/audio/voice-live/status (mode={mode}, available={r.get('available')})")
    if mode != "gpt-live":
        return

    def post_session(sdp):
        try:
            http_json("POST", "/api/audio/voice-live/session", {"sdp": sdp}, token)
        except urllib.error.HTTPError as e:
            return e.code
        return 200

    code = post_session("  ")
    if code != 400:
        fail(f"voice-live session with blank SDP -> HTTP {code}, expected 400")
    step("POST /api/audio/voice-live/session (blank SDP -> 400)")
    code = post_session("v=0\r\n")
    if code not in (502, 503):
        fail(f"voice-live session with a junk SDP -> HTTP {code}, expected 502 or 503")
    step(f"POST /api/audio/voice-live/session (junk SDP -> {code}, vendor/key refusal)")


def minimal_pdf():
    """A valid one-page PDF (blank US Letter page) with a byte-exact xref table, built in code."""
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>"]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    out += b"".join(b"%010d 00000 n \n" % off for off in offsets)
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref)
    return bytes(out)


@contextlib.asynccontextmanager
async def ws_rpc(token, on_event=None):
    """A WS connection with a reader task; yields `rpc(method, params)`. Server requests are denied like ws_part;
    each `event` frame's params (`{type, session_id, payload}`) go to `on_event` when given."""
    import websockets
    ticket = http_json("POST", "/api/auth/ws-ticket", token=token)["ticket"]
    url = BASE.replace("http", "ws", 1) + "/api/ws?ticket=" + ticket
    pending, n = {}, 0
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
                        if on_event:
                            on_event(m.get("params") or {})
                    elif "method" in m and "id" in m:
                        await ws.send(json.dumps({"jsonrpc": "2.0", "id": m["id"], "result": {"choice": "deny"}}))

        async def rpc(method, params=None, check=True):
            """The result; with check=False the whole reply (so a caller can inspect an error)."""
            nonlocal n
            n += 1
            rid = f"p{n}"
            fut = asyncio.get_running_loop().create_future()
            pending[rid] = fut
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}))
            m = await asyncio.wait_for(fut, 60)
            if not check:
                return m
            if "error" in m:
                fail(f"{method}: {m['error']}")
            return m["result"]

        rt = asyncio.create_task(reader())
        try:
            yield rpc
        finally:
            rt.cancel()


async def pdf_attach_part(token):
    """PDF attach: `pdf.attach` renders the pages server-side and queues them as images for the next turn."""
    async with ws_rpc(token) as rpc:
        await rpc("client.capabilities", {"server_requests": True})
        r = await rpc("session.create", {"source": "tsukaima-smoke"})
        sid, stored = r["session_id"], r["stored_session_id"]
        try:
            pdf = minimal_pdf()
            a = await rpc("pdf.attach", {"session_id": sid, "content_base64": base64.b64encode(pdf).decode(),
                                         "filename": "smoke.pdf"})
            if not (a.get("pages_attached", 0) >= 1 and a.get("count", 0) >= 1):
                fail(f"pdf.attach: unexpected result keys={sorted(a)} pages_attached={a.get('pages_attached')!r} "
                     f"count={a.get('count')!r}")
            step(f"pdf.attach ({len(pdf)} B PDF -> pages_attached={a['pages_attached']}, count={a['count']})")
        finally:
            # No turn ran, so the lazy session may never have been stored: 4007 on delete means nothing to clean up.
            try:
                await rpc("session.close", {"session_id": sid})
                d = await rpc("session.delete", {"session_id": stored}, check=False)
                err = d.get("error")
                if err is None:
                    step("session.delete (pdf)")
                elif err.get("code") == 4007:
                    step("session cleanup (pdf; never stored)")
                else:
                    print(f"warn: session.delete failed: {err!r}", file=sys.stderr)
            except Exception as e:  # noqa: BLE001
                print(f"warn: session cleanup failed: {e!r}", file=sys.stderr)


async def todo_part(token):
    """Todo checklists: ask the agent to call todo_list and expect a `todo.updated` full snapshot (`todos` list,
    integer `revision >= 1`) before the turn completes. The model may not call the tool, so no event within the
    turn prints a skip line instead of failing."""
    loop = asyncio.get_running_loop()
    updates, complete = [], loop.create_future()
    sid = None

    def on_event(p):
        if p.get("session_id") != sid:
            return
        if p.get("type") == "todo.updated":
            updates.append(p.get("payload") or {})
        elif p.get("type") == "message.complete" and not complete.done():
            complete.set_result(p.get("payload") or {})

    async with ws_rpc(token, on_event) as rpc:
        await rpc("client.capabilities", {"server_requests": True})
        r = await rpc("session.create", {"source": "tsukaima-smoke"})
        sid, stored = r["session_id"], r["stored_session_id"]
        try:
            await rpc("prompt.submit", {"session_id": sid, "text": "Create a two-item todo list with todo_list "
                                        "(items: 'smoke one', 'smoke two'), then reply with exactly: done"})
            await asyncio.wait_for(complete, TIMEOUT)
            if not updates:
                print("skip todo.updated (no todo_list call within the turn)", flush=True)
                return
            last = updates[-1]
            todos, rev = last.get("todos"), last.get("revision")
            if not isinstance(todos, list) or not isinstance(rev, int) or isinstance(rev, bool) or rev < 1:
                fail(f"todo.updated: unexpected shape keys={sorted(last)} revision={rev!r} "
                     f"todos={type(todos).__name__}")
            if not all(isinstance(t, dict) and t.get("id") and t.get("status") for t in todos):
                fail(f"todo.updated: entries lack id/status ({len(todos)} entries)")
            step(f"todo.updated ({len(todos)} items, revision={rev}, {len(updates)} event(s))")
        finally:
            try:
                await rpc("session.close", {"session_id": sid})
                d = await rpc("session.delete", {"session_id": stored}, check=False)
                err = d.get("error")
                if err is None:
                    step("session.delete (todo)")
                elif err.get("code") == 4007:
                    step("session cleanup (todo; never stored)")
                else:
                    print(f"warn: session.delete failed: {err!r}", file=sys.stderr)
            except Exception as e:  # noqa: BLE001
                print(f"warn: session cleanup failed: {e!r}", file=sys.stderr)


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
    transcribe_part(token)
    speak_part(token)
    voice_live_part(token)
    asyncio.run(pdf_attach_part(token))
    asyncio.run(todo_part(token))
    print("PASS")


try:
    main()
except SystemExit:
    raise
except Exception as e:  # noqa: BLE001
    fail(repr(e))
