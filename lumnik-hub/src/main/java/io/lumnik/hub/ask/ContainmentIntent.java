package io.lumnik.hub.ask;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
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

    /** One quoted text, or one word with the joiners a code carries inside it (as FollowUp reads a value). */
    private static final String ONE_TEXT = "(?:\"([^\"]+)\"|([\\p{L}\\p{N}](?:[\\p{L}\\p{N}_./-]*[\\p{L}\\p{N}])?))";

    /** "dont la description contient badu.": the text right after the verb, and its clause ends there. */
    private static final Pattern SEARCHED_AFTER_THE_VERB = Pattern.compile(
        TEXT_COLUMN_THEN_VERB.pattern() + "\\s+" + ONE_TEXT + "\\s*(?=[,;:.?!]|$)");

    /** "ont badu dans leur description", "have badu in the description": the text right before the column. */
    private static final Pattern SEARCHED_BEFORE_THE_COLUMN = Pattern.compile(
        "\\b(?:ont|a|avec|have|has|with)\\s+" + ONE_TEXT + "\\s+" + IN_A_TEXT_COLUMN.pattern());

    private ContainmentIntent() {}

    static boolean in(String question) {
        if (question == null || question.isBlank()) return false;
        String folded = QuestionWords.fold(question);
        return TEXT_COLUMN_THEN_VERB.matcher(folded).find() || IN_A_TEXT_COLUMN.matcher(folded).find();
    }

    /**
     * The one text the question searches for, folded (#1099), or empty when it searches none or several. Only the two
     * shapes above, the text a single word or quoted: « contient badu ou pentair », « ne contient pas badu » or
     * « contient badu dans la catégorie Pompes » name no single text a follow-up could replace.
     */
    static Optional<String> searched(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        String folded = QuestionWords.fold(question);
        // one search in the question: « contient badu et dont le nom contient pentair » ends one clause on a text, not two
        long searches = TEXT_COLUMN_THEN_VERB.matcher(folded).results().count()
            + IN_A_TEXT_COLUMN.matcher(folded).results().count();
        if (searches != 1) return Optional.empty();
        Set<String> found = new LinkedHashSet<>();
        for (Pattern shape : List.of(SEARCHED_AFTER_THE_VERB, SEARCHED_BEFORE_THE_COLUMN)) {
            Matcher m = shape.matcher(folded);
            while (m.find()) found.add(m.group(1) != null ? m.group(1) : m.group(2));
        }
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    }
}
