import { html, useState, useEffect, useRef } from '../html.mjs';
import { urlKey, kcBase, redirectUri } from '../config.mjs';
import {
  resolveCached, resolveFromRegistry, cacheRegistry, applyIdentity, applyTheme,
} from '../appRegistry.mjs';
import { createAuth } from '../auth.mjs';
import { createApi } from '../api.mjs';
import { hasChat } from '../capabilities.mjs';
import { createViews } from '../views.mjs';
import { Login } from './login.mjs';
import { TabBar } from './tabbar.mjs';
import { Fiches } from './fiches.mjs';
import { Inbox } from './inbox.mjs';
import { Chat } from './chat.mjs';

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
  const [tab, setTab] = useState('fiches'); // 'fiches' | 'inbox' | 'chat'
  const [count, setCount] = useState(''); // header count slot — set per-tab in Tasks 6–7
  const [badge, setBadge] = useState(0); // unacked-decisions badge — set in Task 7 (C3)
  const [chatLog, setChatLog] = useState([]); // D5: session-scoped, lifted so tab switches keep it
  // Whether THIS build carries the chat doors. Starts true so an unread answer never claims
  // absence (capabilities.mjs); only a readable /me that lacks the rag module turns it off.
  const [chatAvailable, setChatAvailable] = useState(true);
  // Moving to a tab is one gesture: clear the header count slot the previous tab filled, then
  // switch. Both callers — the tab bar, and the door an abstention offers (#515) — go through it.
  const goTab = t => { setCount(''); setTab(t); };

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
        try {
          const entries = await apiRef.current.json('/api/apps', { method: 'GET' });
          cacheRegistry(entries);
          const resolved = resolveFromRegistry(entries, location.search);
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
        } catch (e) {
          if (e && e.kind === 'auth') return; // onAuthLost already set screen to 'login'
          setScreen('offline');
          return;
        }
        setScreen('tabs');
        // C3: quiet badge refresh at boot — fire the inbox GET once on entering tabs, without
        // visiting the tab, so the ⚡ badge shows what waits from the first frame.
        apiRef.current.json('/api/inbox?limit=50&all=true', { method: 'GET' })
          .then(entries => setBadge(entries.filter(e => !e.acked).length))
          .catch(() => {});
        // Same shape, same reason: read once at boot what this build carries, so the Chat tab can
        // say so instead of letting the métier type into a door that is not there. /api/bff/v1/me
        // is the lm_user door and already returns the module list — no new endpoint.
        apiRef.current.json('/api/bff/v1/me', { method: 'GET' })
          .then(me => setChatAvailable(hasChat(me)))
          .catch(() => {});
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
      ${screen === 'tabs' && tab === 'fiches' ? html`<${Fiches} views=${viewsRef.current} onCount=${setCount} />` : null}
      ${screen === 'tabs' && tab === 'inbox' ? html`<${Inbox} api=${apiRef.current} onBadge=${setBadge} />` : null}
      ${screen === 'tabs' && tab === 'chat' ? html`<${Chat} api=${apiRef.current} scope=${metier.scope} log=${chatLog} setLog=${setChatLog} available=${chatAvailable} onOpenFiches=${() => goTab('fiches')} />` : null}
    </main>
    ${screen === 'tabs' ? html`<${TabBar} tab=${tab} onTab=${goTab} badge=${badge} />` : null}
  `;
}
