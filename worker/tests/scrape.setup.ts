import { test as setup, expect } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

// Runs the real Scrapling scraper against the local `wrangler dev` Worker. No fixtures:
// every later test asserts against whatever ESPN / Google News / SofaScore return right now.
setup("scrape live data with Scrapling into the Worker", async ({ request, baseURL }) => {
  const scraperDir = resolve(here, "../../scraper");
  const venvPython = resolve(here, "../../.venv/bin/python");
  const python = process.env.PYTHON ?? (existsSync(venvPython) ? venvPython : "python3");

  const run = spawnSync(python, ["-m", "fcb_scraper", "--push", baseURL!], {
    cwd: scraperDir,
    env: { ...process.env, INGEST_TOKEN: process.env.E2E_INGEST_TOKEN },
    encoding: "utf8",
    timeout: 540_000,
  });
  const log = `${run.stdout}\n${run.stderr}`;
  console.log(log.split("\n").filter((l) => /scraped|ingest|failed/.test(l)).join("\n"));
  expect(run.status, log.slice(-3000)).toBe(0);
  expect(log).toMatch(/ingest -> 200/);

  const health = await (await request.get("/api/health")).json();
  expect(health.updatedAt, "meta written by ingest").toBeTruthy();
  const meta = (await (await request.get("/api/matches")).json()).meta;
  expect(meta.sources.espn.ok, `ESPN via Scrapling: ${meta.sources.espn.detail}`).toBe(true);
  expect(meta.sources.google.ok, `Google News via Scrapling: ${meta.sources.google.detail}`).toBe(true);
  // SofaScore is best effort: it blocks many datacenter IPs. Report, don't fail.
  console.log("SofaScore:", JSON.stringify(meta.sources.sofascore));
});
