/**
 * FCB Tracker Worker.
 *
 * Serves the static frontend (Workers Static Assets) and a small JSON API backed by KV.
 * KV is filled by the Scrapling scraper (../scraper), which POSTs to /api/ingest. During a match
 * the Worker also refreshes live data itself (see live.ts): every minute from a cron trigger, and at
 * most every LIVE_MIN_INTERVAL_MS while people have the match open.
 */

import { espnDetail, inLiveWindow, livescorePatch } from "./live";

export interface Env {
  FCB_DATA: KVNamespace;
  ASSETS: Fetcher;
  INGEST_TOKEN?: string;
  /** Dev only: route outbound live fetches through a local relay (`<relay>?url=<encoded>`), for
   * sandboxes where the local runtime has no direct internet access. Never set in production. */
  DEV_FETCH_RELAY?: string;
}

function outbound(env: Env): typeof fetch {
  const relay = env.DEV_FETCH_RELAY;
  if (!relay) return fetch;
  return ((input: RequestInfo | URL, init?: RequestInit) =>
    fetch(`${relay}?url=${encodeURIComponent(String(input))}`, init)) as typeof fetch;
}

type Json = Record<string, any>;

const KEY_PATTERN = /^(meta|team|matches|squad|standings|news|match:\d{1,12})$/;
const FCB_ID = "83";
const LIVE_MIN_INTERVAL_MS = 20_000;

const json = (body: unknown, status = 200, maxAge = 60): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": status === 200 ? `public, max-age=${maxAge}` : "no-store",
    },
  });

const noData = () =>
  json({ error: "no-data", message: "No data yet. Run the scraper to populate this Worker." }, 503);

async function read<T = any>(env: Env, key: string): Promise<T | null> {
  return env.FCB_DATA.get<T>(key, "json");
}

function byDate(a: Json, b: Json) {
  return (a.date || "").localeCompare(b.date || "");
}

/** Refresh one match from ESPN (LiveScore fallback) into KV. Writes only when something changed. */
async function refreshMatch(env: Env, base: Json, teamId = FCB_ID): Promise<Json> {
  let detail: Json;
  let source = "ESPN";
  try {
    detail = await espnDetail(base.id, base.competition?.slug ?? "all", teamId, outbound(env));
  } catch (err) {
    console.error("ESPN live fetch failed, trying LiveScore:", String(err));
    const patched = await livescorePatch(base, outbound(env));
    if (!patched) throw err;
    const prev = (await read(env, `match:${base.id}`)) ?? { stats: [], lineups: {}, events: [], officials: [] };
    detail = { ...prev, match: patched };
    source = "LiveScore";
  }
  const now = new Date().toISOString();
  const prev = await read(env, `match:${base.id}`);
  const strip = (d: Json | null) => d && JSON.stringify({ ...d, liveUpdatedAt: undefined, liveSource: undefined });
  const changed = strip(prev) !== strip(detail);
  detail = { ...detail, liveUpdatedAt: now, liveSource: `${source}, fetched by the Worker` };
  const writes: Promise<unknown>[] = [env.FCB_DATA.put("live", JSON.stringify({ id: base.id, at: Date.now(), source, changed }))];
  if (changed || !prev?.liveUpdatedAt || Date.now() - Date.parse(prev.liveUpdatedAt) > 60_000) {
    writes.push(env.FCB_DATA.put(`match:${base.id}`, JSON.stringify(detail)));
  }
  if (changed) {
    const matches = await read<Json[]>(env, "matches");
    const i = matches?.findIndex((m) => m.id === base.id) ?? -1;
    if (matches && i >= 0) {
      matches[i] = detail.match;
      writes.push(env.FCB_DATA.put("matches", JSON.stringify(matches)));
    }
  }
  await Promise.all(writes);
  return detail;
}

/** Refresh every match currently in its live window, unless one was refreshed very recently. */
async function refreshLive(env: Env, force = false): Promise<number> {
  const matches = await read<Json[]>(env, "matches");
  const due = inLiveWindow(matches ?? []);
  if (!due.length) return 0;
  const last = await read<{ at: number }>(env, "live");
  if (!force && last && Date.now() - last.at < LIVE_MIN_INTERVAL_MS) return 0;
  const results = await Promise.allSettled(due.map((m) => refreshMatch(env, m)));
  for (const r of results) if (r.status === "rejected") console.error("live refresh failed", r.reason);
  return results.filter((r) => r.status === "fulfilled").length;
}

async function overview(env: Env): Promise<Response> {
  const [meta, team, matches, squad, standings, news] = await Promise.all([
    read(env, "meta"),
    read(env, "team"),
    read<Json[]>(env, "matches"),
    read<Json[]>(env, "squad"),
    read(env, "standings"),
    read<Json[]>(env, "news"),
  ]);
  if (!meta || !matches) return noData();

  const sorted = [...matches].sort(byDate);
  const live = sorted.find((m) => m.status?.state === "in") ?? null;
  const played = sorted.filter((m) => m.status?.state === "post");
  const upcoming = sorted.filter((m) => m.status?.state === "pre");
  const lastMatch = played.at(-1) ?? null;
  const last = lastMatch ? (await read(env, `match:${lastMatch.id}`)) ?? { match: lastMatch } : null;

  const byGoals = (key: "goals" | "assists") =>
    (squad ?? [])
      .filter((p) => (p.stats?.[key] ?? 0) > 0)
      .sort((a, b) => b.stats[key] - a.stats[key] || (a.stats.apps ?? 0) - (b.stats.apps ?? 0))
      .slice(0, 5);

  const rows: Json[] = standings?.rows ?? [];
  const idx = rows.findIndex((r) => r.team?.id === FCB_ID);
  const start = Math.max(0, Math.min(idx - 2, rows.length - 5));
  const around = idx >= 0 ? rows.slice(start, start + 5) : rows.slice(0, 5);

  return json({
    meta,
    team,
    live,
    next: upcoming[0] ?? null,
    last,
    form: played.slice(-5).reverse(),
    upcoming: upcoming.slice(0, 5),
    standings: standings ? { league: standings.league, rows: around, position: rows[idx] ?? null } : null,
    leaders: { goals: byGoals("goals"), assists: byGoals("assists") },
    news: (news ?? []).slice(0, 6),
  }, 200, live ? 5 : 60);
}

async function list(env: Env, key: "matches" | "squad" | "standings" | "news"): Promise<Response> {
  const [meta, data] = await Promise.all([read(env, "meta"), read(env, key)]);
  if (!data) return noData();
  return json({ meta, [key]: data });
}

async function matchDetail(env: Env, id: string): Promise<Response> {
  const detail = await read(env, `match:${id}`);
  if (detail) return json(detail, 200, detail.match?.status?.state === "in" ? 5 : 120);
  const matches = await read<Json[]>(env, "matches");
  const match = matches?.find((m) => m.id === id);
  if (!match) return json({ error: "not-found", message: `No match with id ${id}` }, 404);
  return json({ match, stats: [], lineups: {}, events: [], officials: [] });
}

async function timingSafeEqual(a: string, b: string): Promise<boolean> {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  return crypto.subtle.timingSafeEqual(ha, hb);
}

async function authorized(request: Request, env: Env): Promise<boolean> {
  if (!env.INGEST_TOKEN) return false;
  const auth = request.headers.get("authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  return !!token && (await timingSafeEqual(token, env.INGEST_TOKEN));
}

async function ingest(request: Request, env: Env): Promise<Response> {
  if (!env.INGEST_TOKEN) return json({ error: "ingest-disabled", message: "INGEST_TOKEN is not configured" }, 503);
  if (!(await authorized(request, env))) return json({ error: "unauthorized" }, 401);

  let body: Json;
  try {
    body = await request.json();
  } catch {
    return json({ error: "bad-request", message: "Body must be JSON" }, 400);
  }
  const items = body?.items;
  if (!items || typeof items !== "object" || Array.isArray(items)) {
    return json({ error: "bad-request", message: "Expected {items: {key: value}}" }, 400);
  }
  const keys = Object.keys(items);
  const invalid = keys.filter((k) => !KEY_PATTERN.test(k));
  if (invalid.length) return json({ error: "bad-request", message: `Unknown keys: ${invalid.join(", ")}` }, 400);

  // Write meta last so readers never see a fresh timestamp over stale data.
  const ordered = keys.filter((k) => k !== "meta");
  await Promise.all(ordered.map((k) => env.FCB_DATA.put(k, JSON.stringify(items[k]))));
  if ("meta" in items) await env.FCB_DATA.put("meta", JSON.stringify(items.meta));
  return json({ ok: true, written: keys.length });
}

async function manualRefresh(request: Request, env: Env): Promise<Response> {
  if (!(await authorized(request, env))) return json({ error: "unauthorized" }, 401);
  const url = new URL(request.url);
  const event = url.searchParams.get("event");
  if (!event) return json({ refreshed: await refreshLive(env, true) }, 200, 0);
  if (!/^\d{1,12}$/.test(event)) return json({ error: "bad-request", message: "event must be numeric" }, 400);
  const known = (await read<Json[]>(env, "matches"))?.find((m) => m.id === event);
  const base = known ?? { id: event, competition: { slug: url.searchParams.get("league") ?? "all" }, date: new Date().toISOString(), home: {}, away: {} };
  const detail = await refreshMatch(env, base, url.searchParams.get("team") ?? FCB_ID);
  return json({ ok: true, match: detail.match, events: detail.events.length, stats: detail.stats.length, source: detail.liveSource }, 200, 0);
}

async function api(request: Request, env: Env, path: string, ctx: ExecutionContext): Promise<Response> {
  if (path === "/api/live/refresh") {
    return request.method === "POST" ? manualRefresh(request, env) : json({ error: "method-not-allowed" }, 405);
  }
  if (path === "/api/overview" || path.startsWith("/api/matches")) {
    // Viewers of a live match pull fresh data in; the response itself never waits for it.
    ctx.waitUntil(refreshLive(env).catch((e) => console.error("live refresh failed", e)));
  }
  if (path === "/api/ingest") {
    return request.method === "POST" ? ingest(request, env) : json({ error: "method-not-allowed" }, 405);
  }
  if (request.method !== "GET" && request.method !== "HEAD") return json({ error: "method-not-allowed" }, 405);

  switch (path) {
    case "/api/health":
      return json({ ok: true, updatedAt: (await read(env, "meta"))?.updatedAt ?? null }, 200, 0);
    case "/api/overview":
      return overview(env);
    case "/api/matches":
      return list(env, "matches");
    case "/api/squad":
      return list(env, "squad");
    case "/api/standings":
      return list(env, "standings");
    case "/api/news":
      return list(env, "news");
  }
  const m = path.match(/^\/api\/matches\/(\d{1,12})$/);
  if (m) return matchDetail(env, m[1]);
  return json({ error: "not-found" }, 404);
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/")) {
      try {
        return await api(request, env, url.pathname, ctx);
      } catch (err) {
        console.error("api error", err);
        return json({ error: "internal", message: "Something went wrong reading data" }, 500);
      }
    }
    return env.ASSETS.fetch(request);
  },

  async scheduled(_controller: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(refreshLive(env, true).then((n) => { if (n) console.log(`live: refreshed ${n} match(es)`); }));
  },
} satisfies ExportedHandler<Env>;
