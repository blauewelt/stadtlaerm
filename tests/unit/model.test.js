// Unit tests of docs/map/model.js (wording, colour scales, night chart model).
// Run: node --test tests/unit/
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const M = require("../../docs/map/model.js");
const cell = require("../../server/example_cell.json");

test("numbers use the decimal comma", () => {
  assert.equal(M.fmt1(54.9), "54,9");
  assert.equal(M.fmt1(55), "55,0");
  assert.equal(M.fmt1(null), "–");
  assert.equal(M.fmtPct(0.97), "97 %");
});

test("night arithmetic and labels", () => {
  assert.equal(M.addDays("2026-10-07", 1), "2026-10-08");
  assert.equal(M.addDays("2026-03-01", -1), "2026-02-28");
  assert.equal(M.addDays("2026-10-25", 1), "2026-10-26");   // DST end: calendar days only
  assert.equal(M.daysBetween("2026-07-10", "2026-10-07"), 89);
  assert.equal(M.nightLabel("2026-10-07"), "Nacht vom Mi 7. auf Do 8. Oktober 2026");
  assert.equal(M.nightLabel("2026-09-30"), "Nacht vom Mi 30. September auf Do 1. Oktober 2026");
  assert.equal(M.nightLabel("2026-12-31"), "Nacht vom Do 31. Dezember 2026 auf Fr 1. Januar 2027");
  assert.equal(M.nightShort("2026-10-07"), "7./8. Okt.");
  assert.equal(M.spanLabel("2026-10-07", 7), "1.–8. Okt.");
  assert.equal(M.spanLabel("2026-10-07", 30), "8. Sept.–8. Okt.");
  assert.equal(M.stampLabel("2026-10-08T06:40:00+02:00"), "8. Okt. 2026, 06:40");
});

test("LAeq classes have the LSV night values 45, 50, 55, 65 as class edges", () => {
  const m = M.METRICS.laeq;
  for (const v of [45, 50, 55, 65]) assert.ok(m.breaks.includes(v), `${v} is a class edge`);
  assert.deepEqual(m.ticks, [45, 50, 55, 65]);
  assert.deepEqual(M.LSV_TICKS.map((t) => t.db), [45, 50, 55, 65]);
  // a value on the edge belongs to the louder class
  assert.notEqual(M.colourFor("laeq", { laeq_db: 49.9 }), M.colourFor("laeq", { laeq_db: 50.0 }));
  assert.equal(M.colourFor("laeq", { laeq_db: 50.0 }), M.colourFor("laeq", { laeq_db: 54.9 }));
  assert.equal(M.colourFor("laeq", null), null);
  assert.equal(M.colourFor("loud", { events_per_h_by_category: { loud_vehicle: 1.2 } }), M.METRICS.loud.colours[2]);
  assert.ok(Math.abs(M.tickPosition("laeq", 45) - 2 / 7) < 1e-12);
});

test("y range follows the app's rule (ChartModel.kt yRange)", () => {
  // fixture hours: lowest L90 44.0, loudest event 88.5 -> 35..95
  assert.deepEqual(M.yRange(cell.hours), { lo: 35, hi: 95 });
  assert.deepEqual(M.yRange([]), { lo: 20, hi: 80 });
  // at least 30 dB
  assert.deepEqual(M.yRange([{ laeq_db: 40, l90_db: 38, l10_db: 42, loudest_db: null }]), { lo: 30, hi: 60 });
  assert.deepEqual(M.gridlines({ lo: 35, hi: 95 }), [40, 50, 60, 70, 80, 90]);
});

test("chart model of the fixture night: 8 hours, one run, events counted", () => {
  const cm = M.chartModel(cell.hours);
  assert.equal(cm.slots.length, 8);
  assert.equal(cm.runs.length, 1);
  assert.equal(cm.gaps.length, 0);
  assert.equal(cm.slots[0].x0, 0);
  assert.equal(cm.slots[7].x1, 1);
  assert.equal(cm.labels[0].text, "22");
  assert.equal(cm.labels[8].text, "06");
  const hl = cell.hours.reduce((a, h) => a + Math.round(h.events.loud_vehicle), 0);
  assert.equal(cm.eventsHighlighted, hl);
});

test("no line across a missing hour: a null hour splits the runs and becomes a gap", () => {
  const hours = JSON.parse(JSON.stringify(cell.hours));
  Object.assign(hours[3], { laeq_db: null, l90_db: null, l10_db: null });
  const cm = M.chartModel(hours);
  assert.equal(cm.runs.length, 2);
  assert.equal(cm.runs[0].length, 3);
  assert.equal(cm.runs[1].length, 4);
  assert.equal(cm.gaps.length, 1);
  assert.equal(cm.gaps[0].hour, "01");
  const pts = M.stepPoints(cm.runs[0], "laeq");
  assert.equal(pts.length, 6);
  assert.ok(pts[pts.length - 1][0] <= cm.gaps[0].x0 + 1e-12);
});

test("autumn DST night: hour \"02\" twice, nine slots, still one run", () => {
  const hours = [];
  const iso = ["2026-10-24T22:00:00+02:00", "2026-10-24T23:00:00+02:00", "2026-10-25T00:00:00+02:00",
    "2026-10-25T01:00:00+02:00", "2026-10-25T02:00:00+02:00", "2026-10-25T02:00:00+01:00",
    "2026-10-25T03:00:00+01:00", "2026-10-25T04:00:00+01:00", "2026-10-25T05:00:00+01:00"];
  for (const s of iso) {
    hours.push({ hour: s.slice(11, 13), start: s, laeq_db: 50, l90_db: 45, l10_db: 52, events: {}, loudest_db: 70, measured_share: 1 });
  }
  const cm = M.chartModel(hours);
  assert.equal(cm.slots.length, 9);
  assert.equal(cm.runs.length, 1);
  assert.ok(Math.abs(cm.slots[5].x0 - 5 / 9) < 1e-12);
});
