import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createViews } from '../../main/resources/META-INF/resources/app/src/views.mjs';

function fakeApi(jsonResult, textResult) {
  const jsonCalls = [];
  const textCalls = [];
  return {
    json: async (path, opts) => { jsonCalls.push({ path, opts }); return jsonResult; },
    text: async (path, accept) => { textCalls.push({ path, accept }); return textResult; },
    jsonCalls,
    textCalls,
  };
}

test('list(): GET /api/views?scope= with the scope encoded', async () => {
  const api = fakeApi([{ name: 'stock-view', scope: 'sto ck', title: 'Stock' }]);
  const views = createViews(api, 'sto ck');

  const result = await views.list();

  assert.deepEqual(result, [{ name: 'stock-view', scope: 'sto ck', title: 'Stock' }]);
  assert.equal(api.jsonCalls.length, 1);
  assert.equal(api.jsonCalls[0].path, '/api/views?scope=sto%20ck');
  assert.deepEqual(api.jsonCalls[0].opts, { method: 'GET' });
});

test('yaml(): api.text on /api/views/{name} with the name encoded, Accept application/yaml', async () => {
  const api = fakeApi(undefined, 'title: Stock\nfields: []\n');
  const views = createViews(api, 'stock');

  const raw = await views.yaml('sto ck view');

  assert.equal(raw, 'title: Stock\nfields: []\n');
  assert.equal(api.textCalls.length, 1);
  assert.equal(api.textCalls[0].path, '/api/views/sto%20ck%20view');
  assert.equal(api.textCalls[0].accept, 'application/yaml');
});

test('render(): no filters → no query string, POSTs the yaml body', async () => {
  const api = fakeApi({ title: 'Stock', columns: [], rows: [] });
  const views = createViews(api, 'stock');

  const result = await views.render('title: Stock\n');

  assert.deepEqual(result, { title: 'Stock', columns: [], rows: [] });
  assert.equal(api.jsonCalls.length, 1);
  assert.equal(api.jsonCalls[0].path, '/api/views/render');
  assert.deepEqual(api.jsonCalls[0].opts, { yaml: 'title: Stock\n' });
});

test('render(): 1 filter → ?filter=<encoded>', async () => {
  const api = fakeApi({ rows: [] });
  const views = createViews(api, 'stock');

  await views.render('title: Stock\n', ['montant>1000']);

  assert.equal(api.jsonCalls[0].path, '/api/views/render?filter=montant%3E1000');
});

test('render(): 2 filters → joined with & in order, each encoded independently', async () => {
  const api = fakeApi({ rows: [] });
  const views = createViews(api, 'stock');

  await views.render('title: Stock\n', ['montant>1000', 'statut=ouvert']);

  assert.equal(
    api.jsonCalls[0].path,
    '/api/views/render?filter=montant%3E1000&filter=statut%3Douvert',
  );
});

test('render(): defaults filters to [] when omitted (same as no filters)', async () => {
  const api = fakeApi({ rows: [] });
  const views = createViews(api, 'stock');

  await views.render('title: Stock\n');

  assert.equal(api.jsonCalls[0].path, '/api/views/render');
});

test('detail(): POST /api/views/detail?key= with the key encoded and the yaml body', async () => {
  const api = fakeApi({ title: 'Fiche', key: 'C 200', fields: [] });
  const views = createViews(api, 'stock');

  const result = await views.detail('title: Stock\n', 'C 200');

  assert.deepEqual(result, { title: 'Fiche', key: 'C 200', fields: [] });
  assert.equal(api.jsonCalls.length, 1);
  assert.equal(api.jsonCalls[0].path, '/api/views/detail?key=C%20200');
  assert.deepEqual(api.jsonCalls[0].opts, { yaml: 'title: Stock\n' });
});

test('createViews exposes its scope — the honest-empty message builds from it', () => {
  const views = createViews({ json: () => {}, text: () => {} }, 'stock');
  assert.equal(views.scope, 'stock');
});
