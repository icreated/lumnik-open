import { html, useEffect, useRef } from '../html.mjs';
import { Refusal } from './refusal.mjs';
import { dataAsOfLabel, doorGesture } from '../format.mjs';

// D1-D6: the Chat tab — ported from the spike's renderChat/sendQuestion/bubble (m/index.html
// ~L518-565). Thread state (`log`/`setLog`) is LIFTED to app.mjs so it survives tab switches
// (D5) — this component is pure rendering + the send flow over that state.
const HINT = 'Pose une question sur tes données — je réponds avec le SQL montré, ou je refuse honnêtement.';
const WAITING = '… je regarde tes données';
// Shown INSTEAD of the input when this build carries no chat module: the métier never types a
// question into a door that is not there. See capabilities.mjs — absence is proven, not assumed.
const ABSENT = "Le chat n'est pas installé sur cette version de lumnik. Les fiches et les décisions fonctionnent normalement.";

export function Chat({ api, scope, log, setLog, available = true, onOpenFiches }) {
  const inputRef = useRef(null);

  useEffect(() => {
    requestAnimationFrame(() => window.scrollTo(0, document.body.scrollHeight));
  }, [log]);

  async function send() {
    const el = inputRef.current;
    const question = (el.value || '').trim();
    if (!question) return;
    el.value = '';
    const withMe = [...log, { role: 'me', text: question }];
    setLog([...withMe, { role: 'wait', text: WAITING }]);

    let reply;
    try {
      const a = await api.json('/api/rag/chat', { json: { question, mode: 'analytic', scope } }); // D1 — the lm_user door (#139)
      // D3: an answer that ran NO sql is an abstention — the honest refusal wears amber, not a SQL bubble
      reply = a.sql
        ? { role: 'bot', text: a.answer || '(réponse vide)', sql: a.sql, rowCount: a.rowCount, dataAsOf: a.dataAsOf }
        // #515: `otherDoors` is the abstention's own sentence as tokens, so this surface can
        // offer the door it has (format.mjs doorGesture) instead of parsing prose for it.
        : { role: 'refusal', kind: 'abstention', text: a.answer || '(réponse vide)',
            doors: a.otherDoors || [] };
    } catch (e) {
      if (e.kind === 'auth') { setLog(withMe); return; } // 401 already flips the screen — no spurious bubble
      // D4 said one tone for every failure — right while every failure WAS an answer-level
      // refusal. It is not: api.mjs already separates 'scope' from 'error', and refusal.mjs
      // already renders both. Amber means lumnik honestly declined about your data; a surface
      // that broke wears the error tone. Collapsing them made a missing module look honest.
      reply = { role: 'refusal', kind: e.kind === 'scope' ? 'scope' : 'error',
                text: e.message || String(e) };
    }
    setLog([...withMe, reply]);
  }

  function onKeyDown(e) { if (e.key === 'Enter') send(); }

  return html`
    <div class="chat-col">
      <div class="msgs">
        ${log.length === 0 ? html`<div class="bubble bot">${available ? HINT : ABSENT}</div>` : null}
        ${log.map(m => html`<${Bubble} m=${m} onOpenFiches=${onOpenFiches} />`)}
      </div>
      ${available
        ? html`
          <div id="ask">
            <input ref=${inputRef} type="text" placeholder="Demande à tes données…" autocomplete="off" onKeyDown=${onKeyDown} />
            <button onClick=${send} aria-label="Envoyer la question">→</button>
          </div>
        `
        : html`<div id="ask" class="unavailable">${ABSENT}</div>`}
    </div>
  `;
}

function Bubble({ m, onOpenFiches }) {
  if (m.role === 'me') return html`<div class="bubble me">${m.text}</div>`;
  if (m.role === 'wait') return html`<div class="bubble wait">${m.text}</div>`;
  if (m.role === 'refusal') return html`
    <div>
      <${Refusal} kind=${m.kind} message=${m.text} />
      ${(m.doors || []).map(doorGesture).filter(Boolean).map(label => html`
        <button class="door" onClick=${() => onOpenFiches && onOpenFiches()}>${label}</button>
      `)}
    </div>
  `;
  return html`
    <div class="bubble bot">
      ${m.text}
      ${dataAsOfLabel(m.dataAsOf)
        ? html`<div class="data-asof">${dataAsOfLabel(m.dataAsOf)}</div>`
        : null}
      ${m.sql
        ? html`
          <details>
            <summary>${`le SQL exécuté · ${m.rowCount} ligne${m.rowCount > 1 ? 's' : ''}`}</summary>
            <pre>${m.sql}</pre>
          </details>
        `
        : null}
    </div>
  `;
}
