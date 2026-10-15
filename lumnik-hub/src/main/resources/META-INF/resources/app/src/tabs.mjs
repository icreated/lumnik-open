// The PWA's navigation: ONE registry, one entry per tab (descriptor and screen together), and the
// pure functions that decide which entries a given app shows.
//
//   offered   = what the hub can draw         (GET /api/bff/v1/me → tabs)
//   declared  = what the app asks for         (kind:App spec.tabs; null/absent = inherit)
//   effective = declared == null ? registry ∩ offered (registry order)
//                                : declared ∩ offered ∩ registry (declared order, first = landing)
//
// A tab is added HERE and nowhere else in the shell: one entry, and the screen component it points
// to. The shell (app.mjs) hands every screen the same `ctx` and names no tab; a screen that needs
// shell-level state the shell does not hold yet adds a field to ctx (the chat log is today's only
// case). An id this registry does not know is ignored — an older shell served by a newer hub — and
// a declared id the hub does not offer is dropped here but kept in the manifest, so the same
// manifest serves an Open hub and a Pro hub.
import { html } from './html.mjs';
import { Fiches } from './components/fiches.mjs';
import { Inbox } from './components/inbox.mjs';
import { Chat } from './components/chat.mjs';
import { Search } from './components/search.mjs';
import { probeSearch } from './search.mjs';

// An entry may carry `probe(ctx) → Promise<boolean>`: one more question, asked once before the tabs are
// drawn, for a tab that is offered but may be worthless for this caller. false drops the tab; a probe
// that throws keeps it (a failed read proves nothing about absence).
//
// ctx, built by the shell on every render: api, views, scope, can(id), openTab(id), setCount(text),
// setBadge(id, n), chatLog, setChatLog. A boot(ctx) gets the quiet subset the shell has at start-up:
// api, views, scope, can(id) and setBadge.
export const TABS = [
  {
    id: 'fiches', label: 'Fiches', icon: '▦',
    screen: (ctx) => html`<${Fiches} views=${ctx.views} onCount=${ctx.setCount} />`,
  },
  {
    id: 'inbox', label: 'Décisions', icon: '⚡',
    screen: (ctx) => html`<${Inbox} api=${ctx.api} onBadge=${(n) => ctx.setBadge('inbox', n)} />`,
    // C3: the badge shows what waits from the first frame, without visiting the tab. Quiet: a
    // failure leaves the badge as it was.
    boot: (ctx) => ctx.api.json('/api/inbox?limit=50&all=true', { method: 'GET' })
      .then((entries) => ctx.setBadge('inbox', entries.filter((e) => !e.acked).length))
      .catch(() => {}),
  },
  {
    id: 'chat', label: 'Chat', icon: '✶',
    // The conflicts-door button sends the métier to the Fiches: it exists only when that tab does.
    screen: (ctx) => html`<${Chat} api=${ctx.api} scope=${ctx.scope}
      log=${ctx.chatLog} setLog=${ctx.setChatLog}
      onOpenFiches=${ctx.can('fiches') ? () => ctx.openTab('fiches') : undefined} />`,
  },
  {
    id: 'search', label: 'Recherche', icon: '⌕',
    // Always sends the app's scope: the hub narrows to this métier and the screen adds nothing of its own.
    screen: (ctx) => html`<${Search} api=${ctx.api} scope=${ctx.scope} />`,
    // The hub OFFERS this tab when a provider module is active; the probe asks the two doors that
    // say whether it is worth showing for THIS caller (a provider that answers, an index they may read).
    probe: (ctx) => probeSearch(ctx.api),
  },
];

export function effectiveTabs(offered, declared, registry = TABS) {
  const offeredIds = new Set(offered || []);
  if (declared == null) return registry.filter((t) => offeredIds.has(t.id));
  const byId = new Map(registry.map((t) => [t.id, t]));
  const out = [];
  for (const id of declared) {
    const t = byId.get(id);
    if (t && offeredIds.has(id) && !out.includes(t)) out.push(t);
  }
  return out;
}

/**
 * What the shell does once phase 2 has answered, in one call: the app's registry entry and the hub's
 * /me give the effective tabs and the one to land on. app.mjs cannot be imported under node, so
 * this is where the entry's `tabs` and the hub's offer are tied together AND tested.
 */
export function planTabs(appEntry, me) {
  const tabs = effectiveTabs(offerFrom(me), appEntry && appEntry.tabs);
  return { tabs, landing: landingTab(tabs) };
}

/**
 * Asks each tab's probe (in parallel) and returns the tabs without the ones that said no. A probe
 * that fails keeps its tab. The one exception is a lost session: that is not "absence is unproven",
 * it is the app ending, so it propagates and the shell lets onAuthLost's login screen stand.
 */
export async function applyProbes(tabs, ctx) {
  const kept = await Promise.all(tabs.map(async (t) => {
    if (!t.probe) return true;
    try { return (await t.probe(ctx)) !== false; } catch (e) {
      if (e && e.kind === 'auth') throw e;
      return true;
    }
  }));
  return tabs.filter((_, i) => kept[i]);
}

/** The tab the app opens on; null when nothing is effective (the shell then shows its empty page). */
export function landingTab(effective) {
  return effective.length ? effective[0].id : null;
}

export function has(effective, id) {
  return effective.some((t) => t.id === id);
}

/**
 * Runs each effective entry's quiet start-up work, once. One failing boot never stops the others
 * or the app: this is a convenience (a badge), not a precondition.
 */
export async function runBoots(effective, ctx) {
  await Promise.all(effective.filter((t) => t.boot).map(async (t) => {
    try { await t.boot(ctx); } catch { /* quiet by design */ }
  }));
}

/**
 * The hub's offer from its /me. A hub that sends `tabs` is believed. A hub older than this change
 * sends none, and the answer is then what the old shell concluded from /me.modules: chat only when
 * both halves (`rag` doors, `llm` provider) are listed, or when /me gave nothing to read — absence
 * is proven, never assumed. This is the one place the PWA still names module ids; delete it once no
 * hub older than this change can be met.
 */
export function offerFrom(me) {
  if (me && Array.isArray(me.tabs)) return me.tabs;
  const modules = me && me.modules;
  const chat = !Array.isArray(modules) || (
    modules.some((m) => m && m.id === 'rag') && modules.some((m) => m && m.id === 'llm'));
  return chat ? ['fiches', 'inbox', 'chat'] : ['fiches', 'inbox'];
}
