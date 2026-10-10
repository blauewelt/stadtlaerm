/* The noise map (docs/karte.html): Leaflet, swisstopo's grey map, one quadrilateral per
 * hectare cell, coloured by the chosen metric; a card per cell with the night chart.
 *
 * Reads only the published files of server/DESIGN.md §6 from the base URL in the map
 * element's `data-api` attribute: cells.json (the last complete night), nights/{date}.json
 * (older nights, 404 when the server has none) and cells/{cell}.json (one cell's history and
 * the hours of the last night). Depends on window.L (vendored Leaflet 1.9.4), window.Lv95
 * (lv95.js) and window.KarteModel (model.js). No inline script, no innerHTML with data:
 * the page runs under a CSP without 'unsafe-inline'.
 */
(function () {
  "use strict";

  var M = window.KarteModel;
  var Lv95 = window.Lv95;
  var L = window.L;

  var TILES = "https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-grau/default/current/3857/{z}/{x}/{y}.jpeg";
  var BOUNDS = [[47.30, 8.42], [47.45, 8.65]];   // Zürich region, [S, W], [N, E]
  var MIN_ZOOM = 11;
  var MAX_ZOOM = 18;
  var DOT_MAX_ZOOM = 13;     // up to this zoom a hectare is ≤ 8 px: also draw a dot
  var HISTORY_NIGHTS = 90;   // DESIGN §7: ‹ › through the last 90 nights
  var SVGNS = "http://www.w3.org/2000/svg";

  var mapEl = document.getElementById("karte");
  var api = (mapEl.getAttribute("data-api") || "https://api.stadtlaerm.ch/v1/map/").replace(/\/?$/, "/");

  var state = {
    metric: "laeq",
    latest: null,       // night of cells.json
    night: null,        // night shown
    file: null,         // the night file shown (cells.json or nights/{date}.json), or null
    byCell: {},         // cell id -> entry of the shown file
    layers: {},         // cell id -> { poly, dot }
    selected: null,     // cell id of the open card
    history: {},        // cell id -> Promise of cells/{cell}.json
    loadSeq: 0,
  };

  /* ---------- small DOM helpers ---------- */

  function el(tag, attrs, children) {
    var n = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      if (k === "text") n.textContent = attrs[k];
      else if (k === "class") n.className = attrs[k];
      else n.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) {
      if (c !== null && c !== undefined) n.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return n;
  }

  function svg(tag, attrs, children) {
    var n = document.createElementNS(SVGNS, tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === "text") n.textContent = attrs[k];
      else n.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c) n.appendChild(c); });
    return n;
  }

  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }

  function setStatus(text) {
    var s = document.getElementById("status");
    s.textContent = text || "";
    s.hidden = !text;
  }

  /* ---------- fetching ---------- */

  function getJson(path) {
    return fetch(api + path, { credentials: "omit", referrerPolicy: "no-referrer" }).then(function (r) {
      if (r.status === 404) { var e = new Error("404"); e.notFound = true; throw e; }
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    });
  }

  function history(cellId) {
    if (!state.history[cellId]) {
      state.history[cellId] = getJson("cells/" + cellId + ".json").catch(function (e) {
        delete state.history[cellId];
        throw e;
      });
    }
    return state.history[cellId];
  }

  /* ---------- the map ---------- */

  var map = L.map(mapEl, {
    minZoom: MIN_ZOOM,
    maxZoom: MAX_ZOOM,
    maxBounds: BOUNDS,
    maxBoundsViscosity: 1.0,
    zoomSnap: 0.5,
    keyboard: true,
    attributionControl: true,
  });
  map.fitBounds(BOUNDS);

  L.tileLayer(TILES, {
    attribution: "© swisstopo",
    minZoom: MIN_ZOOM,
    maxZoom: MAX_ZOOM,
    bounds: BOUNDS,          // swisstopo answers 400 outside Switzerland; stay inside
    referrerPolicy: "no-referrer",
  }).addTo(map);

  var canHover = window.matchMedia && window.matchMedia("(hover: hover) and (pointer: fine)").matches;
  var tip = L.tooltip({ className: "cell-tip", direction: "top", offset: [0, -6], opacity: 1 });

  function valueText(metricId, block) {
    var m = M.METRICS[metricId];
    var v = m.get(block);
    if (v === null || v === undefined) return "keine Messung";
    return M.fmt1(v) + " " + m.unit;
  }

  function cellStyle(entry) {
    var fill = M.colourFor(state.metric, entry.last_night);
    return { fillColor: fill || "#ffffff", fillOpacity: fill ? 0.78 : 0 };
  }

  function cellClasses(entry) {
    var c = ["cell"];
    if (!entry.calibrated) c.push("uncal");
    if (!M.colourFor(state.metric, entry.last_night)) c.push("nodata");
    if (entry.cell === state.selected) c.push("selected");
    return c.join(" ");
  }

  function applyClasses(layer, entry) {
    var p = layer.getElement && layer.getElement();
    if (p) {
      p.setAttribute("class", cellClasses(entry) + " leaflet-interactive");
      p.setAttribute("data-cell", entry.cell);
    }
  }

  function bindCell(layer, entry) {
    layer.on("click", function () { openCard(entry.cell, false); });
    if (canHover) {
      layer.on("mouseover", function () {
        var e = state.byCell[entry.cell];
        if (!e) return;
        tip.setLatLng(Lv95.cellCenter(entry.cell))
          .setContent(el("span", { text: valueText(state.metric, e.last_night) + (e.calibrated ? "" : " · unkalibriert") }));
        map.openTooltip(tip);
      });
      layer.on("mouseout", function () { map.closeTooltip(tip); });
    }
  }

  function clearCells() {
    Object.keys(state.layers).forEach(function (id) {
      map.removeLayer(state.layers[id].poly);
      map.removeLayer(state.layers[id].dot);
    });
    state.layers = {};
  }

  function drawCells() {
    clearCells();
    var cells = state.file ? state.file.cells : [];
    // quiet first, loud last, so the loud ones are drawn on top where dots overlap
    var sorted = cells.slice().sort(function (a, b) {
      var va = M.METRICS[state.metric].get(a.last_night), vb = M.METRICS[state.metric].get(b.last_night);
      return (va === null || va === undefined ? -1 : va) - (vb === null || vb === undefined ? -1 : vb);
    });
    sorted.forEach(function (entry) {
      var corners;
      try { corners = Lv95.cellToCorners(entry.cell); } catch (e) { return; }
      var style = cellStyle(entry);
      var poly = L.polygon(corners, {
        color: "#17202e", weight: 1, fillColor: style.fillColor, fillOpacity: style.fillOpacity,
        className: cellClasses(entry), bubblingMouseEvents: false,
      });
      var dot = L.circleMarker(Lv95.cellCenter(entry.cell), {
        radius: 6, color: "#17202e", weight: 1, fillColor: style.fillColor,
        fillOpacity: style.fillOpacity ? 0.9 : 0, className: cellClasses(entry), bubblingMouseEvents: false,
      });
      bindCell(poly, entry);
      bindCell(dot, entry);
      poly.addTo(map);
      applyClasses(poly, entry);
      state.layers[entry.cell] = { poly: poly, dot: dot };
    });
    updateDots();
    fillList();
  }

  function restyleCells() {
    Object.keys(state.layers).forEach(function (id) {
      var entry = state.byCell[id];
      var s = cellStyle(entry);
      var l = state.layers[id];
      l.poly.setStyle({ fillColor: s.fillColor, fillOpacity: s.fillOpacity });
      l.dot.setStyle({ fillColor: s.fillColor, fillOpacity: s.fillOpacity ? 0.9 : 0 });
      applyClasses(l.poly, entry);
      applyClasses(l.dot, entry);
    });
  }

  function updateDots() {
    var show = map.getZoom() <= DOT_MAX_ZOOM;
    Object.keys(state.layers).forEach(function (id) {
      var l = state.layers[id];
      if (show && !map.hasLayer(l.dot)) { l.dot.addTo(map); applyClasses(l.dot, state.byCell[id]); }
      if (!show && map.hasLayer(l.dot)) map.removeLayer(l.dot);
    });
  }
  map.on("zoomend", updateDots);

  function fitToCells() {
    var cells = state.file ? state.file.cells : [];
    if (!cells.length) return;
    var b = L.latLngBounds([]);
    cells.forEach(function (c) {
      try { Lv95.cellToCorners(c.cell).forEach(function (p) { b.extend(p); }); } catch (e) { /* skip */ }
    });
    if (b.isValid()) map.fitBounds(b, { padding: [24, 24], maxZoom: 15 });
  }

  /* ---------- legend ---------- */

  function drawLegend() {
    var m = M.METRICS[state.metric];
    document.getElementById("legend-title").textContent = m.title;
    var bar = document.getElementById("legend-bar");
    clear(bar);
    m.colours.forEach(function (c) {
      var s = el("span");
      s.style.background = c;
      bar.appendChild(s);
    });
    var ticks = document.getElementById("legend-ticks");
    clear(ticks);
    m.ticks.forEach(function (v) {
      var isLsv = state.metric === "laeq";
      var t = el("span", { class: isLsv ? "lsv" : "", "data-value": String(v), text: M.fmt1(v).replace(/,0$/, "") });
      t.style.left = (M.tickPosition(state.metric, v) * 100) + "%";
      ticks.appendChild(t);
    });
    var lsv = document.getElementById("legend-lsv");
    clear(lsv);
    var note = document.getElementById("legend-note");
    if (state.metric === "laeq") {
      lsv.hidden = false;
      lsv.appendChild(el("li", { class: "lsv-head" }, [
        "Nachtwerte der Lärmschutz-Verordnung (LSV) für Strassenlärm; ES heisst Empfindlichkeitsstufe:",
      ]));
      M.LSV_TICKS.forEach(function (t) {
        lsv.appendChild(el("li", { "data-db": String(t.db) }, [el("b", { text: t.db + " dB" }), el("span", { text: t.text })]));
      });
      note.textContent = "Die Werte der Verordnung sind Beurteilungspegel Lr, mit Korrekturen und über das ganze Jahr " +
        "beurteilt; die Karte zeigt den gemessenen LAeq einzelner Nächte – die Marken dienen der Orientierung " +
        "und sind keine rechtliche Beurteilung.";
    } else {
      lsv.hidden = true;
      note.textContent = {
        dynamics: "Wenige dB: gleichmässiger Verkehr. Viele dB: ruhige Strasse mit einzelnen lauten Fahrzeugen.",
        events: "Gezählt pro Stunde gültiger Messzeit; ein Ereignis ist ein kurzer Pegelanstieg deutlich über den Hintergrund.",
        loud: "Gezählt pro Stunde gültiger Messzeit; die Quelle erkennt die App auf dem Telefon, ohne Audio zu speichern.",
      }[state.metric];
    }
  }

  /* ---------- list of cells (keyboard and screen readers) ---------- */

  function fillList() {
    var ol = document.getElementById("celllist-items");
    clear(ol);
    var cells = state.file ? state.file.cells.slice() : [];
    var get = M.METRICS[state.metric].get;
    cells.sort(function (a, b) {
      var va = get(a.last_night), vb = get(b.last_night);
      if (va === null || va === undefined) return 1;
      if (vb === null || vb === undefined) return -1;
      return vb - va;
    });
    cells.forEach(function (c) {
      var sw = el("span", { class: "swatch", "aria-hidden": "true" });
      sw.style.background = M.colourFor(state.metric, c.last_night) || "transparent";
      var b = el("button", { type: "button", "data-cell": c.cell }, [
        sw,
        el("span", { text: c.cell + (c.calibrated ? "" : " (unkalibriert)") }),
        el("span", { class: "val", text: valueText(state.metric, c.last_night) }),
      ]);
      b.addEventListener("click", function () { openCard(c.cell, true); });
      ol.appendChild(el("li", null, [b]));
    });
    document.getElementById("celllist").hidden = !cells.length;
  }

  /* ---------- the card ---------- */

  function row(label, value, when) {
    return el("div", { class: "row" }, [
      el("dt", { text: label }),
      el("dd", { text: value }),
      when ? el("span", { class: "when", text: when }) : null,
    ]);
  }

  function windowRows(title, w, n) {
    if (!w || !w.nights_with_data) {
      return el("section", { class: "card-sec" }, [
        el("h3", { text: title }), el("p", { class: "card-note", text: "Keine Messung in diesen Nächten." }),
      ]);
    }
    var span = M.spanLabel(state.night, n);
    return el("section", { class: "card-sec" }, [
      el("h3", null, [title, el("small", { text: span + ", " + w.nights_with_data + " von " + n + " Nächten gemessen" })]),
      el("dl", { class: "rows" }, [
        row("Nacht-LAeq", M.fmt1(w.laeq_db) + " dB(A)"),
        row("Dynamik", M.fmt1(w.dynamics_db) + " dB"),
        row("Ereignisse", M.fmt1(w.events_per_h) + " pro h"),
      ]),
    ]);
  }

  function nightRows(b) {
    var when = M.nightShort(state.night);
    var share = b.measured_share;
    return el("dl", { class: "rows" }, [
      row("Nacht-LAeq", M.fmt1(b.laeq_db) + " dB(A)", when),
      row("L90 (Hintergrund)", M.fmt1(b.l90_db) + " dB(A)", when),
      row("L10 (laut)", M.fmt1(b.l10_db) + " dB(A)", when),
      row("Dynamik", M.fmt1(b.dynamics_db) + " dB", when),
      row("Ereignisse", M.fmt1(b.events_per_h) + " pro h", when),
      row("Töff & Poser", M.fmt1((b.events_per_h_by_category || {}).loud_vehicle) + " pro h", when),
      row("Lautestes", M.fmt1(b.loudest_event_db) + " dB(A)", when),
      row("Gemessen", M.fmtPct(share), when),
    ]);
  }

  /** The app's night chart, redrawn from the hourly rows of cells/{cell}.json. */
  function nightChart(hours) {
    var cm = M.chartModel(hours);
    if (!cm) return null;
    var W = 320, left = 26, right = 6, top = 6, plotH = 104;
    var maxDots = 0;
    cm.slots.forEach(function (s) { maxDots = Math.max(maxDots, s.highlighted + s.others); });
    var plotW = W - left - right;
    var slotW = plotW / cm.slots.length;
    var perRow = Math.max(1, Math.floor((slotW - 2) / 4.4));
    var rows = Math.min(3, Math.max(1, Math.ceil(maxDots / perRow)));
    var stripTop = top + plotH + 4, stripH = rows * 4.4 + 2;
    var axisY = stripTop + stripH + 11;
    var H = axisY + 3;
    var r = cm.range;
    function X(f) { return left + f * plotW; }
    function Y(db) { return top + (r.hi - db) / (r.hi - r.lo) * plotH; }

    var root = svg("svg", {
      class: "nightchart", viewBox: "0 0 " + W + " " + H.toFixed(1), role: "img",
      "aria-label": "Nachtverlauf pro Stunde: LAeq zwischen " + M.fmt1(cm.laeqMin) + " und " + M.fmt1(cm.laeqMax) +
        " dB(A), " + cm.eventsTotal + " Ereignisse, davon " + cm.eventsHighlighted + " Töff & Poser" +
        (cm.gaps.length ? ", " + cm.gaps.length + " Stunden ohne Messung" : ""),
    });

    cm.grid.forEach(function (g) {
      root.appendChild(svg("line", { class: "grid", x1: left, x2: W - right, y1: Y(g).toFixed(1), y2: Y(g).toFixed(1) }));
      root.appendChild(svg("text", { class: "axis", x: left - 4, y: (Y(g) + 3.5).toFixed(1), "text-anchor": "end", text: String(g) }));
    });

    cm.gaps.forEach(function (s) {
      var x0 = X(s.x0), x1 = X(s.x1);
      root.appendChild(svg("rect", { class: "gap", "data-hour": s.hour, x: x0.toFixed(1), y: top, width: (x1 - x0).toFixed(1), height: plotH }));
      root.appendChild(svg("text", { class: "gaplabel", x: ((x0 + x1) / 2).toFixed(1), y: top + plotH / 2, "text-anchor": "middle", text: "–" }));
    });

    // band L90–L10 and the LAeq line, one shape per run of consecutive hours with data
    cm.runs.forEach(function (run) {
      var hasBand = run.every(function (s) { return s.l90 !== null && s.l10 !== null; });
      if (hasBand) {
        var up = M.stepPoints(run, "l10"), down = M.stepPoints(run, "l90").reverse();
        var d = up.concat(down).map(function (p, i) {
          return (i ? "L" : "M") + X(p[0]).toFixed(1) + " " + Y(p[1]).toFixed(1);
        }).join(" ") + " Z";
        root.appendChild(svg("path", { class: "band", d: d }));
      }
      var line = M.stepPoints(run, "laeq").map(function (p, i) {
        return (i ? "L" : "M") + X(p[0]).toFixed(1) + " " + Y(p[1]).toFixed(1);
      }).join(" ");
      root.appendChild(svg("path", { class: "laeq", d: line }));
    });

    // loudest event of each hour, and one dot per event in the strip under the plot
    cm.slots.forEach(function (s) {
      var cx = (X(s.x0) + X(s.x1)) / 2;
      if (s.loudest !== null && s.loudest !== undefined) {
        root.appendChild(svg("circle", { class: "peak", cx: cx.toFixed(1), cy: Y(Math.min(r.hi, s.loudest)).toFixed(1), r: 3 }));
      }
      var kinds = [];
      for (var i = 0; i < s.highlighted; i++) kinds.push(true);
      for (var j = 0; j < s.others; j++) kinds.push(false);
      kinds = kinds.slice(0, perRow * rows);
      var x0 = X(s.x0) + (slotW - Math.min(kinds.length, perRow) * 4.4) / 2 + 2.2;
      kinds.forEach(function (hl, k) {
        root.appendChild(svg("circle", {
          class: hl ? "dot hl" : "dot",
          cx: (x0 + (k % perRow) * 4.4).toFixed(1),
          cy: (stripTop + 2.2 + Math.floor(k / perRow) * 4.4).toFixed(1),
          r: hl ? 1.9 : 1.5,
        }));
      });
    });

    cm.labels.forEach(function (lb, i) {
      if (i % 2) return;
      root.appendChild(svg("text", { class: "axis", x: X(lb.x).toFixed(1), y: axisY.toFixed(1), "text-anchor": "middle", text: lb.text }));
    });
    return root;
  }

  function chartSection(cellId, sec) {
    history(cellId).then(function (h) {
      if (state.selected !== cellId) return;
      clear(sec);
      if (h.hours_night !== state.night || !h.hours || !h.hours.length) {
        sec.appendChild(el("h3", { text: "Verlauf der Nacht" }));
        sec.appendChild(el("p", {
          class: "card-note",
          text: h.hours_night === state.night
            ? "Für diese Nacht gibt es keine Stundenwerte."
            : "Den Verlauf pro Stunde veröffentlicht der Server nur für die letzte Nacht (" + M.nightShort(h.hours_night) + ").",
        }));
        return;
      }
      sec.appendChild(el("h3", null, ["Verlauf der Nacht", el("small", { text: "pro Stunde, " + M.nightShort(state.night) })]));
      sec.appendChild(nightChart(h.hours));
      sec.appendChild(el("ul", { class: "chart-keys" }, [
        el("li", null, [el("i", { class: "k-band" }), "L90–L10"]),
        el("li", null, [el("i", { class: "k-line" }), "LAeq"]),
        el("li", null, [el("i", { class: "k-peak" }), "lautestes Ereignis"]),
        el("li", null, [el("i", { class: "k-hl" }), "Töff & Poser"]),
        el("li", null, [el("i", { class: "k-dot" }), "andere Ereignisse"]),
      ]));
      var missing = h.hours.filter(function (x) { return x.laeq_db === null || x.laeq_db === undefined; }).length;
      if (missing) sec.appendChild(el("p", { class: "card-note", text: missing + (missing === 1 ? " Stunde" : " Stunden") + " mit zu wenig Messzeit (unter 30 Minuten): dort keine Linie." }));
    }).catch(function (e) {
      if (state.selected !== cellId) return;
      clear(sec);
      sec.appendChild(el("h3", { text: "Verlauf der Nacht" }));
      sec.appendChild(el("p", { class: "card-note", text: e.notFound ? "Für diese Hektare gibt es keinen Verlauf." : "Der Verlauf konnte nicht geladen werden." }));
    });
  }

  function renderCard() {
    var card = document.getElementById("card");
    var body = document.getElementById("card-body");
    var id = state.selected;
    if (!id) { card.hidden = true; document.querySelector(".mapgrid").classList.add("no-card"); return; }
    var entry = state.byCell[id];
    clear(body);
    var title = document.getElementById("card-title");
    clear(title);
    title.appendChild(document.createTextNode("Hektare " + id + " "));
    if (entry && !entry.calibrated) title.appendChild(el("span", { class: "badge", id: "badge-uncal", text: "unkalibriert" }));

    var sw = Lv95.parseCell(id);
    body.appendChild(el("p", { class: "card-sub", text: "LV95 E " + sw.e + "–" + (sw.e + 100) + ", N " + sw.n + "–" + (sw.n + 100) +
      (entry ? " · " + entry.devices + (entry.devices === 1 ? " Gerät" : " Geräte") : "") }));

    if (!entry) {
      body.appendChild(el("p", { class: "card-note", text: "In der " + M.nightLabel(state.night) + " ist diese Hektare nicht auf der Karte." }));
    } else {
      var b = entry.last_night;
      var nightSec = el("section", { class: "card-sec" }, [el("h3", null, [M.nightLabel(state.night)])]);
      if (b) nightSec.appendChild(nightRows(b));
      else nightSec.appendChild(el("p", { class: "card-note", text: "In dieser Nacht keine Messung; unten die Mittel der Nächte davor." }));
      body.appendChild(nightSec);
      if (b) {
        var chart = el("section", { class: "card-sec chart-sec" }, [el("p", { class: "card-note", text: "Verlauf wird geladen …" })]);
        body.appendChild(chart);
        chartSection(id, chart);
      }
      body.appendChild(windowRows("Mittel der letzten 7 Nächte", entry.last_7_nights, 7));
      body.appendChild(windowRows("Mittel der letzten 30 Nächte", entry.last_30_nights, 30));
      if (!entry.calibrated) {
        body.appendChild(el("p", { class: "card-note", text: "Unkalibriert: Mindestens ein Telefon hier wurde nicht mit einem Schallpegelmesser verglichen; die Pegel können um mehrere Dezibel abweichen." }));
      }
    }
    card.hidden = false;
    document.querySelector(".mapgrid").classList.remove("no-card");
  }

  function openCard(cellId, fromKeyboard) {
    state.selected = cellId;
    restyleCells();
    renderCard();
    map.closeTooltip(tip);
    var card = document.getElementById("card");
    if (fromKeyboard) {
      card.focus();
      var l = state.layers[cellId];
      if (l) map.panTo(Lv95.cellCenter(cellId));
    } else if (window.matchMedia && !window.matchMedia("(min-width: 56rem)").matches) {
      card.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }
  }

  function closeCard() {
    var was = state.selected;
    state.selected = null;
    restyleCells();
    renderCard();
    if (was) {
      var btn = document.querySelector('#celllist-items button[data-cell="' + was + '"]');
      if (btn && document.getElementById("celllist").open) btn.focus();
      else mapEl.focus();
    }
  }

  document.getElementById("card-close").addEventListener("click", closeCard);
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape" && state.selected) closeCard();
  });

  /* ---------- nights ---------- */

  function setNightText(text) {
    document.getElementById("night").textContent = text;
  }

  function updateButtons() {
    var prev = document.getElementById("prev"), next = document.getElementById("next");
    if (!state.latest) { prev.disabled = next.disabled = true; return; }
    var back = M.daysBetween(state.night, state.latest);
    prev.disabled = back >= HISTORY_NIGHTS - 1;
    next.disabled = back <= 0;
  }

  function showFile(file, night) {
    state.file = file;
    state.night = night;
    state.byCell = {};
    (file ? file.cells : []).forEach(function (c) { state.byCell[c.cell] = c; });
    setNightText(M.nightLabel(night));
    drawCells();
    if (state.selected) renderCard();
    updateButtons();
  }

  function loadNight(night) {
    var seq = ++state.loadSeq;
    state.night = night;
    setNightText(M.nightLabel(night) + " – wird geladen …");
    updateButtons();
    var path = night === state.latest ? "cells.json" : "nights/" + night + ".json";
    return getJson(path).then(function (file) {
      if (seq !== state.loadSeq) return;
      showFile(file, night);
      setStatus(file.cells && file.cells.length ? "" : "In dieser Nacht keine Messwerte.");
    }).catch(function (e) {
      if (seq !== state.loadSeq) return;
      showFile(null, night);
      if (e.notFound) setNightText(M.nightLabel(night) + " – keine Daten");
      setStatus(e.notFound ? "Für diese Nacht hat der Server keine Daten." : "Die Messwerte konnten nicht geladen werden (" + e.message + ").");
    });
  }

  document.getElementById("prev").addEventListener("click", function () {
    if (state.night) loadNight(M.addDays(state.night, -1));
  });
  document.getElementById("next").addEventListener("click", function () {
    if (state.night && state.night !== state.latest) loadNight(M.addDays(state.night, 1));
  });

  Array.prototype.forEach.call(document.querySelectorAll('#metric input[name="metric"]'), function (input) {
    input.addEventListener("change", function () {
      if (!input.checked) return;
      state.metric = input.value;
      drawLegend();
      drawCells();
      if (state.selected) renderCard();
    });
  });

  function showNetwork(file) {
    var n = file && file.network;
    var p = document.getElementById("network");
    if (!n) { p.textContent = ""; return; }
    var models = Object.keys(n.device_models || {}).sort(function (a, b) {
      return n.device_models[b] - n.device_models[a] || a.localeCompare(b);
    }).map(function (k) { return k + " " + n.device_models[k]; });
    p.textContent = "Beteiligte Geräte in den letzten 7 Tagen: " + n.devices_active_7d +
      (models.length ? " (" + models.join(", ") + ")" : "") + ". Stand der Daten: " + M.stampLabel(file.generated_at) + ".";
  }

  /* ---------- start ---------- */

  drawLegend();
  renderCard();
  getJson("cells.json").then(function (file) {
    if (!M.isDate(file.night)) throw new Error("cells.json ohne Nacht");
    state.latest = file.night;
    showFile(file, file.night);
    showNetwork(file);
    fitToCells();
    setStatus(file.cells.length ? "" : "Noch keine Messwerte auf der Karte.");
    mapEl.setAttribute("data-ready", "1");
  }).catch(function (e) {
    setNightText("keine Daten");
    setStatus("Die Messwerte konnten nicht geladen werden" + (e.notFound ? "." : " (" + e.message + ")."));
    updateButtons();
    mapEl.setAttribute("data-ready", "error");
  });

  // for the Playwright tests: the map and the layer of each cell
  window.__karte = { map: map, state: state };
})();
