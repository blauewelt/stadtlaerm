// Browser tests of the noise map page (docs/karte.html), at 360×740 and 1280×800
// (projects in playwright.config.js). The API is answered from the server fixtures and the
// swisstopo tiles from a placeholder (tests/support/mock.js); any other host fails the test.
"use strict";
const { test, expect } = require("@playwright/test");
const { mockNetwork, cellsJson, cellJson, LATEST, PREV, TILE_HOST } = require("./support/mock");

const CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: https://wmts.geo.admin.ch; connect-src https://api.stadtlaerm.ch; base-uri 'none'; form-action 'none'";
const UNCAL = cellsJson.cells.find((c) => !c.calibrated && c.last_night).cell;   // h26818_12478
const CAL = cellJson.cell;                                                       // h26828_12455, calibrated

let seen;
let hosts;
let consoleErrors;

test.beforeEach(async ({ page, baseURL }) => {
  seen = await mockNetwork(page, { baseURL });
  hosts = new Set();
  consoleErrors = [];
  page.on("request", (r) => {
    const u = r.url();
    if (!u.startsWith("data:")) hosts.add(new URL(u).host);
  });
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
  page.on("pageerror", (e) => consoleErrors.push(String(e)));
  await page.goto("/karte.html");
  await expect(page.locator("#karte")).toHaveAttribute("data-ready", "1");
});

test.afterEach(async () => {
  // No request to any host other than the local server, swisstopo and the (mocked) API.
  const allowed = new Set(["localhost:8090", TILE_HOST, "api.stadtlaerm.ch"]);
  const bad = [...hosts].filter((h) => !allowed.has(h));
  expect(bad, "requests to unexpected hosts").toEqual([]);
  expect(seen.foreign, "aborted foreign requests").toEqual([]);
  // a 404 from the (mocked) API is a tested answer, which Chromium also logs as a failed resource
  expect(consoleErrors.filter((e) => !/status of 404/.test(e)), "console errors").toEqual([]);
});

/** Taps (phone) or clicks (desktop) the centre of a cell on the map, zoomed in to `zoom`. */
async function hitCell(page, cellId, isMobile, zoom = 16) {
  await page.locator("#karte").scrollIntoViewIfNeeded();
  await page.evaluate(([id, z]) => new Promise((resolve) => {
    const k = window.__karte;
    k.map.once("moveend", () => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    k.map.setView(window.Lv95.cellCenter(id), z, { animate: false });
  }), [cellId, zoom]);
  const pt = await page.evaluate((id) => {
    const p = window.__karte.map.latLngToContainerPoint(window.Lv95.cellCenter(id));
    const r = document.getElementById("karte").getBoundingClientRect();
    return { x: r.left + p.x, y: r.top + p.y };
  }, cellId);
  const hit = await page.evaluate(({ x, y }) => {
    const e = document.elementFromPoint(x, y);
    return e && e.getAttribute("data-cell");
  }, pt);
  expect(hit, "the cell is what is under the pointer").toBe(cellId);
  if (isMobile) await page.touchscreen.tap(pt.x, pt.y);
  else await page.mouse.click(pt.x, pt.y);
}

test("renders: map, tiles from swisstopo, one shape per fixture cell, sentence about sources", async ({ page }) => {
  await expect(page.locator("#karte .leaflet-tile-pane img").first()).toBeVisible();
  const tileSrc = await page.locator("#karte .leaflet-tile-pane img").first().getAttribute("src");
  expect(tileSrc).toMatch(/^https:\/\/wmts\.geo\.admin\.ch\/1\.0\.0\/ch\.swisstopo\.pixelkarte-grau\/default\/current\/3857\/\d+\/\d+\/\d+\.jpeg$/);
  expect(seen.tiles).toBeGreaterThan(0);
  const ids = await page.locator("#karte path.cell").evaluateAll((ps) => [...new Set(ps.map((p) => p.getAttribute("data-cell")))]);
  expect(ids.sort()).toEqual(cellsJson.cells.map((c) => c.cell).sort());
  await expect(page.locator(".leaflet-control-attribution")).toContainText("© swisstopo");
  await expect(page.locator("#quellen")).toHaveText(
    "Diese Seite lädt die Kartenbilder von swisstopo und die Messwerte von api.stadtlaerm.ch, sonst nichts von anderen Servern.");
  await expect(page.locator("#night")).toHaveText("Nacht vom Mi 7. auf Do 8. Oktober 2026");
  expect(seen.api).toContain("cells.json");
  // the zoom limits of the spec
  const z = await page.evaluate(() => ({ min: window.__karte.map.getMinZoom(), b: window.__karte.map.options.maxBounds }));
  expect(z.min).toBe(11);
});

test("no horizontal scrolling at this width", async ({ page }) => {
  const w = await page.evaluate(() => ({ doc: document.documentElement.scrollWidth, vw: window.innerWidth }));
  expect(w.doc).toBeLessThanOrEqual(w.vw);
});

test("legend: LSV ticks 45 · 50 · 55 · 65 with their meaning and the Lr caveat", async ({ page }) => {
  const ticks = await page.locator("#legend-ticks span").allTextContents();
  expect(ticks).toEqual(["45", "50", "55", "65"]);
  const lsv = page.locator("#legend-lsv li[data-db]");
  await expect(lsv).toHaveCount(4);
  await expect(lsv.nth(0)).toContainText("Planungswert");
  await expect(lsv.nth(0)).toContainText("ES II");
  await expect(lsv.nth(1)).toContainText("Immissionsgrenzwert");
  await expect(lsv.nth(2)).toContainText("ES III");
  await expect(lsv.nth(3)).toContainText("Alarmwert");
  await expect(page.locator("#legend-note")).toContainText("Beurteilungspegel Lr");
  await expect(page.locator("#legend-note")).toContainText("keine rechtliche Beurteilung");
});

test("metric selector recolours the cells and changes the legend", async ({ page }) => {
  const fill = () => page.locator(`#karte path.cell[data-cell="${CAL}"]`).first().getAttribute("fill");
  const before = await fill();
  await page.locator('#metric input[value="loud"]').check();
  await expect(page.locator("#legend-title")).toContainText("Töff & Poser");
  await expect(page.locator("#legend-lsv")).toBeHidden();
  expect(await fill()).not.toBe(before);
  // keyboard: arrow keys move between the radio buttons
  await page.locator('#metric input[value="loud"]').focus();
  await page.keyboard.press("ArrowLeft");
  await expect(page.locator('#metric input[value="events"]')).toBeChecked();
});

test("tapping a cell opens the card with its LAeq, when it was measured, the night chart and the means", async ({ page, isMobile }) => {
  await hitCell(page, CAL, isMobile);
  const card = page.locator("#card");
  await expect(card).toBeVisible();
  await expect(page.locator("#card-title")).toContainText(CAL);
  const laeq = card.locator(".row", { hasText: "Nacht-LAeq" }).first();
  await expect(laeq).toContainText("54,9 dB(A)");
  await expect(laeq.locator(".when")).toHaveText("7./8. Okt.");
  await expect(page.locator("#badge-uncal")).toHaveCount(0);
  // the chart: band, one LAeq path per run (fixture: one), amber dots = rounded Töff & Poser counts
  const chart = card.locator("svg.nightchart");
  await expect(chart).toBeVisible();
  await expect(chart.locator("path.laeq")).toHaveCount(1);
  await expect(chart.locator("path.band")).toHaveCount(1);
  const amber = cellJson.hours.reduce((a, h) => a + Math.round(h.events.loud_vehicle), 0);
  await expect(chart.locator("circle.dot.hl")).toHaveCount(amber);
  await expect(card).toContainText("Mittel der letzten 7 Nächte");
  const fx = cellsJson.cells.find((c) => c.cell === CAL);
  const w7 = card.locator(".card-sec", { hasText: "letzten 7" });
  await expect(w7.locator(".row").first()).toContainText(fx.last_7_nights.laeq_db.toFixed(1).replace(".", ",") + " dB(A)");
  await expect(w7).toContainText(`${fx.last_7_nights.nights_with_data} von 7 Nächten`);
  await expect(card.locator(".card-sec", { hasText: "letzten 30" })).toContainText(`${fx.last_30_nights.nights_with_data} von 30 Nächten`);
  // close
  await page.locator("#card-close").click();
  await expect(card).toBeHidden();
});

test("the uncalibrated fixture cell shows the «unkalibriert» badge", async ({ page, isMobile }) => {
  await hitCell(page, UNCAL, isMobile);
  await expect(page.locator("#card-title")).toContainText(UNCAL);
  await expect(page.locator("#badge-uncal")).toHaveText("unkalibriert");
  await expect(page.locator(`#karte path.cell.uncal[data-cell="${UNCAL}"]`).first()).toBeAttached();
  // its history file is not in the fixtures: the card says so instead of breaking
  await expect(page.locator("#card")).toContainText("keinen Verlauf");
});

test("a missing hour is not bridged by the LAeq line", async ({ page, isMobile }) => {
  // re-route this cell's file with one hour without levels
  const hours = JSON.parse(JSON.stringify(cellJson));
  Object.assign(hours.hours[3], { laeq_db: null, l90_db: null, l10_db: null });
  await page.route(`https://api.stadtlaerm.ch/v1/map/cells/${CAL}.json`, (r) =>
    r.fulfill({ status: 200, contentType: "application/json", headers: { "Access-Control-Allow-Origin": "*" }, body: JSON.stringify(hours) }));
  await hitCell(page, CAL, isMobile);
  const chart = page.locator("#card svg.nightchart");
  await expect(chart.locator("path.laeq")).toHaveCount(2);
  await expect(chart.locator("rect.gap")).toHaveCount(1);
});

test("date control: ‹ goes back a night, › returns, a 404 night shows «keine Daten»", async ({ page }) => {
  const prev = page.locator("#prev"), next = page.locator("#next");
  await expect(next).toBeDisabled();
  await expect(prev).toBeEnabled();
  await prev.click();
  await expect(page.locator("#night")).toHaveText("Nacht vom Di 6. auf Mi 7. Oktober 2026");
  await expect(page.locator("#karte path.cell")).not.toHaveCount(0);
  const ids = await page.locator("#karte path.cell").evaluateAll((ps) => [...new Set(ps.map((p) => p.getAttribute("data-cell")))]);
  expect(ids.length).toBe(PREV.file.cells.length);
  expect(seen.api).toContain(`nights/${PREV.night}.json`);
  await prev.click();                        // the fixture server has no file for 5./6. Oct.
  await expect(page.locator("#night")).toContainText("keine Daten");
  await expect(page.locator("#status")).toContainText("keine Daten");
  await expect(page.locator("#karte path.cell")).toHaveCount(0);
  await next.click();
  await next.click();
  await expect(page.locator("#night")).toHaveText("Nacht vom Mi 7. auf Do 8. Oktober 2026");
  await expect(page.locator("#status")).toBeHidden();
  await expect(next).toBeDisabled();
  expect(LATEST).toBe("2026-10-07");
});

test("keyboard: the list of cells opens the card, Escape closes it", async ({ page }) => {
  await page.locator("#celllist summary").focus();
  await page.keyboard.press("Enter");
  const btn = page.locator(`#celllist-items button[data-cell="${CAL}"]`);
  await btn.focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("#card")).toBeVisible();
  await expect(page.locator("#card")).toBeFocused();
  await expect(page.locator("#card .row", { hasText: "Nacht-LAeq" }).first()).toContainText("54,9 dB(A)");
  await page.keyboard.press("Escape");
  await expect(page.locator("#card")).toBeHidden();
  await expect(btn).toBeFocused();
});

test("CSP meta is exactly the specified policy, no inline scripts, no-referrer", async ({ page }) => {
  const csp = await page.locator('meta[http-equiv="Content-Security-Policy"]').getAttribute("content");
  expect(csp).toBe(CSP);
  await expect(page.locator('meta[name="referrer"]')).toHaveAttribute("content", "no-referrer");
  const inline = await page.locator("script:not([src])").count();
  expect(inline).toBe(0);
  const srcs = await page.locator("script[src]").evaluateAll((s) => s.map((x) => x.getAttribute("src")));
  for (const s of srcs) expect(s).toMatch(/^(vendor|map)\/[\w/.-]+\.js\?v=[0-9a-f]{8}$/);
});

test("dark mode uses the site's colours", async ({ browser, baseURL }) => {
  const ctx = await browser.newContext({ colorScheme: "dark", viewport: { width: 360, height: 740 } });
  const p = await ctx.newPage();
  const s = await mockNetwork(p, { baseURL });
  await p.goto("/karte.html");
  await expect(p.locator("#karte")).toHaveAttribute("data-ready", "1");
  const bg = await p.evaluate(() => getComputedStyle(document.body).backgroundColor);
  expect(bg).toBe("rgb(13, 22, 38)");        // --paper in dark mode (style.css)
  expect(s.foreign).toEqual([]);
  await ctx.close();
});
