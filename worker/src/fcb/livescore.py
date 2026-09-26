"""LiveScore's public feed: score and clock for a live match when ESPN fails."""

from __future__ import annotations

import re
import unicodedata

URL = "https://prod-public-api.livescore.com/v1/api/app/date/soccer/{day}/0?MD=1"


def url_for(match: dict) -> str:
    return URL.format(day=(match.get("date") or "")[:10].replace("-", ""))


def _norm(name: str | None) -> str:
    text = unicodedata.normalize("NFKD", name or "").encode("ascii", "ignore").decode().lower()
    text = re.sub(r"\b(w|women|fc|cf|cd|ud|sd)\b", "", text)
    return re.sub(r"[^a-z]", "", text)


def _int(v) -> int | None:
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def patch(match: dict, data: dict) -> dict | None:
    """Return `match` with LiveScore's score and clock, or None if the fixture isn't in the feed."""
    h, a = _norm(match["home"]["name"]), _norm(match["away"]["name"])
    same = lambda x, y: bool(x and y and (x in y or y in x))  # noqa: E731
    for stage in data.get("Stages") or []:
        for e in stage.get("Events") or []:
            t1, t2 = (e.get("T1") or [{}])[0].get("Nm"), (e.get("T2") or [{}])[0].get("Nm")
            if not (same(_norm(t1), h) and same(_norm(t2), a)):
                continue
            eps = e.get("Eps") or ""
            state = "pre" if eps == "NS" else "post" if eps in ("FT", "AET", "AP", "Canc.", "Abd.") else "in"
            return {
                **match,
                "home": {**match["home"], "score": _int(e.get("Tr1")) if _int(e.get("Tr1")) is not None else match["home"]["score"]},
                "away": {**match["away"], "score": _int(e.get("Tr2")) if _int(e.get("Tr2")) is not None else match["away"]["score"]},
                "status": {**match["status"], "state": state, "completed": state == "post",
                           "detail": "Halftime" if eps == "HT" else eps, "clock": eps},
            }
    return None
