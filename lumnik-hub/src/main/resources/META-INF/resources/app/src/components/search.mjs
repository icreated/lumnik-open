import { html, Fragment, useState, useEffect, useRef } from '../html.mjs';
import { Refusal } from './refusal.mjs';
import { Detail } from './detail.mjs';
import { CellLink } from './celllink.mjs';
import { useMatchMedia } from '../media.mjs';
import { WIDE_QUERY } from '../layout.mjs';
import {
  checkQuery, queryString, clearedSearch, canClear, classify, hitView, facetChips, asOfLine, recordToDetail, facetLabel, mergeLabels,
} from '../search.mjs';

// The Search tab: one input, an explicit submit (the hub rate-limits search and q is 1-200
// characters: no type-ahead), ranked hits with the matched words marked, the facets the hub returns
// as chips, more results by cursor, and a hit that opens a read-only record sheet. All the decisions
// live in src/search.mjs; this draws them. `scope` is the open app's scope: every search carries it.
export function Search({ api, scope }) {
  const [q, setQ] = useState('');
  const [facets, setFacets] = useState({});
  const [state, setState] = useState({ status: 'idle' }); // idle | loading | ready | error
  const [degraded, setDegraded] = useState(null);         // the reason, when the provider is degraded
  const [detail, setDetail] = useState(null);
  const lastQ = useRef('');
  const ticket = useRef(0);                               // a slower, older answer never replaces a newer one
  const openTicket = useRef(0);                           // same rule for the record sheet: the last tap wins
  const wide = useMatchMedia(WIDE_QUERY);

  // The probe decided PRESENCE; the reason a degraded provider gives is read here, once.
  useEffect(() => {
    api.json('/api/search/capability', { method: 'GET' })
      .then((c) => { if (c && c.state === 'degraded') setDegraded(c.reason || 'raison inconnue'); })
      .catch(() => {});
  }, []);

  async function run(text, nextFacets, cursor) {
    const refusal = checkQuery(text);
    if (refusal) { setState({ status: 'error', kind: 'error', message: refusal }); return; }
    const mine = ++ticket.current;
    const before = cursor && state.status === 'ready' ? state : null;
    setState(before ? { ...before, more: 'loading', moreError: null } : { status: 'loading' });
    try {
      const res = await api.json('/api/search?' + queryString({ q: text, scope, facets: nextFacets, cursor }), { method: 'GET' });
      if (mine !== ticket.current) return;
      lastQ.current = text.trim();
      setState({
        status: 'ready',
        hits: (before ? before.hits : []).concat(res.hits || []),
        facets: res.facets || {},
        nextCursor: res.nextCursor || null,
        asOf: res.asOf || null,
        labels: mergeLabels(before ? before.labels : {}, res.fieldLabels), // the entity's column names, #1055
      });
    } catch (e) {
      if (mine !== ticket.current || (e && e.kind === 'auth')) return;
      const said = classify(e);
      // a failed "more" keeps the page already on screen and says so under it
      setState(before ? { ...before, more: null, moreError: said } : { status: 'error', ...said });
    }
  }

  function submit(e) { e.preventDefault(); setFacets({}); run(q, {}, null); }
  function toggleFacet(field, value) {
    const next = { ...facets };
    if (next[field] === value) delete next[field]; else next[field] = value;
    setFacets(next);
    run(lastQ.current || q, next, null);
  }

  async function open(hit) {
    const mine = ++openTicket.current;
    const labels = state.status === 'ready' ? state.labels : {};
    setDetail({ status: 'loading' });
    try {
      const rows = await api.json(hit.link, { method: 'GET' }); // the hub's own canonicalLink, as given
      if (mine === openTicket.current) setDetail(recordToDetail(hit.entity, hit.rawKey, rows, labels));
    } catch (e) {
      if (mine !== openTicket.current || (e && e.kind === 'auth')) return;
      const said = classify(e);
      setDetail({ status: 'error', ...said });
    }
  }
  // Back to the initial screen. Bumping both tickets drops any answer still on its way, and nothing
  // here calls run(): clearing never sends a search.
  function clear() {
    ticket.current += 1;
    openTicket.current += 1;
    lastQ.current = '';
    const at = clearedSearch();
    setQ(at.q); setFacets(at.facets); setState(at.state); setDetail(at.detail);
  }
  function closeDetail() { openTicket.current += 1; setDetail(null); } // a late answer must not reopen it

  const ready = state.status === 'ready' ? state : null;
  const entities = ready ? [...new Set(ready.hits.map((h) => h.entity))] : [];
  return html`
    <${Fragment}>
      <form class="searchbar" onSubmit=${submit}>
        <input type="search" value=${q} maxlength="200" placeholder="Rechercher dans les données…"
               aria-label="Recherche" onInput=${(e) => setQ(e.target.value)} />
        <button class="primary" type="submit" disabled=${state.status === 'loading'}>Chercher</button>
        ${canClear({ q, facets, state, detail })
          ? html`<button class="clear" type="button" aria-label="Effacer la recherche" title="Effacer" onClick=${clear}>✕</button>`
          : null}
      </form>
      ${degraded !== null
        ? html`<div class="refusal">La recherche fonctionne en mode dégradé (${degraded}). Les résultats peuvent manquer.</div>`
        : null}
      ${state.status === 'loading' ? html`<div class="center">…</div>` : null}
      ${state.status === 'error' ? html`<${Refusal} kind=${state.kind} message=${state.message} />` : null}
      ${ready ? html`
        ${facetChips(ready.facets, facets).map((f) => ({ ...f, label: facetLabel(ready.labels, entities, f.field) })).map((f) => html`
          <div class="filterbar-chips">
            <span class="chip">${f.label}</span>
            ${f.values.map((v) => html`
              <span class="chip facet ${v.active ? 'on' : ''}" role="button" tabindex="0"
                    onClick=${() => toggleFacet(f.field, v.value)}
                    onKeyDown=${(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleFacet(f.field, v.value); } }}>
                ${v.value} <b>${v.count}</b>
              </span>`)}
          </div>`)}
        ${ready.hits.length > 0 && asOfLine(ready.asOf) ? html`<div class="data-asof">${asOfLine(ready.asOf)}</div>` : null}
        ${ready.hits.length === 0 ? Object.entries(facets).map(([field, value]) => html`
          <span class="chip facet on" role="button" tabindex="0" onClick=${() => toggleFacet(field, value)}
                onKeyDown=${(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleFacet(field, value); } }}>
            ${field} = ${value} ✕
          </span>`) : null}
        ${ready.hits.length === 0
          ? html`<div class="center">Aucun résultat.</div>`
          : ready.hits.map((hit) => {
              const v = hitView(hit, ready.labels);
              return html`
                <div class="card" role="button" tabindex="0" onClick=${() => open({ ...v, rawKey: hit.key })}
                     onKeyDown=${(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open({ ...v, rawKey: hit.key }); } }}>
                  <div class="top">
                    <div class="title"><${CellLink} links=${v.links} column=${v.titleField} row=${v.row}
                        text=${v.headingSegments.map((s) => s.hit ? html`<mark>${s.text}</mark>` : s.text)} /></div>
                  </div>
                  <div class="sub">${v.sub}</div>
                  <div class="meta">
                    ${v.fields.map((f) => html`
                      <span class="chip">${f.label + ' '}<b><${CellLink} links=${v.links} column=${f.name} row=${v.row}
                        text=${f.segments.map((s) => s.hit ? html`<mark>${s.text}</mark>` : s.text)} /></b></span>`)}
                  </div>
                </div>`;
            })}
        ${ready.moreError ? html`<${Refusal} kind=${ready.moreError.kind} message=${ready.moreError.message} />` : null}
        ${ready.nextCursor
          ? html`<button class="more" disabled=${ready.more === 'loading'}
                         onClick=${() => run(lastQ.current, facets, ready.nextCursor)}>Plus de résultats</button>`
          : null}
      ` : null}
      ${detail ? html`<${Detail} data=${detail} onClose=${closeDetail} wide=${wide} />` : null}
    <//>
  `;
}
