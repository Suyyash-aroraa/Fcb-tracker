"""Where to watch in India, from LiveSoccerTV's per-country TV listings.

The team page lists upcoming Barcelona matches with kick-off timestamps; each match page lists
broadcasters for every country. Pure parsing with Scrapling's Selector: callers download TEAM_URL
and the match pages themselves.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone

from scrapling.parser import Selector

BASE = "https://www.livesoccertv.com"
# No trailing slash: the slash variant answers plain (non-browser) requests with 403.
TEAM_URL = f"{BASE}/teams/spain/barcelona"
COUNTRY = "India"
MATCH_WINDOW_S = 2 * 3600  # kick-off times must agree within this to be the same fixture


def parse_team(html: str) -> list[dict]:
    """Upcoming and recent Barcelona listings: [{url, kickoff (ISO), title}]."""
    out, seen = [], set()
    for row in Selector(html).css("tr.matchrow"):
        links = row.css("a[href^='/match/']")
        stamps = row.css(".ts[dv]")
        if not links or not stamps:
            continue
        href = (links[0].attrib.get("href") or "").split("#")[0]
        try:
            kickoff = datetime.fromtimestamp(int(stamps[0].attrib["dv"]) / 1000, tz=timezone.utc)
        except (KeyError, ValueError):
            continue
        if href in seen:
            continue
        seen.add(href)
        out.append({"url": BASE + href, "kickoff": kickoff.isoformat(timespec="minutes"),
                    "title": links[0].attrib.get("title") or ""})
    return out


def parse_country(html: str, country: str = COUNTRY) -> list[str]:
    """Broadcaster names listed for one country on a match page."""
    for row in Selector(html).css("tr"):
        flag = row.css("span.flag")
        if flag and " ".join(flag[0].css("::text").getall()).strip() == country:
            cells = row.css("td")
            names = [t.strip() for t in cells[-1].css("a::text").getall() if t.strip()] if len(cells) > 1 else []
            return list(dict.fromkeys(names))
    return []


def _norm(text: str) -> str:
    return "".join(ch for ch in (text or "").lower() if ch.isalnum())


def match_listing(match: dict, listings: list[dict]) -> dict | None:
    """The listing for one of our fixtures: kick-off within MATCH_WINDOW_S and the opponent named."""
    try:
        kickoff = datetime.fromisoformat(match["date"].replace("Z", "+00:00"))
    except (KeyError, ValueError):
        return None
    opponent = match["away"] if match.get("fcbSide") == "home" else match["home"]
    names = {_norm(opponent.get("name")), _norm(opponent.get("short")), _norm(opponent.get("abbr"))} - {""}
    for item in listings:
        delta = abs((datetime.fromisoformat(item["kickoff"]) - kickoff).total_seconds())
        title = _norm(item["title"])
        if delta <= MATCH_WINDOW_S and any(n in title or title.find(n[:6]) >= 0 for n in names if len(n) >= 3):
            return item
    return None


# FanCode streams LALIGA in India, and may carry the Copa del Rey and Supercopa. Its pages answer
# requests from Cloudflare (LiveSoccerTV refuses Cloudflare's IPs), so it is the fallback for these
# competitions. Tournaments ("tours") are discovered from its football page, so a new season or a
# newly added competition needs no code change.
FANCODE_BASE = "https://www.fancode.com"
FANCODE_FOOTBALL = f"{FANCODE_BASE}/football"
# ESPN competition slug -> tour slug pattern on FanCode.
FANCODE_TOURS = {
    "esp.1": re.compile(r"^laliga-\d"),  # not laliga-hypermotion (second division)
    "esp.copa_del_rey": re.compile(r"copa-del-rey"),
    "esp.super_cup": re.compile(r"super-?copa|super-cup"),
}


def parse_fancode_tours(html: str) -> dict[str, list[str]]:
    """{ESPN competition slug: [FanCode matches URLs]} for the competitions FanCode lists right now.

    FanCode can list one competition under several tour slugs (e.g. laliga-2026-27-… and
    laliga-202627-…), each with part of the schedule, so every one is kept.
    """
    tours: dict[str, list[str]] = {}
    for a in Selector(html).css("a[href*='/football/tour/']"):
        slug = (a.attrib.get("href") or "").split("/football/tour/")[-1].split("/")[0].split("?")[0]
        if not slug or "women" in slug:
            continue
        url = f"{FANCODE_BASE}/football/tour/{slug}/matches"
        for comp, pattern in FANCODE_TOURS.items():
            if pattern.search(slug) and url not in tours.setdefault(comp, []):
                tours[comp].append(url)
    return {comp: urls for comp, urls in tours.items() if urls}


_FC_ID = re.compile(r'\{"id":(\d+),"teamType"')
_FC_SLUG = re.compile(r'"matchSlug":"([a-z0-9-]+)"')
_FC_START = re.compile(r'"startTime":"([^"]+)"')
_FC_TOUR = re.compile(r'"collectionId":(\d+)[^{}]*?"collectionSlug":"([a-z0-9-]+)"')


def _is_barca(slug: str) -> bool:
    return "barcelona" in slug and "women" not in slug and "femeni" not in slug


def parse_fancode(html: str, competition: str, tour_url: str = "") -> list[dict]:
    """Barcelona fixtures on a FanCode tour's schedule: [{url, slug, kickoff, competition}].

    The page embeds its schedule as JSON (only some fixtures also get links), so both are read.
    """
    tour_slug = tour_url.split("/football/tour/")[-1].split("/")[0] if tour_url else ""
    out, seen = [], set()
    for m in _FC_SLUG.finditer(html):
        slug = m.group(1)
        ids = list(_FC_ID.finditer(html, max(0, m.start() - 3000), m.start()))
        if not _is_barca(slug) or not ids:
            continue
        full = f"{slug}-{ids[-1].group(1)}"
        if full in seen:
            continue
        seen.add(full)
        after = html[m.end():m.end() + 1500]
        tour = _FC_TOUR.search(after)
        start = _FC_START.search(after)
        ts = f"{tour.group(2)}-{tour.group(1)}" if tour else tour_slug
        out.append({"url": f"{FANCODE_BASE}/football/tour/{ts}/matches/{full}/live-match-info", "slug": full,
                    "kickoff": start.group(1) if start else None, "competition": competition})
    for a in Selector(html).css("a[href*='/matches/']"):
        href = (a.attrib.get("href") or "").split("?")[0]
        slug = href.rstrip("/").split("/matches/")[-1].split("/")[0]
        if not _is_barca(slug) or slug in seen:
            continue
        seen.add(slug)
        out.append({"url": href if href.startswith("http") else FANCODE_BASE + href, "slug": slug,
                    "kickoff": None, "competition": competition})
    return out


def match_fancode(match: dict, items: list[dict]) -> dict | None:
    """The FanCode fixture for one of ours: same competition, opponent named, kick-off agreeing if known."""
    comp = (match.get("competition") or {}).get("slug")
    opponent = match["away"] if match.get("fcbSide") == "home" else match["home"]
    names = [n for n in (_norm(opponent.get("short")), _norm(opponent.get("name"))) if len(n) >= 4]
    try:
        kickoff = datetime.fromisoformat(match["date"].replace("Z", "+00:00"))
    except (KeyError, ValueError):
        kickoff = None
    for item in items:
        if item["competition"] != comp:
            continue
        if kickoff and item.get("kickoff"):
            try:
                when = datetime.fromisoformat(item["kickoff"].replace("Z", "+00:00"))
            except ValueError:
                when = None
            # The reverse fixture has the same opponent; only the kick-off tells them apart.
            if when and abs((when - kickoff).total_seconds()) > MATCH_WINDOW_S:
                continue
        slug = _norm(item["slug"].replace("barcelona", ""))
        if any(n[:6] in slug for n in names):
            return item
    return None
