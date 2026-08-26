// What this build actually carries, read from the door the métier already has.
//
// `GET /api/bff/v1/me` is lm_user-reachable and returns `modules`, built from the same manifest
// registry `/api/platform/health` serves the integrator. The chat doors (`/api/rag/chat` and its
// platform sibling) live in `lumnik-rag`, which the open edition does not carry — so the presence
// of the `rag` module is the signal, and no new endpoint is needed to read it.
//
// ABSENCE IS PROVEN, NEVER ASSUMED. Telling a métier "chat is not installed" because a probe
// failed would be a worse lie than the missing feature itself: anything unreadable means carry on.
export function hasChat(me) {
  const modules = me && me.modules;
  if (!Array.isArray(modules)) return true;
  // BOTH halves: the doors are in lumnik-rag, the model provider in lumnik-llm-langchain. One
  // without the other shows the input and fails at the first question.
  const ids = new Set(modules.filter(Boolean).map((m) => m.id));
  return ids.has('rag') && ids.has('llm');
}
