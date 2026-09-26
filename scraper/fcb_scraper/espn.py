"""ESPN adapter: fixtures, results, match summaries, squad and standings.

ESPN's site API is fetched with Scrapling's impersonating `Fetcher`, since a plain
HTTP client gets an Akamai 403. Everything is normalised into the app's own schema.
"""

from __future__ import annotations

from typing import Any

from . import FCB_ESPN_ID
from .fetch import get_json

SITE = "https://site.api.espn.com/apis/site/v2/sports/soccer"
STANDINGS = "https://site.api.espn.com/apis/v2/sports/soccer/esp.1/standings"

# Team stat keys worth showing, in display order. `pair` stats render as "a/b".
TEAM_STATS: list[tuple[str, str, str]] = [
    ("possessionPct", "Possession", "pct"),
    ("totalShots", "Shots", "count"),
    ("shotsOnTarget", "Shots on target", "count"),
    ("blockedShots", "Blocked shots", "count"),
    ("wonCorners", "Corners", "count"),
    ("totalPasses", "Passes", "count"),
    ("passAccuracy", "Pass accuracy", "pct"),
    ("totalCrosses", "Crosses", "count"),
    ("totalLongBalls", "Long balls", "count"),
    ("totalTackles", "Tackles", "count"),
    ("interceptions", "Interceptions", "count"),
    ("totalClearance", "Clearances", "count"),
    ("saves", "Saves", "count"),
    ("foulsCommitted", "Fouls", "count"),
    ("offsides", "Offsides", "count"),
    ("yellowCards", "Yellow cards", "count"),
    ("redCards", "Red cards", "count"),
]

EVENT_KINDS = {
    "goal": "goal",
    "penalty---scored": "goal",
    "own-goal": "own-goal",
    "yellow-card": "yellow",
    "red-card": "red",
    "substitution": "sub",
    "penalty---missed": "pen-miss",
    "penalty---saved": "pen-miss",
    "halftime": "period",
    "end-regular-time": "period",
}


def _num(value: Any) -> float | None:
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def _int(value: Any) -> int | None:
    n = _num(value)
    return int(n) if n is not None else None


def _logos(team: dict) -> tuple[str | None, str | None]:
    light = dark = None
    for logo in team.get("logos") or []:
        rel = logo.get("rel") or []
        if "dark" in rel:
            dark = logo.get("href")
        elif light is None:
            light = logo.get("href")
    if not light and team.get("logo"):
        light = team["logo"]
    return light, dark or light


def _team_ref(competitor: dict) -> dict:
    team = competitor.get("team") or {}
    logo, logo_dark = _logos(team)
    score = competitor.get("score")
    if isinstance(score, dict):
        score_val, shootout = _int(score.get("value")), _int(score.get("shootoutScore"))
    else:
        score_val, shootout = _int(score), _int(competitor.get("shootoutScore"))
    return {
        "id": str(team.get("id") or competitor.get("id")),
        "name": team.get("displayName") or team.get("name"),
        "short": team.get("shortDisplayName") or team.get("displayName"),
        "abbr": team.get("abbreviation"),
        "logo": logo,
        "logoDark": logo_dark,
        "score": score_val,
        "shootout": shootout,
        "winner": competitor.get("winner"),
    }


def _status(status: dict) -> dict:
    t = status.get("type") or {}
    return {
        "state": t.get("state"),  # pre | in | post
        "completed": bool(t.get("completed")),
        "name": t.get("name"),
        "detail": t.get("detail") or t.get("description"),
        "short": t.get("shortDetail"),
        "clock": status.get("displayClock"),
    }


def normalize_event(event: dict) -> dict:
    comp = (event.get("competitions") or [{}])[0]
    competitors = comp.get("competitors") or []
    home = next((c for c in competitors if c.get("homeAway") == "home"), competitors[0] if competitors else {})
    away = next((c for c in competitors if c.get("homeAway") == "away"), competitors[-1] if competitors else {})
    home_ref, away_ref = _team_ref(home), _team_ref(away)
    fcb_side = "home" if home_ref["id"] == FCB_ESPN_ID else "away"
    status = _status(comp.get("status") or event.get("status") or {})

    result = None
    if status["completed"] and home_ref["score"] is not None and away_ref["score"] is not None:
        us, them = (home_ref, away_ref) if fcb_side == "home" else (away_ref, home_ref)
        if us["score"] != them["score"]:
            result = "W" if us["score"] > them["score"] else "L"
        elif us["shootout"] is not None and them["shootout"] is not None:
            result = "W" if us["shootout"] > them["shootout"] else "L"
        else:
            result = "D"

    league = event.get("league") or {}
    venue = comp.get("venue") or {}
    notes = [n.get("headline") for n in comp.get("notes") or [] if n.get("headline")]
    broadcasts = []
    for b in comp.get("broadcasts") or []:
        name = (b.get("media") or {}).get("shortName") or (b.get("names") or [None])[0]
        if name and name not in broadcasts:
            broadcasts.append(name)

    return {
        "id": str(event["id"]),
        "date": comp.get("date") or event.get("date"),
        "competition": {
            "id": str(league.get("id") or ""),
            "name": league.get("name") or (event.get("seasonType") or {}).get("name"),
            "slug": league.get("slug"),
        },
        "note": notes[0] if notes else None,
        "venue": {"name": venue.get("fullName"), "city": (venue.get("address") or {}).get("city")},
        "attendance": comp.get("attendance") or None,
        "status": status,
        "home": home_ref,
        "away": away_ref,
        "fcbSide": fcb_side,
        "result": result,
        "broadcasts": broadcasts[:4],
    }


def fetch_team() -> dict:
    team = get_json(f"{SITE}/esp.1/teams/{FCB_ESPN_ID}")["team"]
    logo, logo_dark = _logos(team)
    record = ((team.get("record") or {}).get("items") or [{}])[0]
    return {
        "id": str(team["id"]),
        "name": team.get("displayName"),
        "short": team.get("shortDisplayName"),
        "abbr": team.get("abbreviation"),
        "logo": logo,
        "logoDark": logo_dark,
        "color": team.get("color"),
        "altColor": team.get("alternateColor"),
        "record": record.get("summary"),
        "standingSummary": team.get("standingSummary"),
    }


def fetch_matches() -> tuple[list[dict], str | None]:
    """All competitions: completed results plus upcoming fixtures, de-duplicated and sorted by date."""
    results = get_json(f"{SITE}/all/teams/{FCB_ESPN_ID}/schedule")
    fixtures = get_json(f"{SITE}/all/teams/{FCB_ESPN_ID}/schedule?fixture=true")
    season = (results.get("season") or {}).get("displayName")
    by_id: dict[str, dict] = {}
    for payload in (results, fixtures):
        for event in payload.get("events") or []:
            match = normalize_event(event)
            by_id[match["id"]] = match
    matches = sorted(by_id.values(), key=lambda m: m["date"] or "")
    return matches, season


def _player(entry: dict) -> dict:
    athlete = entry.get("athlete") or {}
    stats = {s.get("name"): _int(s.get("value")) for s in entry.get("stats") or []}
    sub_minute = None
    for play in entry.get("plays") or []:
        if play.get("substitution"):
            sub_minute = (play.get("clock") or {}).get("displayValue")
    return {
        "id": str(athlete.get("id")),
        "name": athlete.get("displayName"),
        "short": athlete.get("shortName") or athlete.get("displayName"),
        "number": entry.get("jersey"),
        "pos": (entry.get("position") or {}).get("abbreviation"),
        "place": _int(entry.get("formationPlace")) or 0,
        "starter": bool(entry.get("starter")),
        "subbedIn": bool(entry.get("subbedIn")),
        "subbedOut": bool(entry.get("subbedOut")),
        "subMinute": sub_minute,
        "stats": {
            "goals": stats.get("totalGoals"),
            "assists": stats.get("goalAssists"),
            "shots": stats.get("totalShots"),
            "shotsOnTarget": stats.get("shotsOnTarget"),
            "saves": stats.get("saves"),
            "yellow": stats.get("yellowCards"),
            "red": stats.get("redCards"),
            "fouls": stats.get("foulsCommitted"),
            "ownGoals": stats.get("ownGoals"),
        },
    }


def _team_stats(boxscore: dict, home_id: str) -> list[dict]:
    teams = boxscore.get("teams") or []
    if len(teams) != 2:
        return []
    raw = {}
    for t in teams:
        side = "home" if str((t.get("team") or {}).get("id")) == home_id else "away"
        raw[side] = {s.get("name"): _num(s.get("displayValue")) for s in t.get("statistics") or []}
    if set(raw) != {"home", "away"}:
        return []
    for side in raw.values():
        acc, tot = side.get("accuratePasses"), side.get("totalPasses")
        side["passAccuracy"] = round(100 * acc / tot, 1) if acc is not None and tot else None
    rows = []
    for key, label, kind in TEAM_STATS:
        h, a = raw["home"].get(key), raw["away"].get(key)
        if h is None and a is None:
            continue
        rows.append({"key": key, "label": label, "type": kind, "home": h, "away": a})
    return rows


def _events(key_events: list[dict], home_id: str) -> list[dict]:
    out = []
    for ev in key_events:
        kind = EVENT_KINDS.get((ev.get("type") or {}).get("type") or "")
        if kind is None:
            continue
        type_text = (ev.get("type") or {}).get("text") or ""
        if kind == "goal" and "own goal" in (ev.get("text") or "").lower():
            kind = "own-goal"
        team_id = str((ev.get("team") or {}).get("id") or "")
        players = [((p.get("athlete") or {}).get("displayName")) for p in ev.get("participants") or []]
        out.append({
            "id": str(ev.get("id")),
            "kind": kind,
            "label": type_text,
            "minute": (ev.get("clock") or {}).get("displayValue") or "",
            "period": (ev.get("period") or {}).get("number"),
            "side": ("home" if team_id == home_id else "away") if team_id else None,
            "players": [p for p in players if p],
            "text": ev.get("text"),
            "penalty": "penalty" in type_text.lower(),
        })
    return out


def fetch_match_detail(match: dict) -> dict:
    slug = match["competition"].get("slug") or "all"
    summary = get_json(f"{SITE}/{slug}/summary?event={match['id']}")
    header = ((summary.get("header") or {}).get("competitions") or [{}])[0]
    home_id = match["home"]["id"]

    # The header carries fresher status/score than the schedule list during live games.
    if header.get("competitors"):
        live = normalize_event({"id": match["id"], "league": {"id": match["competition"]["id"],
                                "name": match["competition"]["name"], "slug": slug},
                                "competitions": [header]})
        for key in ("status", "home", "away", "result"):
            match = {**match, key: live[key] if live[key] is not None else match[key]}

    lineups = {}
    for roster in summary.get("rosters") or []:
        side = "home" if str((roster.get("team") or {}).get("id")) == home_id else "away"
        players = [_player(p) for p in roster.get("roster") or []]
        lineups[side] = {
            "formation": roster.get("formation"),
            "starters": sorted([p for p in players if p["starter"]], key=lambda p: p["place"]),
            "subs": [p for p in players if not p["starter"]],
        }

    info = summary.get("gameInfo") or {}
    officials = [
        {"name": o.get("displayName"), "role": (o.get("position") or {}).get("displayName")}
        for o in info.get("officials") or []
    ]

    return {
        "match": match,
        "stats": _team_stats(summary.get("boxscore") or {}, home_id),
        "lineups": lineups,
        "events": _events(summary.get("keyEvents") or [], home_id),
        "officials": officials,
        "attendance": info.get("attendance") or match.get("attendance"),
        "venue": ((info.get("venue") or {}).get("fullName")) or match["venue"]["name"],
    }


def fetch_squad() -> list[dict]:
    data = get_json(f"{SITE}/esp.1/teams/{FCB_ESPN_ID}/roster")
    squad = []
    for a in data.get("athletes") or []:
        stats: dict[str, int | None] = {}
        for cat in ((a.get("statistics") or {}).get("splits") or {}).get("categories") or []:
            for s in cat.get("stats") or []:
                stats[s.get("name")] = _int(s.get("value"))
        pos = a.get("position") or {}
        squad.append({
            "id": str(a.get("id")),
            "name": a.get("displayName"),
            "short": a.get("shortName"),
            "number": a.get("jersey"),
            "pos": pos.get("abbreviation"),
            "posName": pos.get("displayName"),
            "age": a.get("age"),
            "nationality": a.get("citizenship"),
            "flag": (a.get("flag") or {}).get("href"),
            "headshot": (a.get("headshot") or {}).get("href"),
            "injured": bool(a.get("injuries")),
            "stats": {
                "apps": stats.get("appearances"),
                "subIns": stats.get("subIns"),
                "goals": stats.get("totalGoals"),
                "assists": stats.get("goalAssists"),
                "shots": stats.get("totalShots"),
                "shotsOnTarget": stats.get("shotsOnTarget"),
                "yellow": stats.get("yellowCards"),
                "red": stats.get("redCards"),
                "fouls": stats.get("foulsCommitted"),
                "saves": stats.get("saves"),
                "conceded": stats.get("goalsConceded"),
            },
        })
    return squad


def fetch_standings() -> dict:
    data = get_json(STANDINGS)
    group = (data.get("children") or [{}])[0]
    rows = []
    for entry in (group.get("standings") or {}).get("entries") or []:
        stats = {s.get("name"): s for s in entry.get("stats") or []}
        team = entry.get("team") or {}
        logo, logo_dark = _logos(team)

        def val(name: str) -> int | None:
            return _int((stats.get(name) or {}).get("value"))

        rows.append({
            "rank": val("rank"),
            "team": {"id": str(team.get("id")), "name": team.get("displayName"),
                     "short": team.get("shortDisplayName"), "abbr": team.get("abbreviation"),
                     "logo": logo, "logoDark": logo_dark},
            "played": val("gamesPlayed"),
            "won": val("wins"),
            "drawn": val("ties"),
            "lost": val("losses"),
            "gf": val("pointsFor"),
            "ga": val("pointsAgainst"),
            "gd": val("pointDifferential"),
            "points": val("points"),
            "note": ((entry.get("note") or {}).get("description")),
        })
    rows.sort(key=lambda r: r["rank"] or 99)
    return {"league": group.get("name") or data.get("name"), "season": (group.get("standings") or {}).get("seasonDisplayName"), "rows": rows}


def fetch_news() -> list[dict]:
    data = get_json(f"{SITE}/esp.1/news?team={FCB_ESPN_ID}")
    out = []
    for a in data.get("articles") or []:
        link = ((a.get("links") or {}).get("web") or {}).get("href")
        image = next((i.get("url") for i in a.get("images") or [] if i.get("url")), None)
        if not link:
            continue
        out.append({
            "title": a.get("headline"),
            "summary": a.get("description"),
            "url": link,
            "image": image,
            "source": "ESPN",
            "published": a.get("published"),
            "origin": "espn",
        })
    return out
