/**
 * FCB Tracker Worker.
 *
 * Serves the static frontend (Workers Static Assets) and a small JSON API backed by KV.
 * KV is filled by the Scrapling scraper (../scraper), which POSTs to /api/ingest.
 */

export interface Env {
  FCB_DATA: KVNamespace;
  ASSETS: Fetcher;
  INGEST_TOKEN?: string;
}

type Json = Record<string, any>;

const KEY_PATTERN = /^(meta|team|matches|squad|standings|news|match:\d{1,12})$/;
const FCB_ID = "83";

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
  }, 200, live ? 15 : 60);
}

async function list(env: Env, key: "matches" | "squad" | "standings" | "news"): Promise<Response> {
  const [meta, data] = await Promise.all([read(env, "meta"), read(env, key)]);
  if (!data) return noData();
  return json({ meta, [key]: data });
}

async function matchDetail(env: Env, id: string): Promise<Response> {
  const detail = await read(env, `match:${id}`);
  if (detail) return json(detail, 200, detail.match?.status?.state === "in" ? 15 : 120);
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

async function ingest(request: Request, env: Env): Promise<Response> {
  if (!env.INGEST_TOKEN) return json({ error: "ingest-disabled", message: "INGEST_TOKEN is not configured" }, 503);
  const auth = request.headers.get("authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  if (!token || !(await timingSafeEqual(token, env.INGEST_TOKEN))) {
    return json({ error: "unauthorized" }, 401);
  }

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

async function api(request: Request, env: Env, path: string): Promise<Response> {
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
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/")) {
      try {
        return await api(request, env, url.pathname);
      } catch (err) {
        console.error("api error", err);
        return json({ error: "internal", message: "Something went wrong reading data" }, 500);
      }
    }
    return env.ASSETS.fetch(request);
  },
} satisfies ExportedHandler<Env>;
