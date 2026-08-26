import { html, useState, useEffect, useRef } from '../html.mjs';
import { resolveMetier, kcBase, redirectUri } from '../config.mjs';
import { createAuth } from '../auth.mjs';
import { createApi } from '../api.mjs';
import { hasChat } from '../capabilities.mjs';
import { createViews } from '../views.mjs';
import { Login } from './login.mjs';
import { TabBar } from './tabbar.mjs';
import { Fiches } from './fiches.mjs';
import { Inbox } from './inbox.mjs';
import { Chat } from './chat.mjs';

// Resolved once at module load — the métier never changes without a page reload, and both
// the auth redirect_uri and the header title need it before the first render.
const METIER = resolveMetier(location.search);
const EXPIRED_NOTE = 'Session expirée — reconnecte-toi.';

// The conductor (A-series wiring): boots the session, drives the login/tabs screen switch,
// schedules silent renewal, and hosts the state Tasks 6–7 fill in (tab content, badge, chat
// log) — navigation (history.replaceState, location.href) lives HERE, deliberately never
// inside auth.mjs, which stays pure injected-effect logic (see its header comment).
export function App() {
  const [screen, setScreen] = useState('boot'); // 'boot' | 'login' | 'offline' | 'tabs'
  const [note, setNote] = useState('');
  const [tab, setTab] = useState('fiches'); // 'fiches' | 'inbox' | 'chat'
  const [count, setCount] = useState(''); // header count slot — set per-tab in Tasks 6–7
  const [badge, setBadge] = useState(0); // unacked-decisions badge — set in Task 7 (C3)
  const [chatLog, setChatLog] = useState([]); // D5: session-scoped, lifted so tab switches keep it
  // Whether THIS build carries the chat doors. Starts true so an unread answer never claims
  // absence (capabilities.mjs); only a readable /me that lacks the rag module turns it off.
  const [chatAvailable, setChatAvailable] = useState(true);
  const authRef = useRef(null);
  const renewTimerRef = useRef(null);

  if (!authRef.current) {
    authRef.current = createAuth({
      kc: kcBase(location),
      clientId: 'lumnik-spa',
      redirect: redirectUri(location, METIER.key),
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
  const viewsRef = useRef(null);
  if (!viewsRef.current) {
    viewsRef.current = createViews(apiRef.current, METIER.scope);
  }

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
          history.replaceState(null, '', redirectUri(location, METIER.key)); // strip ?code
        } catch (e) {
          setNote(e.message);
        }
      }
      const state = auth.bootState();
      if (state === 'live') {
        scheduleRenewal();
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
      <h1>${METIER.title}</h1>
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
      ${screen === 'login' ? html`<${Login} note=${note} onLogin=${onLogin} />` : null}
      ${screen === 'tabs' && tab === 'fiches' ? html`<${Fiches} views=${viewsRef.current} onCount=${setCount} />` : null}
      ${screen === 'tabs' && tab === 'inbox' ? html`<${Inbox} api=${apiRef.current} onBadge=${setBadge} />` : null}
      ${screen === 'tabs' && tab === 'chat' ? html`<${Chat} api=${apiRef.current} scope=${METIER.scope} log=${chatLog} setLog=${setChatLog} available=${chatAvailable} />` : null}
    </main>
    ${screen === 'tabs' ? html`<${TabBar} tab=${tab} onTab=${t => { setCount(''); setTab(t); }} badge=${badge} />` : null}
  `;
}
