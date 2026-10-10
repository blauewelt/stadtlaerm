#!/usr/bin/env node
// Renders docs/karte.html at 360 px with the server fixture and saves docs/img/karte-360.png.
// The API is answered from server/example_cells.json / example_cell.json (tests/support/mock.js).
// swisstopo tiles are replaced by a grey placeholder unless REAL_TILES=1 is set — agents must
// not fetch from the web (CLAUDE.md rule 6), so only the main session retakes it with real tiles.
//
//   python3 -m http.server 8090 --bind 127.0.0.1 --directory docs &
//   node scripts/karte_screenshot.js [out.png] [--dark] [--card] [--full]
// Default: the map, its controls and the legend (what a phone shows after the heading);
// --full: the whole page; --card: with the card of the fixture's example cell open.
"use strict";
const path = require("path");
const { chromium } = require("@playwright/test");
const { mockNetwork, cellJson } = require("../tests/support/mock");

(async () => {
  const args = process.argv.slice(2);
  const out = args.find((a) => !a.startsWith("--")) || path.join(__dirname, "..", "docs", "img", "karte-360.png");
  const baseURL = process.env.BASE_URL || "http://localhost:8090";
  const browser = await chromium.launch();
  const ctx = await browser.newContext({
    viewport: { width: 360, height: 740 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    colorScheme: args.includes("--dark") ? "dark" : "light",
  });
  const page = await ctx.newPage();
  const seen = await mockNetwork(page, { baseURL, realTiles: process.env.REAL_TILES === "1" });
  await page.goto(baseURL + "/karte.html");
  await page.waitForSelector('#karte[data-ready="1"]');
  if (args.includes("--card")) {
    await page.evaluate((id) => {
      const b = document.querySelector(`#celllist-items button[data-cell="${id}"]`);
      b.click();
    }, cellJson.cell);
    await page.waitForSelector("#card svg.nightchart");
  }
  await page.waitForTimeout(400);
  if (args.includes("--full")) {
    await page.screenshot({ path: out, fullPage: true });
  } else {
    const clip = await page.evaluate(() => {
      const top = document.querySelector(".controls").getBoundingClientRect().top + window.scrollY - 12;
      const last = document.querySelector(document.querySelector("#card").hidden ? "#legend" : "#card");
      const bottom = last.getBoundingClientRect().bottom + window.scrollY + 12;
      return { x: 0, y: top, width: document.documentElement.clientWidth, height: bottom - top };
    });
    await page.screenshot({ path: out, clip, fullPage: true });
  }
  if (seen.foreign.length) throw new Error("foreign requests: " + seen.foreign.join(", "));
  console.log("saved " + out + " (" + seen.tiles + " tile requests, " + (process.env.REAL_TILES === "1" ? "real" : "placeholder") + " tiles)");
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
