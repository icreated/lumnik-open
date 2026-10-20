import { test } from 'node:test';
import assert from 'node:assert/strict';
import { MAX_PREVIOUS, previousOf, detach, understoodLine, continuesLabel } from '../../main/resources/META-INF/resources/app/src/thread.mjs';

// #1097: the chat's one sentence of memory — what the next question continues, and what the user sees of it.
const asked = 'Donne-moi les références dont la description contient Badu.';
const haward = 'Donne-moi les références dont la description contient HAWARD.';
const me = (text) => ({ role: 'me', text });
const bot = (text, understoodAs = null) => ({ role: 'bot', text: 'réponse', sql: 'SELECT 1', asked: text, understoodAs });
const refusal = (kind = 'abstention', understoodAs = null) => ({ role: 'refusal', kind, text: 'non', understoodAs });

test('an empty thread — a new conversation — continues nothing', () => {
  assert.equal(previousOf([]), null);
});

test('an answer to a standalone question holds the question as asked', () => {
  assert.equal(previousOf([me(asked), bot(asked)]), asked);
});

test('an answer to a follow-up holds what the hub understood, not the follow-up typed', () => {
  assert.equal(previousOf([me(asked), bot(asked), me('Et HAWARD ?'), bot('Et HAWARD ?', haward)]), haward);
});

test('a refusal or an error keeps the previous sentence: the user often retries in other words', () => {
  const answered = [me(asked), bot(asked)];
  for (const kind of ['abstention', 'scope', 'error']) {
    assert.equal(previousOf([...answered, me('Et le deuxième ?'), refusal(kind)]), asked, kind);
  }
});

test('a refusal of a resolved follow-up does not replace the sentence either', () => {
  assert.equal(previousOf([me(asked), bot(asked), me('Et seulement les pompes ?'), refusal('abstention', haward)]), asked);
});

test('a question still waiting, or cut short by a lost session, keeps the previous sentence', () => {
  assert.equal(previousOf([me(asked), bot(asked), me('Et HAWARD ?'), { role: 'wait', text: '…' }]), asked);
  assert.equal(previousOf([me(asked), bot(asked), me('Et HAWARD ?')]), asked);
});

test('✕ detaches the next question, and a refusal after it brings nothing back', () => {
  const cut = detach([me(asked), bot(asked)]);
  assert.equal(previousOf(cut), null);
  assert.equal(previousOf([...cut, me('Combien de clients ?'), refusal()]), null);
});

test('✕ keeps the thread on screen: it only adds the cut', () => {
  const log = [me(asked), bot(asked)];
  assert.deepEqual(detach(log).slice(0, 2), log);
  assert.equal(detach(log).length, 3);
});

test('the first answer after ✕ starts the new thread', () => {
  const q = 'Combien de clients ?';
  assert.equal(previousOf([...detach([me(asked), bot(asked)]), me(q), bot(q)]), q);
});

test('a question past the hub bound is not held — the hub would refuse every next question', () => {
  const atBound = 'x'.repeat(MAX_PREVIOUS);
  const pastBound = 'x'.repeat(MAX_PREVIOUS + 1);
  assert.equal(MAX_PREVIOUS, 1000);
  assert.equal(previousOf([me(atBound), bot(atBound)]), atBound);
  assert.equal(previousOf([me(pastBound), bot(pastBound)]), null);
});

test('« Compris comme » shows when the hub answered another sentence than the one typed', () => {
  assert.equal(understoodLine({ asked: 'Et HAWARD ?', understoodAs: haward }), `Compris comme : ${haward}`);
});

test('« Compris comme » stays silent for a standalone question, or one understood as typed', () => {
  assert.equal(understoodLine({ asked, understoodAs: null }), null);
  assert.equal(understoodLine({ asked, understoodAs: asked }), null);
  assert.equal(understoodLine({ asked: `  ${asked} `, understoodAs: asked }), null);
});

test('the chip names the sentence the next question continues, whole when it fits', () => {
  assert.equal(continuesLabel('Combien de clients ?'), 'suite de : Combien de clients ?');
  const narrowed = 'Donne-moi les références de la catégorie Pompes dont la description contient HAWARD.';
  assert.equal(continuesLabel(narrowed), `suite de : ${narrowed}`);
});

// Live, a chip cut at its end read the same after « Badu » and after « Et HAWARD ? »: the value a
// follow-up changes is usually last, so a long sentence loses its middle instead — between words.
test('a long sentence keeps its start and its end on the chip, cut between words', () => {
  // a cut by characters would split « références » in the first, « fabriquées » in the second
  for (const long of [
    'Donne-moi toutes les références de la catégorie Pompes à chaleur fabriquées en 2024 dont la description contient HAWARD ou PENTAIR.',
    'Donne-moi les références de la catégorie Pompes à chaleur fabriquées avant 2024 dont la description contient HAWARD ou PENTAIR.',
  ]) {
    const label = continuesLabel(long);
    assert.ok(label.startsWith('suite de : Donne-moi'), label);
    assert.ok(label.endsWith('la description contient HAWARD ou PENTAIR.'), label);
    assert.ok(label.length <= 'suite de : '.length + 100, label);
    const words = new Set(long.split(' '));
    for (const w of label.slice('suite de : '.length).split(' ')) {
      if (w !== '…') assert.ok(words.has(w), `« ${w} » is a cut word: ${label}`);
    }
  }
});

test('a sentence without spaces is still cut in its middle', () => {
  const label = continuesLabel('x'.repeat(150));
  assert.ok(label.includes(' … '), label);
  assert.ok(label.length <= 'suite de : '.length + 100, label);
});
