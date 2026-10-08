// Playwright config for the website tests (tests/*.spec.js).
// Serves docs/ with Python's static server; the API and the swisstopo tiles are answered by
// page.route in the tests (tests/support/mock.js), so no test touches the network.
// Browsers: PLAYWRIGHT_BROWSERS_PATH (in this sandbox /opt/pw-browsers); `npx playwright
// install chromium` elsewhere.
"use strict";
const { defineConfig } = require("@playwright/test");

const PORT = 8090;

module.exports = defineConfig({
  testDir: "tests",
  testMatch: /.*\.spec\.js$/,
  timeout: 30000,
  retries: 0,
  workers: 2,
  reporter: "list",
  use: {
    baseURL: `http://localhost:${PORT}`,
    serviceWorkers: "block",
  },
  projects: [
    { name: "phone-360", use: { viewport: { width: 360, height: 740 }, hasTouch: true, isMobile: true, deviceScaleFactor: 2 } },
    { name: "desktop-1280", use: { viewport: { width: 1280, height: 800 } } },
  ],
  webServer: {
    command: `python3 -m http.server ${PORT} --bind 127.0.0.1 --directory docs`,
    url: `http://localhost:${PORT}/karte.html`,
    reuseExistingServer: !process.env.CI,
  },
});
