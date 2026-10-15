import { html, Fragment, useState, useEffect, useRef } from '../html.mjs';
import { Refusal } from './refusal.mjs';
import { ViewPicker } from './viewpicker.mjs';
import { Cards } from './cards.mjs';
import { Table } from './table.mjs';
import { ROW_QUERY, WIDE_QUERY, rowRepresentation } from '../layout.mjs';
import { FilterBar } from './filterbar.mjs';
import { Detail } from './detail.mjs';
import { PageNav } from './pagenav.mjs';
import { dataAsOfLabel } from '../format.mjs';
import { createPager, truncationLabel, PAGE_SIZE } from '../pager.mjs';
import { useMatchMedia } from '../media.mjs';
import { resolveLink, followView, settleProbe } from '../links.mjs';

// B1-B7: the Fiches tab orchestrator. Owns discovery → picker/auto-select → yaml+render → the
// cards-or-table → filters → detail sheet/panel. `views` is a createViews(api, scope) instance
// (app.mjs constructs it); `onCount` feeds the header's count slot after every successful render.
//
// Two error tracks, deliberately kept apart: `error` blocks the whole tab (discovery/initial
// load failed — nothing to show yet); a refusal once a page is on screen is scoped — a bad
// filter (B5) shows under the bar, a failed page move under the page controls — and the last
// GOOD page stays on screen either way.
//
// Rows arrive one page at a time (pager.mjs owns position, cursors and the stale-response guard).
// A change of view starts a fresh pager; a change of filters resets the same one to page 1.
// A followed link (#1050) probes its target on a CANDIDATE pager and takes over only when the first
// page arrived: a refused or missing target leaves the current view on screen with the refusal, and
// never opens the target without its filters.
export function Fiches({ views, onCount }) {
  const [viewList, setViewList] = useState(null); // null = loading, [] = none, [...] = choices
  const [currentView, setCurrentView] = useState(null); // {name, title, ...} from the list
  const [pickerOpen, setPickerOpen] = useState(false);
  const [filters, setFilters] = useState([]);
  const [ps, setPs] = useState(null); // pager state: {result, pageNo, hasMore, error, loading, ...}
  const [error, setError] = useState(null); // {kind, message} — blocks the tab (discovery)
  const [detail, setDetail] = useState(null); // null | {status, kind?, message?, payload?}
  const yamlCache = useRef({}); // name -> yaml text; mirrors the spike's module-level cache var
  const pagerRef = useRef(null);
  const unsubRef = useRef(null);
  const filtersRef = useRef({ current: [] }); // the active pager's filters box, read by its load at request time
  const linkSeq = useRef(0); // the newest followed link wins; a view change cancels a pending one
  const [linkRefusal, setLinkRefusal] = useState(null); // {message} — scoped, the page on screen stays

  const wide = useMatchMedia(WIDE_QUERY); // decides sheet vs panel (F)
  const tabletUp = useMatchMedia(ROW_QUERY); // decides cards vs table — only one is mounted

  // Leaving the tab must silence the pager: an in-flight page could otherwise land after unmount
  // and write the header count slot of whatever tab the user moved to.
  useEffect(() => () => { linkSeq.current += 1; if (unsubRef.current) unsubRef.current(); }, []);

  useEffect(() => {
    (async () => {
      try {
        const list = await views.list();
        setViewList(list);
        if (list.length === 1) selectView(list[0]);
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

  function makePager(entry, box) {
    return createPager(async cursor => {
      const yaml = await loadYaml(entry.name);
      return views.render(yaml, box.current, { pageSize: PAGE_SIZE, cursor });
    });
  }

  function onPagerState(s) {
    setPs(s);
    if (s.result && !s.loading) onCount(`${s.result.rows.length}${s.hasMore ? '+' : ''}`);
  }

  // Makes (entry, box, pager) the one on screen. The caller starts the first load.
  function mount(entry, box, pager) {
    setPickerOpen(false);
    setCurrentView(entry);
    setFilters(box.current);
    setError(null);
    setLinkRefusal(null);
    setDetail(null);
    if (unsubRef.current) unsubRef.current();
    filtersRef.current = box;
    pagerRef.current = pager;
    unsubRef.current = pager.subscribe(onPagerState);
  }

  function selectView(entry) {
    linkSeq.current += 1;
    const box = { current: [] };
    const pager = makePager(entry, box);
    mount(entry, box, pager);
    setPs(null);
    pager.reset();
  }

  async function followLink(link, row) {
    const resolved = resolveLink(link, row);
    if (!resolved || resolved.kind !== 'view') return;
    const mine = ++linkSeq.current;
    const out = followView(resolved, viewList);
    if (!out.ok) { setLinkRefusal({ message: out.message }); return; }
    setLinkRefusal(null);
    const box = { current: out.filters };
    const candidate = makePager(out.entry, box);
    const off = candidate.subscribe(s => {
      const verdict = settleProbe(s, mine !== linkSeq.current);
      if (verdict === 'wait') return;
      off();
      if (verdict === 'commit') { mount(out.entry, box, candidate); onPagerState(s); }
      else if (verdict === 'refuse') setLinkRefusal({ message: `Lien refusé : ${s.error ? s.error.message : 'réponse vide'}` });
    });
    candidate.reset();
  }

  function applyFilters(next) {
    linkSeq.current += 1; // a link still being probed must not take over a newer gesture
    setLinkRefusal(null);
    setFilters(next);
    filtersRef.current.current = next;
    // a failure leaves the last good page on screen — the pager keeps it (B5)
    pagerRef.current.reset();
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

  const pagerError = ps && ps.error;
  const blocking = pagerError && !ps.result ? pagerError : error;
  if (blocking) return html`<${Refusal} kind=${blocking.kind} message=${blocking.message} />`;
  if (viewList === null) return html`<div class="center">…</div>`;
  if (viewList.length === 0) {
    return html`<${Refusal} kind="scope"
      message=${`aucune vue appliquée pour le scope ${views.scope} — l'intégrateur doit en appliquer une`} />`;
  }
  if (pickerOpen) return html`<${ViewPicker} views=${viewList} onSelect=${selectView} />`;
  const result = ps && ps.result;
  if (!result) return html`<div class="center">…</div>`;
  const pageLabel = truncationLabel(ps);

  // The same controls above and below the rows, both driving the one pager.
  const pageNav = where => html`<${PageNav} where=${where} state=${ps}
    onFirst=${() => pagerRef.current.first()}
    onPrev=${() => pagerRef.current.prev()}
    onNext=${() => pagerRef.current.next()}
    refusal=${ps.errorOrigin === 'move' ? pagerError : null} />`;

  return html`
    <${Fragment}>
      ${viewList.length > 1
        ? html`<button class="switch-chip" onClick=${() => { linkSeq.current += 1; setPickerOpen(true); }}>⇄ ${currentView.title || currentView.name}</button>`
        : null}
      <h2 class="view-title">${result.title || currentView.title || currentView.name}</h2>
      ${linkRefusal ? html`<${Refusal} kind="error" message=${linkRefusal.message} />` : null}
      <${FilterBar} columns=${result.columns} filters=${filters} onAdd=${onAddFilter} onRemove=${onRemoveFilter} refusal=${ps.errorOrigin === 'reset' ? pagerError : null} />
      ${dataAsOfLabel(result.dataAsOf)
        ? html`<div class="data-asof">${dataAsOfLabel(result.dataAsOf)}</div>`
        : null}
      ${pageLabel ? html`<div class="page-label">${pageLabel}</div>` : null}
      ${pageNav('haut')}
      ${result.rows.length === 0
        ? html`<div class="center">Aucune ligne — la vue a rendu zéro résultat.</div>`
        : html`
          ${rowRepresentation(tabletUp) === 'table'
            ? html`<${Table} columns=${result.columns} rows=${result.rows} links=${result.links} onFollow=${followLink} onOpen=${openDetail} />`
            : html`<${Cards} columns=${result.columns} rows=${result.rows} links=${result.links} onFollow=${followLink} onOpen=${openDetail} />`}
        `}
      ${pageNav('bas')}
      ${detail ? html`<${Detail} data=${detail} onClose=${closeDetail} wide=${wide} />` : null}
    <//>
  `;
}
