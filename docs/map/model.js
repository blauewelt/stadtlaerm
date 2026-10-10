/* Pure helpers of the noise map (docs/karte.html): number and date wording, the colour
 * scales of the four metrics, and the model of the small night chart in the cell card.
 * No DOM, no Leaflet. Loaded as a classic script in the browser (window.KarteModel) and
 * with require() in the node unit tests (tests/unit/model.test.js).
 *
 * Data contract: server/DESIGN.md §6 and server/README.md "Published files"; the fixtures
 * server/example_cells.json and server/example_cell.json are what the server emits.
 */
(function (root, factory) {
  "use strict";
  var api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.KarteModel = api;
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  /* ---------- numbers (Swiss German: decimal comma, as the site writes «18,4 MB») ------ */

  /** 54.9 -> "54,9"; null/undefined/NaN -> "–". */
  function fmt1(x) {
    if (x === null || x === undefined || typeof x !== "number" || !isFinite(x)) return "–";
    return x.toFixed(1).replace(".", ",").replace("-", "−");
  }

  /** 0.97 -> "97 %" (narrow no-break space, as in Swiss typesetting). */
  function fmtPct(share) {
    if (share === null || share === undefined || !isFinite(share)) return "–";
    return Math.round(share * 100) + " %";
  }

  /* ---------- nights and dates ---------------------------------------------------------- */

  var MONTHS = ["Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August",
    "September", "Oktober", "November", "Dezember"];
  var MONTHS_SHORT = ["Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sept.",
    "Okt.", "Nov.", "Dez."];
  var WEEKDAYS = ["So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"];
  var DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

  function isDate(s) { return DATE_RE.test(String(s)); }

  function parts(dateStr) {
    var m = DATE_RE.exec(String(dateStr));
    if (!m) throw new Error("not a date: " + dateStr);
    var d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
    return { y: d.getUTCFullYear(), m: d.getUTCMonth(), d: d.getUTCDate(), wd: d.getUTCDay() };
  }

  /** "2026-10-07" + 1 -> "2026-10-08" (calendar days; no time zone involved). */
  function addDays(dateStr, n) {
    var p = parts(dateStr);
    var d = new Date(Date.UTC(p.y, p.m, p.d + n));
    return d.toISOString().slice(0, 10);
  }

  /** Whole days from a to b (b − a). */
  function daysBetween(a, b) {
    var pa = parts(a), pb = parts(b);
    return Math.round((Date.UTC(pb.y, pb.m, pb.d) - Date.UTC(pa.y, pa.m, pa.d)) / 86400000);
  }

  /** The night that starts at 22:00 on `dateStr`: "Nacht vom Mi 7. auf Do 8. Oktober 2026". */
  function nightLabel(dateStr) {
    var a = parts(dateStr), b = parts(addDays(dateStr, 1));
    var from = WEEKDAYS[a.wd] + " " + a.d + ".";
    if (a.y !== b.y) from += " " + MONTHS[a.m] + " " + a.y;
    else if (a.m !== b.m) from += " " + MONTHS[a.m];
    return "Nacht vom " + from + " auf " + WEEKDAYS[b.wd] + " " + b.d + ". " + MONTHS[b.m] + " " + b.y;
  }

  /** Short form for the "when" column: "7./8. Okt.", "30. Sept./1. Okt.". */
  function nightShort(dateStr) {
    var a = parts(dateStr), b = parts(addDays(dateStr, 1));
    if (a.m === b.m) return a.d + "./" + b.d + ". " + MONTHS_SHORT[b.m];
    return a.d + ". " + MONTHS_SHORT[a.m] + "/" + b.d + ". " + MONTHS_SHORT[b.m];
  }

  /** Calendar span covered by `n` nights ending with the night of `lastNight`:
   *  (7 nights to 2026-10-07) -> "1.–8. Okt."; across months "9. Sept.–8. Okt.". */
  function spanLabel(lastNight, n) {
    var a = parts(addDays(lastNight, -(n - 1))), b = parts(addDays(lastNight, 1));
    if (a.y === b.y && a.m === b.m) return a.d + ".–" + b.d + ". " + MONTHS_SHORT[b.m];
    return a.d + ". " + MONTHS_SHORT[a.m] + "–" + b.d + ". " + MONTHS_SHORT[b.m];
  }

  /** "2026-10-08T06:40:00+02:00" -> "8. Okt. 2026, 06:40" — read from the text, which the
   *  server writes in Zürich local time, so the viewer's own time zone does not matter. */
  function stampLabel(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(String(iso || ""));
    if (!m) return "";
    return (+m[3]) + ". " + MONTHS_SHORT[+m[2] - 1] + " " + m[1] + ", " + m[4] + ":" + m[5];
  }

  /* ---------- metrics and colour scales ------------------------------------------------ */

  // Seven classes per metric. LAeq: 5 dB classes so that the ordinance's night values
  // (LSV Anhang 3: 45, 50, 55, 65) fall on class edges and the legend ticks mean something.
  var LEVEL_COLOURS = ["#2b7a78", "#7aae6a", "#d8c35a", "#eb9a3a", "#d95a2b", "#b02a3a", "#6c1d5f"];
  var RATE_COLOURS = ["#f1e6a6", "#c9dd8a", "#86c27c", "#3fa27f", "#227d82", "#2a5a85", "#33306e"];

  var LSV_TICKS = [
    { db: 45, text: "Planungswert für Wohnzonen (ES\u00a0II)" },
    { db: 50, text: "Immissionsgrenzwert für Wohnzonen (ES\u00a0II)" },
    { db: 55, text: "Immissionsgrenzwert für Mischzonen (ES\u00a0III)" },
    { db: 65, text: "Alarmwert für Wohn- und Mischzonen (ES\u00a0II/III)" },
  ];

  var METRICS = {
    laeq: {
      id: "laeq", label: "Nacht-LAeq", unit: "dB(A)",
      title: "Mittlerer Pegel der Nacht (LAeq, 22–6 Uhr), dB(A)",
      breaks: [40, 45, 50, 55, 60, 65], colours: LEVEL_COLOURS,
      ticks: [45, 50, 55, 65],
      get: function (b) { return b ? b.laeq_db : null; },
    },
    dynamics: {
      id: "dynamics", label: "Dynamik", unit: "dB",
      title: "Dynamik: typischer Abstand zwischen lauten und leisen Momenten (L10 − L90), dB",
      breaks: [3, 5, 7, 9, 11, 13], colours: RATE_COLOURS,
      ticks: [3, 5, 7, 9, 11, 13],
      get: function (b) { return b ? b.dynamics_db : null; },
    },
    events: {
      id: "events", label: "Ereignisse/h", unit: "pro Stunde",
      title: "Laute Einzelereignisse pro Stunde Messzeit, alle Quellen",
      breaks: [2, 5, 10, 15, 20, 30], colours: RATE_COLOURS,
      ticks: [2, 5, 10, 15, 20, 30],
      get: function (b) { return b ? b.events_per_h : null; },
    },
    loud: {
      id: "loud", label: "Töff & Poser/h", unit: "pro Stunde",
      title: "Ereignisse «Töff & Poser» (Motorräder, Hochdrehen) pro Stunde Messzeit",
      breaks: [0.5, 1, 2, 3, 5, 8], colours: RATE_COLOURS,
      ticks: [0.5, 1, 2, 3, 5, 8],
      get: function (b) {
        return b && b.events_per_h_by_category ? b.events_per_h_by_category.loud_vehicle : null;
      },
    },
  };
  var METRIC_ORDER = ["laeq", "dynamics", "events", "loud"];

  /** Index of the class a value falls in: value < breaks[0] -> 0, ≥ last break -> n. */
  function classIndex(value, breaks) {
    var i = 0;
    while (i < breaks.length && value >= breaks[i]) i++;
    return i;
  }

  /** Fill colour of a cell for a metric, or null when the cell has no value that night. */
  function colourFor(metricId, block) {
    var m = METRICS[metricId];
    var v = m.get(block);
    if (v === null || v === undefined || !isFinite(v)) return null;
    return m.colours[classIndex(v, m.breaks)];
  }

  /** Legend-bar position (0..1) of a class edge: classes are drawn with equal width. */
  function tickPosition(metricId, value) {
    var m = METRICS[metricId];
    var i = m.breaks.indexOf(value);
    if (i < 0) throw new Error("not a class edge of " + metricId + ": " + value);
    return (i + 1) / (m.breaks.length + 1);
  }

  /* ---------- the night chart in the card ----------------------------------------------- */

  var CATEGORY_NAMES = {
    loud_vehicle: "Töff & Poser", road_traffic: "Strassenverkehr", rail_tram: "Tram & Bahn",
    aircraft: "Flugzeug", construction: "Baustelle", voices: "Stimmen", music: "Musik",
    unclassified: "Sonstiges",
  };
  var HIGHLIGHT = "loud_vehicle";   // the app's default highlight

  /** The app's y-axis rule (android/chart/.../ChartModel.kt `yRange`): lowest L90 − 5 dB to
   *  the loudest shown event or level + 5 dB, rounded to 5 dB, at least 30 dB, within 0–120. */
  function yRange(hours) {
    var lows = [], highs = [];
    hours.forEach(function (h) {
      if (h.laeq_db !== null && h.laeq_db !== undefined) {
        lows.push(h.l90_db !== null && h.l90_db !== undefined ? h.l90_db : h.laeq_db);
        highs.push(h.laeq_db);
        if (h.l10_db !== null && h.l10_db !== undefined) highs.push(h.l10_db);
      }
      if (h.loudest_db !== null && h.loudest_db !== undefined) highs.push(h.loudest_db);
    });
    if (!lows.length && !highs.length) return { lo: 20, hi: 80 };
    var minLow = lows.length ? Math.min.apply(null, lows) : Math.min.apply(null, highs);
    var maxTop = Math.max.apply(null, highs.length ? highs : lows);
    var lo = Math.floor((minLow - 5) / 5) * 5;
    var hi = Math.ceil((maxTop + 5) / 5) * 5;
    if (hi - lo < 30) hi = lo + 30;
    lo = Math.min(120, Math.max(0, lo));
    hi = Math.min(120, Math.max(0, hi));
    if (hi - lo < 30) { if (hi >= 120) lo = 90; else hi = lo + 30; }
    return { lo: lo, hi: hi };
  }

  /** Gridlines every 10 dB inside the range. */
  function gridlines(r) {
    var out = [];
    for (var v = Math.ceil(r.lo / 10) * 10; v <= r.hi + 1e-9; v += 10) out.push(v);
    return out;
  }

  function hasLevels(h) {
    return h.laeq_db !== null && h.laeq_db !== undefined;
  }

  /** Data -> drawing model of the night chart. Hours are the rows of cells/{cell}.json
   *  (§6.2): one per clock hour of the night, `start` with offset (the autumn DST night has
   *  "02" twice). Returns x positions as fractions 0..1 of the night, so the SVG code only
   *  scales. `runs` are maximal stretches of consecutive hours with levels: the band and the
   *  LAeq line are drawn per run, so nothing is drawn across a missing hour. */
  function chartModel(hours) {
    if (!hours || !hours.length) return null;
    var starts = hours.map(function (h) { return Date.parse(h.start); });
    var t0 = starts[0];
    var t1 = starts[starts.length - 1] + 3600000;
    var span = t1 - t0;
    var slots = hours.map(function (h, i) {
      var x0 = (starts[i] - t0) / span, x1 = (starts[i] + 3600000 - t0) / span;
      var counts = h.events || {};
      var total = 0;
      Object.keys(counts).forEach(function (k) { total += counts[k] || 0; });
      var hl = Math.round(counts[HIGHLIGHT] || 0);
      var all = Math.round(total);
      return {
        hour: h.hour, x0: x0, x1: x1, valid: hasLevels(h),
        laeq: h.laeq_db, l90: h.l90_db, l10: h.l10_db, loudest: h.loudest_db,
        highlighted: hl, others: Math.max(0, all - hl), measuredShare: h.measured_share,
      };
    });
    var runs = [], cur = null;
    slots.forEach(function (s, i) {
      if (s.valid && cur && starts[i] === starts[i - 1] + 3600000) cur.push(s);
      else if (s.valid) { cur = [s]; runs.push(cur); }
      else cur = null;
    });
    var gaps = slots.filter(function (s) { return !s.valid; });
    var endHour = (parseInt(hours[hours.length - 1].hour, 10) + 1) % 24;
    var labels = slots.map(function (s) { return { x: s.x0, text: s.hour }; })
      .concat([{ x: 1, text: (endHour < 10 ? "0" : "") + endHour }]);
    var r = yRange(hours);
    var valid = slots.filter(function (s) { return s.valid; });
    return {
      range: r, grid: gridlines(r), slots: slots, runs: runs, gaps: gaps, labels: labels,
      eventsHighlighted: slots.reduce(function (a, s) { return a + s.highlighted; }, 0),
      eventsTotal: slots.reduce(function (a, s) { return a + s.highlighted + s.others; }, 0),
      laeqMin: valid.length ? Math.min.apply(null, valid.map(function (s) { return s.laeq; })) : null,
      laeqMax: valid.length ? Math.max.apply(null, valid.map(function (s) { return s.laeq; })) : null,
    };
  }

  /** Step path through a run: for each hour a horizontal segment at `key`. */
  function stepPoints(run, key) {
    var pts = [];
    run.forEach(function (s) { pts.push([s.x0, s[key]], [s.x1, s[key]]); });
    return pts;
  }

  return {
    fmt1: fmt1, fmtPct: fmtPct,
    isDate: isDate, addDays: addDays, daysBetween: daysBetween,
    nightLabel: nightLabel, nightShort: nightShort, spanLabel: spanLabel, stampLabel: stampLabel,
    METRICS: METRICS, METRIC_ORDER: METRIC_ORDER, LSV_TICKS: LSV_TICKS,
    classIndex: classIndex, colourFor: colourFor, tickPosition: tickPosition,
    CATEGORY_NAMES: CATEGORY_NAMES, HIGHLIGHT: HIGHLIGHT,
    yRange: yRange, gridlines: gridlines, chartModel: chartModel, stepPoints: stepPoints,
  };
});
