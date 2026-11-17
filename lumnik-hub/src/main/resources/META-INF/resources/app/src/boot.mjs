// The shell's boot (#1994): a file, not an inline <script>, so the PWA's CSP (script-src 'self') runs it and would
// refuse any script injected into the page.
import { resolveCached, applyIdentity, applyTheme } from './appRegistry.mjs';
import { html, render } from './html.mjs';
import { App } from './components/app.mjs';

// wear the cached (or built-in/neutral fallback) identity AND theme: tab title,
// manifest, icons (iOS reads apple-touch-icon), accent and mobile-chrome tint.
// app.mjs corrects this once phase 2 resolves the real registry entry, so an
// unrecognised tag or a first-ever visit to a runtime app never sees the WRONG
// identity for more than one frame.
const cached = resolveCached(location.search);
applyIdentity(cached);
applyTheme(cached);

// app-shell cache only (E2) — data is never cached; a failed registration is not fatal
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/app/sw.js').catch(() => {});
}

render(html`<${App} />`, document.getElementById('root'));
