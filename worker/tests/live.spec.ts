import { test, expect } from "@playwright/test";
import { execFileSync } from "node:child_process";

// Live path, end to end: find a match that is in progress right now on ESPN (any competition),
// have the Worker fetch it itself, then check the match centre shows it live. Skips when nothing
// is being played anywhere at test time.
function liveNow(): { id: string; teamId: string; home: number; away: number } | null {
  const day = new Date().toISOString().slice(0, 10).replaceAll("-", "");
  const raw = execFileSync("curl", ["-sf", "--max-time", "20", `https://site.web.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard?dates=${day}`], { encoding: "utf8" });
  for (const e of JSON.parse(raw).events ?? []) {
    const c = e.competitions[0];
    if (c.status.type.state !== "in") continue;
    const h = c.competitors.find((x: any) => x.homeAway === "home"), a = c.competitors.find((x: any) => x.homeAway === "away");
    return { id: e.id, teamId: a.team.id, home: Number(h.score), away: Number(a.score) };
  }
  return null;
}

test("Worker fetches a live match itself and the match centre shows it", async ({ page, request }) => {
  const live = liveNow();
  test.skip(!live, "no football match is in progress on ESPN right now");
  const res = await request.post(`/api/live/refresh?event=${live!.id}&league=all&team=${live!.teamId}`, {
    headers: { authorization: `Bearer ${process.env.E2E_INGEST_TOKEN}` },
  });
  const body = await res.json();
  expect(res.status(), JSON.stringify(body)).toBe(200);
  expect(body.source).toMatch(/fetched by the Worker/);
  expect(["in", "post"]).toContain(body.match.status.state);

  await page.goto(`/#/match/${live!.id}`);
  const score = page.locator(".scoreboard .bigscore");
  await expect(score).toHaveAttribute("aria-label", new RegExp(`${body.match.home.score}.*${body.match.away.score}`));
  // The score can only have moved forward since we read ESPN's scoreboard.
  expect(body.match.home.score).toBeGreaterThanOrEqual(live!.home);
  expect(body.match.away.score).toBeGreaterThanOrEqual(live!.away);
  await expect(page.locator(".status-line")).toContainText("fetched by the Worker");
  if (body.match.status.state === "in") await expect(page.locator(".scoreboard .live-flag")).toBeVisible();
});
