// Unit tests of docs/map/lv95.js against the reference points of server/DESIGN.md §3.
// Run: node --test tests/unit/
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const Lv95 = require("../../docs/map/lv95.js");

const dms = (d, m, s) => d + m / 60 + s / 3600;

// server/DESIGN.md §3, copied exactly: LV95 E, N; φ, λ (ETRF93); tolerance in metres.
const POINTS = [
  { name: "swisstopo worked example", e: 2700000.00, n: 1100000.00, lat: dms(46, 2, 38.87), lon: dms(8, 43, 49.79), tol: 1 },
  { name: "AGNES ZIMM (Zimmerwald)", e: 2602030.740, n: 1191775.030, lat: dms(46, 52, 37.540569), lon: dms(7, 27, 54.983511), tol: 1 },
  { name: "AGNES ETH2 (ETH Zürich)", e: 2680910.112, n: 1251259.201, lat: dms(47, 24, 25.842486), lon: dms(8, 30, 38.194637), tol: 1 },
  { name: "AGNES LOMO (Locarno Monti)", e: 2704160.863, n: 1114349.376, lat: dms(46, 10, 21.225556), lon: dms(8, 47, 14.732003), tol: 1 },
  { name: "AGNES GENE (Genève)", e: 2498930.196, n: 1122714.152, lat: dms(46, 14, 53.692140), lon: dms(6, 7, 41.065513), tol: 3 },
];

// Distance in metres between two WGS84 points (local flat approximation, fine at metres).
function groundMetres(lat1, lon1, lat2, lon2) {
  const R = 6378137;
  const rad = Math.PI / 180;
  const dn = (lat2 - lat1) * rad * R;
  const de = (lon2 - lon1) * rad * R * Math.cos(((lat1 + lat2) / 2) * rad);
  return Math.hypot(dn, de);
}

for (const p of POINTS) {
  test(`LV95 -> WGS84 within ${p.tol} m: ${p.name}`, () => {
    const g = Lv95.lv95ToWgs84(p.e, p.n);
    const d = groundMetres(g.lat, g.lon, p.lat, p.lon);
    assert.ok(d <= p.tol, `${d.toFixed(3)} m off (tolerance ${p.tol} m)`);
  });

  test(`WGS84 -> LV95 within ${p.tol} m: ${p.name}`, () => {
    const c = Lv95.wgs84ToLv95(p.lat, p.lon);
    const d = Math.hypot(c.e - p.e, c.n - p.n);
    assert.ok(d <= p.tol, `${d.toFixed(3)} m off (tolerance ${p.tol} m)`);
  });
}

test("round trip LV95 -> WGS84 -> LV95 stays within 1 m over the Zürich map area", () => {
  let worst = 0;
  // the map's bounds, 8.42–8.65 E / 47.30–47.45 N, are roughly E 2 676 000–2 694 000, N 1 237 000–1 255 000
  for (let e = 2676000; e <= 2694000; e += 1500) {
    for (let n = 1237000; n <= 1255000; n += 1500) {
      const g = Lv95.lv95ToWgs84(e, n);
      const back = Lv95.wgs84ToLv95(g.lat, g.lon);
      worst = Math.max(worst, Math.hypot(back.e - e, back.n - n));
    }
  }
  assert.ok(worst < 1, `worst round-trip error ${worst.toFixed(3)} m`);
});

test("round trip WGS84 -> LV95 -> WGS84 stays within 1 m at the reference points", () => {
  for (const p of POINTS) {
    const c = Lv95.wgs84ToLv95(p.lat, p.lon);
    const g = Lv95.lv95ToWgs84(c.e, c.n);
    const d = groundMetres(g.lat, g.lon, p.lat, p.lon);
    assert.ok(d <= p.tol, `${p.name}: ${d.toFixed(3)} m`);
  }
});

test("cell ids: the DESIGN §3 example and the cell of ETH2", () => {
  assert.deepEqual(Lv95.parseCell("h26824_12473"), { e: 2682400, n: 1247300 });
  assert.equal(Lv95.lv95ToCell(2682400, 1247300), "h26824_12473");
  assert.equal(Lv95.lv95ToCell(2682499.99, 1247399.99), "h26824_12473");
  assert.equal(Lv95.lv95ToCell(2682500, 1247300), "h26825_12473");
  assert.equal(Lv95.wgs84ToCell(POINTS[2].lat, POINTS[2].lon), "h26809_12512");
  assert.equal(Lv95.parseCell("h2682_12473"), null);
  assert.equal(Lv95.parseCell("x26824_12473"), null);
  assert.equal(Lv95.parseCell("h26824_12473 "), null);
});

test("cellToCorners: SW, SE, NE, NW, each converting back to the hectare's corners within 1 m", () => {
  const id = "h26824_12473";
  const corners = Lv95.cellToCorners(id);
  assert.equal(corners.length, 4);
  const expected = [[2682400, 1247300], [2682500, 1247300], [2682500, 1247400], [2682400, 1247400]];
  corners.forEach(([lat, lon], i) => {
    const c = Lv95.wgs84ToLv95(lat, lon);
    const d = Math.hypot(c.e - expected[i][0], c.n - expected[i][1]);
    assert.ok(d < 1, `corner ${i}: ${d.toFixed(3)} m`);
  });
  // orientation: SE is east of SW, NW is north of SW
  assert.ok(corners[1][1] > corners[0][1]);
  assert.ok(corners[3][0] > corners[0][0]);
  // a hectare is ~100 m on each side on the ground
  for (let i = 0; i < 4; i++) {
    const [a, b] = [corners[i], corners[(i + 1) % 4]];
    const side = groundMetres(a[0], a[1], b[0], b[1]);
    assert.ok(Math.abs(side - 100) < 1, `side ${i}: ${side.toFixed(2)} m`);
  }
  // the centre lies in the cell
  const ctr = Lv95.cellCenter(id);
  assert.equal(Lv95.wgs84ToCell(ctr[0], ctr[1]), id);
  assert.throws(() => Lv95.cellToCorners("nonsense"));
});

test("every cell of the server fixture lies inside the map bounds", () => {
  const cells = require("../../server/example_cells.json").cells;
  for (const c of cells) {
    for (const [lat, lon] of Lv95.cellToCorners(c.cell)) {
      assert.ok(lat > 47.30 && lat < 47.45 && lon > 8.42 && lon < 8.65, `${c.cell}: ${lat}, ${lon}`);
    }
  }
});
