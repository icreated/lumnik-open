package io.lumnik.hub.ask;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** How the ask pipeline reads the words of a question: accents and case folded, typographic apostrophes straightened. */
final class QuestionWords {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private QuestionWords() {}

    static String fold(String question) {
        return MARKS.matcher(Normalizer.normalize(question, Normalizer.Form.NFD)).replaceAll("")
            .toLowerCase(Locale.ROOT).replace('’', '\'');
    }

    /** {@code phrase} occurs in {@code folded} starting a word (never in the middle of one). */
    static boolean startsAWord(String folded, String phrase) {
        for (int at = folded.indexOf(phrase); at >= 0; at = folded.indexOf(phrase, at + 1)) {
            if (at == 0 || !Character.isLetterOrDigit(folded.charAt(at - 1))) return true;
        }
        return false;
    }
}
