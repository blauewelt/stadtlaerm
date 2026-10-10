// Network stand-ins for the map page: the API is answered from the server's fixtures
// (server/example_cells.json, server/example_cell.json — what the real publisher writes),
// swisstopo tiles with a local grey placeholder. Every other non-local request is aborted and
// recorded, so a test can fail on it. Used by tests/map.spec.js and scripts/karte_screenshot.js.
"use strict";
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..", "..");
const API = "https://api.stadtlaerm.ch/v1/map/";
const TILE_HOST = "wmts.geo.admin.ch";

const cellsJson = JSON.parse(fs.readFileSync(path.join(ROOT, "server", "example_cells.json"), "utf8"));
const cellJson = JSON.parse(fs.readFileSync(path.join(ROOT, "server", "example_cell.json"), "utf8"));
const tile = fs.readFileSync(path.join(ROOT, "tests", "fixtures", "tile-placeholder.jpeg"));

const LATEST = cellsJson.night;                       // "2026-10-07"

/** The night before the fixture's night: the fixture with every LAeq 1.5 dB lower and the
 *  first cell left out, so a test can tell the two files apart. Older nights answer 404. */
function previousNight() {
  const d = new Date(LATEST + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() - 1);
  const night = d.toISOString().slice(0, 10);
  const file = JSON.parse(JSON.stringify(cellsJson));
  file.night = night;
  file.cells = file.cells.slice(1);
  for (const c of file.cells) if (c.last_night) c.last_night.laeq_db = Math.round((c.last_night.laeq_db - 1.5) * 10) / 10;
  return { night, file };
}
const PREV = previousNight();

function json(route, status, body) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers: { "Access-Control-Allow-Origin": "*", "Cache-Control": "max-age=300" },
    body: JSON.stringify(body),
  });
}

/**
 * Installs the routes on a page. Returns { foreign: [urls], api: [paths], tiles: n }.
 * Options: realTiles (let wmts.geo.admin.ch through — only for a session allowed to fetch).
 */
async function mockNetwork(page, opts = {}) {
  const seen = { foreign: [], api: [], tiles: 0 };
  const base = opts.baseURL || "http://localhost:8090";
  await page.route("**/*", async (route) => {
    const url = route.request().url();
    if (url.startsWith(base + "/") || url.startsWith("data:")) return route.continue();
    if (url.startsWith(API)) {
      const p = url.slice(API.length).split("?")[0];
      seen.api.push(p);
      if (p === "cells.json") return json(route, 200, cellsJson);
      if (p === `nights/${LATEST}.json`) return json(route, 200, cellsJson);
      if (p === `nights/${PREV.night}.json`) return json(route, 200, PREV.file);
      if (p === `cells/${cellJson.cell}.json`) return json(route, 200, cellJson);
      return json(route, 404, { detail: "not found" });
    }
    if (new URL(url).hostname === TILE_HOST) {
      seen.tiles++;
      if (opts.realTiles) return route.continue();
      return route.fulfill({ status: 200, contentType: "image/jpeg", body: tile });
    }
    seen.foreign.push(url);
    return route.abort("blockedbyclient");
  });
  return seen;
}

module.exports = { mockNetwork, cellsJson, cellJson, LATEST, PREV, API, TILE_HOST };
