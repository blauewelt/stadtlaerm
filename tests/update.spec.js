// Browser tests of the update check (docs/update.html) and the two downloads on the home page.
// The app opens update.html#v=<versionName>&c=<versionCode>&e=<edition> (since 0.5.0; older
// apps send no e). The page must offer the download of the same edition, the Offline-Version
// when e is missing or unknown, and a link to the other edition. No request leaves localhost.
"use strict";
const { test, expect } = require("@playwright/test");

const RELEASES = "https://github.com/blauewelt/stadtlaerm/releases/download/";
let foreign;
let consoleErrors;

test.beforeEach(async ({ page }) => {
  foreign = [];
  consoleErrors = [];
  await page.route((url) => !/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//.test(url.href), (route) => {
    foreign.push(route.request().url());
    return route.abort();
  });
  page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
  page.on("pageerror", (e) => consoleErrors.push(String(e)));
});

test.afterEach(async () => {
  expect(foreign, "requests to other hosts").toEqual([]);
  // A CSP violation (stale script hash) would show up here and leave the status unchanged.
  expect(consoleErrors, "console errors").toEqual([]);
});

/** The release tag and the version the page announces, read from its own attributes. */
async function pageData(page) {
  const main = page.locator("#update");
  return {
    version: await main.getAttribute("data-version"),
    code: parseInt(await main.getAttribute("data-code"), 10),
    offline: await main.getAttribute("data-offline"),
    karte: await main.getAttribute("data-karte"),
  };
}

test("without a hash: the Offline-Version, and a link to the Karten-Version", async ({ page }) => {
  await page.goto("/update.html");
  const d = await pageData(page);
  expect(d.offline).toMatch(new RegExp("^" + RELEASES + "v[0-9.]+/stadtlaerm\\.apk$"));
  expect(d.karte).toBe(d.offline.replace(/stadtlaerm\.apk$/, "stadtlaerm-karte.apk"));
  await expect(page.locator("#status")).toHaveText("Aktuelle Version: " + d.version);
  await expect(page.locator("#download-link")).toHaveAttribute("href", d.offline);
  await expect(page.locator("#download-link")).toHaveText("Offline-Version herunterladen");
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.karte);
  await expect(page.locator("#other-link")).toHaveText("Karten-Version herunterladen");
});

test("an app up to 0.4.0 (no e) is offered the Offline-Version as its update", async ({ page }) => {
  await page.goto("/update.html#v=0.4.0&c=10");
  const d = await pageData(page);
  await expect(page.locator("#status")).toHaveText(`Update verfügbar: Version ${d.version} (installiert: 0.4.0)`);
  await expect(page.locator("#download")).toBeVisible();
  await expect(page.locator("#hints")).toBeVisible();
  await expect(page.locator("#download-link")).toHaveAttribute("href", d.offline);
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.karte);
});

test("an older Karten-Version is offered the Karten-Version, with a way back to Offline", async ({ page }) => {
  await page.goto("/update.html#v=0.4.9&c=10&e=karte");
  const d = await pageData(page);
  await expect(page.locator("#status")).toHaveText(
    `Update verfügbar: Version ${d.version} (installiert: 0.4.9, Karten-Version)`);
  await expect(page.locator("#download-link")).toHaveAttribute("href", d.karte);
  await expect(page.locator("#download-link")).toHaveText("Karten-Version herunterladen");
  await expect(page.locator("#other-text")).toHaveText("Zur Offline-Version wechseln (ohne Internet-Berechtigung):");
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.offline);
});

test("an up-to-date edition: no download button, the switch link stays", async ({ page }) => {
  await page.goto("/update.html");
  const d = await pageData(page);
  await page.goto(`/update.html#v=${d.version}&c=${d.code}&e=offline`);
  await expect(page.locator("#status")).toHaveText(`Ihre Version ${d.version} (Offline-Version) ist aktuell.`);
  await expect(page.locator("#download")).toBeHidden();
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.karte);
  // hashchange re-renders
  await page.evaluate((h) => { location.hash = h; }, `v=${d.version}&c=${d.code}&e=karte`);
  await expect(page.locator("#status")).toHaveText(`Ihre Version ${d.version} (Karten-Version) ist aktuell.`);
  await expect(page.locator("#other-link")).toHaveAttribute("href", d.offline);
});

for (const e of ["labor", "KARTE", "karte2", "<b>x</b>", ""]) {
  test(`an unknown edition (${JSON.stringify(e)}) is treated like none`, async ({ page }) => {
    await page.goto("/update.html#v=0.4.0&c=10&e=" + encodeURIComponent(e));
    const d = await pageData(page);
    await expect(page.locator("#status")).toHaveText(`Update verfügbar: Version ${d.version} (installiert: 0.4.0)`);
    await expect(page.locator("#download-link")).toHaveAttribute("href", d.offline);
  });
}

test("the home page links the same two release files as update.html", async ({ page }) => {
  await page.goto("/update.html");
  const d = await pageData(page);
  await page.goto("/");
  const hrefs = await page.locator(`a[href^="${RELEASES}"]`).evaluateAll((as) => as.map((a) => a.getAttribute("href")));
  expect(new Set(hrefs)).toEqual(new Set([d.offline, d.karte]));
  // The primary button is the Offline-Version.
  await expect(page.locator(".hero a.button").first()).toHaveAttribute("href", d.offline);
  await expect(page.locator(".hero a.button-secondary")).toHaveAttribute("href", d.karte);
  // Both version strings agree.
  await expect(page.locator(".facts")).toContainText(d.version);
  await expect(page.locator(".facts")).toContainText("stadtlaerm.apk");
  await expect(page.locator(".facts")).toContainText("stadtlaerm-karte.apk");
});
