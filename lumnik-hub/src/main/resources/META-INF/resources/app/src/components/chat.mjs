import { html, useEffect, useRef } from '../html.mjs';
import { Refusal } from './refusal.mjs';
import { dataAsOfLabel, doorGesture, disputedBlock, analyticAsk } from '../format.mjs';
import { previousOf, detach, understoodLine, continuesLabel } from '../thread.mjs';

// D1-D6: the Chat tab — ported from the spike's renderChat/sendQuestion/bubble (m/index.html
// ~L518-565). Thread state (`log`/`setLog`) is LIFTED to app.mjs so it survives tab switches
// (D5) — this component is pure rendering + the send flow over that state. #1097: the sentence a
// follow-up continues is read from that same log (thread.mjs), and the chat speaks as lumnik.
const HINT = 'Je suis lumnik. Pose une question sur tes données — je réponds avec le SQL montré, ou je refuse honnêtement. '
  + 'Tu peux enchaîner : « et seulement… ». Je ne retiens que la dernière question à laquelle j\'ai répondu, '
  + 'et je l\'oublie si la page se recharge.';
const WAITING = '… je regarde tes données';

export function Chat({ api, scope, log, setLog, onOpenFiches }) {
  const inputRef = useRef(null);
  const previous = previousOf(log);
  const waiting = log.length > 0 && log[log.length - 1].role === 'wait';

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
      const a = await api.json('/api/rag/chat', { json: analyticAsk(question, scope, previous) }); // D1 — the lm_user door (#139)
      const understood = { asked: question, understoodAs: a.understoodAs || null };
      // D3: an answer that ran NO sql is an abstention — the honest refusal wears amber, not a SQL bubble
      reply = a.sql
        ? { role: 'bot', text: (a.answer || '').trim() || '(réponse vide)', sql: a.sql, rowCount: a.rowCount, dataAsOf: a.dataAsOf,
            disputed: a.disputed || [], disputedTruncated: !!a.disputedTruncated, doors: a.otherDoors || [], ...understood }
        // #515: `otherDoors` is the abstention's own sentence as tokens, so this surface can
        // offer the door it has (format.mjs doorGesture) instead of parsing prose for it.
        : { role: 'refusal', kind: 'abstention', text: (a.answer || '').trim() || '(réponse vide)',
            doors: a.otherDoors || [], ...understood };
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
        ${log.length === 0 ? html`<div class="bubble bot">${HINT}</div>` : null}
        ${log.map(m => html`<${Bubble} m=${m} onOpenFiches=${onOpenFiches} />`)}
        ${log.length > 0 && !waiting ? html`
          <div class="thread">
            ${previous ? html`
              <button class="chip" title=${previous} onClick=${() => setLog(detach(log))}
                      aria-label=${continuesLabel(previous) + ' — poser la prochaine question sans elle'}>
                ${continuesLabel(previous)} ✕
              </button>` : null}
            <button class="thread-new" onClick=${() => setLog([])}>Nouvelle conversation</button>
          </div>` : null}
      </div>
      <div id="ask">
        <input ref=${inputRef} type="text" placeholder="Demande à lumnik…" autocomplete="off" onKeyDown=${onKeyDown} />
        <button onClick=${send} aria-label="Envoyer la question">→</button>
      </div>
    </div>
  `;
}

function Bubble({ m, onOpenFiches }) {
  if (m.role === 'cut') return null;
  if (m.role === 'me') return html`<div class="bubble me">${m.text}</div>`;
  if (m.role === 'wait') return html`<div class="bubble wait">${m.text}</div>`;
  const understood = understoodLine(m);
  if (m.role === 'refusal') return html`
    <div>
      <${Refusal} kind=${m.kind} message=${m.text} />
      ${understood ? html`<div class="understood">${understood}</div>` : null}
      ${(m.doors || []).map(doorGesture).filter(Boolean).map(label => html`
        ${onOpenFiches ? html`<button class="door" onClick=${onOpenFiches}>${label}</button>` : null}
      `)}
    </div>
  `;
  const block = disputedBlock(m.disputed, m.disputedTruncated);
  return html`
    <div>
      <div class="bubble bot">
        <div class="answer">${m.text}</div>
        ${understood ? html`<div class="understood">${understood}</div>` : null}
        ${dataAsOfLabel(m.dataAsOf)
          ? html`<div class="data-asof">${dataAsOfLabel(m.dataAsOf)}</div>`
          : null}
        ${block ? html`
          <div class="disputed">
            <div class="disputed-title">${block.title}</div>
            ${block.items.map(i => html`<div class="disputed-item"><div>${i.head}</div><div class="src">${i.sources}</div></div>`)}
            ${block.more ? html`<div class="disputed-more">${block.more}</div>` : null}
          </div>` : null}
        ${m.sql
          ? html`
            <details>
              <summary>${`le SQL exécuté · ${m.rowCount} ligne${m.rowCount > 1 ? 's' : ''}`}</summary>
              <pre>${m.sql}</pre>
            </details>
          `
          : null}
      </div>
      ${(m.doors || []).map(doorGesture).filter(Boolean).map(label => html`
        ${onOpenFiches ? html`<button class="door" onClick=${onOpenFiches}>${label}</button>` : null}
      `)}
    </div>
  `;
}
