import { html, Fragment, useState, useEffect, useRef } from '../html.mjs';
import { Refusal } from './refusal.mjs';
import { ViewPicker } from './viewpicker.mjs';
import { Cards } from './cards.mjs';
import { Table } from './table.mjs';
import { FilterBar } from './filterbar.mjs';
import { Detail } from './detail.mjs';
import { dataAsOfLabel } from '../format.mjs';

// B1-B7: the Fiches tab orchestrator. Owns discovery → picker/auto-select → yaml+render → the
// cards/table pair → filters → detail sheet/panel. `views` is a createViews(api, scope) instance
// (app.mjs constructs it); `onCount` feeds the header's count slot after every successful render.
//
// Two error tracks, deliberately kept apart: `error` blocks the whole tab (discovery/initial
// load failed — nothing to show yet); `filterError` is scoped to a bad filter (B5) — the last
// GOOD render's rows/columns stay on screen, only the refusal changes underneath the bar.
export function Fiches({ views, onCount }) {
  const [viewList, setViewList] = useState(null); // null = loading, [] = none, [...] = choices
  const [currentView, setCurrentView] = useState(null); // {name, title, ...} from the list
  const [pickerOpen, setPickerOpen] = useState(false);
  const [filters, setFilters] = useState([]);
  const [result, setResult] = useState(null); // {title, columns, rows} from the last GOOD render
  const [error, setError] = useState(null); // {kind, message} — blocks the tab
  const [filterError, setFilterError] = useState(null); // {kind, message} — bar-scoped only
  const [detail, setDetail] = useState(null); // null | {status, kind?, message?, payload?}
  const [wide, setWide] = useState(false); // matchMedia(≥900px) — decides sheet vs panel (F)
  const yamlCache = useRef({}); // name -> yaml text; mirrors the spike's module-level cache var

  useEffect(() => {
    const mq = matchMedia('(min-width: 900px)');
    setWide(mq.matches);
    const onChange = e => setWide(e.matches);
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const list = await views.list();
        setViewList(list);
        if (list.length === 1) await selectView(list[0]);
        else if (list.length > 1) setPickerOpen(true);
      } catch (e) {
        setError({ kind: e.kind || 'error', message: e.message || String(e) });
      }
    })();
  }, []);

  async function loadYaml(name) {
    if (!yamlCache.current[name]) {
      yamlCache.current[name] = await views.yaml(name);
    }
    return yamlCache.current[name];
  }

  async function selectView(entry) {
    setPickerOpen(false);
    setCurrentView(entry);
    setFilters([]);
    setFilterError(null);
    setError(null);
    try {
      const yaml = await loadYaml(entry.name);
      const rendered = await views.render(yaml, []);
      setResult(rendered);
      onCount(rendered.rows.length);
    } catch (e) {
      setError({ kind: e.kind || 'error', message: e.message || String(e) });
    }
  }

  async function applyFilters(next) {
    setFilters(next);
    setFilterError(null);
    try {
      const yaml = await loadYaml(currentView.name);
      const rendered = await views.render(yaml, next);
      setResult(rendered);
      onCount(rendered.rows.length);
    } catch (e) {
      // deliberately do NOT touch `result` — the last good render stays visible (B5)
      setFilterError({ kind: e.kind || 'error', message: e.message || String(e) });
    }
  }

  function onAddFilter(f) { applyFilters([...filters, f]); }
  function onRemoveFilter(i) { applyFilters(filters.filter((_, idx) => idx !== i)); }

  async function openDetail(key) {
    setDetail({ status: 'loading' });
    try {
      const yaml = await loadYaml(currentView.name);
      const payload = await views.detail(yaml, key);
      setDetail({ status: 'ready', payload });
    } catch (e) {
      setDetail({ status: 'error', kind: e.kind || 'error', message: e.message || String(e) });
    }
  }
  function closeDetail() { setDetail(null); }

  if (error) return html`<${Refusal} kind=${error.kind} message=${error.message} />`;
  if (viewList === null) return html`<div class="center">…</div>`;
  if (viewList.length === 0) {
    return html`<${Refusal} kind="scope"
      message=${`aucune vue appliquée pour le scope ${views.scope} — l'intégrateur doit en appliquer une`} />`;
  }
  if (pickerOpen) return html`<${ViewPicker} views=${viewList} onSelect=${selectView} />`;
  if (!result) return html`<div class="center">…</div>`;

  return html`
    <${Fragment}>
      ${viewList.length > 1
        ? html`<button class="switch-chip" onClick=${() => setPickerOpen(true)}>⇄ ${currentView.title || currentView.name}</button>`
        : null}
      <${FilterBar} filters=${filters} onAdd=${onAddFilter} onRemove=${onRemoveFilter} refusal=${filterError} />
      ${dataAsOfLabel(result.dataAsOf)
        ? html`<div class="data-asof">${dataAsOfLabel(result.dataAsOf)}</div>`
        : null}
      ${result.rows.length === 0
        ? html`<div class="center">Aucune ligne — la vue a rendu zéro résultat.</div>`
        : html`
          <${Cards} columns=${result.columns} rows=${result.rows} onOpen=${openDetail} />
          <${Table} columns=${result.columns} rows=${result.rows} onOpen=${openDetail} />
        `}
      ${detail ? html`<${Detail} data=${detail} onClose=${closeDetail} wide=${wide} />` : null}
    <//>
  `;
}
