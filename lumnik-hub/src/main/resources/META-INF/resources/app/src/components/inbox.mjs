import { html, Fragment, useState, useEffect } from '../html.mjs';
import { since } from '../format.mjs';
import { Refusal } from './refusal.mjs';

// C1-C4: the Décisions tab — ported from the spike's renderInbox (m/index.html ~L480-515).
// Open decisions are shown prominent with an ack button; acked ones dim with a "traité" label.
// `onBadge` feeds the unacked count up to app.mjs after every successful load (C3) so the tab
// badge (and the boot-time quiet refresh) both stay in sync with one source of truth: this list.
export function Inbox({ api, onBadge }) {
  const [entries, setEntries] = useState(null); // null = loading
  const [error, setError] = useState(null); // {kind, message} — blocks the tab
  const [acking, setAcking] = useState(null); // id currently in flight — guards double-submit

  async function load() {
    try {
      const list = await api.json('/api/inbox?limit=50&all=true', { method: 'GET' });
      setEntries(list);
      onBadge(list.filter(e => !e.acked).length);
    } catch (e) {
      setError({ kind: e.kind || 'error', message: e.message || String(e) });
    }
  }

  useEffect(() => { load(); }, []);

  async function ack(id) {
    setAcking(id);
    try {
      await api.json(`/api/inbox/${id}/ack`, { json: {} }); // the ack IS an event (C2)
      await load();
    } catch (e) {
      setError({ kind: e.kind || 'error', message: e.message || String(e) });
    } finally {
      setAcking(null);
    }
  }

  if (error) return html`<${Refusal} kind=${error.kind} message=${error.message} />`;
  if (entries === null) return html`<div class="center">…</div>`;

  return html`
    <${Fragment}>
      ${entries.length === 0
        ? html`<div class="center">Aucune décision — rien ne t’attend.</div>`
        : entries.map(d => html`
          <div class=${'decision' + (d.acked ? ' done' : '')}>
            <div class="msg">${d.message}</div>
            <div class="sub">${`${d.process} · ${since(d.occurredAt)}`}</div>
            <div class="row">
              ${d.acked
                ? html`<span class="treated">✓ traité</span>`
                : html`<button class="ack-btn" disabled=${acking === d.id} onClick=${() => ack(d.id)}>✓ Traiter</button>`}
            </div>
          </div>
        `)}
    <//>
  `;
}
