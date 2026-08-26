package io.lumnik.hub.fidelity;

import io.lumnik.hub.ask.AbstentionGuard;

/** Deterministic generator for the OTK ABSTENTION family (splitmix64 via OtkRng). MODEL_NO_ANSWER varies
 *  the sentinel's surface form (case/whitespace) to probe isAbstention; SENTINEL_LOOKALIKE returns a real
 *  SQL that merely mentions the token. Queries connector.t_abst (seeded by AbstentionOtkIT). */
public final class AbstentionGenerator {

  private AbstentionGenerator() {}

  static final String TABLE = "connector.t_abst";
  private static final String[] NO_ANSWER_FORMS = {
      AbstentionGuard.SENTINEL, "  no_answer  ", AbstentionGuard.SENTINEL + "\n", "No_Answer" };

  public static AbstentionScenario generate(long seed) {
    long h = OtkRng.mix(seed);
    AbstentionKind kind = AbstentionKind.values()[(int) Math.floorMod(h, AbstentionKind.values().length)];
    h = OtkRng.mix(h);
    String reply = switch (kind) {
      case EMPTY_SCHEMA -> "";  // the model is never called
      case MODEL_NO_ANSWER -> NO_ANSWER_FORMS[(int) Math.floorMod(h, NO_ANSWER_FORMS.length)];
      case NORMAL -> "SELECT code FROM " + TABLE;
      case SENTINEL_LOOKALIKE -> "SELECT code FROM " + TABLE + " WHERE code = 'no_answer'";
    };
    return new AbstentionScenario(seed, kind, reply, kind.expectAbstain);
  }
}
