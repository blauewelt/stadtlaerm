/* WGS84 <-> Swiss LV95 (CH1903+) and the hectare cell ids of server/DESIGN.md §3.
 *
 * swisstopo, "Approximate formulas for the transformation between Swiss projection
 * coordinates and WGS84": accuracy about 1 m inside Switzerland, far below the 100 m cell.
 * The same arithmetic exists in the app (Kotlin, dsp module) and both are tested against
 * swisstopo's published reference points.
 *
 * Pure functions, no DOM. Loaded as a classic script in the browser (window.Lv95) and with
 * require() in the node unit tests.
 */
(function (root, factory) {
  "use strict";
  var api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.Lv95 = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  /** WGS84 latitude/longitude in decimal degrees -> LV95 { e, n } in metres. */
  function wgs84ToLv95(lat, lon) {
    // auxiliary values: differences to Bern in units of 10 000"
    var p = (lat * 3600 - 169028.66) / 10000;
    var l = (lon * 3600 - 26782.5) / 10000;
    var e = 2600072.37
      + 211455.93 * l
      - 10938.51 * l * p
      - 0.36 * l * p * p
      - 44.54 * l * l * l;
    var n = 1200147.07
      + 308807.95 * p
      + 3745.25 * l * l
      + 76.63 * p * p
      - 194.56 * l * l * p
      + 119.79 * p * p * p;
    return { e: e, n: n };
  }

  /** LV95 easting/northing in metres -> WGS84 { lat, lon } in decimal degrees. */
  function lv95ToWgs84(e, n) {
    // auxiliary values: differences to Bern in units of 1000 km
    var y = (e - 2600000) / 1000000;
    var x = (n - 1200000) / 1000000;
    var lon = 2.6779094
      + 4.728982 * y
      + 0.791484 * y * x
      + 0.1306 * y * x * x
      - 0.0436 * y * y * y;
    var lat = 16.9023892
      + 3.238272 * x
      - 0.270978 * y * y
      - 0.002528 * x * x
      - 0.0447 * y * y * x
      - 0.0140 * x * x * x;
    // result is in units of 10 000"; * 100 / 36 gives degrees
    return { lat: lat * 100 / 36, lon: lon * 100 / 36 };
  }

  var CELL_RE = /^h(\d{5})_(\d{5})$/;

  /** "h26824_12473" -> { e: 2682400, n: 1247300 } (south-west corner), or null. */
  function parseCell(cellId) {
    var m = CELL_RE.exec(String(cellId));
    if (!m) return null;
    return { e: parseInt(m[1], 10) * 100, n: parseInt(m[2], 10) * 100 };
  }

  /** LV95 point -> id of the hectare that contains it. */
  function lv95ToCell(e, n) {
    return "h" + Math.floor(e / 100) + "_" + Math.floor(n / 100);
  }

  /** WGS84 point -> id of the hectare that contains it (what the app sends). */
  function wgs84ToCell(lat, lon) {
    var p = wgs84ToLv95(lat, lon);
    return lv95ToCell(p.e, p.n);
  }

  /** The four corners of a hectare as [lat, lon] pairs, SW, SE, NE, NW (Leaflet order).
   *  Throws on a malformed id: the server only publishes valid ones. */
  function cellToCorners(cellId) {
    var sw = parseCell(cellId);
    if (!sw) throw new Error("not a hectare cell id: " + cellId);
    return [
      [sw.e, sw.n],
      [sw.e + 100, sw.n],
      [sw.e + 100, sw.n + 100],
      [sw.e, sw.n + 100],
    ].map(function (c) {
      var g = lv95ToWgs84(c[0], c[1]);
      return [g.lat, g.lon];
    });
  }

  /** Centre of a hectare as [lat, lon]. */
  function cellCenter(cellId) {
    var sw = parseCell(cellId);
    if (!sw) throw new Error("not a hectare cell id: " + cellId);
    var g = lv95ToWgs84(sw.e + 50, sw.n + 50);
    return [g.lat, g.lon];
  }

  return {
    wgs84ToLv95: wgs84ToLv95,
    lv95ToWgs84: lv95ToWgs84,
    parseCell: parseCell,
    lv95ToCell: lv95ToCell,
    wgs84ToCell: wgs84ToCell,
    cellToCorners: cellToCorners,
    cellCenter: cellCenter,
  };
});
