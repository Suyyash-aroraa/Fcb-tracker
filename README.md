# FCB Tracker

FC Barcelona fixtures, results, lineups, match stats, squad, league table and news. A Cloudflare Python Worker scrapes the data with [Scrapling](https://github.com/D4Vinci/Scrapling), stores it in KV and serves the app.

```
worker/    Cloudflare Python Worker: scraping (src/fcb, Scrapling parser), cron, KV API, frontend
scraper/   optional command-line runner of the same pipeline with Scrapling's fetchers (+ SofaScore)
.claude/skills/UIUXmasterclass-skill/   design skill the frontend was built with
```

## How it fits together

Everything runs in one **Cloudflare Python Worker** (`worker/`). There is no separate scraper to run.

- **Scraping** happens inside the Worker. `worker/src/fcb/` parses every source with Scrapling's `Selector` (Scrapling's parser runs in Python Workers via Pyodide; lxml and orjson ship as Pyodide packages), and downloads with the Workers runtime `fetch`.
- **A cron trigger runs every minute.** It follows live matches, and otherwise runs the most overdue sync step, one step per minute: fixtures, match details, table, squad and news. Each refreshes every 30 minutes (the squad every 6 hours).
- **A fresh deploy fills itself.** The first request to an empty store fetches the fixtures inline; the cron fills in the rest over the next few minutes.
- **Every source reports its own status** in `/api/health` and the page footer, so a site that blocks Cloudflare shows up straight away.

| Source | What it provides |
|---|---|
| ESPN public JSON | Fixtures and results in every competition, match summaries (team stats, lineups and formations, key events, officials, attendance), squad season stats, LALIGA table, ESPN news |
| fcbarcelona.com (official site) | Official player photos and profile links (merged into the squad), first-team news |
| Google News RSS | The last week of headlines from many publishers. Google Search itself returns a CAPTCHA to datacenter traffic |
| LiveScore public feed | Live score and clock if ESPN fails during a match |
| SofaScore (optional) | Player ratings and xG. Needs Scrapling's stealth browser and a residential connection, so only the optional command-line scraper can add it |

### What Scrapling does where

Scrapling has two halves. Its **parser** runs inside the Worker. Its **fetchers** (curl_cffi browser impersonation and the stealth Chrome) need native networking and a browser, which Python Workers don't have, so the Worker downloads with `fetch` instead. None of the sources above need impersonation. The optional command-line scraper (`scraper/`) runs the same pipeline with Scrapling's fetchers and can push SofaScore data to the Worker's `/api/ingest`.

### Plan limits

Workers Free allows **10 ms of CPU per invocation**. Parsing counts toward it; waiting on the network doesn't. The Worker keeps each cron run to one step to stay small, but parsing ESPN's JSON in Python can still exceed 10 ms. If the dashboard shows `Exceeded CPU Time Limits` (Error 1102), use Workers Paid ($5/month, 30 s CPU per invocation).

## Live matches

- **The cron checks every minute.** While a fixture is in its live window (75 minutes before kick-off, so lineups appear, until the match ends), it pulls ESPN's live summary: clock, score, goals, cards, subs, team stats and lineups. It writes to KV only when something changed.
- **Viewers pull updates in.** Requests for the overview or a match trigger a background refresh at most every 20 seconds, and an open match page polls every 15 seconds while live.
- **LiveScore is the fallback** for score and clock if ESPN fails.
- `POST /api/live/refresh[?event=<id>]` (with the token) refreshes live fixtures now, or one Barcelona fixture. Other teams' matches are refused.

## Run locally

Needs [uv](https://docs.astral.sh/uv/) 0.12.3+ and Node 22+.

```bash
cd worker
npm install
echo "INGEST_TOKEN=$(openssl rand -hex 24)" > .dev.vars
npm run dev                                  # uv run pywrangler dev, http://127.0.0.1:8787
curl "http://127.0.0.1:8787/cdn-cgi/local/scheduled?cron=*+*+*+*+*"   # run one cron tick
```

The optional command-line scraper (SofaScore enrichment, or writing JSON files):

```bash
python3 -m venv .venv && .venv/bin/pip install -r scraper/requirements.txt
cd scraper && ../.venv/bin/python -m fcb_scraper --out ../data
INGEST_TOKEN=<token> ../.venv/bin/python -m fcb_scraper --push https://<worker> --sofascore
```

## End-to-end tests

```bash
cd worker
npm run test:e2e
```

If your sandbox lets command-line tools out through a proxy but gives wrangler's local runtime no internet access, run `python3 worker/scripts/dev-egress-relay.py` and set `E2E_FETCH_RELAY=http://127.0.0.1:8798`.

Playwright starts the Python Worker (`pywrangler dev`) on port 8788 with an empty store. The bootstrap step checks that the first request fills it and drives the real cron handler until every source has synced (no fixtures or mock data). The suites then check every view against the API in desktop and mobile Chromium. The checks cover the API and ingest auth, the overview, results and filters, the match centre tabs (timeline, stats, lineups, keyboard tab navigation), fixtures and the pre-match preview, squad sorting, the table, news filters, theme persistence, the not-found state, no horizontal scroll, and no em/en dashes in the copy.

## Deploy

```bash
cd worker
npm run deploy                       # uv run pywrangler deploy; the KV namespace is provisioned automatically
```

That's all: open the site and it fills itself, then the cron keeps it current. `npx wrangler secret put INGEST_TOKEN` is only needed for the token-protected endpoints (`/api/sync`, `/api/live/refresh`, `/api/ingest`).

## API

| Route | Returns |
|---|---|
| `GET /api/overview` | next or live match, last result with details, form, table excerpt, leaders, upcoming fixtures, news |
| `GET /api/matches` | all matches (results + fixtures) |
| `GET /api/matches/:id` | match detail: stats, lineups, events, officials |
| `GET /api/squad`, `/api/standings`, `/api/news` | as named |
| `POST /api/ingest` | `Authorization: Bearer <INGEST_TOKEN>`, body `{"items": {"key": value}}` |
| `POST /api/sync` | same auth; runs every sync step now |
| `POST /api/live/refresh` | same auth; refreshes live fixtures now, or one Barcelona fixture `?event=` |

## Design

The frontend follows `.claude/skills/UIUXmasterclass-skill`, which combines [taste-skill](https://github.com/leonxlnx/taste-skill)'s anti-slop rules with the [ui-ux-pro-max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) design search engine (both MIT, vendored with their licenses). The design read: a stats tracker for Barça fans in a matchday-programme language. Dials: variance 6, motion 4, density 7. Fonts are Big Shoulders, Geist and Geist Mono. Blaugrana is used as a brand stripe, gold as the single accent, and there are light and dark themes.
