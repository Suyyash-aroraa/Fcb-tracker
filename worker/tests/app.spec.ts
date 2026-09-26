import { test, expect, type Page } from "@playwright/test";

// Every expectation is derived from the live API, which the scrape setup project just filled.

const pageErrors = (page: Page) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  page.on("console", (m) => {
    // External crest/font hosts can be flaky or blocked; only our own origin must be clean.
    if (m.type() === "error" && !/Failed to load resource/.test(m.text())) errors.push(m.text());
  });
  page.on("response", (r) => {
    if (r.url().startsWith(page.url().split("#")[0].replace(/\/$/, "")) && r.status() >= 400) errors.push(`${r.status()} ${r.url()}`);
  });
  return errors;
};

async function noHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, "page must not scroll horizontally").toBeLessThanOrEqual(1);
}

async function noDashes(page: Page) {
  // UIUXmasterclass pre-flight: no em/en dashes in visible copy.
  const text = await page.locator("body").innerText();
  expect(text).not.toMatch(/[–—]/);
}

test.describe("API", () => {
  test("serves scraped data", async ({ request }) => {
    const o = await (await request.get("/api/overview")).json();
    expect(o.team.name).toBe("Barcelona");
    expect(o.meta.scraper).toBe("scrapling");
    expect(Array.isArray(o.form)).toBe(true);
    const { matches } = await (await request.get("/api/matches")).json();
    expect(matches.length).toBeGreaterThan(5);
    for (const m of matches) {
      expect([m.home.id, m.away.id]).toContain("83");
      expect(["pre", "in", "post"]).toContain(m.status.state);
    }
  });

  test("rejects unauthenticated or malformed ingest", async ({ request }) => {
    expect((await request.post("/api/ingest", { data: { items: { team: {} } } })).status()).toBe(401);
    expect((await request.post("/api/ingest", { headers: { authorization: "Bearer wrong" }, data: { items: {} } })).status()).toBe(401);
    const bad = await request.post("/api/ingest", {
      headers: { authorization: `Bearer ${process.env.E2E_INGEST_TOKEN}` },
      data: { items: { "../etc": 1 } },
    });
    expect(bad.status()).toBe(400);
    expect((await request.get("/api/ingest")).status()).toBe(405);
    expect((await request.get("/api/matches/999999999999")).status()).toBe(404);
    expect((await request.get("/api/nope")).status()).toBe(404);
  });
});

test.describe("UI", () => {
  test("overview shows next match, last result, form, table, leaders and news", async ({ page, request }) => {
    const errors = pageErrors(page);
    const o = await (await request.get("/api/overview")).json();
    await page.goto("/");

    const hero = page.locator("section.hero");
    await expect(hero).toBeVisible();
    const headline = o.live ?? o.next;
    if (headline) {
      await expect(hero.locator(".side-name").first()).toHaveText(new RegExp(headline.home.short || headline.home.name, "i"));
      await expect(hero.locator(".side-name").nth(1)).toHaveText(new RegExp(headline.away.short || headline.away.name, "i"));
    }
    if (o.last) {
      const aside = page.getByRole("complementary", { name: "Last result" });
      await expect(aside.locator(".bigscore")).toHaveAttribute("aria-label", new RegExp(`${o.last.match.home.score}.*${o.last.match.away.score}`));
    }
    await expect(page.locator(".area-form .form-item")).toHaveCount(o.form.length);
    if (o.standings?.position) {
      await expect(page.locator(".area-table tr.is-fcb .pts")).toHaveText(String(o.standings.position.points));
    }
    if (o.leaders.goals[0]) {
      await expect(page.locator(".area-leaders .leader").first().locator(".leader-name")).toHaveText(o.leaders.goals[0].name);
    }
    await expect(page.locator(".area-news .news-item")).toHaveCount(Math.min(o.news.length, 5));
    await expect(page.locator("footer")).toContainText("Scrapling");
    await noHorizontalScroll(page);
    await noDashes(page);
    expect(errors).toEqual([]);
  });

  test("results list, filter and match centre tabs", async ({ page, request }) => {
    const errors = pageErrors(page);
    const { matches } = await (await request.get("/api/matches")).json();
    const played = matches.filter((m: any) => m.status.state !== "pre");
    await page.goto("/#/matches");
    await expect(page.locator("#match-body .match-row")).toHaveCount(played.length);

    // Competition filter chips
    const league = played.find((m: any) => /LALIGA/i.test(m.competition.name));
    if (league) {
      await page.getByRole("button", { name: "LALIGA", exact: true }).click();
      await expect(page.getByRole("button", { name: "LALIGA", exact: true })).toHaveAttribute("aria-pressed", "true");
      const n = played.filter((m: any) => m.competition.name === league.competition.name).length;
      await expect(page.locator("#match-body .match-row")).toHaveCount(n);
    }

    // Open the most recent result
    const latest = played.at(-1);
    await page.locator(`a.match-row[href="#/match/${latest.id}"]`).first().click();
    await expect(page).toHaveURL(new RegExp(`#/match/${latest.id}`));
    const detail = await (await request.get(`/api/matches/${latest.id}`)).json();
    await expect(page.locator(".scoreboard .bigscore")).toHaveAttribute("aria-label", new RegExp(`${latest.home.score}.*${latest.away.score}`));

    // Summary timeline
    const shown = detail.events.length;
    if (shown) await expect(page.locator(".timeline > li")).toHaveCount(shown);

    // Stats tab via keyboard (roving tabs)
    await page.getByRole("tab", { name: "Summary" }).focus();
    await page.keyboard.press("ArrowRight");
    await expect(page).toHaveURL(new RegExp(`#/match/${latest.id}/stats`));
    await expect(page.getByRole("tab", { name: "Stats" })).toHaveAttribute("aria-selected", "true");
    await expect(page.getByRole("tab", { name: "Stats" })).toBeFocused();
    if (detail.stats.length) {
      await expect(page.locator(".stat")).toHaveCount(detail.stats.length);
      const poss = detail.stats.find((s: any) => s.key === "possessionPct");
      if (poss) await expect(page.getByRole("group", { name: /^Possession:/ })).toContainText(`${poss.home}%`);
    }

    // Lineups tab: 11 per side on the pitch + bench
    await page.getByRole("tab", { name: "Lineups" }).click();
    for (const side of ["home", "away"] as const) {
      const lu = detail.lineups[side];
      if (!lu?.starters?.length) continue;
      const section = page.getByRole("region", { name: `${detail.match[side].name} lineup` });
      await expect(section.locator(".pitch .pl")).toHaveCount(lu.starters.length);
      await expect(section.locator(".bench li")).toHaveCount(lu.subs.length);
      if (lu.formation) await expect(section.locator(".formation")).toHaveText(lu.formation);
    }
    await noHorizontalScroll(page);
    await noDashes(page);
    expect(errors).toEqual([]);
  });

  test("fixtures and pre-match preview", async ({ page, request }) => {
    const errors = pageErrors(page);
    const { matches } = await (await request.get("/api/matches")).json();
    const upcoming = matches.filter((m: any) => m.status.state === "pre");
    test.skip(!upcoming.length, "no fixtures scheduled right now");
    await page.goto("/#/matches/fixtures");
    await expect(page.locator("#match-body .match-row")).toHaveCount(upcoming.length);
    await page.locator(`a.match-row[href="#/match/${upcoming[0].id}"]`).click();
    await expect(page.getByRole("tab", { name: "Preview" })).toHaveAttribute("aria-selected", "true");
    await expect(page.locator(".countdown")).toBeVisible();
    await expect(page.locator(".preview")).toContainText("Barcelona form");
    await page.getByRole("tab", { name: "Lineups" }).click();
    const detail = await (await request.get(`/api/matches/${upcoming[0].id}`)).json();
    if (!detail.lineups?.home?.starters?.length) await expect(page.locator(".state")).toContainText("No lineups yet");
    await noHorizontalScroll(page);
    expect(errors).toEqual([]);
  });

  test("squad groups and sorting", async ({ page, request }) => {
    const errors = pageErrors(page);
    const { squad } = await (await request.get("/api/squad")).json();
    await page.goto("/#/squad");
    await expect(page.locator("article.player")).toHaveCount(squad.filter((p: any) => ["G", "D", "M", "F"].includes(p.pos)).length);
    await page.getByLabel("Sort by").selectOption("goals");
    const fwd = squad.filter((p: any) => p.pos === "F").sort((a: any, b: any) => (b.stats.goals ?? 0) - (a.stats.goals ?? 0));
    if (fwd.length) await expect(page.locator("#g-F + .players article.player").first().locator(".player-name")).toHaveText(fwd[0].name);
    await noHorizontalScroll(page);
    await noDashes(page);
    expect(errors).toEqual([]);
  });

  test("full table highlights Barcelona", async ({ page, request }) => {
    const { standings } = await (await request.get("/api/standings")).json();
    await page.goto("/#/table");
    await expect(page.locator("tbody tr")).toHaveCount(standings.rows.length);
    const fcb = standings.rows.find((r: any) => r.team.id === "83");
    await expect(page.locator("tr.is-fcb")).toHaveCount(1);
    await expect(page.locator("tr.is-fcb td.rank")).toHaveText(String(fcb.rank));
    await noHorizontalScroll(page);
  });

  test("news from Google News and ESPN, filterable", async ({ page, request }) => {
    const { news } = await (await request.get("/api/news")).json();
    await page.goto("/#/news");
    const total = page.locator(".news-card, .headlines .news-item");
    await expect(total).toHaveCount(news.length);
    await page.getByRole("button", { name: "Google News" }).click();
    await expect(total).toHaveCount(news.filter((a: any) => a.origin === "google").length);
    for (const href of await page.locator(".headlines a, a.news-card").evaluateAll((as) => as.map((a) => a.getAttribute("href")))) {
      expect(href).toMatch(/^https?:\/\//);
    }
    await noHorizontalScroll(page);
  });

  test("theme toggle persists and keeps contrast tokens", async ({ page }) => {
    await page.goto("/");
    const before = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    await page.locator("#theme-toggle").click();
    const theme = await page.evaluate(() => document.documentElement.dataset.theme);
    expect(["light", "dark"]).toContain(theme);
    const after = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    expect(after).not.toBe(before);
    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("data-theme", theme!);
  });

  test("unknown routes and matches show a recoverable state", async ({ page }) => {
    await page.goto("/#/match/999999999999");
    await expect(page.getByRole("heading", { name: "Not found" })).toBeVisible();
    await page.getByRole("link", { name: "Back to overview" }).click();
    await expect(page.locator("section.hero")).toBeVisible();
  });

  test("keyboard: skip link and visible focus", async ({ page, isMobile }) => {
    test.skip(isMobile, "keyboard flow is desktop only");
    await page.goto("/");
    await page.keyboard.press("Tab");
    await expect(page.getByRole("link", { name: "Skip to content" })).toBeFocused();
    await page.keyboard.press("Tab");
    const outline = await page.evaluate(() => getComputedStyle(document.activeElement!).outlineStyle);
    expect(outline).not.toBe("none");
  });
});
