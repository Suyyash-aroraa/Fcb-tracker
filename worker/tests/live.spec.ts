import { test, expect } from "@playwright/test";

// Live path, end to end: when a Barcelona fixture is in progress, have the Worker fetch it itself
// and check the match centre shows it live. Skips when Barcelona are not playing at test time.
test("Worker fetches Barcelona's live match itself and the match centre shows it", async ({ page, request }) => {
  const { matches } = await (await request.get("/api/matches")).json();
  const live = matches.find((m: any) => m.status.state === "in");
  test.skip(!live, "Barcelona are not playing right now");

  const res = await request.post(`/api/live/refresh?event=${live.id}`, {
    headers: { authorization: `Bearer ${process.env.E2E_INGEST_TOKEN}` },
  });
  const body = await res.json();
  expect(res.status(), JSON.stringify(body)).toBe(200);
  expect(body.source).toMatch(/fetched by the Worker/);
  expect([body.match.home.id, body.match.away.id]).toContain("83");

  await page.goto(`/#/match/${live.id}`);
  await expect(page.locator(".scoreboard .bigscore")).toHaveAttribute("aria-label", new RegExp(`${body.match.home.score}.*${body.match.away.score}`));
  await expect(page.locator(".status-line")).toContainText("fetched by the Worker");
  if (body.match.status.state === "in") await expect(page.locator(".scoreboard .live-flag")).toBeVisible();
});

test("manual live refresh only accepts Barcelona fixtures", async ({ request }) => {
  const res = await request.post("/api/live/refresh?event=999999999999", {
    headers: { authorization: `Bearer ${process.env.E2E_INGEST_TOKEN}` },
  });
  expect(res.status()).toBe(404);
});
