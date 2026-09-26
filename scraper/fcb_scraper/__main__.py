"""Scrape everything and publish it.

    python -m fcb_scraper --out ../data                     # write JSON files
    python -m fcb_scraper --push http://127.0.0.1:8787      # POST to the Worker's /api/ingest
    python -m fcb_scraper --push URL --skip sofascore       # skip a source

The ingest token is read from INGEST_TOKEN.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from . import espn, google_news, sofascore
from .fetch import run_safely

SOURCES = ("espn", "google", "sofascore")


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def pick_detail_matches(matches: list[dict], recent: int) -> list[dict]:
    """Recent completed matches, anything live, and the next fixture (for early lineups)."""
    done = [m for m in matches if m["status"]["state"] == "post"][-recent:]
    live = [m for m in matches if m["status"]["state"] == "in"]
    upcoming = [m for m in matches if m["status"]["state"] == "pre"][:1]
    seen, out = set(), []
    for m in done + live + upcoming:
        if m["id"] not in seen:
            seen.add(m["id"])
            out.append(m)
    return out


def scrape(skip: set[str], recent: int) -> dict[str, object]:
    items: dict[str, object] = {}
    sources: dict[str, dict] = {}

    if "espn" not in skip:
        team, s_team = run_safely("espn:team", espn.fetch_team, log)
        fixtures, s_matches = run_safely("espn:matches", espn.fetch_matches, log)
        squad, s_squad = run_safely("espn:squad", espn.fetch_squad, log)
        table, s_table = run_safely("espn:standings", espn.fetch_standings, log)
        espn_news, _ = run_safely("espn:news", espn.fetch_news, log)
        season = None
        if team: items["team"] = team
        if squad: items["squad"] = squad
        if table: items["standings"] = table
        details = []
        if fixtures:
            matches, season = fixtures
            by_id = {m["id"]: m for m in matches}
            for m in pick_detail_matches(matches, recent):
                detail, _ = run_safely(f"espn:match:{m['id']}", lambda m=m: espn.fetch_match_detail(m), log)
                if detail:
                    details.append(detail)
                    by_id[m["id"]] = detail["match"]  # keep the list in sync with the fresher header
            items["matches"] = sorted(by_id.values(), key=lambda m: m["date"] or "")
        failures = [s for s in (s_team, s_matches, s_squad, s_table) if not s["ok"]]
        sources["espn"] = {"ok": not failures, "detail": "; ".join(f["detail"] for f in failures) or None,
                           "details": len(details)}
        if espn_news:
            items["news:espn"] = espn_news

        if "sofascore" not in skip and details:
            count, s_sofa = run_safely("sofascore", lambda: sofascore.enrich(details), log)
            sources["sofascore"] = {**s_sofa, "enriched": count or 0}
        for d in details:
            items[f"match:{d['match']['id']}"] = d
        if season:
            items["season"] = season

    if "google" not in skip:
        g_news, s_google = run_safely("google:news", google_news.fetch_news, log)
        sources["google"] = s_google
        if g_news:
            items["news:google"] = g_news

    news = list(items.pop("news:google", [])) + list(items.pop("news:espn", []))
    if news:
        news.sort(key=lambda a: a.get("published") or "", reverse=True)
        items["news"] = news

    items["meta"] = {
        "updatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "season": items.pop("season", None),
        "sources": sources,
        "scraper": "scrapling",
    }
    return items


def push(url: str, items: dict[str, object]) -> None:
    token = os.environ.get("INGEST_TOKEN")
    if not token:
        sys.exit("INGEST_TOKEN is not set")
    req = urllib.request.Request(
        url.rstrip("/") + "/api/ingest",
        data=json.dumps({"items": items}).encode(),
        headers={"content-type": "application/json", "authorization": f"Bearer {token}"},
        method="POST",
    )
    # Local wrangler dev must not go through the outbound proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({})) if "127.0.0.1" in url or "localhost" in url \
        else urllib.request.build_opener()
    with opener.open(req, timeout=60) as resp:
        log(f"ingest -> {resp.status} {resp.read().decode()[:200]}")


def main() -> None:
    ap = argparse.ArgumentParser(prog="fcb_scraper")
    ap.add_argument("--out", type=Path, help="directory to write <key>.json files")
    ap.add_argument("--push", metavar="WORKER_URL", help="Worker base URL to POST /api/ingest")
    ap.add_argument("--skip", action="append", default=[], choices=SOURCES)
    ap.add_argument("--recent", type=int, default=12, help="completed matches to fetch full detail for")
    args = ap.parse_args()
    if not args.out and not args.push:
        ap.error("pass --out and/or --push")

    items = scrape(set(args.skip), args.recent)
    log(f"scraped {len(items)} keys: " + json.dumps(items["meta"]["sources"]))
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
        for key, value in items.items():
            (args.out / f"{key.replace(':', '_')}.json").write_text(json.dumps(value, ensure_ascii=False, indent=1))
    if args.push:
        push(args.push, items)


if __name__ == "__main__":
    main()
