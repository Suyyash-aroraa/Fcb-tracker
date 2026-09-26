"""Thin wrappers around Scrapling fetchers.

`Fetcher` (curl_cffi with browser TLS impersonation) handles JSON APIs and feeds.
`StealthyFetcher` (patched Chromium) is used for sites that only answer a real browser.
"""

from __future__ import annotations

import json
import os
from typing import Any, Callable

from scrapling.fetchers import Fetcher, StealthyFetcher


class SourceError(RuntimeError):
    """A source answered with something other than usable data."""


def _proxy() -> str | None:
    return os.environ.get("SCRAPER_PROXY") or None


def browser_path() -> str | None:
    """Chromium binary for StealthyFetcher. Falls back to Scrapling's own install."""
    return os.environ.get("SCRAPLING_EXECUTABLE_PATH") or None


def get(url: str, *, timeout: int = 30) -> bytes:
    page = Fetcher.get(
        url,
        impersonate="chrome",
        stealthy_headers=True,
        timeout=timeout,
        retries=2,
        proxy=_proxy(),
    )
    if page.status != 200:
        raise SourceError(f"HTTP {page.status} from {url}")
    return page.body


def get_json(url: str, *, timeout: int = 30) -> Any:
    body = get(url, timeout=timeout)
    try:
        return json.loads(body)
    except ValueError as exc:
        raise SourceError(f"Non-JSON response from {url}") from exc


def browser_session_json(start_url: str, api_paths: dict[str, str], *, timeout_ms: int = 60_000) -> dict[str, Any]:
    """Open `start_url` in a stealth browser, then call same-origin JSON endpoints from inside the page.

    Running the requests from the page context means they carry the cookies and headers
    the site's own frontend would send. Returns {key: parsed_json | SourceError}.
    """
    results: dict[str, Any] = {}

    def action(page):
        for key, path in api_paths.items():
            status, text = page.evaluate(
                "async p => { const r = await fetch(p, {credentials: 'include'}); return [r.status, await r.text()]; }",
                path,
            )
            if status != 200:
                results[key] = SourceError(f"HTTP {status} from {path}")
                continue
            try:
                results[key] = json.loads(text)
            except ValueError:
                results[key] = SourceError(f"Non-JSON response from {path}")
        return page

    page = StealthyFetcher.fetch(
        start_url,
        headless=True,
        timeout=timeout_ms,
        page_action=action,
        solve_cloudflare=True,
        proxy=_proxy(),
        executable_path=browser_path(),
    )
    if page.status != 200 and not results:
        raise SourceError(f"HTTP {page.status} from {start_url}")
    return results


def run_safely(label: str, fn: Callable[[], Any], log: Callable[[str], None]) -> tuple[Any, dict]:
    """Run a source step and return (value, status) without letting one source kill the run."""
    try:
        value = fn()
        return value, {"ok": True}
    except Exception as exc:  # noqa: BLE001 - every failure is reported in meta
        log(f"[{label}] failed: {exc}")
        return None, {"ok": False, "detail": str(exc)[:300]}
