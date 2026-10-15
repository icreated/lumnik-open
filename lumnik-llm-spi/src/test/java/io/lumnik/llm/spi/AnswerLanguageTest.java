package io.lumnik.llm.spi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The one language instruction every answer-writing prompt carries (#877). */
class AnswerLanguageTest {

    @Test
    void no_language_follows_the_question() {
        assertThat(AnswerLanguage.instruction(null, "rows")).isEqualTo(
            "Write the answer in the same language as the question, whatever the language of this prompt.\n"
                + "Quote values from the rows exactly as written; never translate them.");
    }

    @Test
    void a_named_language_overrides_the_question() {
        assertThat(AnswerLanguage.instruction("fr", "context")).isEqualTo(
            "Write the answer in French, whatever the language of the question or of this prompt.\n"
                + "Quote values from the context exactly as written; never translate them.");
    }
}
