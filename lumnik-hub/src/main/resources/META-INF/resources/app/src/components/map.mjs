import { html, useState, useEffect, useRef } from '../html.mjs';
import { splitRows, bounds, graticule, escapeText, tileOptions } from '../map.mjs';
import { Refusal } from './refusal.mjs';

// A view on a map (#2057): draws what map.mjs decided, nothing more. Leaflet is loaded the first time a
// map is opened, never with the shell: a user who never opens a map never downloads it. The dots are
// drawn on one canvas (preferCanvas), not one SVG node per row, so the 1 000-row cap stays light on a phone.
//
// `background` is the operator's tile server ({tiles, attribution}) or null: then no third party is
// contacted and the dots sit on a grid of parallels and meridians.
let leaflet = null;
function loadLeaflet() {
  if (!leaflet) {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'vendor/leaflet.css';
    document.head.appendChild(link);
    // A failed load (offline, a hub restart) is not kept: the next opening of a map tries again.
    leaflet = import('../../vendor/leaflet.mjs').catch((e) => { leaflet = null; throw e; });
  }
  return leaflet;
}

export function MapView({ rows, columns, background, onOpen }) {
  const el = useRef(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let map = null;
    let gone = false;
    loadLeaflet().then((L) => {
      if (gone) return;
      map = L.map(el.current, { preferCanvas: true, attributionControl: Boolean(background) });
      if (background) {
        map.attributionControl.setPrefix('Leaflet'); // text only: no link, no flag the operator did not choose
        L.tileLayer(background.tiles, tileOptions(background)).addTo(map);
      } else {
        for (const line of graticule()) L.polyline(line, { color: '#8a8aa0', opacity: 0.35, weight: 1, interactive: false }).addTo(map);
      }
      L.control.scale({ imperial: false }).addTo(map);
      const accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#7c5cff';
      const { placed } = splitRows(rows);
      const keyCol = columns[0].name;
      const titleCol = (columns[1] || columns[0]).name;
      for (const p of placed) {
        const key = String(p.row[keyCol] ?? '');
        L.circleMarker([p.lat, p.lon], { radius: 6, weight: 2, color: accent, fillColor: accent, fillOpacity: 0.75 })
          .bindTooltip(escapeText(p.row[titleCol] ?? key))
          .on('click', () => onOpen(key))
          .addTo(map);
      }
      const frame = bounds(placed);
      if (frame) map.fitBounds(frame, { padding: [28, 28], maxZoom: 13 });
      else map.setView([20, 0], 2);
    }).catch((e) => { if (!gone) setError(e && e.message ? e.message : String(e)); });
    return () => { gone = true; if (map) map.remove(); };
  }, [rows, background]);

  if (error) return html`<${Refusal} kind="error" message=${`La carte n'a pas pu se charger : ${error}`} />`;
  return html`<div class="map-view" ref=${el} role="application" aria-label="Carte des fiches"></div>`;
}
