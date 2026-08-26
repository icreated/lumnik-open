import { html } from '../html.mjs';

// The spike login screen, ported verbatim (glyph, French copy, primary CTA).
// `note` carries an honest reason when we land here after an expired session or a failed
// code exchange — absent on a plain first visit.
export function Login({ note, onLogin }) {
  return html`
    <div class="center">
      <span class="glyph">☀️</span>
      ${note ? html`<p>${note}</p>` : null}
      <p>Tes données métier, dans ta poche.</p>
      <button class="primary" onClick=${onLogin}>Se connecter</button>
    </div>
  `;
}
