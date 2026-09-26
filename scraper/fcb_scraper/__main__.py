"""Optional command-line scraper.

The Worker scrapes everything itself. This CLI runs the same pipeline (worker/src/fcb) with
Scrapling's impersonating Fetcher instead of the Workers runtime, and can add SofaScore ratings and
xG, which need Scrapling's stealth browser and usually a residential connection.

    python -m fcb_scraper --out ../data                      # write JSON files
    python -m fcb_scraper --push https://<worker> --sofascore # add SofaScore data to a Worker

The ingest token is read from INGEST_TOKEN.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "worker" / "src"))

from fcb import pipeline  # noqa: E402

from . import sofascore  # noqa: E402
from .fetch import get, get_json  # noqa: E402


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


class ScraplingIO:
    """Downloads with Scrapling's Fetcher (browser TLS impersonation)."""

    async def get_json(self, url: str):
        return await asyncio.to_thread(get_json, url)

    async def get_text(self, url: str) -> str:
        return (await asyncio.to_thread(get, url)).decode("utf-8", "replace")


class MemoryStore:
    def __init__(self):
        self.items: dict[str, object] = {}

    async def get(self, key: str):
        return self.items.get(key)

    async def put(self, key: str, value) -> None:
        self.items[key] = value


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
    # Local wrangler dev must not go through an outbound proxy.
    local = "127.0.0.1" in url or "localhost" in url
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({})) if local else urllib.request.build_opener()
    with opener.open(req, timeout=60) as resp:
        log(f"ingest -> {resp.status} {resp.read().decode()[:200]}")


async def run(args) -> dict[str, object]:
    store = MemoryStore()
    await pipeline.sync(ScraplingIO(), store, force=True, bootstrap=True, log=log)
    meta = store.items["meta"]
    meta["scraper"] = "Scrapling (command line)"
    if args.sofascore:
        details = [v for k, v in store.items.items() if k.startswith("match:")]
        try:
            count = await asyncio.to_thread(sofascore.enrich, details)
            meta["sources"]["sofascore"] = {"ok": True, "enriched": count}
        except Exception as exc:  # noqa: BLE001
            log(f"[sofascore] failed: {exc}")
            meta["sources"]["sofascore"] = {"ok": False, "detail": str(exc)[:300]}
    return store.items


def main() -> None:
    ap = argparse.ArgumentParser(prog="fcb_scraper")
    ap.add_argument("--out", type=Path, help="directory to write <key>.json files")
    ap.add_argument("--push", metavar="WORKER_URL", help="Worker base URL to POST /api/ingest")
    ap.add_argument("--sofascore", action="store_true", help="add SofaScore ratings and xG (stealth browser)")
    args = ap.parse_args()
    if not args.out and not args.push:
        ap.error("pass --out and/or --push")

    items = asyncio.run(run(args))
    log(f"scraped {len(items)} keys: " + json.dumps(items["meta"]["sources"]))
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
        for key, value in items.items():
            (args.out / f"{key.replace(':', '_')}.json").write_text(json.dumps(value, ensure_ascii=False, indent=1))
    if args.push:
        push(args.push, items)


if __name__ == "__main__":
    main()
