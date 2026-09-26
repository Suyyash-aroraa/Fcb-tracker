/**
 * Live match updates fetched by the Worker itself.
 *
 * Scrapling does the full scrapes, but it is Python and cannot run in a Worker. During a match the
 * Worker calls ESPN's public JSON directly (it accepts plain requests without a spoofed browser
 * user-agent) and normalises it into the same shape the scraper writes. LiveScore's public feed is
 * a fallback for score and clock when ESPN fails.
 */

type Json = Record<string, any>;

const ESPN = "https://site.web.api.espn.com/apis/site/v2/sports/soccer";
const LIVESCORE = "https://prod-public-api.livescore.com/v1/api/app/date/soccer";

// Window in which a fixture counts as "live" for refreshing: lineups appear ~1h before kick-off,
// and a match with extra time and penalties can run ~3h.
export const WINDOW_BEFORE_MS = 75 * 60_000;
export const WINDOW_AFTER_MS = 3.5 * 3600_000;

const TEAM_STATS: [string, string, string][] = [
  ["possessionPct", "Possession", "pct"], ["totalShots", "Shots", "count"], ["shotsOnTarget", "Shots on target", "count"],
  ["blockedShots", "Blocked shots", "count"], ["wonCorners", "Corners", "count"], ["totalPasses", "Passes", "count"],
  ["passAccuracy", "Pass accuracy", "pct"], ["totalCrosses", "Crosses", "count"], ["totalLongBalls", "Long balls", "count"],
  ["totalTackles", "Tackles", "count"], ["interceptions", "Interceptions", "count"], ["totalClearance", "Clearances", "count"],
  ["saves", "Saves", "count"], ["foulsCommitted", "Fouls", "count"], ["offsides", "Offsides", "count"],
  ["yellowCards", "Yellow cards", "count"], ["redCards", "Red cards", "count"],
];

const EVENT_KINDS: Record<string, string> = {
  goal: "goal", "penalty---scored": "goal", "own-goal": "own-goal", "yellow-card": "yellow", "red-card": "red",
  substitution: "sub", "penalty---missed": "pen-miss", "penalty---saved": "pen-miss", halftime: "period", "end-regular-time": "period",
};

const num = (v: unknown): number | null => {
  const n = typeof v === "number" ? v : parseFloat(String(v));
  return Number.isFinite(n) ? n : null;
};
const int = (v: unknown) => { const n = num(v); return n === null ? null : Math.trunc(n); };

function logos(team: Json): [string | null, string | null] {
  let light: string | null = null, dark: string | null = null;
  for (const l of team.logos ?? []) {
    if ((l.rel ?? []).includes("dark")) dark = l.href;
    else light ??= l.href;
  }
  light ??= team.logo ?? null;
  return [light, dark ?? light];
}

function teamRef(c: Json): Json {
  const t = c.team ?? {};
  const [logo, logoDark] = logos(t);
  const score = typeof c.score === "object" && c.score ? c.score : null;
  return {
    id: String(t.id ?? c.id), name: t.displayName ?? t.name, short: t.shortDisplayName ?? t.displayName, abbr: t.abbreviation,
    logo, logoDark, score: score ? int(score.value) : int(c.score), shootout: score ? int(score.shootoutScore) : int(c.shootoutScore),
    winner: c.winner ?? null,
  };
}

function status(s: Json): Json {
  const t = s.type ?? {};
  return { state: t.state, completed: !!t.completed, name: t.name, detail: t.detail ?? t.description, short: t.shortDetail, clock: s.displayClock ?? null };
}

export function normalizeEvent(event: Json, teamId: string): Json {
  const comp = (event.competitions ?? [{}])[0];
  const cs: Json[] = comp.competitors ?? [];
  const home = teamRef(cs.find((c) => c.homeAway === "home") ?? cs[0] ?? {});
  const away = teamRef(cs.find((c) => c.homeAway === "away") ?? cs.at(-1) ?? {});
  const fcbSide = home.id === teamId ? "home" : "away";
  const st = status(comp.status ?? event.status ?? {});
  let result: string | null = null;
  if (st.completed && home.score !== null && away.score !== null) {
    const [us, them] = fcbSide === "home" ? [home, away] : [away, home];
    if (us.score !== them.score) result = us.score > them.score ? "W" : "L";
    else if (us.shootout !== null && them.shootout !== null) result = us.shootout > them.shootout ? "W" : "L";
    else result = "D";
  }
  const league = event.league ?? {};
  const broadcasts: string[] = [];
  for (const b of comp.broadcasts ?? []) {
    const n = b.media?.shortName ?? b.names?.[0];
    if (n && !broadcasts.includes(n)) broadcasts.push(n);
  }
  return {
    id: String(event.id), date: comp.date ?? event.date,
    competition: { id: String(league.id ?? ""), name: league.name ?? event.seasonType?.name, slug: league.slug },
    note: (comp.notes ?? []).map((n: Json) => n.headline).find(Boolean) ?? null,
    venue: { name: comp.venue?.fullName ?? null, city: comp.venue?.address?.city ?? null },
    attendance: comp.attendance || null, status: st, home, away, fcbSide, result, broadcasts: broadcasts.slice(0, 4),
  };
}

function player(e: Json): Json {
  const a = e.athlete ?? {};
  const s: Record<string, number | null> = {};
  for (const x of e.stats ?? []) s[x.name] = int(x.value);
  const subMinute = (e.plays ?? []).filter((p: Json) => p.substitution).map((p: Json) => p.clock?.displayValue).at(-1) ?? null;
  return {
    id: String(a.id), name: a.displayName, short: a.shortName ?? a.displayName, number: e.jersey, pos: e.position?.abbreviation,
    place: int(e.formationPlace) ?? 0, starter: !!e.starter, subbedIn: !!e.subbedIn, subbedOut: !!e.subbedOut, subMinute,
    stats: { goals: s.totalGoals, assists: s.goalAssists, shots: s.totalShots, shotsOnTarget: s.shotsOnTarget, saves: s.saves,
             yellow: s.yellowCards, red: s.redCards, fouls: s.foulsCommitted, ownGoals: s.ownGoals },
  };
}

function teamStats(box: Json, homeId: string): Json[] {
  const raw: Record<string, Record<string, number | null>> = {};
  for (const t of box.teams ?? []) {
    const side = String(t.team?.id) === homeId ? "home" : "away";
    raw[side] = Object.fromEntries((t.statistics ?? []).map((s: Json) => [s.name, num(s.displayValue)]));
  }
  if (!raw.home || !raw.away) return [];
  for (const side of [raw.home, raw.away]) {
    const acc = side.accuratePasses, tot = side.totalPasses;
    side.passAccuracy = acc != null && tot ? Math.round((1000 * acc) / tot) / 10 : null;
  }
  return TEAM_STATS.filter(([k]) => raw.home[k] != null || raw.away[k] != null)
    .map(([key, label, type]) => ({ key, label, type, home: raw.home[key] ?? null, away: raw.away[key] ?? null }));
}

function keyEvents(list: Json[], homeId: string): Json[] {
  const out: Json[] = [];
  for (const ev of list) {
    let kind = EVENT_KINDS[ev.type?.type ?? ""];
    if (!kind) continue;
    const typeText: string = ev.type?.text ?? "";
    if (kind === "goal" && /own goal/i.test(ev.text ?? "")) kind = "own-goal";
    const teamId = String(ev.team?.id ?? "");
    out.push({
      id: String(ev.id), kind, label: typeText, minute: ev.clock?.displayValue ?? "", period: ev.period?.number ?? null,
      side: teamId ? (teamId === homeId ? "home" : "away") : null,
      players: (ev.participants ?? []).map((p: Json) => p.athlete?.displayName).filter(Boolean),
      text: ev.text ?? null, penalty: /penalty/i.test(typeText),
    });
  }
  return out;
}

/** Full match detail from ESPN's summary endpoint, in the scraper's schema. */
export async function espnDetail(eventId: string, slug: string, teamId: string, fetcher: typeof fetch = fetch): Promise<Json> {
  const res = await fetcher(`${ESPN}/${slug || "all"}/summary?event=${eventId}`, { headers: { accept: "application/json" } });
  if (!res.ok) throw new Error(`ESPN summary HTTP ${res.status}: ${(await res.text()).slice(0, 160)}`);
  const s: Json = await res.json();
  const header = s.header?.competitions?.[0];
  if (!header) throw new Error("ESPN summary had no header");
  const lg = s.header?.league ?? {};
  const match = normalizeEvent({ id: eventId, league: { id: lg.id, name: lg.name, slug: slug || lg.slug }, competitions: [header] }, teamId);
  const homeId = match.home.id;
  const lineups: Json = {};
  for (const r of s.rosters ?? []) {
    const side = String(r.team?.id) === homeId ? "home" : "away";
    const players = (r.roster ?? []).map(player);
    lineups[side] = {
      formation: r.formation ?? null,
      starters: players.filter((p: Json) => p.starter).sort((a: Json, b: Json) => a.place - b.place),
      subs: players.filter((p: Json) => !p.starter),
    };
  }
  const info = s.gameInfo ?? {};
  return {
    match, stats: teamStats(s.boxscore ?? {}, homeId), lineups, events: keyEvents(s.keyEvents ?? [], homeId),
    officials: (info.officials ?? []).map((o: Json) => ({ name: o.displayName, role: o.position?.displayName })),
    attendance: info.attendance || match.attendance, venue: info.venue?.fullName ?? match.venue.name,
  };
}

const norm = (s = "") => s.normalize("NFKD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/\b(w|women|fc|cf|cd|ud|sd)\b/g, "").replace(/[^a-z]/g, "");

/** Score and clock from LiveScore, matched by date and team names. Used when ESPN fails. */
export async function livescorePatch(match: Json, fetcher: typeof fetch = fetch): Promise<Json | null> {
  const day = (match.date as string).slice(0, 10).replaceAll("-", "");
  const res = await fetcher(`${LIVESCORE}/${day}/0?MD=1`, { headers: { accept: "application/json" } });
  if (!res.ok) throw new Error(`LiveScore HTTP ${res.status}`);
  const d: Json = await res.json();
  const h = norm(match.home.name), a = norm(match.away.name);
  const same = (x: string, y: string) => x && y && (x.includes(y) || y.includes(x));
  for (const stage of d.Stages ?? []) for (const e of stage.Events ?? []) {
    if (!same(norm(e.T1?.[0]?.Nm), h) || !same(norm(e.T2?.[0]?.Nm), a)) continue;
    const eps: string = e.Eps ?? "";
    const state = eps === "NS" ? "pre" : ["FT", "AET", "AP", "Canc.", "Abd."].includes(eps) ? "post" : "in";
    return {
      ...match,
      home: { ...match.home, score: int(e.Tr1) ?? match.home.score },
      away: { ...match.away, score: int(e.Tr2) ?? match.away.score },
      status: { ...match.status, state, completed: state === "post", detail: eps === "HT" ? "Halftime" : eps, clock: eps },
    };
  }
  return null;
}

/** Fixtures whose live window contains `now`, soonest first. */
export function inLiveWindow(matches: Json[], now = Date.now()): Json[] {
  return matches
    .filter((m) => {
      const t = Date.parse(m.date);
      if (!Number.isFinite(t) || now < t - WINDOW_BEFORE_MS || now > t + WINDOW_AFTER_MS) return false;
      return !(m.status?.state === "post" && m.status?.completed && now > t + 2.5 * 3600_000);
    })
    .sort((a, b) => Date.parse(a.date) - Date.parse(b.date));
}
