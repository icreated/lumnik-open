import { html } from '../html.mjs';

// ONE component for every refusal surface (house rule #135 — no bespoke error markup per tab):
// scope/abstention wear the amber "honest refusal" tone (spike .conflict/.bubble.refusal);
// anything else (network, unexpected) wears the red .error tone. `message` is always a text
// child — never innerHTML. CSS (not this component) narrows the look inside a chat thread
// via the `.msgs .refusal`/`.msgs .error` descendant rules in styles.css.
export function Refusal({ kind, message }) {
  const cls = kind === 'scope' || kind === 'abstention' ? 'refusal' : 'error';
  return html`<div class=${cls}>${message}</div>`;
}
