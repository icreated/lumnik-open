package io.lumnik.hub.fidelity;

/** The shape of an abstention scenario, and whether the system MUST abstain. */
public enum AbstentionKind {
  EMPTY_SCHEMA(true),        // the scope's card is empty -> abstain before the model is called
  MODEL_NO_ANSWER(true),     // the model emits the sentinel -> abstain
  NORMAL(false),             // the model emits a valid SQL -> answer
  SENTINEL_LOOKALIKE(false); // a valid SQL that merely mentions the token -> answer (no false abstention)

  public final boolean expectAbstain;
  AbstentionKind(boolean expectAbstain) { this.expectAbstain = expectAbstain; }
}
