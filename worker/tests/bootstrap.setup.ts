import { test as setup, expect } from "@playwright/test";

// The Worker starts with an empty KV store. The first API request must make it fetch fixtures itself
// before answering; the cron fills in the rest one step a minute. The test then runs the remaining
// steps through the cron handler (ESPN, fcbarcelona.com, Google News, parsed by Scrapling in the
// Python Worker). No fixtures: every later test asserts against whatever those sites return now.
setup("the Worker fills an empty store by scraping every source itself", async ({ request }) => {
  const before = await (await request.get("/api/health")).json();
  expect(before.updatedAt, "store must start empty").toBeNull();

  const first = await request.get("/api/overview", { timeout: 240_000 });
  expect(first.status()).toBe(200);
  expect((await first.json()).team.name).toBe("Barcelona");

  // Drive the real cron handler until every sync step has run (one step per invocation).
  for (let i = 0; i < 12; i++) {
    const cron = await request.get("/cdn-cgi/local/scheduled?cron=*+*+*+*+*", { timeout: 120_000 });
    expect(cron.ok()).toBe(true);
    const sync = (await (await request.get("/api/health")).json()).sources ?? {};
    if (["espn", "official", "google"].every((s) => sync[s])) {
      const done = await (await request.get("/api/news")).status();
      if (done === 200 && (await request.get("/api/squad")).status() === 200 && (await request.get("/api/standings")).status() === 200) break;
    }
  }
  const o = await (await request.get("/api/overview")).json();
  expect(o.meta.scraper).toMatch(/Scrapling/);
  for (const source of ["espn", "official", "google"]) {
    expect(o.meta.sources[source]?.ok, `${source}: ${o.meta.sources[source]?.detail}`).toBe(true);
  }
  console.log("sources:", JSON.stringify(o.meta.sources));
});
