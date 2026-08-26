package io.lumnik.hub.connector.rest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SnippetsTest {

    @Test
    void short_text_is_returned_unchanged() {
        assertThat(Snippets.bound("boom")).isEqualTo("boom");
    }

    @Test
    void null_becomes_empty_string() {
        assertThat(Snippets.bound(null)).isEmpty();
    }

    @Test
    void long_text_is_truncated_to_200_with_ellipsis() {
        String s = Snippets.bound("x".repeat(500));
        assertThat(s).hasSize(201).endsWith("…");      // 200 chars + the ellipsis
    }

    @Test
    void whitespace_is_collapsed_to_single_spaces_and_trimmed() {
        // newlines/tabs from pretty-printed XML/JSON must not bloat a log line.
        assertThat(Snippets.bound("  a\n\t  b  ")).isEqualTo("a b");
    }

    @Test
    void a_huge_mixed_input_stays_bounded() {
        // Windowing the raw input before the regex must not break the ≤ MAX+ellipsis guarantee.
        String huge = ("word \n\t ".repeat(100_000));   // ~800k chars of mixed content + whitespace
        assertThat(Snippets.bound(huge)).hasSize(201).endsWith("…");
    }
}
