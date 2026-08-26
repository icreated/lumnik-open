import { test } from 'node:test';
import assert from 'node:assert/strict';
import { METIERS, resolveMetier, kcBase, redirectUri } from '../../main/resources/META-INF/resources/app/src/config.mjs';

test('resolveMetier: known métier param resolves to that métier', () => {
  const m = resolveMetier('?m=stock');
  assert.equal(m.key, 'stock');
  assert.equal(m.scope, METIERS.stock.scope);
  assert.equal(m.title, METIERS.stock.title);
});

test('resolveMetier: unknown métier param falls back to clients', () => {
  const m = resolveMetier('?m=nope');
  assert.equal(m.key, 'clients');
});

test('resolveMetier: absent param falls back to clients', () => {
  const m = resolveMetier('');
  assert.equal(m.key, 'clients');
});

test('kcBase: https façade uses same-origin realm path', () => {
  const loc = { protocol: 'https:', origin: 'https://lumnik.example', hostname: 'lumnik.example' };
  assert.equal(kcBase(loc), 'https://lumnik.example/realms/lumnik');
});

test('kcBase: plain LAN mode uses port 8180 on the hostname', () => {
  const loc = { protocol: 'http:', origin: 'http://192.168.1.20:8080', hostname: '192.168.1.20' };
  assert.equal(kcBase(loc), 'http://192.168.1.20:8180/realms/lumnik');
});

test('redirectUri: clients métier has no query string', () => {
  const loc = { origin: 'https://lumnik.example' };
  assert.equal(redirectUri(loc, 'clients'), 'https://lumnik.example/app/');
});

test('redirectUri: non-clients métier rides ?m= on the redirect', () => {
  const loc = { origin: 'https://lumnik.example' };
  assert.equal(redirectUri(loc, 'stock'), 'https://lumnik.example/app/?m=stock');
});
