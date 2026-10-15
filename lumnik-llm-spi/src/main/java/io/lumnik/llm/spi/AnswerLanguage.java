package io.lumnik.llm.spi;

import java.util.Locale;

/**
 * The language instruction every answer-writing prompt carries — the analytic phrasing and the
 * semantic chat alike (#873, #877), so the two doors cannot drift apart.
 */
public final class AnswerLanguage {

    private AnswerLanguage() {}

    /**
     * @param lang   an ISO 639 code the calling door already checked, or null to follow the question's language
     * @param source what the values are quoted from, as the prompt names it ("rows", "context")
     */
    public static String instruction(String lang, String source) {
        String language = lang == null
            ? "Write the answer in the same language as the question, whatever the language of this prompt."
            : "Write the answer in " + Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH)
                + ", whatever the language of the question or of this prompt.";
        return language + "\nQuote values from the " + source + " exactly as written; never translate them.";
    }
}
