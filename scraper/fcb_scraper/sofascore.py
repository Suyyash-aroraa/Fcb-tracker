"""SofaScore adapter: player ratings and expected goals, merged into ESPN match details.

SofaScore only answers a real browser, so this opens the team page in Scrapling's
StealthyFetcher and calls SofaScore's JSON API from inside that page. SofaScore also
blocks many datacenter IP ranges outright (HTTP 403 from its Varnish edge regardless of
client). If that happens, set SCRAPER_PROXY to a residential proxy or run from a home
connection. The rest of the app works without this source.
"""

from __future__ import annotations

import unicodedata
from datetime import datetime, timezone

from . import FCB_SOFASCORE_ID
from .fetch import SourceError, browser_session_json

TEAM_PAGE = f"https://www.sofascore.com/football/team/barcelona/{FCB_SOFASCORE_ID}"


def _norm(name: str | None) -> str:
    text = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode()
    return " ".join(text.lower().replace("-", " ").split())


def _same_player(a: str, b: str) -> bool:
    a, b = _norm(a), _norm(b)
    if not a or not b:
        return False
    return a == b or a.split()[-1] == b.split()[-1] and a[0] == b[0]


def _day(ts: int | str | None) -> str | None:
    if isinstance(ts, (int, float)):
        return datetime.fromtimestamp(ts, tz=timezone.utc).date().isoformat()
    if isinstance(ts, str):
        return ts[:10]
    return None


def _find_event(events: list[dict], match: dict) -> dict | None:
    day = _day(match.get("date"))
    opponent = match["away"]["name"] if match["fcbSide"] == "home" else match["home"]["name"]
    for ev in events:
        if _day(ev.get("startTimestamp")) != day:
            continue
        names = {_norm((ev.get("homeTeam") or {}).get("name")), _norm((ev.get("awayTeam") or {}).get("name"))}
        opp = _norm(opponent)
        if any(opp and (opp in n or n in opp) for n in names):
            return ev
    return None


def enrich(details: list[dict]) -> int:
    """Add SofaScore ratings and xG to ESPN match details in place. Returns the number enriched."""
    paths = {"last": f"/api/v1/team/{FCB_SOFASCORE_ID}/events/last/0",
             "next": f"/api/v1/team/{FCB_SOFASCORE_ID}/events/next/0"}
    first = browser_session_json(TEAM_PAGE, paths)
    for key in paths:
        if isinstance(first.get(key), Exception):
            raise first[key]
    events = (first["last"].get("events") or []) + (first["next"].get("events") or [])

    wanted: dict[str, dict] = {}
    for detail in details:
        ev = _find_event(events, detail["match"])
        if ev:
            wanted[str(ev["id"])] = detail
    if not wanted:
        return 0

    per_event = {}
    for ev_id in wanted:
        per_event[f"{ev_id}:lineups"] = f"/api/v1/event/{ev_id}/lineups"
        per_event[f"{ev_id}:stats"] = f"/api/v1/event/{ev_id}/statistics"
    payloads = browser_session_json(TEAM_PAGE, per_event)

    enriched = 0
    for ev_id, detail in wanted.items():
        lineups = payloads.get(f"{ev_id}:lineups")
        stats = payloads.get(f"{ev_id}:stats")
        touched = False
        if isinstance(lineups, dict):
            for side in ("home", "away"):
                sofa_players = ((lineups.get(side) or {}).get("players")) or []
                ours = detail["lineups"].get(side) or {}
                for player in (ours.get("starters") or []) + (ours.get("subs") or []):
                    for sp in sofa_players:
                        rating = (sp.get("statistics") or {}).get("rating")
                        if rating and _same_player(player["name"], (sp.get("player") or {}).get("name")):
                            player["rating"] = round(float(rating), 1)
                            touched = True
                            break
        if isinstance(stats, dict):
            for period in stats.get("statistics") or []:
                if period.get("period") != "ALL":
                    continue
                for group in period.get("groups") or []:
                    for item in group.get("statisticsItems") or []:
                        if (item.get("key") == "expectedGoals" or item.get("name") == "Expected goals") and "home" in item:
                            try:
                                detail["stats"].insert(1, {"key": "xg", "label": "Expected goals (xG)", "type": "decimal",
                                                           "home": float(item.get("homeValue", item["home"])),
                                                           "away": float(item.get("awayValue", item["away"]))})
                                touched = True
                            except (TypeError, ValueError):
                                pass
        if touched:
            detail["sofascoreId"] = ev_id
            enriched += 1
    if not enriched:
        raise SourceError("SofaScore matched no events")
    return enriched
