import { defineConfig, devices } from "@playwright/test";

// End-to-end: `wrangler dev` serves the Worker, the "scrape" project runs the real Scrapling
// scraper against it, then the UI suites run in desktop and mobile Chromium.
const PORT = Number(process.env.E2E_PORT ?? 8788);
export const BASE_URL = `http://127.0.0.1:${PORT}`;
// Only ever used by this throwaway local dev server.
process.env.E2E_INGEST_TOKEN ??= "e2e-local-ingest-token";

// Behind an HTTPS proxy (CI sandboxes), let Chromium reach external crests/fonts through it
// while talking to the local Worker directly.
const proxy = process.env.HTTPS_PROXY || process.env.https_proxy;
const launchOptions = proxy
  ? { args: [`--proxy-server=${proxy}`, "--proxy-bypass-list=127.0.0.1;localhost"] }
  : {};

export default defineConfig({
  testDir: "./tests",
  timeout: 45_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  use: { baseURL: BASE_URL, trace: "retain-on-failure", launchOptions },
  webServer: {
    // E2E_FETCH_RELAY: see scripts/dev-egress-relay.py (only for sandboxes without direct egress).
    command: `npx wrangler dev --port ${PORT} --ip 127.0.0.1 --persist-to .wrangler/e2e-state --var INGEST_TOKEN:${process.env.E2E_INGEST_TOKEN}` +
      (process.env.E2E_FETCH_RELAY ? ` --var DEV_FETCH_RELAY:${process.env.E2E_FETCH_RELAY}` : ""),
    url: `${BASE_URL}/api/health`,
    reuseExistingServer: false,
    timeout: 90_000,
    stdout: "ignore",
    stderr: "pipe",
  },
  projects: [
    { name: "scrape", testMatch: /scrape\.setup\.ts/, timeout: 600_000 },
    { name: "desktop", testMatch: /\.spec\.ts/, dependencies: ["scrape"], use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 900 } } },
    { name: "mobile", testMatch: /\.spec\.ts/, dependencies: ["scrape"], use: { ...devices["Pixel 7"] } },
  ],
});
