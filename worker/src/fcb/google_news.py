"""Google News: the last week of FC Barcelona headlines from the Google News RSS feed.

Google Search answers datacenter traffic with a JS redirect or a /sorry CAPTCHA, so the Google
source is the RSS feed. Pure parsing: callers download FEED_URL themselves.
"""

from __future__ import annotations

import xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime
from urllib.parse import quote_plus


FEED = "https://news.google.com/rss/search?q={q}&hl=en-US&gl=US&ceid=US:en"
QUERY = '"FC Barcelona" OR "Barça" when:7d'
FEED_URL = FEED.format(q=quote_plus(QUERY))


def parse_news(xml: str | bytes, limit: int = 24) -> list[dict]:
    root = ET.fromstring(xml)
    out, seen = [], set()
    for item in root.iter("item"):
        title = (item.findtext("title") or "").strip()
        source_el = item.find("source")
        source = (source_el.text or "").strip() if source_el is not None else None
        # Titles arrive as "Headline - Publisher"; keep just the headline.
        if source and title.endswith(f" - {source}"):
            title = title[: -len(source) - 3]
        key = title.lower()
        if not title or key in seen:
            continue
        seen.add(key)
        published = None
        if item.findtext("pubDate"):
            try:
                published = parsedate_to_datetime(item.findtext("pubDate")).isoformat()
            except (TypeError, ValueError):
                pass
        out.append({
            "title": title,
            "summary": None,
            "url": item.findtext("link"),
            "image": None,
            "source": source,
            "published": published,
            "origin": "google",
        })
        if len(out) >= limit:
            break
    return out
