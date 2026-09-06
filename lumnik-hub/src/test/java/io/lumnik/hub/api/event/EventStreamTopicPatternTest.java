package io.lumnik.hub.api.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The topic glob is client-supplied ({@code ?topic=...}). Only {@code *} is a wildcard; everything
 * else is literal — including regex metacharacters, which must never reach {@code Pattern.compile}
 * unescaped and blow up the subscription with a {@code PatternSyntaxException} (a 500).
 */
class EventStreamTopicPatternTest {

  private static boolean matches(String topic, String eventType) {
    return EventStreamResource.topicToPattern(topic).matcher(eventType).matches();
  }

  @Test
  void star_is_the_only_wildcard_and_dots_are_literal() {
    assertThat(matches("connector.run.*", "connector.run.completed")).isTrue();
    assertThat(matches("connector.run.*", "connector.dlq.raised")).isFalse();
    // The dot is literal, not "any char": a different separator must not match.
    assertThat(matches("connector.run", "connectorXrun")).isFalse();
    assertThat(matches("*", "anything.at.all")).isTrue();
    assertThat(matches("a*b", "a-middle-b")).isTrue();
  }

  @Test
  void a_topic_with_regex_metacharacters_is_literal_and_never_throws() {
    // Each of these would break `replace(".","\\.")`-style compilation; here they are quoted literals.
    for (String hostile : new String[] {"[", "(", "\\", "a+", "a?", "{2}", "$", "^", "a[b"}) {
      assertThatCode(() -> EventStreamResource.topicToPattern(hostile))
          .as("topic '%s' must compile, not throw", hostile)
          .doesNotThrowAnyException();
    }
    // And it matches the literal text, not as a pattern.
    assertThat(matches("order.[draft]", "order.[draft]")).isTrue();
    assertThat(matches("order.[draft]", "order.d")).isFalse();
  }
}
