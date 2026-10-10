# docs/ — the website stadtlaerm.ch

Static files served by GitHub Pages (`CNAME`: stadtlaerm.ch). No build step. German, Swiss
spelling, system fonts, no third-party requests — except on the map page, which says so.

| File | What |
|---|---|
| `index.html`, `style.css` | Home page and the shared stylesheet (light/dark via CSS variables) |
| `datenschutz.html` | Privacy page (mirrors [../PRIVACY.md](../PRIVACY.md)) |
| `update.html` | Update check opened by the app (one inline script, pinned by hash in its CSP) |
| `karte.html` | The noise map ([../server/DESIGN.md](../server/DESIGN.md) §7) |
| `map/lv95.js` | WGS84 ↔ Swiss LV95 and hectare cell ids (§3), pure functions |
| `map/model.js` | Pure helpers of the map: wording, colour scales, the card's night-chart model |
| `map/karte.js`, `map/karte.css` | The map page itself (Leaflet, legend, card, date control) |
| `vendor/leaflet/` | Leaflet 1.9.4, copied unchanged from the npm package (`node_modules/leaflet/dist`) |
| `img/`, `download/` | Screenshots; the two signed APKs (`stadtlaerm-offline.apk`, `stadtlaerm-karte.apk`) |

## The map page

- Reads `cells.json`, `nights/{date}.json` and `cells/{cell}.json` from the URL in the map
  element's `data-api` attribute (default `https://api.stadtlaerm.ch/v1/map/`). The page's CSP
  allows `connect-src` only to that host, so a different API host needs a CSP change too.
- Tiles: swisstopo `ch.swisstopo.pixelkarte-grau` (Web Mercator), credit «© swisstopo».
- The formats are those of `server/example_cells.json` and `server/example_cell.json`, which
  the server's tests check against the real publisher.

## Before committing a change to the map

```sh
python3 scripts/stamp_assets.py            # hash-stamp the ?v= of karte.html's scripts/styles
npm test                                   # unit tests, stamp check, Playwright (360 px and 1280 px)
```

`npm test` runs `node --test 'tests/unit/*.test.js'`, `python3 scripts/stamp_assets.py --check`
and `playwright test`. The Playwright tests serve `docs/` locally and answer the API from the
server fixtures and swisstopo with a grey placeholder tile; a request to any other host fails.
`node scripts/karte_screenshot.js` (with `python3 -m http.server 8090 --directory docs`
running) renders `img/karte-360.png` the same way; `REAL_TILES=1` lets the swisstopo tiles
through.

To try the page against a local server, serve `docs/` and change `data-api` and the CSP's
`connect-src` together — and change them back before committing (a test checks the CSP).
