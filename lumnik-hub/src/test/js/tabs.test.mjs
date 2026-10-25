import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  TABS, effectiveTabs, landingTab, has, offerFrom, runBoots, planTabs, applyProbes,
} from '../../main/resources/META-INF/resources/app/src/tabs.mjs';

// The contract table of docs/superpowers/specs/2026-10-13-hub-facets-design.md, row by row.
// effective = declared == null ? registry ∩ offered (registry order)
//                              : declared ∩ offered ∩ registry (declared order, first = landing).
const ids = (list) => list.map((t) => t.id);

test('the registry draws fiches, inbox, chat and search, in that order', () => {
  assert.deepEqual(ids(TABS), ['fiches', 'inbox', 'chat', 'search']);
  for (const t of TABS) {
    assert.equal(typeof t.label, 'string');
    assert.equal(typeof t.icon, 'string');
    assert.equal(typeof t.screen, 'function', `${t.id} carries its own screen`);
  }
});

test('Open: nothing declared, no Chat tab at all', () => {
  assert.deepEqual(ids(effectiveTabs(['fiches', 'inbox'], null)), ['fiches', 'inbox']);
});

test('Pro today: nothing declared, every offered tab in registry order', () => {
  assert.deepEqual(ids(effectiveTabs(['chat', 'fiches', 'inbox'], null)), ['fiches', 'inbox', 'chat']);
  assert.deepEqual(ids(effectiveTabs(['chat', 'fiches', 'inbox'], undefined)), ['fiches', 'inbox', 'chat'],
    'an old cached registry entry has no tabs key at all: that is inherit too');
});

test('the warehouse app declares two tabs and has no chat', () => {
  assert.deepEqual(ids(effectiveTabs(['chat', 'fiches', 'inbox'], ['fiches', 'inbox'])), ['fiches', 'inbox']);
});

test('the declared order wins and the first is the landing tab', () => {
  const eff = effectiveTabs(['chat', 'fiches', 'inbox'], ['chat', 'fiches']);
  assert.deepEqual(ids(eff), ['chat', 'fiches']);
  assert.equal(landingTab(eff), 'chat');
});

test('portable: a tab this hub does not offer is dropped here, kept in the manifest', () => {
  assert.deepEqual(ids(effectiveTabs(['fiches', 'inbox'], ['fiches', 'chat'])), ['fiches']);
});

test('a declaration that matches nothing offered leaves no tab: the empty page, no tab bar', () => {
  const eff = effectiveTabs(['fiches', 'inbox'], ['chat']);
  assert.deepEqual(eff, []);
  assert.equal(landingTab(eff), null);
});

test('a tab the shell cannot draw is ignored, never an error', () => {
  assert.deepEqual(ids(effectiveTabs(['fiches', 'holodeck'], null)), ['fiches']);
  assert.deepEqual(ids(effectiveTabs(['fiches', 'holodeck'], ['holodeck', 'fiches'])), ['fiches']);
});

test('a duplicate in the declaration appears once', () => {
  assert.deepEqual(ids(effectiveTabs(['fiches', 'inbox'], ['fiches', 'fiches', 'inbox'])), ['fiches', 'inbox']);
});

test('has() answers by id', () => {
  const eff = effectiveTabs(['fiches', 'inbox'], ['inbox']);
  assert.equal(has(eff, 'inbox'), true);
  assert.equal(has(eff, 'fiches'), false);
});

// Open/closed witness: a new tab is ONE registry entry. effectiveTabs, landingTab and has() take
// a registry argument, so an extended registry offers the new tab with no other code change.
test('a registry extended with a fake `map` entry offers it with no other change', () => {
  const map = { id: 'map', label: 'Carte', icon: '◍', screen: () => null };
  const extended = [...TABS, map];
  assert.deepEqual(ids(effectiveTabs(['fiches', 'inbox', 'map'], null, extended)), ['fiches', 'inbox', 'map']);
  assert.deepEqual(ids(effectiveTabs(['fiches', 'inbox', 'map'], ['map'], extended)), ['map']);
  assert.deepEqual(ids(effectiveTabs(['fiches', 'inbox'], null, extended)), ['fiches', 'inbox'],
    'offered by nobody, so not shown');
});

test('boot runs for the effective entries only', async () => {
  const calls = [];
  const registry = [
    { id: 'a', label: 'A', icon: '1', screen: () => null, boot: (ctx) => calls.push(['a', ctx.tag]) },
    { id: 'b', label: 'B', icon: '2', screen: () => null, boot: (ctx) => calls.push(['b', ctx.tag]) },
    { id: 'c', label: 'C', icon: '3', screen: () => null },
  ];
  await runBoots(effectiveTabs(['a', 'c'], null, registry), { tag: 'ctx' });
  assert.deepEqual(calls, [['a', 'ctx']], 'b is not effective; c has no boot');
});

test('a failing boot never stops the others or the app', async () => {
  const calls = [];
  const registry = [
    { id: 'a', label: 'A', icon: '1', screen: () => null, boot: () => { throw new Error('boom'); } },
    { id: 'b', label: 'B', icon: '2', screen: () => null, boot: () => calls.push('b') },
  ];
  await runBoots(effectiveTabs(['a', 'b'], null, registry), {});
  assert.deepEqual(calls, ['b']);
});

test('the inbox boot counts what has not been acked, and only that', async () => {
  const inbox = TABS.find((t) => t.id === 'inbox');
  const badges = [];
  const api = { json: async (url) => {
    assert.equal(url, '/api/inbox?limit=50&all=true');
    return [{ acked: false }, { acked: true }, { acked: false }];
  } };
  await inbox.boot({ api, setBadge: (id, n) => badges.push([id, n]) });
  assert.deepEqual(badges, [['inbox', 2]]);
});

// offerFrom(me): the hub's `tabs` when it sends them; otherwise a hub older than this change,
// for which the answer is what the old shell concluded from /me.modules (the old capabilities.mjs, deleted with this change).
// This is the one place the PWA still names module ids; delete it when no such hub can be met.
test('a /me with tabs is taken as it is', () => {
  assert.deepEqual(offerFrom({ tabs: ['fiches', 'inbox'], modules: [{ id: 'rag' }, { id: 'llm' }] }), ['fiches', 'inbox']);
});

test('legacy hub, both halves listed: chat is offered, as the old shell showed it', () => {
  assert.deepEqual(offerFrom({ modules: [{ id: 'core' }, { id: 'rag' }, { id: 'llm' }] }), ['fiches', 'inbox', 'chat']);
});

test('legacy hub, Open: no chat, so no live input on a door that answers 404', () => {
  assert.deepEqual(offerFrom({ modules: [{ id: 'core' }, { id: 'identity' }] }), ['fiches', 'inbox']);
  assert.deepEqual(offerFrom({ modules: [{ id: 'core' }, { id: 'rag' }] }), ['fiches', 'inbox'],
    'rag without llm is not a working chat');
});

test('legacy hub, nothing readable: carry on, chat stays (absence is proven, never assumed)', () => {
  assert.deepEqual(offerFrom({}), ['fiches', 'inbox', 'chat']);
  assert.deepEqual(offerFrom(null), ['fiches', 'inbox', 'chat']);
});

// planTabs(appEntry, me): the one call app.mjs makes once phase 2 has answered — it is what ties the
// registry entry's `tabs` and the hub's /me together, and app.mjs cannot be imported under node.
// The spec's acceptance lines, run for real:
const PRO_ME = { tabs: ['chat', 'fiches', 'inbox'] };
const OPEN_ME = { tabs: ['fiches', 'inbox'] };

test('plan: an app declaring [fiches, inbox] on a Pro hub shows two tabs and lands on Fiches', () => {
  const plan = planTabs({ tabs: ['fiches', 'inbox'] }, PRO_ME);
  assert.deepEqual(ids(plan.tabs), ['fiches', 'inbox']);
  assert.equal(plan.landing, 'fiches');
});

test('plan: an app declaring nothing behaves as today on a Pro hub', () => {
  assert.deepEqual(ids(planTabs({ tabs: null }, PRO_ME).tabs), ['fiches', 'inbox', 'chat']);
  assert.deepEqual(ids(planTabs({}, PRO_ME).tabs), ['fiches', 'inbox', 'chat']);
});

test('plan: [chat] alone never calls /api/inbox, and its chat has no Fiches to open', async () => {
  const plan = planTabs({ tabs: ['chat'] }, PRO_ME);
  assert.deepEqual(ids(plan.tabs), ['chat']);
  let inboxCalls = 0;
  await runBoots(plan.tabs, { api: { json: async () => { inboxCalls++; return []; } }, setBadge() {} });
  assert.equal(inboxCalls, 0, 'the inbox boot belongs to the inbox tab; this app does not show it');
  const ctx = { can: (id) => has(plan.tabs, id), openTab() {} };
  const chat = TABS.find((t) => t.id === 'chat').screen(ctx);
  assert.equal(chat.props.onOpenFiches, undefined, 'no button to a tab that is not there');
});

test('plan: [chat] on an Open hub keeps the declaration but shows the empty page', () => {
  const plan = planTabs({ tabs: ['chat'] }, OPEN_ME);
  assert.deepEqual(plan.tabs, []);
  assert.equal(plan.landing, null);
});

test('plan: an app that lists chat first lands on it only where the hub offers it', () => {
  assert.equal(planTabs({ tabs: ['chat', 'fiches'] }, PRO_ME).landing, 'chat');
  assert.equal(planTabs({ tabs: ['chat', 'fiches'] }, OPEN_ME).landing, 'fiches');
});

test('plan: a legacy hub (no tabs in /me) goes through the legacy adapter', () => {
  assert.deepEqual(ids(planTabs({ tabs: null }, { modules: [{ id: 'core' }] }).tabs), ['fiches', 'inbox']);
});

test('the inbox boot failing leaves no badge and no error', async () => {
  const inbox = TABS.find((t) => t.id === 'inbox');
  const badges = [];
  await runBoots([inbox], { api: { json: async () => { throw new Error('503'); } }, setBadge: (...a) => badges.push(a) });
  assert.deepEqual(badges, []);
});

// ── probes: a tab may need one more answer before it is worth showing ────────────────────────────
const entry = (id, probe) => ({ id, label: id, icon: '.', screen: () => null, ...(probe ? { probe } : {}) });

test('applyProbes: a tab whose probe says no is dropped, the others stay in order', async () => {
  const tabs = [entry('a'), entry('b', async () => false), entry('c', async () => true)];
  assert.deepEqual(ids(await applyProbes(tabs, {})), ['a', 'c']);
});

test('applyProbes: a probe that fails keeps its tab (absence is not proven)', async () => {
  const tabs = [entry('a', async () => { throw new Error('network'); })];
  assert.deepEqual(ids(await applyProbes(tabs, {})), ['a']);
});

test('applyProbes: probes run in parallel, once each, with the ctx they are given', async () => {
  const log = [];
  const gate = (name) => async (ctx) => { log.push(`start ${name} ${ctx.scope}`); await Promise.resolve(); log.push(`end ${name}`); return true; };
  await applyProbes([entry('a', gate('a')), entry('b', gate('b'))], { scope: 'stock' });
  // both began before either finished: not one after the other
  assert.deepEqual(log.slice(0, 2), ['start a stock', 'start b stock']);
  assert.equal(log.length, 4);
});

test('applyProbes: a lost session propagates instead of being swallowed as "unproven"', async () => {
  const tabs = [entry('a', async () => { throw { kind: 'auth' }; })];
  await assert.rejects(() => applyProbes(tabs, {}), (e) => e.kind === 'auth');
});

test('applyProbes: no tab with a probe is a no-op that returns the same tabs', async () => {
  const tabs = [entry('a'), entry('b')];
  assert.deepEqual(ids(await applyProbes(tabs, {})), ['a', 'b']);
});

test('the search entry carries a probe; the others do not', () => {
  assert.equal(typeof TABS.find((t) => t.id === 'search').probe, 'function');
  for (const t of TABS.filter((x) => x.id !== 'search')) assert.equal(t.probe, undefined, `${t.id} has no probe`);
});

// The search screen is handed the app's own scope, so every search it sends carries it: the hub then
// narrows to this scope and the PWA never widens (queryString always forwards a scope it is given).
test('the search entry hands its screen the open app\'s scope and api, nothing else', () => {
  const api = { json: async () => ({}) };
  const node = TABS.find((t) => t.id === 'search').screen({ api, scope: 'stock', can: () => true });
  assert.equal(node.props.scope, 'stock');
  assert.equal(node.props.api, api);
});

// app.mjs cannot load under node (top-level `location`), so its one new obligation is pinned by
// reading it, as shell-names-no-tab does: the probes are asked before the first tabs paint.
test('the shell asks the probes before it draws the tabs', async () => {
  const { readFile } = await import('node:fs/promises');
  const src = await readFile(new URL('../../main/resources/META-INF/resources/app/src/components/app.mjs', import.meta.url), 'utf8');
  const probes = src.indexOf('applyProbes(plan.tabs');
  const paint = src.indexOf("setScreen('tabs')");
  assert.ok(probes !== -1, 'app.mjs calls applyProbes on the planned tabs');
  assert.ok(probes < paint, 'and does so before setScreen(\'tabs\')');
});
