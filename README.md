# FCB Tracker

FC Barcelona fixtures, results, lineups, match stats, squad, league table and news. A Cloudflare Worker serves the app, and the data is scraped with [Scrapling](https://github.com/D4Vinci/Scrapling).

```
scraper/   Python + Scrapling: ESPN, fcbarcelona.com, Google News, SofaScore  ->  POST /api/ingest
worker/    Cloudflare Worker: KV-backed JSON API + static frontend (no build step)
.claude/skills/UIUXmasterclass-skill/   design skill the frontend was built with
```

## How it fits together

Scrapling is a Python library (curl_cffi impersonation plus a patched Chromium), so it cannot run inside a Worker. The scraper runs wherever Python runs (your machine, or the included GitHub Actions cron) and pushes normalised JSON to the Worker, which stores it in KV and serves it.

| Source | How it's scraped | What it provides |
|---|---|---|
| ESPN site API | Scrapling `Fetcher` with Chrome impersonation (plain clients get an Akamai 403) | Fixtures and results across all competitions, match summaries (team stats, lineups + formations, key events, officials, attendance), squad season stats, LALIGA table, ESPN news |
| fcbarcelona.com (official site) | Scrapling `Fetcher` + CSS selectors on the server-rendered pages | Official player photos and profile links (merged into the squad), first-team news |
| Google News | Scrapling `Fetcher` on the Google News RSS feed. Google Search itself returns a JS redirect or `/sorry` CAPTCHA to datacenter traffic | Last 7 days of FC Barcelona headlines from many publishers |
| SofaScore | Scrapling `StealthyFetcher` opens the team page and calls SofaScore's own API from inside it | Player ratings and xG, merged into ESPN match details |

SofaScore blocks many datacenter IP ranges outright (its edge returns 403 to every client). From those networks, set `SCRAPER_PROXY` to a residential proxy or run the scraper from a home connection. Without it the app still works, and the footer shows SofaScore as unavailable.

## Live matches

Scrapling can't run inside a Worker, so during a match the Worker fetches live data itself (`worker/src/live.ts`):

- A **cron trigger runs every minute**. It does nothing unless a fixture is in its live window (75 minutes before kick-off, so lineups appear, until the match ends). Inside the window it pulls ESPN's live summary (clock, score, goals, cards, subs, team stats, lineups) and writes it to KV only when something changed.
- **Viewers pull updates in.** Requests for the overview or a match trigger a background refresh at most every 20 seconds, so an open match page (which polls every 15 seconds while live) stays within seconds of ESPN.
- **LiveScore is the fallback.** If ESPN fails, LiveScore's public feed supplies the score and clock.
- `POST /api/live/refresh?event=<id>&league=<slug>&team=<id>` (with the ingest token) refreshes any ESPN event on demand. The e2e suite uses it on whatever match is in progress at test time.

## Run locally

```bash
# 1. Scraper
python3 -m venv .venv
.venv/bin/pip install -r scraper/requirements.txt
.venv/bin/scrapling install          # Chromium for StealthyFetcher (SofaScore)

# 2. Worker
cd worker
npm install
echo "INGEST_TOKEN=$(openssl rand -hex 24)" > .dev.vars
npm run dev                          # http://127.0.0.1:8787

# 3. Fill it (in another shell, from the repo root)
cd scraper
INGEST_TOKEN=<same token> ../.venv/bin/python -m fcb_scraper --push http://127.0.0.1:8787
```

Scraper flags: `--out DIR` writes the JSON files instead of (or as well as) pushing, `--skip espn|official|google|sofascore` skips a source, and `--recent N` sets how many completed matches get full detail (default 12).

## End-to-end tests

```bash
cd worker
npm run test:e2e
```

If your sandbox lets command-line tools out through a proxy but gives wrangler's local runtime no internet access, run `python3 worker/scripts/dev-egress-relay.py` and set `E2E_FETCH_RELAY=http://127.0.0.1:8798`.

Playwright starts `wrangler dev` on port 8788, runs the real Scrapling scraper against it (no fixtures or mock data), then checks every view against the API in desktop and mobile Chromium. The checks cover the API and ingest auth, the overview, results and filters, the match centre tabs (timeline, stats, lineups, keyboard tab navigation), fixtures and the pre-match preview, squad sorting, the table, news filters, theme persistence, the not-found state, no horizontal scroll, and no em/en dashes in the copy.

## Deploy

```bash
cd worker
npx wrangler deploy                  # the KV namespace is provisioned automatically
npx wrangler secret put INGEST_TOKEN
```

Then add the repository secrets `WORKER_URL`, `INGEST_TOKEN` and, optionally, `SCRAPER_PROXY`. `.github/workflows/scrape.yml` refreshes the data every 30 minutes.

## API

| Route | Returns |
|---|---|
| `GET /api/overview` | next or live match, last result with details, form, table excerpt, leaders, upcoming fixtures, news |
| `GET /api/matches` | all matches (results + fixtures) |
| `GET /api/matches/:id` | match detail: stats, lineups, events, officials |
| `GET /api/squad`, `/api/standings`, `/api/news` | as named |
| `POST /api/ingest` | `Authorization: Bearer <INGEST_TOKEN>`, body `{"items": {"key": value}}` |
| `POST /api/live/refresh` | same auth; refreshes live matches now, or one `?event=` |

## Design

The frontend follows `.claude/skills/UIUXmasterclass-skill`, which combines [taste-skill](https://github.com/leonxlnx/taste-skill)'s anti-slop rules with the [ui-ux-pro-max](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) design search engine (both MIT, vendored with their licenses). The design read: a stats tracker for Barça fans in a matchday-programme language. Dials: variance 6, motion 4, density 7. Fonts are Big Shoulders, Geist and Geist Mono. Blaugrana is used as a brand stripe, gold as the single accent, and there are light and dark themes.
