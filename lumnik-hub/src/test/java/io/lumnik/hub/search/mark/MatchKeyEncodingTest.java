package io.lumnik.hub.search.mark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * TDD for the length-prefixed match-key framing (design §2/§9): {@code "<n>|<len1>:<v1>|<len2>:<v2>|…"}.
 * The whole point of length-prefixing is that no delimiter-and-escape scheme is needed — these tests
 * exist to prove the naive alternative (plain {@code String.join("|", ...)}) really would collide,
 * and that this encoding does not.
 */
class MatchKeyEncodingTest {

    @Test
    void deterministicForTheSameInput() {
        List<String> tuple = List.of("acme", "42", "widget");
        assertEquals(MatchKeyEncoding.encode(tuple), MatchKeyEncoding.encode(tuple));
    }

    @Test
    void collidesUnderNaiveJoinButNotUnderThisEncoding() {
        List<String> a = List.of("a|b", "c");
        List<String> b = List.of("a", "b|c");

        // The naive alternative this encoding replaces really does collide on these two tuples.
        assertEquals(String.join("|", a), String.join("|", b));

        assertNotEquals(MatchKeyEncoding.encode(a), MatchKeyEncoding.encode(b));
    }

    @Test
    void handlesEmptyStringsWithoutCollision() {
        List<String> withEmpty = List.of("", "x");
        List<String> withoutEmpty = List.of("x");
        assertNotEquals(MatchKeyEncoding.encode(withEmpty), MatchKeyEncoding.encode(withoutEmpty));
    }

    @Test
    void handlesValuesContainingSeparatorAndColonCharacters() {
        List<String> a = List.of("v1:has|both:chars");
        List<String> b = List.of("v1:has|both", "chars");
        assertNotEquals(MatchKeyEncoding.encode(a), MatchKeyEncoding.encode(b));
    }

    @Test
    void handlesMultiByteUtf8ValuesWithoutCollision() {
        // "café" (Latin small e with acute, 2-byte UTF-8) vs the same length in ASCII code points but
        // a different byte length — proves length prefixing uses UTF-8 byte length, not char count.
        List<String> a = List.of("café", "x");
        List<String> b = List.of("caféx");
        assertNotEquals(MatchKeyEncoding.encode(a), MatchKeyEncoding.encode(b));

        List<String> multiByte = List.of("naïve", "日本語");
        assertEquals(MatchKeyEncoding.encode(multiByte), MatchKeyEncoding.encode(multiByte));
    }

    @Test
    void differentArityNeverCollidesEvenIfSuffixesCouldOtherwiseOverlap() {
        // Without the leading count, encode(["ab"]) and encode(["a","b"]) could be engineered to
        // overlap once framed; the leading "n|" guards this.
        List<String> single = List.of("ab");
        List<String> pair = List.of("a", "b");
        assertNotEquals(MatchKeyEncoding.encode(single), MatchKeyEncoding.encode(pair));
    }

    @Test
    void emptyTupleEncodesToJustTheCount() {
        assertEquals("0", MatchKeyEncoding.encode(List.of()));
    }

    @Test
    void singleValueTupleMatchesTheDesignDocFraming() {
        assertEquals("1|3:abc", MatchKeyEncoding.encode(List.of("abc")));
    }
}
