"""The sync pipeline: what to download, when, and what to store.

Transport and storage are injected, so the same code runs in the Cloudflare Python Worker (Workers
`fetch` + KV) and in the command-line scraper (Scrapling's Fetcher + a push to the Worker).
Every step is independent: one source failing never blocks the others.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any, Awaitable, Callable, Protocol

from . import espn, google_news, livescore, official, tv

MINUTE = 60


class IO(Protocol):
    async def get_json(self, url: str) -> Any: ...
    async def get_text(self, url: str) -> str: ...


class Store(Protocol):
    async def get(self, key: str) -> Any: ...
    async def put(self, key: str, value: Any) -> None: ...


# How often each step runs. Live matches are handled separately, every cron tick.
STEPS: dict[str, int] = {
    "fixtures": 30 * MINUTE,
    "details": 30 * MINUTE,
    "table": 30 * MINUTE,
    "squad": 6 * 60 * MINUTE,
    "news": 30 * MINUTE,
    "tv": 6 * 60 * MINUTE,
}
# Which data sources each step uses, for the per-source status shown in the footer.
STEP_SOURCES = {"fixtures": ["espn"], "details": ["espn"], "table": ["espn"], "squad": ["espn", "official"], "news": ["official", "google", "espn"], "tv": ["livesoccertv"]}
TV_UPCOMING = 6  # India TV listings are fetched for this many upcoming fixtures

# Live window: lineups appear about an hour before kick-off; extra time and penalties run long.
LIVE_BEFORE = timedelta(minutes=75)
LIVE_AFTER = timedelta(hours=3, minutes=30)
RECENT_DETAILS = None  # every completed match this season


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(dt: datetime) -> str:
    return dt.isoformat(timespec="seconds")


def _parse(ts: str | None) -> datetime | None:
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except ValueError:
        return None


async def _fixtures(io: IO, store: Store, status: dict) -> dict:
    team = espn.parse_team(await io.get_json(espn.URLS["team"]))
    results, fixtures = await io.get_json(espn.URLS["results"]), await io.get_json(espn.URLS["fixtures"])
    matches, season = espn.parse_matches(results, fixtures)
    # Keep live-refreshed state for fixtures the schedule endpoint hasn't caught up with yet.
    previous = {m["id"]: m for m in (await store.get("matches") or [])}
    for i, m in enumerate(matches):
        prev = previous.get(m["id"])
        if prev and prev["status"].get("state") == "in" and m["status"].get("state") == "pre":
            matches[i] = prev
    await store.put("team", team)
    await store.put("matches", matches)
    return {"season": season, "matches": len(matches)}


async def _details(io: IO, store: Store, status: dict, limit: int) -> dict:
    matches = await store.get("matches") or []
    done = [m for m in matches if m["status"].get("state") == "post"]
    if RECENT_DETAILS:
        done = done[-RECENT_DETAILS:]
    upcoming = [m for m in matches if m["status"].get("state") == "pre"][:1]
    todo = []
    for m in reversed(done):  # newest first, so a partial run covers the most relevant games
        stored = await store.get(f"match:{m['id']}")
        if (not stored or stored.get("v") != espn.DETAIL_VERSION
                or not stored.get("match", {}).get("status", {}).get("completed")):
            todo.append(m)
    remaining = max(0, len(todo) - limit)
    todo = todo[:limit] + upcoming
    for m in todo:
        detail = espn.parse_detail(await io.get_json(espn.summary_url(m)), m)
        await store.put(f"match:{m['id']}", detail)
    return {"fetched": len(todo), "remaining": remaining}


async def _table(io: IO, store: Store, status: dict) -> dict:
    table = espn.parse_standings(await io.get_json(espn.URLS["standings"]))
    await store.put("standings", table)
    return {"rows": len(table["rows"])}


async def _squad(io: IO, store: Store, status: dict) -> dict:
    squad = espn.parse_squad(await io.get_json(espn.URLS["squad"]))
    photos = 0
    try:
        photos = official.merge_into_squad(squad, official.parse_players(await io.get_text(official.PLAYERS)))
        status["official"] = {"ok": True}
    except Exception as exc:  # noqa: BLE001 - photos are optional; keep ESPN's squad
        status["official"] = {"ok": False, "detail": f"players: {exc}"[:300]}
        previous = {p["id"]: p for p in (await store.get("squad") or [])}
        for p in squad:  # keep photos from the last good run
            old = previous.get(p["id"]) or {}
            p["headshot"] = p.get("headshot") or old.get("headshot")
            p["profile"] = old.get("profile")
    await store.put("squad", squad)
    return {"players": len(squad), "photos": photos}


async def _tv(io: IO, store: Store, status: dict) -> dict:
    """Where to watch in India for the next fixtures (and any live one), keyed by match id."""
    matches = await store.get("matches") or []
    wanted = [m for m in matches if m["status"].get("state") == "in"]
    wanted += [m for m in matches if m["status"].get("state") == "pre"][:TV_UPCOMING]
    listings = tv.parse_team(await io.get_text(tv.TEAM_URL))
    guide = {k: v for k, v in (await store.get("tv") or {}).items() if k in {m["id"] for m in matches}}
    found = 0
    for m in wanted:
        item = tv.match_listing(m, listings)
        if not item:
            continue
        channels = tv.parse_country(await io.get_text(item["url"]))
        guide[m["id"]] = {"country": tv.COUNTRY, "channels": channels, "url": item["url"], "checked": _iso(_now())}
        found += bool(channels)
    await store.put("tv", guide)
    return {"matches": len(wanted), "with_channels": found}


async def _news(io: IO, store: Store, status: dict) -> dict:
    lists: dict[str, list] = {}
    for name, get in (
        ("official", lambda: io.get_text(official.NEWS)),
        ("google", lambda: io.get_text(google_news.FEED_URL)),
        ("espn", lambda: io.get_json(espn.URLS["news"])),
    ):
        try:
            payload = await get()
            lists[name] = {"official": official.parse_news, "google": google_news.parse_news, "espn": espn.parse_news}[name](payload)
            status.setdefault(name, {"ok": True})
        except Exception as exc:  # noqa: BLE001
            status[name] = {"ok": False, "detail": f"news: {exc}"[:300]}
    if not lists:
        raise RuntimeError("every news source failed")
    google = lists.get("google", [])
    if lists.get("official"):
        # The club's own stories come straight from fcbarcelona.com; drop Google's copies of them.
        google = [a for a in google if not (a.get("source") or "").lower().startswith("fc barcelona")]
    news = lists.get("official", []) + google + lists.get("espn", [])
    news.sort(key=lambda a: a.get("published") or "", reverse=True)
    await store.put("news", news)
    return {"articles": len(news)}


def due_steps(meta: dict | None, now: datetime | None = None) -> list[str]:
    """Steps whose data is older than their refresh interval (or has backfill work left)."""
    meta = meta or {}
    last, now = meta.get("sync") or {}, now or _now()
    pending = set(meta.get("pending") or [])
    return [s for s, every in STEPS.items()
            if s in pending or not (_parse(last.get(s)) and now - _parse(last[s]) < timedelta(seconds=every))]


async def sync(io: IO, store: Store, *, force: bool = False, bootstrap: bool = False, only: list[str] | None = None,
               max_steps: int | None = None, details_per_run: int = 1000, log: Callable[[str], None] = print) -> dict:
    """Run the steps that are due (all of them with force) and update meta.

    `only` limits which steps may run; `max_steps` caps how many run in this invocation and
    `details_per_run` how many match summaries are fetched. By default everything due runs at once
    (Workers Paid). On Workers Free (10 ms CPU per invocation) pass max_steps=1, details_per_run=2
    to spread the work across cron ticks."""
    meta = await store.get("meta") or {}
    last = meta.get("sync") or {}
    now = _now()
    pending = set(meta.get("pending") or [])  # steps with backfill work left (match details)
    due = list(STEPS) if force else due_steps(meta, now)
    if only is not None:
        due = [s for s in due if s in only]
    if max_steps is not None:
        # Steps that never ran first, then regular refreshes, then backfill; oldest first within each.
        rank = lambda s: (0 if s not in last else 2 if s in pending else 1, last.get(s) or "")  # noqa: E731
        due = sorted(due, key=rank)[:max_steps]
    if not due:
        return {"ran": []}
    status: dict[str, dict] = {}
    results: dict[str, Any] = {}
    runners: dict[str, Callable[[], Awaitable[dict]]] = {
        "fixtures": lambda: _fixtures(io, store, status),
        "details": lambda: _details(io, store, status, details_per_run),
        "table": lambda: _table(io, store, status),
        "squad": lambda: _squad(io, store, status),
        "news": lambda: _news(io, store, status),
        "tv": lambda: _tv(io, store, status),
    }
    for step in due:
        try:
            results[step] = await runners[step]()
            last[step] = _iso(now)
            # A step with work left over (details backfill) stays due for the next tick.
            (pending.add if results[step].get("remaining") else pending.discard)(step)
            for src in STEP_SOURCES[step]:
                status.setdefault(src, {"ok": True})
        except Exception as exc:  # noqa: BLE001 - reported in meta, retried next tick
            log(f"sync step {step} failed: {exc}")
            results[step] = {"error": str(exc)[:300]}
            for src in STEP_SOURCES[step][:1]:
                status[src] = {"ok": False, "detail": f"{step}: {exc}"[:300]}
    sources = {**(meta.get("sources") or {}), **status}
    season = (results.get("fixtures") or {}).get("season") or meta.get("season")
    await store.put("meta", {**meta, "updatedAt": _iso(now), "season": season, "sources": sources, "sync": last,
                             "pending": sorted(pending),
                             "scraper": "Scrapling (Cloudflare Python Worker)"})
    return {"ran": due, "results": results}


def in_live_window(matches: list[dict], now: datetime | None = None) -> list[dict]:
    now = now or _now()
    out = []
    for m in matches:
        kickoff = _parse(m.get("date"))
        if not kickoff or not (kickoff - LIVE_BEFORE <= now <= kickoff + LIVE_AFTER):
            continue
        if m["status"].get("completed") and now > kickoff + timedelta(hours=2, minutes=30):
            continue
        out.append(m)
    return sorted(out, key=lambda m: m["date"])


async def refresh_match(io: IO, store: Store, base: dict, log: Callable[[str], None] = print) -> dict:
    """Refresh one fixture from ESPN (LiveScore fallback). Writes only on change, or every 3 minutes."""
    prev = await store.get(f"match:{base['id']}")
    source = "ESPN"
    try:
        detail = espn.parse_detail(await io.get_json(espn.summary_url(base)), base)
    except Exception as exc:  # noqa: BLE001
        log(f"ESPN live fetch failed, trying LiveScore: {exc}")
        patched = livescore.patch(base, await io.get_json(livescore.url_for(base)))
        if not patched:
            raise
        detail = {**(prev or {"stats": [], "lineups": {}, "events": [], "officials": []}), "match": patched}
        source = "LiveScore"
    strip = lambda d: d and {k: v for k, v in d.items() if k not in ("liveUpdatedAt", "liveSource")}  # noqa: E731
    changed = strip(prev) != strip(detail)
    now = _now()
    detail = {**detail, "liveUpdatedAt": _iso(now), "liveSource": f"{source}, fetched by the Worker"}
    stale = not prev or not _parse(prev.get("liveUpdatedAt")) or now - _parse(prev["liveUpdatedAt"]) > timedelta(minutes=3)
    if changed or stale:
        await store.put(f"match:{base['id']}", detail)
    if changed:
        matches = await store.get("matches") or []
        for i, m in enumerate(matches):
            if m["id"] == base["id"]:
                matches[i] = detail["match"]
                await store.put("matches", matches)
                break
    return detail


async def refresh_live(io: IO, store: Store, log: Callable[[str], None] = print) -> int:
    due = in_live_window(await store.get("matches") or [])
    count = 0
    for m in due:
        try:
            await refresh_match(io, store, m, log)
            count += 1
        except Exception as exc:  # noqa: BLE001
            log(f"live refresh of {m['id']} failed: {exc}")
    return count
