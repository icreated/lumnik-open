import { test } from 'node:test';
import assert from 'node:assert/strict';
import { urlKey, kcBase, redirectUri } from '../../main/resources/META-INF/resources/app/src/config.mjs';

test('urlKey: a known métier param passes through', () => {
  assert.equal(urlKey('?m=stock'), 'stock');
});

test('urlKey: any explicit param passes through, even one the registry does not know yet', () => {
  assert.equal(urlKey('?m=lumilait'), 'lumilait');
});

test('urlKey: absent param defaults to clients', () => {
  assert.equal(urlKey(''), 'clients');
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

test('redirectUri: a tag needing encoding does not break the query string', () => {
  const loc = { origin: 'https://lumnik.example' };
  assert.equal(redirectUri(loc, 'a&b'), 'https://lumnik.example/app/?m=a%26b');
});
