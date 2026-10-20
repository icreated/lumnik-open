// #1097: the chat's one sentence of memory. The thread is the lifted chatLog itself (D5: in memory,
// gone on reload); the sentence the next question continues is READ from it, never kept beside it,
// so a tab switch cannot desynchronise the two.

// The hub refuses a longer `previous` (AskPort.MAX_PREVIOUS_CHARS): holding one would refuse every next question.
export const MAX_PREVIOUS = 1000;

const CHIP_MAX = 100;
const CHIP_HEAD = 30;

// The sentence of the last ANSWERED turn — what the hub understood, or the question as asked when it
// was standalone. A refusal or an error does not replace it: the user often retries in other words.
// A cut (✕) ends the walk: what came before it is no longer continued.
export function previousOf(log) {
  for (let i = log.length - 1; i >= 0; i--) {
    const m = log[i];
    if (m.role === 'cut') return null;
    if (m.role === 'bot') {
      const sentence = m.understoodAs || m.asked;
      return sentence && sentence.length <= MAX_PREVIOUS ? sentence : null;
    }
  }
  return null;
}

// ✕: the next question is sent alone. The thread stays on screen; only the cut is added.
export function detach(log) {
  return [...log, { role: 'cut' }];
}

// What the hub really answered, when it is not what was typed — the only honest guard against a
// wrong resolution is that the user reads it.
export function understoodLine({ asked, understoodAs }) {
  return understoodAs && understoodAs !== (asked || '').trim() ? `Compris comme : ${understoodAs}` : null;
}

// A long sentence loses its middle, not its end: the value a follow-up changes (« Et HAWARD ? ») is usually
// last. The cut falls between words; a sentence with no space there is cut where it falls.
export function continuesLabel(previous) {
  if (previous.length <= CHIP_MAX) return `suite de : ${previous}`;
  const tailMax = CHIP_MAX - CHIP_HEAD - 3;
  const head = previous.slice(0, CHIP_HEAD + 1).replace(/\s*\S*$/, '') || previous.slice(0, CHIP_HEAD);
  const tail = previous.slice(-(tailMax + 1)).replace(/^\S*\s*/, '') || previous.slice(-tailMax);
  return `suite de : ${head} … ${tail}`;
}
