package io.lumnik.hub.ask;

import java.util.regex.Pattern;

/**
 * Does the question say that a TEXT column contains a text (#1060)? Only then does the SQL prompt carry the
 * {@code ILIKE} rule. The 7B follows an instruction it was not asked to apply: with the rule always present, "which
 * articles belong to the category Pompes" became a substring search (9 307 rows where the exact filter gives 7 040),
 * and an unrelated status lookup stopped being translated. So the rule is read where it is needed, and the prompt of
 * every other question is the one it always was.
 *
 * <p>The signal is explicit wording, French or English, accents and case ignored, and it names a text column:
 * <ul>
 *   <li>a text column that is the SUBJECT of a containment verb — "dont la description contient", "whose name
 *       contains", "dont le commentaire inclut" (the noun comes first, so "la catégorie contient des articles" and
 *       "containers" are not it);</li>
 *   <li>or the text said to sit IN a named text column — "Badu dans leur description", "Badu in the product
 *       description".</li>
 * </ul>
 * The nouns and the verbs are a closed list. A wording outside it gets no rule: the model then chooses on its own,
 * as it did before #1047 (a case-sensitive {@code LIKE} is possible). A bare "with", a category, a status or a code
 * are not containment — those stay equalities.
 */
final class ContainmentIntent {

    /** Generic text columns. The bare word is enough: a card's own column names are not consulted. */
    private static final String TEXT_NOUN =
        "(?:descriptions?|libelles?|designations?|noms?|textes?|intitules?|titres?|commentaires?|remarques?"
        + "|names?(?!\\s+of\\b)|texts?|labels?|titles?|comments?|remarks?)\\b";

    private static final String VERB =
        "(?:contien(?:t|nent|ne|nes)|contenir|contenait|contenaient|contiendra|contain(?:s|ed|ing)?"
        + "|includ(?:e|es|ed|ing)|inclu(?:t|ent|re|ait)|mention(?:s|ne|nent|ned|ning|ing)?"
        + "|comprend|comprennent|comporte|comportent)\\b";

    private static final String DETERMINER = "(?:la|le|les|sa|son|ses|leur|leurs|l'|the|its|their|a|an|un|une)";

    /** The noun comes first, a few words before the verb: "description qui ne contient pas", "name containing". */
    private static final Pattern TEXT_COLUMN_THEN_VERB = Pattern.compile(
        "\\b" + TEXT_NOUN + "[^?!.;]{0,40}?\\b" + VERB);

    /** "Badu dans leur description", "Badu in the product description": up to two words between the article and the noun. */
    private static final Pattern IN_A_TEXT_COLUMN = Pattern.compile(
        "\\b(?:dans|in)\\s+(?:" + DETERMINER + "\\s*)?(?:[\\p{L}'-]+\\s+){0,2}?" + TEXT_NOUN);

    private ContainmentIntent() {}

    static boolean in(String question) {
        if (question == null || question.isBlank()) return false;
        String folded = QuestionWords.fold(question);
        return TEXT_COLUMN_THEN_VERB.matcher(folded).find() || IN_A_TEXT_COLUMN.matcher(folded).find();
    }
}
