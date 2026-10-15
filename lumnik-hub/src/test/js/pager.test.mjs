import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createPager, PAGE_SIZE, MAX_TRAIL, truncationLabel } from '../../main/resources/META-INF/resources/app/src/pager.mjs';

// A fake hub: pages of rows chained by cursors "c1", "c2", ...; the last page has no nextCursor.
function fakeLoad(pageCount, { fail = {} } = {}) {
  const calls = [];
  const load = async cursor => {
    calls.push(cursor);
    const n = cursor === null ? 1 : Number(cursor.slice(1)) + 1;
    if (fail[n]) throw fail[n];
    const last = n >= pageCount;
    return { columns: ['a'], rows: [[`row-${n}`]], hasMore: !last, nextCursor: last ? null : `c${n}` };
  };
  return { load, calls };
}

// A load whose promises the test settles by hand — to order responses against each other.
function manualLoad() {
  const pending = [];
  const load = cursor => new Promise((resolve, reject) => pending.push({ cursor, resolve, reject }));
  return { load, pending };
}

const page = (n, last = false) => ({ columns: ['a'], rows: [[`row-${n}`]], hasMore: !last, nextCursor: last ? null : `c${n}` });

test('PAGE_SIZE is inside the hub contract (1..200)', () => {
  assert.ok(PAGE_SIZE >= 1 && PAGE_SIZE <= 200);
});

test('first load: page 1, no previous, next only when the hub says there is more', async () => {
  const { load, calls } = fakeLoad(3);
  const p = createPager(load);
  await p.reset();
  const s = p.getState();
  assert.deepEqual(calls, [null]);
  assert.equal(s.pageNo, 1);
  assert.equal(s.canPrev, false);
  assert.equal(s.canNext, true);
  assert.equal(s.hasMore, true);
  assert.deepEqual(s.result.rows, [['row-1']]);
});

test('a single page: no next, hasMore false', async () => {
  const p = createPager(fakeLoad(1).load);
  await p.reset();
  assert.equal(p.getState().canNext, false);
  assert.equal(p.getState().hasMore, false);
});

test('next() sends the cursor the hub issued, prev() replays the one before', async () => {
  const { load, calls } = fakeLoad(3);
  const p = createPager(load);
  await p.reset();
  await p.next();
  await p.next();
  assert.deepEqual(calls, [null, 'c1', 'c2']);
  assert.equal(p.getState().pageNo, 3);
  assert.equal(p.getState().canNext, false);
  await p.prev();
  assert.deepEqual(calls, [null, 'c1', 'c2', 'c1']);
  assert.equal(p.getState().pageNo, 2);
  assert.deepEqual(p.getState().result.rows, [['row-2']]);
  await p.prev();
  assert.deepEqual(calls.at(-1), null);
  assert.equal(p.getState().pageNo, 1);
  assert.equal(p.getState().canPrev, false);
});

test('next() on the last page and prev() on the first are no-ops (no request)', async () => {
  const { load, calls } = fakeLoad(1);
  const p = createPager(load);
  await p.reset();
  await p.next();
  await p.prev();
  assert.deepEqual(calls, [null]);
});

test('reset() returns to page 1 and forgets the history (filter change)', async () => {
  const { load, calls } = fakeLoad(4);
  const p = createPager(load);
  await p.reset();
  await p.next();
  await p.next();
  await p.reset();
  assert.deepEqual(calls.slice(-1), [null]);
  assert.equal(p.getState().pageNo, 1);
  assert.equal(p.getState().canPrev, false);
});

test('first() jumps to page 1 from anywhere', async () => {
  const { load, calls } = fakeLoad(4);
  const p = createPager(load);
  await p.reset();
  await p.next();
  await p.next();
  await p.first();
  assert.equal(calls.at(-1), null);
  assert.equal(p.getState().pageNo, 1);
});

test('a failed next() keeps the last good page, the position and the retry cursor', async () => {
  const err = { kind: 'error', message: 'boom' };
  const { load, calls } = fakeLoad(3, { fail: { 2: err } });
  const p = createPager(load);
  await p.reset();
  await p.next();
  const s = p.getState();
  assert.deepEqual(s.error, err);
  assert.equal(s.pageNo, 1);
  assert.deepEqual(s.result.rows, [['row-1']]);
  assert.equal(s.canNext, true);
  assert.equal(s.loading, false);
  assert.deepEqual(calls, [null, 'c1']);
});

test('the error clears on the next success', async () => {
  let failing = true;
  const p = createPager(async cursor => {
    if (cursor === 'c1' && failing) throw { kind: 'error', message: 'boom' };
    return cursor === null ? page(1) : page(2, true);
  });
  await p.reset();
  await p.next();
  assert.ok(p.getState().error);
  failing = false;
  await p.next();
  assert.equal(p.getState().error, null);
  assert.equal(p.getState().pageNo, 2);
});

test('a failed reset() keeps the last good result on screen', async () => {
  let failing = false;
  const p = createPager(async () => {
    if (failing) throw { kind: 'error', message: 'bad filter' };
    return page(1);
  });
  await p.reset();
  failing = true;
  await p.reset();
  assert.equal(p.getState().error.message, 'bad filter');
  assert.deepEqual(p.getState().result.rows, [['row-1']]);
});

test('loading is true while a request is in flight and false after', async () => {
  const m = manualLoad();
  const p = createPager(m.load);
  const done = p.reset();
  assert.equal(p.getState().loading, true);
  m.pending[0].resolve(page(1, true));
  await done;
  assert.equal(p.getState().loading, false);
});

test('next() while a page is loading is ignored (no double request)', async () => {
  const m = manualLoad();
  const p = createPager(m.load);
  const first = p.reset();
  m.pending[0].resolve(page(1));
  await first;
  const a = p.next();
  const b = p.next();
  assert.equal(m.pending.length, 2);
  m.pending[1].resolve(page(2, true));
  await Promise.all([a, b]);
  assert.equal(p.getState().pageNo, 2);
});

test('a stale response never replaces a newer one (reset overtakes next)', async () => {
  const m = manualLoad();
  const p = createPager(m.load);
  const r0 = p.reset();
  m.pending[0].resolve(page(1));
  await r0;
  const slow = p.next();        // pending[1], cursor c1
  const fresh = p.reset();      // pending[2], cursor null — the filter changed
  m.pending[2].resolve({ columns: ['a'], rows: [['filtered']], hasMore: false, nextCursor: null });
  await fresh;
  m.pending[1].resolve(page(2));   // the old answer lands last
  await slow;
  const s = p.getState();
  assert.deepEqual(s.result.rows, [['filtered']]);
  assert.equal(s.pageNo, 1);
  assert.equal(s.canNext, false);
});

test('a stale FAILURE does not paint an error over a newer success', async () => {
  const m = manualLoad();
  const p = createPager(m.load);
  const r0 = p.reset();
  m.pending[0].resolve(page(1));
  await r0;
  const slow = p.next();
  const fresh = p.reset();
  m.pending[2].resolve(page(1, true));
  await fresh;
  m.pending[1].reject({ kind: 'error', message: 'late boom' });
  await slow;
  assert.equal(p.getState().error, null);
});

test('the superseded request does not leave loading stuck on', async () => {
  const m = manualLoad();
  const p = createPager(m.load);
  const a = p.reset();
  const b = p.reset();
  m.pending[1].resolve(page(1, true));
  await b;
  assert.equal(p.getState().loading, false);
  m.pending[0].resolve(page(1));
  await a;
  assert.equal(p.getState().loading, false);
});

test('page state is bounded: the trail never exceeds MAX_TRAIL, the page number keeps counting', async () => {
  const total = MAX_TRAIL + 25;
  const { load } = fakeLoad(total + 1);
  const p = createPager(load);
  await p.reset();
  for (let i = 0; i < total; i++) await p.next();
  const s = p.getState();
  assert.equal(s.pageNo, total + 1);
  assert.ok(s.trailLength <= MAX_TRAIL);
  assert.deepEqual(s.result.rows, [[`row-${total + 1}`]]);
});

test('once the trail is exhausted, prev() is refused honestly and first() still works', async () => {
  const total = MAX_TRAIL + 5;
  const { load, calls } = fakeLoad(total + 1);
  const p = createPager(load);
  await p.reset();
  for (let i = 0; i < total; i++) await p.next();
  for (let i = 0; i < MAX_TRAIL; i++) await p.prev();
  assert.equal(p.getState().canPrev, false);
  assert.ok(p.getState().pageNo > 1);
  const n = calls.length;
  await p.prev();
  assert.equal(calls.length, n);
  await p.first();
  assert.equal(p.getState().pageNo, 1);
  assert.equal(p.getState().canFirst, false);
});

test('subscribers are notified on every change, and can unsubscribe', async () => {
  const p = createPager(fakeLoad(2).load);
  const seen = [];
  const off = p.subscribe(s => seen.push(s.loading));
  await p.reset();
  assert.deepEqual(seen, [true, false]);
  off();
  await p.next();
  assert.deepEqual(seen, [true, false]);
});

test('truncationLabel: honest about more results, silent when the page is complete', () => {
  assert.equal(truncationLabel({ pageNo: 1, hasMore: false, rows: 3 }), null);
  assert.match(truncationLabel({ pageNo: 1, hasMore: true, rows: 50 }), /d'autres résultats/);
  assert.match(truncationLabel({ pageNo: 2, hasMore: false, rows: 10 }), /dernière page/);
  assert.match(truncationLabel({ pageNo: 3, hasMore: true, rows: 50 }), /page 3/i);
});

test('the error says which gesture it belongs to: a reset (filter) or a move (page)', async () => {
  let failing = false;
  const p = createPager(async cursor => {
    if (failing) throw { kind: 'error', message: 'boom' };
    return page(1);
  });
  await p.reset();
  assert.equal(p.getState().errorOrigin, null);
  failing = true;
  await p.reset();
  assert.equal(p.getState().errorOrigin, 'reset');
  failing = false;
  await p.reset();
  assert.equal(p.getState().errorOrigin, null);
  failing = true;
  await p.next();
  assert.equal(p.getState().errorOrigin, 'move');
});

test('a non-object failure is normalised to {kind, message}', async () => {
  const p = createPager(async () => { throw new Error('net down'); });
  await p.reset();
  assert.deepEqual(p.getState().error, { kind: 'error', message: 'net down' });
});

test('a rejection with undefined/null still ends as a structured error and clears loading', async () => {
  for (const reason of [undefined, null, 'plain string']) {
    const p = createPager(async () => { throw reason; });
    await p.reset();
    const s = p.getState();
    assert.equal(s.loading, false);
    assert.equal(s.error.kind, 'error');
    assert.equal(typeof s.error.message, 'string');
  }
});
