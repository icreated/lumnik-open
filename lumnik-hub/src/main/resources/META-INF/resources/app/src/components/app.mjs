import { html, useState, useEffect, useRef } from '../html.mjs';
import { urlKey, kcBase, redirectUri } from '../config.mjs';
import {
  resolveCached, resolveFromRegistry, cacheRegistry, applyIdentity, applyTheme,
} from '../appRegistry.mjs';
import { createAuth } from '../auth.mjs';
import { createApi } from '../api.mjs';
import { createViews } from '../views.mjs';
import { planTabs, applyProbes, landingTab, runBoots, has } from '../tabs.mjs';
import { Login } from './login.mjs';
import { TabBar } from './tabbar.mjs';

// The raw ?m= tag (or 'clients' when absent) — ALL the auth redirect needs, and stable for
// the whole session regardless of what appRegistry later resolves it to (see config.mjs's
// header comment: it must round-trip a tag the registry does not recognise, not rewrite it).
const REDIRECT_KEY = urlKey(location.search);
const EXPIRED_NOTE = 'Session expirée — reconnecte-toi.';

// The conductor (A-series wiring): boots the session, drives the login/tabs screen switch,
// schedules silent renewal, and hosts the state Tasks 6–7 fill in (tab content, badge, chat
// log) — navigation (history.replaceState, location.href) lives HERE, deliberately never
// inside auth.mjs, which stays pure injected-effect logic (see its header comment).
export function App() {
  // Phase 1 (appRegistry.mjs): the cached guess, worn until the boot effect's phase-2 fetch
  // (or refusal) corrects it. index.html already painted the SAME guess into the document
  // head before this component ever mounted — this is the in-app half of that identity.
  const [metier, setMetier] = useState(() => resolveCached(location.search));
  const [screen, setScreen] = useState('boot'); // 'boot' | 'login' | 'offline' | 'refused' | 'tabs'
  const [note, setNote] = useState('');
  // The app's effective tabs (tabs.mjs: what the hub offers ∩ what the app declares), known once
  // phase 2 has answered; `tab` is the id on screen, the first of them to begin with.
  const [tabs, setTabs] = useState([]);
  const [tab, setTab] = useState(null);
  const [count, setCount] = useState(''); // header count slot — set by the tab on screen
  const [badges, setBadges] = useState({}); // tab id -> the count it wears (the inbox's unacked)
  const [chatLog, setChatLog] = useState([]); // D5: session-scoped, lifted so tab switches keep it
  // Moving to a tab is one gesture: clear the header count slot the previous tab filled, then
  // switch. Both callers — the tab bar, and the door an abstention offers (#515) — go through it,
  // and a tab that is not effective for this app is not a place to go.
  const goTab = t => { if (!has(tabs, t)) return; setCount(''); setTab(t); };
  const setBadge = (id, n) => setBadges(b => ({ ...b, [id]: n }));

  const authRef = useRef(null);
  const renewTimerRef = useRef(null);

  if (!authRef.current) {
    authRef.current = createAuth({
      kc: kcBase(location),
      clientId: 'lumnik-spa',
      redirect: redirectUri(location, REDIRECT_KEY),
      storage: sessionStorage,
      fetchFn: fetch,
      now: Date.now,
      onExpired: () => { clearTimeout(renewTimerRef.current); setScreen('login'); setNote(EXPIRED_NOTE); },
    });
  }
  const auth = authRef.current;

  const apiRef = useRef(null);
  if (!apiRef.current) {
    apiRef.current = createApi({
      token: () => auth.token(),
      // a 401 mid-session leaves the exp-60s renewal timer pending — clear it, or it
      // fires later against an empty refresh_token (round-2 review catch)
      onAuthLost: () => { clearTimeout(renewTimerRef.current); auth.clear(); setScreen('login'); setNote(EXPIRED_NOTE); },
      fetchFn: fetch,
    });
  }
  // Deferred behind phase 2 (below): it needs the AUTHORITATIVE scope, not the phase-1 guess,
  // so it is built once inside the boot effect rather than eagerly here.
  const viewsRef = useRef(null);

  function scheduleRenewal() {
    clearTimeout(renewTimerRef.current);
    const delay = auth.renewalDelay();
    if (delay == null) return; // no live session to renew — the chain simply stops
    renewTimerRef.current = setTimeout(async () => {
      await auth.renew(); // failure clears the session and fires onExpired() itself
      scheduleRenewal(); // success reschedules; a post-failure call is a safe no-op (delay == null)
    }, delay);
  }

  useEffect(() => {
    (async () => {
      // E2: the SW serves the cached shell offline — the app must say so honestly
      // instead of letting the first API call fail cryptically (navigator.onLine is
      // the readable signal; a navigation response header never reaches the document).
      if (!navigator.onLine) { setScreen('offline'); return; }
      const params = new URLSearchParams(location.search);
      if (params.has('code')) {
        try {
          await auth.exchange(params.get('code'));
          history.replaceState(null, '', redirectUri(location, REDIRECT_KEY)); // strip ?code
        } catch (e) {
          setNote(e.message);
        }
      }
      const state = auth.bootState();
      if (state === 'live') {
        scheduleRenewal();
        // Phase 2: the authoritative registry. A failure here (network blip, CORS, a 5xx)
        // is 'offline' — the app cannot know its own scope, so it must not guess its way
        // into tabs. A refusal (unknown tag, or out-of-scope — the server does not
        // distinguish the two) is 'refused', never a fallthrough to another métier.
        let resolved, plan;
        try {
          // Both doors in phase 2: the landing tab needs to know what the hub offers BEFORE the first
          // tabs paint, and a failure of either is 'offline' — the app cannot know its own shape.
          const [entries, me] = await Promise.all([
            apiRef.current.json('/api/apps', { method: 'GET' }),
            apiRef.current.json('/api/bff/v1/me', { method: 'GET' }),
          ]);
          cacheRegistry(entries);
          resolved = resolveFromRegistry(entries, location.search);
          if (resolved.screen === 'refused') {
            // The phase-1 guess must not linger in the head under a body that says
            // "Application inconnue" — resolveFromRegistry hands back the same neutral
            // identity phase 1 falls back to, so this wears it without a second copy.
            applyIdentity(resolved.entry);
            applyTheme(resolved.entry);
            setScreen('refused');
            return;
          }
          setMetier(resolved.entry);
          applyIdentity(resolved.entry);
          applyTheme(resolved.entry);
          viewsRef.current = createViews(apiRef.current, resolved.entry.scope);
          plan = planTabs(resolved.entry, me);
          // A tab the hub offers may still be worthless for this caller (Search with no index to read):
          // its probe asks before the first paint, so the landing tab is right and nothing flickers.
          const tabs = await applyProbes(plan.tabs, { api: apiRef.current, scope: resolved.entry.scope });
          plan = { tabs, landing: landingTab(tabs) };
        } catch (e) {
          if (e && e.kind === 'auth') return; // onAuthLost already set screen to 'login'
          setScreen('offline');
          return;
        }
        setTabs(plan.tabs);
        setTab(plan.landing);
        setScreen('tabs');
        // Quiet start-up work of the tabs this app shows (the inbox's badge from the first frame);
        // a tab the app does not show never runs its own.
        runBoots(plan.tabs, {
          api: apiRef.current, views: viewsRef.current, scope: resolved.entry.scope,
          can: id => has(plan.tabs, id), setBadge,
        });
      } else {
        if (state === 'expired') setNote(EXPIRED_NOTE);
        setScreen('login');
      }
    })();
    return () => clearTimeout(renewTimerRef.current);
  }, []);

  async function onLogin() {
    location.href = await auth.loginUrl();
  }

  function onLogout() {
    if (!confirm('Se déconnecter ?')) return;
    location.href = auth.logoutUrl();
  }

  const claims = screen === 'tabs' ? auth.claims() : null;
  // What every tab's screen receives (tabs.mjs). The shell names no tab; a screen that needs shell
  // state the shell does not hold yet adds a field here — the chat log is today's only case.
  // Never a tab that is not effective: if `tab` somehow is not, the app is on its landing tab.
  const shownTab = has(tabs, tab) ? tab : landingTab(tabs);
  const current = tabs.find(t => t.id === shownTab);
  const ctx = {
    api: apiRef.current, views: viewsRef.current, scope: metier.scope,
    can: id => has(tabs, id), openTab: goTab, setCount, setBadge, chatLog, setChatLog,
  };

  return html`
    <header>
      <h1>${metier.title}</h1>
      <span class="count">${count}</span>
      ${claims
        ? html`<span class="who" title="Se déconnecter" onClick=${onLogout}>${(claims.preferred_username || '') + ' ⏻'}</span>`
        : null}
    </header>
    <main>
      ${screen === 'boot' ? html`<div class="center">…</div>` : null}
      ${screen === 'offline' ? html`<div class="center">
        <span class="glyph">⚡</span>
        <p>Hors ligne — tes données reviendront avec le réseau.</p>
        <button class="primary" onClick=${() => location.reload()}>Réessayer</button>
      </div>` : null}
      ${screen === 'refused' ? html`<div class="center">
        <span class="glyph">🚫</span>
        <p>Application inconnue — vérifie le lien ou contacte ton intégrateur.</p>
      </div>` : null}
      ${screen === 'login' ? html`<${Login} note=${note} onLogin=${onLogin} />` : null}
      ${screen === 'tabs' && tabs.length === 0 ? html`<div class="center">
        <span class="glyph">▫</span>
        <p>Aucune fonctionnalité n'est disponible pour cette application.</p>
      </div>` : null}
      ${screen === 'tabs' && current ? current.screen(ctx) : null}
    </main>
    ${screen === 'tabs' && tabs.length > 0 ? html`<${TabBar} items=${tabs} tab=${shownTab} onTab=${goTab} badges=${badges} />` : null}
  `;
}
