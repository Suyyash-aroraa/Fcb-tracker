"""fcbarcelona.com adapter: official player photos and profile links, and first-team news.

The official site serves plain HTML to Scrapling's impersonating Fetcher; everything is read
with CSS selectors from the server-rendered markup.
"""

from __future__ import annotations

import re
import unicodedata
from datetime import datetime, timedelta, timezone

from scrapling.parser import Selector

from .fetch import get

BASE = "https://www.fcbarcelona.com"
PLAYERS = f"{BASE}/en/football/first-team/players"
NEWS = f"{BASE}/en/football/first-team/news"


def _text(node, selector: str) -> str:
    return " ".join(t.strip() for t in node.css(f"{selector} ::text").getall() if t.strip())


def _norm(name: str) -> str:
    text = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode().lower()
    return " ".join(text.replace("-", " ").split())


def _photo(node, size: int) -> str | None:
    pics = node.css("[data-img-src]")
    src = pics[0].attrib.get("data-img-src") if pics else None
    if not src:
        srcs = node.css("[data-image-src]")
        src = srcs[0].attrib.get("data-image-src", "").split("?")[0].split(",")[0].strip() or None if srcs else None
    return f"{src}?width={size}&height={size}" if src else None


def fetch_players() -> list[dict]:
    page = Selector(get(PLAYERS))
    players = []
    for card in page.css("a.team-person"):
        first = _text(card, ".team-person__first-name")
        last = _text(card, ".team-person__last-name")
        number = _text(card, ".team-person__number")
        if not (first or last):
            continue
        href = card.attrib.get("href") or ""
        players.append({
            "name": f"{first} {last}".strip(),
            "last": last,
            "number": number or None,
            "position": _text(card, ".team-person__position-meta") or None,
            "photo": _photo(card, 240),
            "url": href if href.startswith("http") else BASE + href,
        })
    return players


def merge_into_squad(squad: list[dict], official: list[dict]) -> int:
    """Attach official photos and profile links to ESPN squad entries. Returns how many matched."""
    by_number = {p["number"]: p for p in official if p["number"]}
    matched = 0
    for p in squad:
        o = by_number.get(str(p.get("number") or ""))
        # Shirt numbers change between seasons; require the surname to agree as well.
        if not o or _norm(o["last"]).split()[-1:] != _norm(p["name"]).split()[-1:] and _norm(o["last"]) not in _norm(p["name"]):
            o = next((x for x in official if _norm(x["name"]) == _norm(p["name"])), None)
        if o:
            p["headshot"] = o["photo"] or p.get("headshot")
            p["profile"] = o["url"]
            matched += 1
    return matched


def _published(label: str, now: datetime) -> str | None:
    """The listing shows relative times ("5 hours ago") or dates ("23 Sep 2026")."""
    label = label.strip().lower()
    units = {"min": "minutes", "minute": "minutes", "hr": "hours", "hour": "hours", "day": "days", "week": "weeks"}
    m = re.search(r"(\d+)\s*(min|minute|hr|hour|day|week)s?\s+ago", label)
    if m:
        return (now - timedelta(**{units[m.group(2)]: int(m.group(1))})).isoformat(timespec="minutes")
    label = re.sub(r"^.*published date\s*", "", label)
    for fmt in ("%d %b %y", "%d %b %Y", "%d %B %Y", "%b %d, %Y"):
        try:
            return datetime.strptime(label, fmt).replace(tzinfo=timezone.utc).isoformat(timespec="minutes")
        except ValueError:
            pass
    return None


def fetch_news(limit: int = 20) -> list[dict]:
    page = Selector(get(NEWS))
    now = datetime.now(timezone.utc)
    out, seen = [], {}
    for card in page.css("a.news-hero, a.thumbnail--news"):
        href = card.attrib.get("href") or ""
        if "/news/" not in href:
            continue
        if href in seen:
            # The lead story repeats further down, sometimes with the image the hero lacked.
            seen[href]["image"] = seen[href]["image"] or _photo(card, 800)
            continue
        title = _text(card, ".thumbnail__title") or _text(card, ".news-hero__title")
        if not title:
            continue
        item = {
            "title": title,
            "summary": _text(card, ".thumbnail__subtitle") or None,
            "url": href if href.startswith("http") else BASE + href,
            "image": _photo(card, 800),
            "source": "FC Barcelona",
            "published": _published(_text(card, "time"), now),
            "origin": "official",
        }
        seen[href] = item
        out.append(item)
        if len(out) >= limit:
            break
    return out
