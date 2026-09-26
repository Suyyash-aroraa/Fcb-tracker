import { test as setup, expect } from "@playwright/test";

// The Worker starts with an empty KV store. The first API request must make it scrape everything
// itself (ESPN, fcbarcelona.com, Google News, parsed by Scrapling in the Python Worker) before
// answering. No fixtures: every later test asserts against whatever those sites return right now.
setup("the Worker fills an empty store by scraping every source itself", async ({ request }) => {
  const before = await (await request.get("/api/health")).json();
  expect(before.updatedAt, "store must start empty").toBeNull();

  const res = await request.get("/api/overview", { timeout: 240_000 });
  expect(res.status()).toBe(200);
  const o = await res.json();
  expect(o.team.name).toBe("Barcelona");
  expect(o.meta.scraper).toMatch(/Scrapling/);
  for (const source of ["espn", "official", "google"]) {
    expect(o.meta.sources[source]?.ok, `${source}: ${o.meta.sources[source]?.detail}`).toBe(true);
  }
  for (const key of ["squad", "standings", "news"]) expect((await request.get(`/api/${key}`)).status()).toBe(200);
  const { matches } = await (await request.get("/api/matches")).json();
  for (const m of matches.filter((x: any) => x.status.state === "post")) {
    const d = await (await request.get(`/api/matches/${m.id}`)).json();
    expect(d.stats.length + d.events.length, `detail for ${m.id}`).toBeGreaterThan(0);
  }
  console.log("sources:", JSON.stringify(o.meta.sources));

  // Fresh data: a second request must not start another background refresh.
  expect((await (await request.get("/api/overview")).json()).refreshing).toBe(false);
});
