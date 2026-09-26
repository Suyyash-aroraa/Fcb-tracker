"""FCB Tracker, a Cloudflare Python Worker.

The Worker does everything itself, and only while someone is using the site (no cron):
  * serves the frontend (static assets) and a JSON API from KV,
  * answers from stored data at once; when data is older than its refresh interval it re-scrapes
    ESPN, fcbarcelona.com and Google News in the background (parsing with Scrapling, fcb/),
  * follows Barcelona's live matches every ~20 s while people have the site open,
  * fills an empty store on the first request after a deploy.
POST /api/ingest still accepts extra data from the optional command-line scraper (SofaScore).
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import re
import time
from urllib.parse import urlparse, parse_qs, quote

from js import Object
from pyodide.ffi import to_js
from workers import Response, WorkerEntrypoint, fetch

from fcb import FCB_ESPN_ID, espn, pipeline

KEY_PATTERN = re.compile(r"^(meta|team|matches|squad|standings|news|tv|history|match:\d{1,12})$")
LIVE_MIN_INTERVAL = 20  # seconds between viewer-triggered live refreshes, per isolate
SYNC_RETRY_AFTER = 30  # seconds before retrying a background sync that failed
USER_AGENT = "fcb-tracker/1.0 (+Cloudflare Worker)"

_last_live_refresh = 0.0
_sync_running = False
_sync_failed_at = 0.0


def with_tv(match: dict | None, guide: dict | None) -> dict | None:
    """Attach where-to-watch-in-India listings (fcb/tv.py) to a match, when known."""
    if match and guide and match.get("id") in guide:
        return {**match, "tv": guide[match["id"]]}
    return match


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


# Reads are the main latency cost on a quiet site: a cold KV read goes to the central store.
# Keep parsed values in the isolate for a few seconds, and let slow-changing keys sit in KV's edge
# cache for longer. Matches and match details keep KV's default so live scores stay fresh.
MEMORY_TTL = 15
SLOW_KEYS = {"team": 300, "squad": 300, "standings": 300, "news": 300, "tv": 300, "history": 300}
_memory: dict[str, tuple[float, object]] = {}


class KVStore:
    def __init__(self, kv):
        self.kv = kv

    async def get(self, key: str):
        hit = _memory.get(key)
        if hit and hit[0] > time.time():
            return hit[1]
        ttl = SLOW_KEYS.get(key)
        raw = await (self.kv.get(key, to_js({"cacheTtl": ttl}, dict_converter=Object.fromEntries)) if ttl else self.kv.get(key))
        value = json.loads(raw) if raw else None
        if value is not None:
            _memory[key] = (time.time() + MEMORY_TTL, value)
        return value

    async def many(self, *keys: str) -> list:
        return list(await asyncio.gather(*(self.get(k) for k in keys)))

    async def put(self, key: str, value) -> None:
        await self.kv.put(key, json.dumps(value, ensure_ascii=False))
        _memory[key] = (time.time() + MEMORY_TTL, value)


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

    async def _fill(self) -> None:
        """A read came back empty (fresh deploy or a new data type): fetch everything now."""
        await pipeline.sync(self.io, self.store, force=True, bootstrap=True)

    def _refresh_stale_in_background(self, meta: dict | None) -> bool:
        """Stale-while-revalidate: if any data is past its refresh interval, re-scrape it after
        answering. Returns True when a refresh was started, so the page can re-fetch shortly."""
        global _sync_running
        if not pipeline.due_steps(meta) or _sync_running or time.time() - _sync_failed_at < SYNC_RETRY_AFTER:
            return False
        _sync_running = True
        self.ctx.waitUntil(self._background_sync())
        return True

    async def _background_sync(self) -> None:
        global _sync_running, _sync_failed_at
        try:
            result = await pipeline.sync(self.io, self.store)
            if any("error" in (r or {}) for r in (result.get("results") or {}).values()):
                _sync_failed_at = time.time()
        except Exception as exc:  # noqa: BLE001
            print(f"background sync failed: {exc}")
            _sync_failed_at = time.time()
        finally:
            _sync_running = False

    def _refresh_live_in_background(self) -> None:
        global _last_live_refresh
        if time.time() - _last_live_refresh < LIVE_MIN_INTERVAL:
            return
        _last_live_refresh = time.time()
        self.ctx.waitUntil(pipeline.refresh_live(self.io, self.store))

    # ---------------------------------------------------------------- views

    async def overview(self) -> Response:
        s = self.store
        keys = ("meta", "team", "matches", "squad", "standings", "news")
        values = await s.many(*keys)
        if any(v is None for v in values):
            await self._fill()
            values = await s.many(*keys)
        meta, team, matches, squad, standings, news = values
        guide = await s.get("tv")
        matches = [with_tv(m, guide) for m in matches] if matches else matches
        refreshing = self._refresh_stale_in_background(meta)
        if not matches:
            return respond({"error": "no-data", "message": "No data yet. The Worker is fetching it; try again in a moment."}, 503)
        matches = sorted(matches, key=lambda m: m.get("date") or "")
        live = next((m for m in matches if m["status"].get("state") == "in"), None)
        played = [m for m in matches if m["status"].get("state") == "post"]
        upcoming = [m for m in matches if m["status"].get("state") == "pre"]
        last = None
        if played:
            last = await self._current(await s.get(f"match:{played[-1]['id']}")) or {"match": played[-1]}

        def leaders(key: str) -> list:
            ranked = [p for p in (squad or []) if (p["stats"].get(key) or 0) > 0]
            ranked.sort(key=lambda p: (-(p["stats"][key] or 0), p["stats"].get("apps") or 0))
            return ranked[:5]

        rows = (standings or {}).get("rows") or []
        idx = next((i for i, r in enumerate(rows) if r["team"]["id"] == FCB_ESPN_ID), -1)
        start = max(0, min(idx - 2, len(rows) - 5))
        around = rows[start:start + 5] if idx >= 0 else rows[:5]
        return respond({
            "meta": meta, "refreshing": refreshing, "team": team, "live": live, "next": upcoming[0] if upcoming else None, "last": last,
            "form": list(reversed(played[-5:])), "upcoming": upcoming[:5],
            "standings": {"league": standings.get("league"), "rows": around, "position": rows[idx] if idx >= 0 else None} if standings else None,
            "leaders": {"goals": leaders("goals"), "assists": leaders("assists")},
            "news": (news or [])[:6],
        }, max_age=5 if live else 60)

    async def listing(self, key: str) -> Response:
        meta, data = await self.store.many("meta", key)
        if data is None:
            await self._fill()
            meta, data = await self.store.many("meta", key)
        if data is None:
            return respond({"error": "no-data", "message": "No data yet. The Worker is fetching it; try again in a moment."}, 503)
        if key == "matches":
            guide = await self.store.get("tv")
            data = [with_tv(m, guide) for m in data]
        return respond({"meta": meta, "refreshing": self._refresh_stale_in_background(meta), key: data})

    async def _current(self, detail: dict | None) -> dict | None:
        """Re-parse a stored match detail written by an older parser version, before serving it."""
        if detail and detail.get("v") != espn.DETAIL_VERSION and detail.get("match"):
            try:
                detail = espn.parse_detail(await self.io.get_json(espn.summary_url(detail["match"])), detail["match"])
                await self.store.put(f"match:{detail['match']['id']}", detail)
            except Exception as exc:  # noqa: BLE001 - serve the old copy rather than nothing
                print(f"re-parse of {detail['match'].get('id')} failed: {exc}")
        return detail

    async def match_detail(self, match_id: str) -> Response:
        detail, guide, matches, history = await self.store.many(f"match:{match_id}", "tv", "matches", "history")
        detail = await self._current(detail)
        if not detail:
            match = next((m for m in matches or [] if m["id"] == match_id), None)
            if match:
                detail = {"match": match, "stats": [], "lineups": {}, "events": [], "officials": []}
            else:
                # An older result (head-to-head list): fetch its summary from ESPN on first view.
                past = next((m for season in ((history or {}).get("seasons") or {}).values() for m in season if m["id"] == match_id), None)
                if not past:
                    return respond({"error": "not-found", "message": f"No match with id {match_id}"}, 404)
                try:
                    detail = espn.parse_detail(await self.io.get_json(espn.summary_url(past)), past)
                    await self.store.put(f"match:{match_id}", detail)
                except Exception as exc:  # noqa: BLE001 - show the result without the details
                    print(f"summary of past match {match_id} failed: {exc}")
                    detail = {"match": past, "stats": [], "lineups": {}, "events": [], "officials": []}
        m = detail["match"]
        opp = m["away"] if m.get("fcbSide") == "home" else m["home"]
        detail = {**detail, "match": with_tv(m, guide),
                  "meetings": pipeline.meetings(opp["id"], history, matches, exclude=match_id),
                  "meetingsFrom": pipeline.HISTORY_FROM}
        live = m.get("status", {}).get("state") == "in"
        return respond(detail, max_age=5 if live else 120)

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
        # Not triggered by default (no cron in wrangler.jsonc: the Worker only runs while the site
        # is in use). Add a cron trigger to keep data fresh without visitors.
        live = await pipeline.refresh_live(self.io, self.store)
        result = await pipeline.sync(self.io, self.store)
        if live or result.get("ran"):
            print(f"cron: live={live} ran={result.get('ran')}")
