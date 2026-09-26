"""FCB Tracker, a Cloudflare Python Worker.

The Worker does everything itself:
  * serves the frontend (static assets) and a JSON API from KV,
  * syncs ESPN, fcbarcelona.com and Google News on a cron, parsing with Scrapling (fcb/),
  * follows Barcelona's live matches every minute, and on demand while people watch,
  * fills an empty store on the first request after a deploy.
POST /api/ingest still accepts extra data from the optional command-line scraper (SofaScore).
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import time
from urllib.parse import urlparse, parse_qs, quote

from workers import Response, WorkerEntrypoint, fetch

from fcb import FCB_ESPN_ID, pipeline

KEY_PATTERN = re.compile(r"^(meta|team|matches|squad|standings|news|match:\d{1,12})$")
LIVE_MIN_INTERVAL = 20  # seconds between viewer-triggered live refreshes, per isolate
USER_AGENT = "fcb-tracker/1.0 (+Cloudflare Worker)"

_last_live_refresh = 0.0


def respond(body, status: int = 200, max_age: int = 60) -> Response:
    headers = {
        "content-type": "application/json; charset=utf-8",
        "cache-control": f"public, max-age={max_age}" if status == 200 else "no-store",
    }
    return Response(json.dumps(body, ensure_ascii=False), status=status, headers=headers)


class HttpIO:
    """Downloads with the Workers runtime `fetch`. DEV_FETCH_RELAY routes through a local relay in
    sandboxes where the dev runtime has no direct internet access; it is never set in production."""

    def __init__(self, relay: str | None):
        self.relay = relay

    async def _get(self, url: str, accept: str):
        target = f"{self.relay}?url={quote(url, safe='')}" if self.relay else url
        res = await fetch(target, headers={"user-agent": USER_AGENT, "accept": accept, "accept-language": "en-US,en;q=0.9"})
        if not res.ok:
            raise RuntimeError(f"HTTP {res.status} from {urlparse(url).hostname}")
        return res

    async def get_json(self, url: str):
        return json.loads(await (await self._get(url, "application/json")).text())

    async def get_text(self, url: str) -> str:
        return await (await self._get(url, "text/html,application/rss+xml;q=0.9,*/*;q=0.8")).text()


class KVStore:
    def __init__(self, kv):
        self.kv = kv

    async def get(self, key: str):
        raw = await self.kv.get(key)
        return json.loads(raw) if raw else None

    async def put(self, key: str, value) -> None:
        await self.kv.put(key, json.dumps(value, ensure_ascii=False))


class Default(WorkerEntrypoint):
    # ---------------------------------------------------------------- plumbing

    @property
    def store(self) -> KVStore:
        return KVStore(self.env.FCB_DATA)

    @property
    def io(self) -> HttpIO:
        return HttpIO(getattr(self.env, "DEV_FETCH_RELAY", None) or None)

    def _secret(self) -> str | None:
        return getattr(self.env, "INGEST_TOKEN", None) or None

    def _authorized(self, request) -> bool:
        secret = self._secret()
        auth = request.headers.get("authorization") or ""
        token = auth[7:] if auth.startswith("Bearer ") else ""
        if not secret or not token:
            return False
        return hmac.compare_digest(hashlib.sha256(token.encode()).digest(), hashlib.sha256(secret.encode()).digest())

    async def _ensure_data(self) -> None:
        """First request after a deploy: fetch everything before answering."""
        if await self.store.get("matches") is None:
            await pipeline.sync(self.io, self.store, force=True, bootstrap=True)

    def _refresh_live_in_background(self) -> None:
        global _last_live_refresh
        if time.time() - _last_live_refresh < LIVE_MIN_INTERVAL:
            return
        _last_live_refresh = time.time()
        self.ctx.waitUntil(pipeline.refresh_live(self.io, self.store))

    # ---------------------------------------------------------------- views

    async def overview(self) -> Response:
        s = self.store
        meta, team, matches, squad, standings, news = (
            await s.get("meta"), await s.get("team"), await s.get("matches"),
            await s.get("squad"), await s.get("standings"), await s.get("news"),
        )
        if not matches:
            return respond({"error": "no-data", "message": "No data yet. The Worker is fetching it; try again in a moment."}, 503)
        matches = sorted(matches, key=lambda m: m.get("date") or "")
        live = next((m for m in matches if m["status"].get("state") == "in"), None)
        played = [m for m in matches if m["status"].get("state") == "post"]
        upcoming = [m for m in matches if m["status"].get("state") == "pre"]
        last = None
        if played:
            last = await s.get(f"match:{played[-1]['id']}") or {"match": played[-1]}

        def leaders(key: str) -> list:
            ranked = [p for p in (squad or []) if (p["stats"].get(key) or 0) > 0]
            ranked.sort(key=lambda p: (-(p["stats"][key] or 0), p["stats"].get("apps") or 0))
            return ranked[:5]

        rows = (standings or {}).get("rows") or []
        idx = next((i for i, r in enumerate(rows) if r["team"]["id"] == FCB_ESPN_ID), -1)
        start = max(0, min(idx - 2, len(rows) - 5))
        around = rows[start:start + 5] if idx >= 0 else rows[:5]
        return respond({
            "meta": meta, "team": team, "live": live, "next": upcoming[0] if upcoming else None, "last": last,
            "form": list(reversed(played[-5:])), "upcoming": upcoming[:5],
            "standings": {"league": standings.get("league"), "rows": around, "position": rows[idx] if idx >= 0 else None} if standings else None,
            "leaders": {"goals": leaders("goals"), "assists": leaders("assists")},
            "news": (news or [])[:6],
        }, max_age=5 if live else 60)

    async def listing(self, key: str) -> Response:
        meta, data = await self.store.get("meta"), await self.store.get(key)
        if data is None:
            return respond({"error": "no-data", "message": "No data yet. The Worker is fetching it; try again in a moment."}, 503)
        return respond({"meta": meta, key: data})

    async def match_detail(self, match_id: str) -> Response:
        detail = await self.store.get(f"match:{match_id}")
        if detail:
            live = detail.get("match", {}).get("status", {}).get("state") == "in"
            return respond(detail, max_age=5 if live else 120)
        match = next((m for m in (await self.store.get("matches") or []) if m["id"] == match_id), None)
        if not match:
            return respond({"error": "not-found", "message": f"No match with id {match_id}"}, 404)
        return respond({"match": match, "stats": [], "lineups": {}, "events": [], "officials": []})

    # ---------------------------------------------------------------- writes

    async def ingest(self, request) -> Response:
        if not self._secret():
            return respond({"error": "ingest-disabled", "message": "INGEST_TOKEN is not configured"}, 503)
        if not self._authorized(request):
            return respond({"error": "unauthorized"}, 401)
        try:
            body = json.loads(await request.text())
        except ValueError:
            return respond({"error": "bad-request", "message": "Body must be JSON"}, 400)
        items = body.get("items") if isinstance(body, dict) else None
        if not isinstance(items, dict):
            return respond({"error": "bad-request", "message": "Expected {items: {key: value}}"}, 400)
        invalid = [k for k in items if not KEY_PATTERN.match(k)]
        if invalid:
            return respond({"error": "bad-request", "message": f"Unknown keys: {', '.join(invalid)}"}, 400)
        for key, value in items.items():
            if key != "meta":
                await self.store.put(key, value)
        if "meta" in items:
            await self.store.put("meta", items["meta"])
        return respond({"ok": True, "written": len(items)})

    async def manual_sync(self, request) -> Response:
        if not self._authorized(request):
            return respond({"error": "unauthorized"}, 401)
        result = await pipeline.sync(self.io, self.store, force=True, bootstrap=True)
        return respond({"ok": True, **result}, max_age=0)

    async def manual_live(self, request, query: dict) -> Response:
        if not self._authorized(request):
            return respond({"error": "unauthorized"}, 401)
        event = (query.get("event") or [None])[0]
        if not event:
            return respond({"refreshed": await pipeline.refresh_live(self.io, self.store)}, max_age=0)
        # Only Barcelona's own fixtures can be stored; anything else would leak into the match pages.
        known = next((m for m in (await self.store.get("matches") or []) if m["id"] == event), None)
        if not known:
            return respond({"error": "not-found", "message": f"Event {event} is not a Barcelona fixture"}, 404)
        detail = await pipeline.refresh_match(self.io, self.store, known)
        return respond({"ok": True, "match": detail["match"], "events": len(detail["events"]),
                        "stats": len(detail["stats"]), "source": detail["liveSource"]}, max_age=0)

    # ---------------------------------------------------------------- entry points

    async def api(self, request, path: str, query: dict) -> Response:
        method = request.method
        if path == "/api/ingest":
            return await self.ingest(request) if method == "POST" else respond({"error": "method-not-allowed"}, 405)
        if path == "/api/sync":
            return await self.manual_sync(request) if method == "POST" else respond({"error": "method-not-allowed"}, 405)
        if path == "/api/live/refresh":
            return await self.manual_live(request, query) if method == "POST" else respond({"error": "method-not-allowed"}, 405)
        if method not in ("GET", "HEAD"):
            return respond({"error": "method-not-allowed"}, 405)

        if path == "/api/health":
            meta = await self.store.get("meta") or {}
            return respond({"ok": True, "updatedAt": meta.get("updatedAt"), "sources": meta.get("sources")}, max_age=0)
        if path in ("/api/overview", "/api/matches", "/api/squad", "/api/standings", "/api/news") or path.startswith("/api/matches/"):
            await self._ensure_data()
            if path == "/api/overview" or path.startswith("/api/matches"):
                self._refresh_live_in_background()
        if path == "/api/overview":
            return await self.overview()
        if path in ("/api/matches", "/api/squad", "/api/standings", "/api/news"):
            return await self.listing(path.rsplit("/", 1)[1])
        m = re.fullmatch(r"/api/matches/(\d{1,12})", path)
        if m:
            return await self.match_detail(m.group(1))
        return respond({"error": "not-found"}, 404)

    async def fetch(self, request):
        url = urlparse(request.url)
        if url.path.startswith("/api/"):
            try:
                return await self.api(request, url.path, parse_qs(url.query))
            except Exception as exc:  # noqa: BLE001
                print(f"api error: {exc!r}")
                return respond({"error": "internal", "message": "Something went wrong reading data"}, 500)
        return await self.env.ASSETS.fetch(request)

    async def scheduled(self, controller, env, ctx):
        # Every minute: follow live matches, then run every sync step that is due.
        live = await pipeline.refresh_live(self.io, self.store)
        result = await pipeline.sync(self.io, self.store)
        if live or result.get("ran"):
            print(f"cron: live={live} ran={result.get('ran')}")
