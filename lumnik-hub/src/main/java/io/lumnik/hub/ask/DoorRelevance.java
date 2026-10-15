package io.lumnik.hub.ask;

import java.util.List;

/**
 * Is the door a refusal could point to relevant to what was asked (#1047)? A refusal that names the
 * fusion ledger to someone who asked for a text search sends them to a door that cannot answer; naming
 * it only when the question is about disagreeing sources, or a status and its lifecycle, keeps the
 * signpost true. The check is on the question's words (French and English, accents and case ignored): a
 * question phrased with none of them gets the refusal without the signpost — shorter, never wrong.
 */
final class DoorRelevance {

    /** Words and phrases that start a word of the question (accent-folded, lower case). A bare data word —
     *  status, state, "different" — is NOT here: a column called that is a lookup, not a question about how a
     *  document moves or where sources disagree. */
    private static final List<String> CONFLICTS = List.of(
        "conflit", "diverg", "desaccord", "contradi", "contredi", "incoheren", "discordan",
        "conflict", "disagree", "discrepan", "inconsisten", "mismatch", "dispute");

    private static final List<String> LIFECYCLE = List.of(
        "cycle de vie", "workflow", "etape", "phase", "transition", "dormant", "bloque", "clotur", "avancement",
        "lifecycle", "life cycle", "stage", "stuck", "move on");

    private DoorRelevance() {}

    /** The question is about sources that disagree — the fusion ledger can answer it. */
    static boolean conflicts(String question) {
        return mentions(question, CONFLICTS);
    }

    /** The question is about a status or a declared lifecycle — the lifecycle door can answer it. */
    static boolean lifecycle(String question) {
        return mentions(question, LIFECYCLE);
    }

    private static boolean mentions(String question, List<String> words) {
        if (question == null || question.isBlank()) return false;
        String folded = QuestionWords.fold(question);
        return words.stream().anyMatch(w -> QuestionWords.startsAWord(folded, w));
    }
}
