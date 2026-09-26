"""Where to watch in India, from LiveSoccerTV's per-country TV listings.

The team page lists upcoming Barcelona matches with kick-off timestamps; each match page lists
broadcasters for every country. Pure parsing with Scrapling's Selector: callers download TEAM_URL
and the match pages themselves.
"""

from __future__ import annotations

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
