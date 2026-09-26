// FCB Tracker frontend. No build step: a hash router, escaped templates, and views per route.

const FCB = "83";
const main = document.getElementById("main");
const footer = document.getElementById("footer");
const liveRegion = document.getElementById("live-region");

/* ---------------- Safe templating ---------------- */

class Html { constructor(s) { this.s = s; } toString() { return this.s; } }
const ESC = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
const esc = (v) => String(v).replace(/[&<>"']/g, (c) => ESC[c]);
function flat(v) {
  if (v == null || v === false) return "";
  if (v instanceof Html) return v.s;
  if (Array.isArray(v)) return v.map(flat).join("");
  return esc(v);
}
const html = (strings, ...vals) => new Html(strings.reduce((out, s, i) => out + s + (i < vals.length ? flat(vals[i]) : ""), ""));
const safeUrl = (u) => (typeof u === "string" && /^https?:\/\//i.test(u) ? u : null);

/* ---------------- Data ---------------- */

class ApiError extends Error {
  constructor(status, body) { super(body?.message || `Request failed (${status})`); this.status = status; this.body = body; }
}

async function api(path) {
  const res = await fetch(path, { headers: { accept: "application/json" } });
  let body = null;
  try { body = await res.json(); } catch { /* keep null */ }
  if (!res.ok) throw new ApiError(res.status, body);
  return body;
}

/* ---------------- Formatting ---------------- */

const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
const fmt = {
  day: new Intl.DateTimeFormat(undefined, { weekday: "short", day: "numeric", month: "short" }),
  dayLong: new Intl.DateTimeFormat(undefined, { weekday: "long", day: "numeric", month: "long", year: "numeric" }),
  time: new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }),
  month: new Intl.DateTimeFormat(undefined, { month: "long", year: "numeric" }),
  rel: new Intl.RelativeTimeFormat(undefined, { numeric: "auto" }),
  zone: new Intl.DateTimeFormat(undefined, { timeZoneName: "short" }),
};
const zoneName = (d) => fmt.zone.formatToParts(d).find((p) => p.type === "timeZoneName")?.value || tz;

function ago(iso) {
  if (!iso) return "";
  const s = (new Date(iso) - Date.now()) / 1000;
  const units = [["year", 31536000], ["month", 2592000], ["week", 604800], ["day", 86400], ["hour", 3600], ["minute", 60]];
  for (const [u, n] of units) if (Math.abs(s) >= n) return fmt.rel.format(Math.round(s / n), u);
  return "just now";
}

function whenLabel(iso) {
  const d = new Date(iso);
  const days = Math.round((new Date(d).setHours(0, 0, 0, 0) - new Date().setHours(0, 0, 0, 0)) / 86400000);
  const day = days === 0 ? "Today" : days === 1 ? "Tomorrow" : days === -1 ? "Yesterday" : fmt.day.format(d);
  return { day, time: fmt.time.format(d) };
}

const COMP_SHORT = { "UEFA Champions League": "UCL", "Club Friendly": "Friendly", "Trofeo Joan Gamper": "Gamper" };
const compName = (c) => (c?.name || "").replace(/^Spanish /, "");
const compShort = (c) => COMP_SHORT[c?.name] || compName(c);
const n = (v, digits = 0) => (v == null || Number.isNaN(v) ? null : Number(v).toFixed(digits));
const initials = (name = "") => name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join("").toUpperCase();
const surname = (name = "") => { const p = name.split(" "); return p.length > 1 ? p.slice(1).join(" ") : name; };

/* ---------------- Theme ---------------- */

const themeBtn = document.getElementById("theme-toggle");
function currentTheme() {
  const set = document.documentElement.dataset.theme;
  if (set) return set;
  return matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}
function paintThemeButton() {
  const t = currentTheme();
  themeBtn.innerHTML = `<i class="ph ${t === "dark" ? "ph-sun" : "ph-moon"}" aria-hidden="true"></i>`;
  themeBtn.setAttribute("aria-label", t === "dark" ? "Switch to light theme" : "Switch to dark theme");
  document.querySelector('meta[name="theme-color"]').content = t === "dark" ? "#0a0e1a" : "#eef1f6";
}
themeBtn.addEventListener("click", () => {
  const next = currentTheme() === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem("fcb-theme", next); } catch { /* private mode */ }
  paintThemeButton();
  swapLogos();
});
matchMedia("(prefers-color-scheme: light)").addEventListener("change", () => { paintThemeButton(); swapLogos(); });

function swapLogos() {
  const dark = currentTheme() === "dark";
  document.querySelectorAll("img[data-logo]").forEach((img) => {
    const src = dark ? img.dataset.logoDark || img.dataset.logo : img.dataset.logo;
    if (src && img.src !== src) img.src = src;
  });
}

/* ---------------- Components ---------------- */

const icon = (name) => html`<i class="ph ph-${name}" aria-hidden="true"></i>`;

function crest(team, size = 40, alt = "") {
  if (!team) return "";
  const light = safeUrl(team.logo);
  const dark = safeUrl(team.logoDark) || light;
  if (!light) return html`<span class="avatar" style="width:${size}px;height:${size}px"><span class="initials">${team.abbr || initials(team.name)}</span></span>`;
  const src = currentTheme() === "dark" ? dark : light;
  return html`<img src="${src}" data-logo="${light}" data-logo-dark="${dark}" alt="${alt}" width="${size}" height="${size}" loading="lazy" decoding="async">`;
}

function avatar(p, size = 40) {
  const src = safeUrl(p.headshot);
  return html`<span class="avatar" style="width:${size}px;height:${size}px">${src ? html`<img src="${src}" alt="" loading="lazy" decoding="async" data-fallback="${initials(p.name)}">` : html`<span class="initials">${initials(p.name)}</span>`}</span>`;
}

// Headshots that 404 fall back to initials.
document.addEventListener("error", (e) => {
  const img = e.target;
  if (img instanceof HTMLImageElement && img.dataset.fallback !== undefined) {
    const span = document.createElement("span");
    span.className = "initials";
    span.textContent = img.dataset.fallback;
    img.replaceWith(span);
  }
}, true);

const wdl = (r) => (r ? html`<span class="wdl ${r}" title="${{ W: "Win", D: "Draw", L: "Loss" }[r]}">${r}</span>` : "");

function opponent(m) { return m.fcbSide === "home" ? m.away : m.home; }

function scoreText(m) {
  if (m.home.score == null || m.away.score == null) return null;
  let s = `${m.home.score}-${m.away.score}`;
  if (m.home.shootout != null && m.away.shootout != null) s += ` (${m.home.shootout}-${m.away.shootout} pens)`;
  return s;
}

function bigScore(m) {
  const h = m.home.score, a = m.away.score;
  return html`<div class="bigscore" aria-label="${m.home.name} ${h}, ${m.away.name} ${a}">
    <span class="${h < a ? "lose" : ""}">${h}</span><span class="dash" aria-hidden="true">-</span><span class="${a < h ? "lose" : ""}">${a}</span>
  </div>`;
}

function sideBlock(team, tag) {
  return html`<div class="side">
    ${crest(team, 96)}
    <div><div class="side-name">${team.short || team.name}</div><div class="side-tag">${tag}</div></div>
  </div>`;
}

function errorState(err, retry = true) {
  if (err instanceof ApiError && err.status === 503 && err.body?.error === "no-data") {
    return html`<div class="panel state" role="status">
      ${icon("database")}
      <h2>No data scraped yet</h2>
      <p>This Worker is running but its store is empty. Run the Scrapling scraper to fill it:</p>
      <p><code>python -m fcb_scraper --push http://127.0.0.1:8787</code></p>
    </div>`;
  }
  if (err instanceof ApiError && err.status === 404) {
    return html`<div class="panel state" role="status">${icon("magnifying-glass")}<h2>Not found</h2><p>That page doesn't exist in the scraped data.</p><a class="btn btn-ghost" href="#/">Back to overview</a></div>`;
  }
  return html`<div class="panel state" role="alert">
    ${icon("warning")}
    <h2>Couldn't load this</h2>
    <p>${err?.message || "Something went wrong."}</p>
    ${retry ? html`<button class="btn btn-ghost" type="button" data-action="retry">${icon("arrow-clockwise")} Try again</button>` : ""}
  </div>`;
}

function renderFooter(meta) {
  if (!meta) { footer.innerHTML = ""; return; }
  const names = { espn: "ESPN", google: "Google News", sofascore: "SofaScore" };
  const src = Object.entries(meta.sources || {}).map(([k, v]) => html`<span class="${v.ok ? "ok" : "bad"}" title="${v.ok ? "Scraped OK" : v.detail || "Failed"}">
      ${icon(v.ok ? "check-circle" : "x-circle")}${names[k] || k}${v.ok ? "" : html`<span class="sr-only"> failed: ${v.detail || ""}</span>`}</span>`);
  footer.innerHTML = html`<span>Updated ${ago(meta.updatedAt)} with Scrapling${meta.season ? html`. ${meta.season}` : ""}</span><span class="sources" aria-label="Data sources">${src}</span>`;
}

/* ---------------- Views ---------------- */

function countdownCells(iso) {
  const ms = new Date(iso) - Date.now();
  if (ms <= 0) return null;
  const d = Math.floor(ms / 86400000), h = Math.floor(ms / 3600000) % 24, m = Math.floor(ms / 60000) % 60;
  return html`<div class="countdown" role="timer" aria-label="Kick-off in ${d} days ${h} hours ${m} minutes">
    ${[[d, "days"], [h, "hrs"], [m, "mins"]].map(([v, l]) => html`<div class="cd-cell" aria-hidden="true"><div class="cd-num">${String(v).padStart(2, "0")}</div><div class="cd-lbl">${l}</div></div>`)}
  </div>`;
}

function matchMeta(m) {
  const w = whenLabel(m.date);
  return html`<div class="hero-meta">
    <span>${icon("calendar-blank")}${w.day}, ${w.time} <span class="sr-only">${zoneName(new Date(m.date))}</span></span>
    ${m.venue?.name ? html`<span>${icon("map-pin")}${m.venue.name}${m.venue.city ? `, ${m.venue.city}` : ""}</span>` : ""}
    ${m.broadcasts?.length ? html`<span>${icon("television-simple")}${m.broadcasts.join(", ")}</span>` : ""}
  </div>`;
}

function heroMain(o) {
  const m = o.live || o.next;
  if (!m) return html`<div class="hero-main"><div class="kicker">Season complete</div><p class="page-sub">No fixtures scheduled.</p></div>`;
  const isLive = !!o.live;
  return html`<div class="hero-main">
    <div class="kicker">
      ${isLive ? html`<span class="live-flag">Live</span>` : html`<span>Next match</span>`}
      <span class="comp">${compName(m.competition)}</span>
      ${m.note ? html`<span>${m.note}</span>` : ""}
    </div>
    <div class="fixture">
      ${sideBlock(m.home, "Home")}
      ${isLive ? html`<div>${bigScore(m)}<div class="status-line" style="text-align:center;margin-top:8px">${m.status.detail || m.status.clock || ""}</div></div>` : html`<div class="vs">vs</div>`}
      ${sideBlock(m.away, "Away")}
    </div>
    ${isLive ? "" : countdownCells(m.date) || ""}
    <div style="margin-top:var(--s5)">${matchMeta(m)}</div>
    <a class="btn btn-accent" href="#/match/${m.id}">${isLive ? "Follow live" : "Match preview"} ${icon("arrow-right")}</a>
  </div>`;
}

function fcbGoals(detail) {
  if (!detail?.events) return [];
  const side = detail.match.fcbSide;
  return detail.events.filter((e) => (e.kind === "goal" && e.side === side) || (e.kind === "own-goal" && e.side !== side));
}

function lastStats(d) {
  const keys = ["possessionPct", "totalShots", "shotsOnTarget", "passAccuracy"];
  const rows = keys.map((k) => (d.stats || []).find((s) => s.key === k)).filter(Boolean);
  if (!rows.length) return "";
  const side = d.match.fcbSide, other = side === "home" ? "away" : "home";
  return html`<dl class="mini-stats">${rows.map((s) => html`<div><dt>${s.label}</dt><dd><b>${fmtStat(s, s[side])}</b><span> vs ${fmtStat(s, s[other])}</span></dd></div>`)}</dl>`;
}

function heroLast(o) {
  const d = o.last;
  if (!d) return "";
  const m = d.match, opp = opponent(m);
  const goals = fcbGoals(d);
  return html`<aside class="hero-last" aria-label="Last result">
    <div class="last-label">Last result</div>
    <div class="last-score">
      ${crest(m.home, 40, m.home.name)}
      ${bigScore(m)}
      ${crest(m.away, 40, m.away.name)}
      ${wdl(m.result)}
    </div>
    <div class="last-opp">${m.fcbSide === "home" ? "vs" : "at"} ${opp.name}. ${compName(m.competition)}, ${fmt.day.format(new Date(m.date))}</div>
    ${goals.length ? html`<ul class="scorers" aria-label="Barcelona goals">${goals.map((g) => html`<li><span class="min num">${g.minute}</span><span>${g.kind === "own-goal" ? `${g.players[0] || "Own goal"} (OG)` : g.players[0] || ""}${g.penalty ? " (pen)" : ""}${g.kind === "goal" && g.players[1] ? html` <span style="color:var(--text-3)">assist ${surname(g.players[1])}</span>` : ""}</span></li>`)}</ul>` : ""}
    ${lastStats(d)}
    <a class="btn btn-ghost" href="#/match/${m.id}">Match centre</a>
  </aside>`;
}

function formPanel(o) {
  const form = o.form || [];
  const tally = form.reduce((t, m) => (m.result && (t[m.result] += 1), t), { W: 0, D: 0, L: 0 });
  const gf = form.reduce((s, m) => s + ((m.fcbSide === "home" ? m.home.score : m.away.score) ?? 0), 0);
  const ga = form.reduce((s, m) => s + ((m.fcbSide === "home" ? m.away.score : m.home.score) ?? 0), 0);
  return html`<section class="panel area-form" aria-labelledby="form-h">
    <div class="panel-head"><h2 class="panel-title" id="form-h">Form</h2><a class="panel-link" href="#/matches">All results ${icon("arrow-right")}</a></div>
    <div class="panel-body">
      <div class="form-row">
        ${form.map((m) => { const opp = opponent(m); return html`<a class="form-item" href="#/match/${m.id}" aria-label="${m.result === "W" ? "Win" : m.result === "D" ? "Draw" : "Loss"} ${m.fcbSide === "home" ? "vs" : "at"} ${opp.name}, ${scoreText(m)}">
          ${crest(opp, 32)}<span class="form-score num">${scoreText(m)?.split(" ")[0]}</span>${wdl(m.result)}</a>`; })}
      </div>
      <div class="form-summary">
        <div class="kv"><b>${tally.W}-${tally.D}-${tally.L}</b><span>W-D-L, last ${form.length}</span></div>
        <div class="kv"><b>${gf}</b><span>Scored</span></div>
        <div class="kv"><b>${ga}</b><span>Conceded</span></div>
        ${o.team?.record ? html`<div class="kv"><b>${o.team.record}</b><span>League record</span></div>` : ""}
      </div>
    </div>
  </section>`;
}

function miniTable(o) {
  const st = o.standings;
  if (!st) return "";
  return html`<section class="panel area-table" aria-labelledby="tbl-h">
    <div class="panel-head"><h2 class="panel-title" id="tbl-h">${st.league || "Table"}</h2><a class="panel-link" href="#/table">Full table ${icon("arrow-right")}</a></div>
    <div class="panel-body">
      ${st.position ? html`<div class="form-summary" style="border:0;margin:0 0 var(--s4);padding:0">
        <div class="kv"><b>${st.position.rank}</b><span>Position</span></div>
        <div class="kv"><b>${st.position.points}</b><span>Points</span></div>
        <div class="kv"><b>${st.position.gd > 0 ? "+" : ""}${st.position.gd}</b><span>Goal diff</span></div>
      </div>` : ""}
      ${standingsTable(st.rows, false)}
    </div>
  </section>`;
}

function standingsTable(rows, full) {
  return html`<div class="table-wrap"><table class="${full ? "standings-full" : ""}">
    <caption class="sr-only">League standings</caption>
    <thead><tr><th class="l" scope="col">#</th><th class="l" scope="col">Team</th><th scope="col" title="Played">P</th>
      ${full ? html`<th scope="col" class="hide-sm" title="Won">W</th><th scope="col" class="hide-sm" title="Drawn">D</th><th scope="col" class="hide-sm" title="Lost">L</th><th scope="col" class="hide-sm" title="Goals for">GF</th><th scope="col" class="hide-sm" title="Goals against">GA</th>` : ""}
      <th scope="col" title="Goal difference">GD</th><th scope="col" title="Points">Pts</th></tr></thead>
    <tbody>${rows.map((r) => html`<tr class="${r.team.id === FCB ? "is-fcb" : ""}" ${r.team.id === FCB ? html`aria-current="true"` : ""}>
      <td class="l rank">${r.rank}</td>
      <td class="l team-cell"><span>${crest(r.team, 22)}${full ? r.team.name : r.team.short || r.team.name}</span></td>
      <td>${r.played}</td>
      ${full ? html`<td class="hide-sm">${r.won}</td><td class="hide-sm">${r.drawn}</td><td class="hide-sm">${r.lost}</td><td class="hide-sm">${r.gf}</td><td class="hide-sm">${r.ga}</td>` : ""}
      <td>${r.gd > 0 ? "+" : ""}${r.gd}</td><td class="pts">${r.points}</td></tr>`)}</tbody>
  </table></div>`;
}

function leadersPanel(o) {
  const block = (title, list, key) => html`<div>
    <h3 class="sub-title">${title}</h3>
    ${list.length ? html`<ol class="leader-list">${list.map((p, i) => html`<li class="leader ${i === 0 ? "leader-top" : ""}">
      ${avatar(p)}<div style="min-width:0"><div class="leader-name">${p.name}</div><div class="leader-sub">${p.posName || ""}${p.stats.apps != null ? `, ${p.stats.apps} apps` : ""}</div></div>
      <span class="n" aria-label="${p.stats[key]} ${key}">${p.stats[key]}</span></li>`)}</ol>` : html`<p class="page-sub">None yet this season.</p>`}
  </div>`;
  return html`<section class="panel area-leaders" aria-labelledby="lead-h">
    <div class="panel-head"><h2 class="panel-title" id="lead-h">Season leaders</h2><a class="panel-link" href="#/squad">Squad ${icon("arrow-right")}</a></div>
    <div class="panel-body leaders">${block("Goals", o.leaders?.goals || [], "goals")}${block("Assists", o.leaders?.assists || [], "assists")}</div>
  </section>`;
}

function matchRow(m) {
  const w = whenLabel(m.date);
  const done = m.status.state === "post", live = m.status.state === "in";
  const label = `${m.home.name} ${done || live ? scoreText(m) : "vs"} ${m.away.name}, ${compName(m.competition)}, ${w.day} ${w.time}`;
  const name = (t) => html`<span class="full">${t.short || t.name}</span><span class="abbr">${t.abbr || t.short || t.name}</span>`;
  return html`<a class="match-row" href="#/match/${m.id}" aria-label="${label}">
    <div class="mr-when"><b>${w.day}</b>${compShort(m.competition)}</div>
    <div class="mr-team home ${m.home.id === FCB ? "fcb" : ""}">${name(m.home)}${crest(m.home, 26)}</div>
    ${done || live ? html`<div class="mr-mid">${m.home.score}-${m.away.score}</div>` : html`<div class="mr-mid time">${w.time}</div>`}
    <div class="mr-team ${m.away.id === FCB ? "fcb" : ""}">${crest(m.away, 26)}${name(m.away)}</div>
    <div class="mr-res">${live ? html`<span class="live-flag">Live</span>` : wdl(m.result)}</div>
    ${m.note ? html`<div class="mr-comp">${m.note}</div>` : ""}
  </a>`;
}

function upcomingPanel(o) {
  return html`<section class="panel area-upcoming" aria-labelledby="up-h">
    <div class="panel-head"><h2 class="panel-title" id="up-h">Coming up</h2><a class="panel-link" href="#/matches/fixtures">Calendar ${icon("arrow-right")}</a></div>
    <div class="panel-body">${o.upcoming?.length ? html`<div class="match-list">${o.upcoming.map(matchRow)}</div>` : html`<p class="page-sub">No fixtures scheduled.</p>`}
      <p class="news-meta" style="margin-top:var(--s3)">Times shown in ${zoneName(new Date())}.</p></div>
  </section>`;
}

function newsList(items) {
  return html`<ul class="news-list">${items.map((a) => html`<li class="news-item"><a href="${safeUrl(a.url) || "#"}" target="_blank" rel="noopener noreferrer">
    <span class="news-title">${a.title}</span><span class="news-meta">${a.source || ""}${a.published ? `, ${ago(a.published)}` : ""}</span></a></li>`)}</ul>`;
}

function newsPanel(o) {
  return html`<section class="panel area-news" aria-labelledby="news-h">
    <div class="panel-head"><h2 class="panel-title" id="news-h">Latest</h2><a class="panel-link" href="#/news">All news ${icon("arrow-right")}</a></div>
    <div class="panel-body">${o.news?.length ? newsList(o.news.slice(0, 5)) : html`<p class="page-sub">No recent stories.</p>`}</div>
  </section>`;
}

let tickTimer = null;
let pollTimer = null;

async function viewOverview() {
  main.innerHTML = html`<div class="view grid-overview" aria-busy="true">
    <div class="skel skel-hero area-hero"></div>
    <div class="skel skel-panel area-form"></div><div class="skel skel-panel area-table"></div>
    <div class="skel skel-panel area-leaders"></div><div class="skel skel-panel area-upcoming"></div><div class="skel skel-panel area-news"></div>
  </div>`;
  const o = await api("/api/overview");
  renderFooter(o.meta);
  main.innerHTML = html`<div class="view">
    <h1 class="sr-only">FC Barcelona overview</h1>
    <div class="grid-overview">
      <section class="hero area-hero" aria-label="${o.live ? "Live match" : "Next match"}">${heroMain(o)}${heroLast(o)}</section>
      ${formPanel(o)}
      ${miniTable(o)}
      ${leadersPanel(o)}
      ${upcomingPanel(o)}
      ${newsPanel(o)}
    </div>
  </div>`;
  if (o.next && !o.live) {
    tickTimer = setInterval(() => {
      const el = main.querySelector(".countdown");
      const cells = countdownCells(o.next.date);
      if (el && cells) el.outerHTML = cells;
    }, 30000);
  }
  if (o.live) {
    liveRegion.textContent = `Live: ${o.live.home.name} ${o.live.home.score}, ${o.live.away.name} ${o.live.away.score}`;
    pollTimer = setTimeout(() => route(), 60000);
  }
}

async function viewMatches(tab = "results") {
  main.innerHTML = html`<div class="view"><div class="page-head"><h1 class="page-title">Matches</h1></div><div class="panel panel-body">${Array.from({ length: 8 }, () => html`<div class="skel skel-line" style="height:44px"></div>`)}</div></div>`;
  const { matches, meta } = await api("/api/matches");
  renderFooter(meta);
  const comps = [...new Set(matches.map((m) => compName(m.competition)).filter(Boolean))];
  let comp = "all";

  const draw = () => {
    const pool = matches.filter((m) => (tab === "results" ? m.status.state !== "pre" : m.status.state === "pre") && (comp === "all" || compName(m.competition) === comp));
    const list = tab === "results" ? [...pool].reverse() : pool;
    const groups = [];
    for (const m of list) {
      const k = fmt.month.format(new Date(m.date));
      if (!groups.length || groups.at(-1).k !== k) groups.push({ k, items: [] });
      groups.at(-1).items.push(m);
    }
    main.querySelector("#match-body").innerHTML = groups.length
      ? html`${groups.map((g) => html`<h2 class="month">${g.k}</h2><div class="match-list">${g.items.map(matchRow)}</div>`)}`
      : html`<div class="state">${icon("calendar-x")}<p>No ${tab === "results" ? "results" : "fixtures"} for this competition yet.</p></div>`;
    main.querySelectorAll("[data-comp]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.comp === comp)));
  };

  main.innerHTML = html`<div class="view">
    <div class="page-head">
      <div><h1 class="page-title">Matches</h1><p class="page-sub">${matches.filter((m) => m.status.state === "post").length} played, ${matches.filter((m) => m.status.state === "pre").length} to come. Times in ${zoneName(new Date())}.</p></div>
      ${tabs("match-tabs", [["results", "Results"], ["fixtures", "Fixtures"]], tab, (t) => `#/matches/${t}`)}
    </div>
    <div class="chips" role="group" aria-label="Filter by competition" style="margin-bottom:var(--s4)">
      <button class="chip" type="button" data-comp="all" aria-pressed="true">All</button>
      ${comps.map((c) => html`<button class="chip" type="button" data-comp="${c}" aria-pressed="false">${c}</button>`)}
    </div>
    <div class="panel panel-body" id="match-body"></div>
  </div>`;
  main.querySelectorAll("[data-comp]").forEach((b) => b.addEventListener("click", () => { comp = b.dataset.comp; draw(); }));
  draw();
}

/* Tabs are links (deep-linkable) with arrow-key roving focus. */
function tabs(id, items, active, hrefFor) {
  return html`<div class="seg" role="tablist" id="${id}" aria-label="Sections">
    ${items.map(([key, label]) => html`<a role="tab" href="${hrefFor(key)}" aria-selected="${key === active}" tabindex="${key === active ? 0 : -1}" style="display:inline-flex;align-items:center">${label}</a>`)}
  </div>`;
}
document.addEventListener("keydown", (e) => {
  const tab = e.target.closest?.('[role="tab"]');
  if (!tab || !["ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key)) return;
  const all = [...tab.parentElement.querySelectorAll('[role="tab"]')];
  let i = all.indexOf(tab);
  i = e.key === "Home" ? 0 : e.key === "End" ? all.length - 1 : (i + (e.key === "ArrowRight" ? 1 : -1) + all.length) % all.length;
  e.preventDefault();
  all[i].focus();
  all[i].click();
});

/* ---------- Match centre ---------- */

function eventIcon(ev) {
  if (ev.kind === "goal") return html`<span class="ev-ico goal">${icon("soccer-ball")}</span>`;
  if (ev.kind === "own-goal") return html`<span class="ev-ico own-goal">${icon("soccer-ball")}</span>`;
  if (ev.kind === "yellow" || ev.kind === "red") return html`<span class="ev-ico"><span class="card-shape ${ev.kind}"></span></span>`;
  if (ev.kind === "sub") return html`<span class="ev-ico">${icon("arrows-down-up")}</span>`;
  if (ev.kind === "pen-miss") return html`<span class="ev-ico">${icon("x")}</span>`;
  return "";
}

function eventText(ev) {
  const [a, b] = ev.players;
  switch (ev.kind) {
    case "goal": return html`<span class="who">${a || "Goal"}${ev.penalty ? " (pen)" : ""}${b ? html`<small>Assist: ${b}</small>` : ""}</span>`;
    case "own-goal": return html`<span class="who">${a || "Own goal"} (OG)</span>`;
    case "sub": return html`<span class="who">${a || ""}<small>Off: ${b || ""}</small></span>`;
    case "yellow": return html`<span class="who">${a || ""}<small>Yellow card</small></span>`;
    case "red": return html`<span class="who">${a || ""}<small>${ev.label || "Red card"}</small></span>`;
    case "pen-miss": return html`<span class="who">${a || ""}<small>${ev.label}</small></span>`;
    default: return html`<span class="who">${ev.label}</span>`;
  }
}

function timeline(d) {
  const evs = d.events || [];
  if (!evs.length) return html`<div class="state">${icon("clock")}<p>${d.match.status.state === "pre" ? "Key events will appear here once the match kicks off." : "No key events were recorded for this match."}</p></div>`;
  return html`<ol class="timeline" aria-label="Key events">${evs.map((ev) => {
    if (ev.kind === "period") return html`<li class="tl period"><span></span><span class="tl-min">${ev.label === "Halftime" ? "HT" : "FT"}</span><span></span></li>`;
    const body = html`<div class="tl-body ${ev.side || "home"}">${ev.side === "home" ? html`${eventText(ev)}${eventIcon(ev)}` : html`${eventIcon(ev)}${eventText(ev)}`}</div>`;
    return html`<li class="tl"><span class="sr-only">${ev.minute} ${ev.label}, ${ev.side === "home" ? d.match.home.name : d.match.away.name}.</span>${ev.side === "away" ? html`<span></span>` : ""}${ev.side !== "away" ? body : ""}<span class="tl-min">${ev.minute}</span>${ev.side === "away" ? body : html`<span></span>`}</li>`;
  })}</ol>`;
}

function fmtStat(s, v) {
  if (v == null) return "-";
  if (s.type === "pct") return `${n(v, v % 1 ? 1 : 0)}%`;
  if (s.type === "decimal") return n(v, 2);
  return n(v);
}

const LOWER_IS_BETTER = new Set(["foulsCommitted", "yellowCards", "redCards", "offsides"]);

function statsView(d) {
  const rows = d.stats || [];
  if (!rows.length) return html`<div class="state">${icon("chart-bar")}<p>${d.match.status.state === "pre" ? "Team stats will appear after kick-off." : "No team stats were published for this match."}</p></div>`;
  const m = d.match;
  return html`<div class="stat-rows">
    <div class="stat-head">${crest(m.home, 28, m.home.name)}<span class="sub-title">Team stats</span><span class="r">${crest(m.away, 28, m.away.name)}</span></div>
    ${rows.map((s, i) => {
      const h = s.home ?? 0, a = s.away ?? 0, total = h + a;
      const hp = total ? (h / total) * 100 : 50, ap = total ? (a / total) * 100 : 50;
      // Fouls, cards and offsides: fewer is better, so the lower side gets the accent.
      const better = LOWER_IS_BETTER.has(s.key) ? (x, y) => x < y : (x, y) => x > y;
      const hl = better(h, a) ? "lead" : "trail", al = better(a, h) ? "lead" : "trail";
      return html`<div class="stat" role="group" aria-label="${s.label}: ${m.home.short || m.home.name} ${fmtStat(s, s.home)}, ${m.away.short || m.away.name} ${fmtStat(s, s.away)}">
        <div class="stat-top" aria-hidden="true"><b class="${hl}">${fmtStat(s, s.home)}</b><span>${s.label}</span><b class="${al}">${fmtStat(s, s.away)}</b></div>
        <div class="bars" aria-hidden="true"><div class="bar home ${hl}" style="width:${hp.toFixed(1)}%;--i:${i}"></div><div class="bar away ${al}" style="width:${ap.toFixed(1)}%;--i:${i}"></div></div>
      </div>`;
    })}
    ${d.officials?.length ? html`<p class="news-meta" style="text-align:center">${d.officials.map((o) => `${o.role || "Official"}: ${o.name}`).join(". ")}</p>` : ""}
  </div>`;
}

/* Formation lines: rank players by role from position codes, then chunk by the formation string. */
const ROLE_RANK = (pos = "") => {
  const p = pos.toUpperCase();
  if (p === "G" || p === "GK") return 0;
  if (/^(CD|SW|RB|LB|D|CB|RWB|LWB)/.test(p)) return 1;
  if (/^DM/.test(p)) return 2;
  if (/^(CM|LM|RM|M)(-|$)/.test(p)) return 3;
  if (/^AM/.test(p)) return 4;
  return 5;
};
const LATERAL = (pos = "") => (/(^L|-L$)/.test(pos) ? 0 : /(^R|-R$)/.test(pos) ? 2 : 1);

function formationLines(starters, formation) {
  const gk = starters.filter((p) => ROLE_RANK(p.pos) === 0);
  const out = starters.filter((p) => ROLE_RANK(p.pos) !== 0).sort((a, b) => ROLE_RANK(a.pos) - ROLE_RANK(b.pos) || a.place - b.place);
  const counts = (formation || "").split("-").map(Number).filter((x) => x > 0);
  const sum = counts.reduce((s, x) => s + x, 0);
  const shape = sum === out.length ? counts : [out.length];
  const lines = [gk.length ? gk : out.splice(0, 1)];
  let i = 0;
  for (const c of shape) { lines.push(out.slice(i, i + c)); i += c; }
  // Left to right from the attacking team's point of view.
  return lines.map((l) => [...l].sort((a, b) => LATERAL(a.pos) - LATERAL(b.pos) || a.place - b.place));
}

function playerDot(p, isFcb, events) {
  const goals = events.filter((e) => e.kind === "goal" && e.players[0] === p.name).length;
  const card = events.find((e) => (e.kind === "yellow" || e.kind === "red") && e.players[0] === p.name);
  const gk = ROLE_RANK(p.pos) === 0;
  const label = `${p.number ? `Number ${p.number}, ` : ""}${p.name}${goals ? `, ${goals} goal${goals > 1 ? "s" : ""}` : ""}${p.subbedOut ? `, subbed off ${p.subMinute || ""}` : ""}${p.rating ? `, rating ${p.rating}` : ""}`;
  return html`<li class="pl" aria-label="${label}">
    <span class="pl-dot ${gk ? "gk" : isFcb ? "fcb" : ""}" aria-hidden="true">${p.number || ""}
      ${goals ? html`<span class="badge goal">${goals > 1 ? `${goals}G` : "G"}</span>` : ""}
      ${p.subbedOut ? html`<span class="badge off">${p.subMinute || "off"}</span>` : ""}
      ${card ? html`<span class="badge card ${card.kind}"></span>` : ""}
      ${p.rating ? html`<span class="badge rating">${p.rating}</span>` : ""}
    </span>
    <span class="pl-name" aria-hidden="true">${surname(p.name)}</span>
  </li>`;
}

function teamLineup(d, side) {
  const lu = d.lineups?.[side];
  const team = d.match[side];
  const isFcb = team.id === FCB;
  if (!lu || !lu.starters?.length) return "";
  const lines = formationLines(lu.starters, lu.formation);
  return html`<section aria-label="${team.name} lineup">
    <div class="lu-head">${crest(team, 32)}<b>${team.short || team.name}</b>${lu.formation ? html`<span class="formation" aria-label="Formation ${lu.formation}">${lu.formation}</span>` : ""}</div>
    <div class="pitch">
      <div class="pitch-lines" aria-hidden="true"><div class="box bottom"></div><div class="box top"></div></div>
      ${lines.map((l) => html`<ul class="line" role="list" style="list-style:none;margin:0;padding:0">${l.map((p) => playerDot(p, isFcb, d.events || []))}</ul>`)}
    </div>
    <ul class="bench" aria-label="Substitutes">
      ${lu.subs.map((p) => html`<li><span class="no">${p.number}</span><span class="${p.subbedIn ? "" : "unused"}">${p.name}</span>${p.subbedIn ? html`<span class="in">${icon("arrow-up")}${p.subMinute || ""}<span class="sr-only"> came on</span></span>` : html`<span></span>`}</li>`)}
    </ul>
  </section>`;
}

function lineupsView(d) {
  const has = ["home", "away"].some((s) => d.lineups?.[s]?.starters?.length);
  if (!has) return html`<div class="state">${icon("users-three")}<p>${d.match.status.state === "pre" ? "No lineups yet. They're usually published about an hour before kick-off." : "Lineups weren't published for this match."}</p></div>`;
  return html`<div class="lineups">${teamLineup(d, "home")}${teamLineup(d, "away")}</div>`;
}

function preview(d, ctx) {
  const m = d.match, opp = opponent(m);
  const rows = ctx.standings?.rows || [];
  const pos = (id) => rows.find((r) => r.team.id === id);
  const table = [pos(m.home.id), pos(m.away.id)].filter(Boolean).sort((a, b) => a.rank - b.rank);
  const played = (ctx.matches || []).filter((x) => x.status.state === "post");
  const form = played.slice(-5).reverse();
  const meetings = played.filter((x) => x.home.id === opp.id || x.away.id === opp.id).reverse();
  return html`<div class="preview">
    ${table.length === 2 ? html`<section><h3 class="sub-title">League position</h3>${standingsTable(table, false)}</section>` : ""}
    <section><h3 class="sub-title">Barcelona form</h3>
      ${form.length ? html`<div class="form-row" style="margin-top:var(--s3)">${form.map((x) => { const o = opponent(x); return html`<a class="form-item" href="#/match/${x.id}" aria-label="${x.result} ${x.fcbSide === "home" ? "vs" : "at"} ${o.name}, ${scoreText(x)}">${crest(o, 32)}<span class="form-score num">${scoreText(x)?.split(" ")[0]}</span>${wdl(x.result)}</a>`; })}</div>` : html`<p class="page-sub">No matches played yet.</p>`}
    </section>
    <section><h3 class="sub-title">Earlier meetings this season</h3>
      ${meetings.length ? html`<div class="match-list">${meetings.map(matchRow)}</div>` : html`<p class="page-sub" style="margin-top:var(--s2)">First meeting of the season with ${opp.name}.</p>`}
    </section>
  </div>`;
}

async function viewMatch(id, tab) {
  main.innerHTML = html`<div class="view"><div class="skel skel-hero"></div><div class="skel skel-panel" style="margin-top:16px"></div></div>`;
  const d = await api(`/api/matches/${encodeURIComponent(id)}`);
  const m = d.match;
  const state = m.status.state;
  tab = tab || "summary";
  const w = new Date(m.date);
  const ctx = {};
  if (state === "pre" && tab === "summary") {
    const [st, ms] = await Promise.allSettled([api("/api/standings"), api("/api/matches")]);
    if (st.status === "fulfilled") ctx.standings = st.value.standings;
    if (ms.status === "fulfilled") ctx.matches = ms.value.matches;
  }
  const summary = state === "pre" ? (x) => preview(x, ctx) : timeline;
  const content = { summary, stats: statsView, lineups: lineupsView }[tab] || summary;
  main.innerHTML = html`<div class="view">
    <a class="back" href="#/matches/${state === "pre" ? "fixtures" : "results"}">${icon("arrow-left")} Matches</a>
    <section class="hero scoreboard" aria-label="Scoreboard" style="display:block">
      <div class="kicker" style="justify-content:center">
        ${state === "in" ? html`<span class="live-flag">Live</span>` : ""}
        <span class="comp">${compName(m.competition)}</span>${m.note ? html`<span>${m.note}</span>` : ""}
      </div>
      <div class="fixture">
        ${sideBlock(m.home, "Home")}
        ${state === "pre" ? html`<div class="vs">${fmt.time.format(w)}</div>` : bigScore(m)}
        ${sideBlock(m.away, "Away")}
      </div>
      <div class="status-line">${state === "pre" ? fmt.dayLong.format(w) : m.status.detail}${m.home.shootout != null ? `. Penalties ${m.home.shootout}-${m.away.shootout}` : ""}</div>
      ${state === "pre" ? html`<div style="margin-top:var(--s4)">${countdownCells(m.date) || ""}</div>` : ""}
      <div class="facts">
        ${state !== "pre" ? html`<span>${icon("calendar-blank")}${fmt.dayLong.format(w)}</span>` : ""}
        ${d.venue || m.venue?.name ? html`<span>${icon("map-pin")}${d.venue || m.venue.name}</span>` : ""}
        ${d.attendance ? html`<span>${icon("users")}${Number(d.attendance).toLocaleString()} fans</span>` : ""}
        ${m.broadcasts?.length ? html`<span>${icon("television-simple")}${m.broadcasts.join(", ")}</span>` : ""}
      </div>
    </section>
    <div class="tabs-row">${tabs("match-tabs", [["summary", state === "pre" ? "Preview" : "Summary"], ["stats", "Stats"], ["lineups", "Lineups"]], tab, (t) => `#/match/${m.id}/${t}`)}</div>
    <div class="panel panel-body" role="tabpanel" aria-label="${tab}">${content(d)}</div>
  </div>`;
  if (state === "in") {
    liveRegion.textContent = `${m.home.name} ${m.home.score}, ${m.away.name} ${m.away.score}. ${m.status.detail || ""}`;
    pollTimer = setTimeout(() => route(), 30000);
  }
}

/* ---------- Squad ---------- */

const GROUPS = [["G", "Goalkeepers"], ["D", "Defenders"], ["M", "Midfielders"], ["F", "Forwards"]];

async function viewSquad() {
  main.innerHTML = html`<div class="view"><div class="page-head"><h1 class="page-title">Squad</h1></div><div class="players">${Array.from({ length: 9 }, () => html`<div class="skel" style="height:150px;border-radius:14px"></div>`)}</div></div>`;
  const { squad, meta } = await api("/api/squad");
  renderFooter(meta);
  let sort = "number";
  const sorters = {
    number: (a, b) => (Number(a.number) || 99) - (Number(b.number) || 99),
    goals: (a, b) => (b.stats.goals ?? 0) - (a.stats.goals ?? 0),
    assists: (a, b) => (b.stats.assists ?? 0) - (a.stats.assists ?? 0),
    apps: (a, b) => (b.stats.apps ?? 0) - (a.stats.apps ?? 0),
    age: (a, b) => (a.age ?? 99) - (b.age ?? 99),
  };
  const card = (p) => {
    const gk = p.pos === "G";
    const cells = gk
      ? [["apps", "Apps"], ["saves", "Saves"], ["conceded", "Conc."], ["yellow", "YC"]]
      : [["apps", "Apps"], ["goals", "Goals"], ["assists", "Assists"], ["shots", "Shots"]];
    return html`<article class="panel player" aria-label="${p.name}">
      ${avatar(p, 56)}
      <div style="min-width:0">
        <div class="player-name">${p.name}</div>
        <div class="player-meta">${safeUrl(p.flag) ? html`<img src="${p.flag}" alt="" width="16" height="11" loading="lazy">` : ""}${p.nationality || ""}${p.age ? `, ${p.age}` : ""}</div>
        ${p.injured ? html`<div class="player-meta injury">${icon("first-aid")}Injured</div>` : ""}
      </div>
      <span class="shirt" aria-label="Shirt number ${p.number || "unknown"}">${p.number || ""}</span>
      <div class="pstats">${cells.map(([k, l]) => html`<div><b>${p.stats[k] ?? "-"}</b><span>${l}</span></div>`)}</div>
    </article>`;
  };
  const draw = () => {
    main.querySelector("#squad-body").innerHTML = html`${GROUPS.map(([code, title]) => {
      const list = squad.filter((p) => p.pos === code).sort(sorters[sort]);
      if (!list.length) return "";
      return html`<section class="squad-group" aria-labelledby="g-${code}"><h2 id="g-${code}">${title} <small>${list.length}</small></h2><div class="players">${list.map(card)}</div></section>`;
    })}`;
  };
  main.innerHTML = html`<div class="view">
    <div class="page-head">
      <div><h1 class="page-title">Squad</h1><p class="page-sub">${squad.length} players. League stats this season from ESPN.</p></div>
      <div class="toolbar"><label class="inline" for="sort">Sort by</label>
        <span class="select"><select id="sort">
          <option value="number">Shirt number</option><option value="goals">Goals</option><option value="assists">Assists</option><option value="apps">Appearances</option><option value="age">Age</option>
        </select>${icon("caret-down")}</span></div>
    </div>
    <div id="squad-body"></div>
  </div>`;
  main.querySelector("#sort").addEventListener("change", (e) => { sort = e.target.value; draw(); });
  draw();
}

/* ---------- Table ---------- */

async function viewTable() {
  main.innerHTML = html`<div class="view"><div class="page-head"><h1 class="page-title">Table</h1></div><div class="panel panel-body">${Array.from({ length: 12 }, () => html`<div class="skel skel-line" style="height:28px"></div>`)}</div></div>`;
  const { standings, meta } = await api("/api/standings");
  renderFooter(meta);
  const fcb = standings.rows.find((r) => r.team.id === FCB);
  main.innerHTML = html`<div class="view">
    <div class="page-head"><div><h1 class="page-title">${(standings.league || "Table").replace(/^Spanish /, "")}</h1>
      <p class="page-sub">${standings.rows.length} teams.${fcb ? ` Barcelona ${fcb.rank}${["", "st", "nd", "rd"][fcb.rank] || "th"} on ${fcb.points} points after ${fcb.played} games.` : ""}</p></div></div>
    <div class="panel panel-body">${standingsTable(standings.rows, true)}</div>
  </div>`;
}

/* ---------- News ---------- */

async function viewNews() {
  main.innerHTML = html`<div class="view"><div class="page-head"><h1 class="page-title">News</h1></div><div class="news-grid">${Array.from({ length: 6 }, () => html`<div class="skel" style="height:220px;border-radius:14px"></div>`)}</div></div>`;
  const { news, meta } = await api("/api/news");
  renderFooter(meta);
  let origin = "all";
  const draw = () => {
    const list = news.filter((a) => origin === "all" || a.origin === origin);
    const lead = list.find((a) => safeUrl(a.image));
    const pictured = list.filter((a) => a !== lead && safeUrl(a.image));
    const headlines = list.filter((a) => a !== lead && !safeUrl(a.image));
    const cardHtml = (a, isLead = false) => html`<a class="panel news-card ${isLead ? "lead" : ""}" href="${safeUrl(a.url) || "#"}" target="_blank" rel="noopener noreferrer">
      ${safeUrl(a.image) ? html`<div class="thumb"><img src="${a.image}" alt="" loading="${isLead ? "eager" : "lazy"}"></div>` : ""}
      <div class="body"><span class="src">${a.source || ""}</span><span class="news-title">${a.title}</span>${a.summary ? html`<p>${a.summary}</p>` : ""}<span class="news-meta">${ago(a.published)}</span></div></a>`;
    main.querySelector("#news-body").innerHTML = list.length
      ? html`${lead || pictured.length ? html`<div class="news-grid">${lead ? cardHtml(lead, true) : ""}${pictured.map((a) => cardHtml(a))}</div>` : ""}
        ${headlines.length ? html`<section class="panel panel-body headlines" aria-labelledby="hl-h"><h2 class="panel-title" id="hl-h">More headlines</h2>${newsList(headlines)}</section>` : ""}`
      : html`<div class="panel state">${icon("newspaper")}<p>No stories from this source right now.</p></div>`;
    main.querySelectorAll("[data-origin]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.origin === origin)));
  };
  main.innerHTML = html`<div class="view">
    <div class="page-head"><div><h1 class="page-title">News</h1><p class="page-sub">The last seven days from Google News and ESPN.</p></div>
      <div class="chips" role="group" aria-label="Filter by source">
        <button class="chip" type="button" data-origin="all" aria-pressed="true">All</button>
        <button class="chip" type="button" data-origin="google" aria-pressed="false">Google News</button>
        <button class="chip" type="button" data-origin="espn" aria-pressed="false">ESPN</button>
      </div></div>
    <div id="news-body"></div>
  </div>`;
  main.querySelectorAll("[data-origin]").forEach((b) => b.addEventListener("click", () => { origin = b.dataset.origin; draw(); }));
  draw();
}

/* ---------------- Router ---------------- */

const TITLES = { overview: "FCB Tracker", matches: "Matches", match: "Match centre", squad: "Squad", table: "Table", news: "News" };
let lastRoute = null;

async function route() {
  clearInterval(tickTimer); clearTimeout(pollTimer);
  const parts = location.hash.replace(/^#\/?/, "").split("/").filter(Boolean);
  const name = parts[0] || "overview";
  const navKey = name === "match" ? "matches" : name;
  document.querySelectorAll("[data-nav]").forEach((a) => (a.dataset.nav === navKey ? a.setAttribute("aria-current", "page") : a.removeAttribute("aria-current")));
  document.title = name === "overview" ? TITLES.overview : `${TITLES[name] || "FCB Tracker"} | FCB Tracker`;
  const key = location.hash;
  const views = {
    overview: () => viewOverview(),
    matches: () => viewMatches(parts[1] === "fixtures" ? "fixtures" : "results"),
    match: () => viewMatch(parts[1], parts[2]),
    squad: () => viewSquad(),
    table: () => viewTable(),
    news: () => viewNews(),
  };
  try {
    await (views[name] || (() => { throw new ApiError(404, { message: "Page not found" }); }))();
  } catch (err) {
    console.error(err);
    main.innerHTML = html`<div class="view">${errorState(err)}</div>`;
  }
  const samePage = lastRoute && lastRoute.split("/").slice(0, 3).join("/") === key.split("/").slice(0, 3).join("/");
  if (!samePage) { window.scrollTo(0, 0); if (lastRoute !== null) main.focus({ preventScroll: true }); }
  else if (!main.contains(document.activeElement)) main.querySelector('[role="tab"][aria-selected="true"]')?.focus({ preventScroll: true });
  lastRoute = key;
}

main.addEventListener("click", (e) => { if (e.target.closest('[data-action="retry"]')) route(); });
window.addEventListener("hashchange", route);
paintThemeButton();
route();
