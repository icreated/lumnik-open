package io.lumnik.hub.connector.rest.reliability;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code default_rate_limit} is a free-text manifest field that reaches this parser untouched, so the
 * parser is where its grammar is decided — and what it decides, the validator reports. Every row here
 * is a value an integrator can put in a manifest today.
 */
class RateLimitSpecTest {

    @ParameterizedTest(name = "\"{0}\" is a usable rate limit")
    @CsvSource({
        "100/s",
        "10/2m",
        "5/h",
        "1/1s",
    })
    void aWellFormedSpecParses(String spec) {
        assertThat(RateLimiterRegistry.describeProblem(spec))
            .as("\"%s\" must be accepted", spec).isEmpty();
        assertThat(RateLimiterRegistry.parse(spec)).isNotNull();
    }

    /**
     * Each of these crashed the run rather than failing the manifest — and the negative count did not
     * crash at all, which is worse: it was accepted and built a limiter nobody can reason about.
     *
     * <p>The third column is the point. A refusal that cannot say WHICH thing is wrong sends the
     * integrator back to guess, so every distinct failure gets its own words.
     */
    @ParameterizedTest(name = "{0} is refused, and named for what is wrong with it")
    @CsvSource(delimiter = '|', value = {
        "a word instead of a count  | abc            | must be a rate",
        "an empty spec              | ''             | must not be blank",
        "a count with no window     | 100            | must be a rate",
        "a window with no unit      | 100/           | must be a rate",
        "an unknown time unit       | 100/x          | must be a rate",
        "a negative count           | -5/s           | must be a rate",
        "a fractional count         | 1.5/s          | must be a rate",
        "a zero count               | 0/s            | count must be at least 1",
        "a count wider than an int  | 99999999999/s  | count is implausibly large",
        "a zero window              | 10/0s          | window must be at least 1",
        "a window wider than an int | 10/9999999999s | window is implausibly large",
    })
    void aMalformedSpecIsRefusedBeforeItCanCrashARun(String axis, String spec, String expected) {
        assertThat(RateLimiterRegistry.describeProblem(spec))
            .as("%s — the validator must be able to explain this", axis)
            .isPresent()
            .get(InstanceOfAssertFactories.STRING)
            .as("%s — and say WHICH thing is wrong, not just that something is", axis)
            .contains(expected);

        assertThatThrownBy(() -> RateLimiterRegistry.parse(spec))
            .as("%s — must fail as a stated refusal, not a raw crash", axis)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("default_rate_limit");
    }

    @ParameterizedTest(name = "a {0} spec is refused rather than dereferenced")
    @CsvSource({"null", "blank"})
    void anAbsentSpecIsRefusedRatherThanDereferenced(String shape) {
        String spec = "null".equals(shape) ? null : "   ";
        assertThat(RateLimiterRegistry.describeProblem(spec)).isPresent();
    }
}
