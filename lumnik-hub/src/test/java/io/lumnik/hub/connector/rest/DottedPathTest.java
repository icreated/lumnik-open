package io.lumnik.hub.connector.rest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DottedPathTest {

    @Test
    void a_clean_dotted_path_is_plain() {
        assertThat(DottedPath.isPlain("$.Body.Out.List.Item")).isTrue();
    }

    @Test
    void missing_dollar_prefix_is_not_plain() {
        assertThat(DottedPath.isPlain("Body.Out")).isFalse();
        assertThat(DottedPath.isPlain(null)).isFalse();
    }

    @Test
    void jsonpath_operators_are_not_plain() {
        assertThat(DottedPath.isPlain("$.Body.List[0]")).isFalse();
        assertThat(DottedPath.isPlain("$.Body..Item")).isFalse();
        assertThat(DottedPath.isPlain("$.Body.*")).isFalse();
    }

    @Test
    void whitespace_or_empty_segments_are_not_plain() {
        assertThat(DottedPath.isPlain("$.Body .Out")).isFalse();
        assertThat(DottedPath.isPlain("$.Body.")).isFalse();
        assertThat(DottedPath.isPlain("$.")).isFalse();
    }

    @Test
    void segments_strips_the_dollar_prefix_and_splits_on_dots() {
        assertThat(DottedPath.segments("$.Body.Out.Item")).containsExactly("Body", "Out", "Item");
    }

    @Test
    void segments_rejects_a_path_without_the_dollar_prefix_fail_fast() {
        // segments() assumes isPlain() held — a non-$. input is a call-site bug, not silent passthrough.
        assertThatThrownBy(() -> DottedPath.segments("Body.Out"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segments_rejects_a_non_plain_path_not_just_a_missing_prefix() {
        // The contract is "requires isPlain()", not "requires a $. prefix": operators, whitespace,
        // and empty segments must also fail fast rather than yield surprising segment arrays.
        assertThatThrownBy(() -> DottedPath.segments("$.a[0]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DottedPath.segments("$.a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DottedPath.segments("$.")).isInstanceOf(IllegalArgumentException.class);
    }
}
