package io.lumnik.hub.connector.mapping.writer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** #1195: the guard measures how much the table would shrink, not how many versions would go. */
class UnseenRowSweeperCountTest {

  @ParameterizedTest(name = "held {0}, met {1}, unseen {2}, max {3} → refused {4}")
  @CsvSource({
      "10, 6, 4, 0.30, true",     // a truncated file: 40% gone
      "10, 7, 3, 0.30, false",    // exactly the limit is allowed
      "10, 10, 10, 0.30, false",  // every value changed, nothing lost
      "10, 0, 10, 1.0, false",    // off
      "0, 4, 0, 0.30, false",     // a first run holds nothing
  })
  void shrinking_beyond_the_limit_is_what_refuses(long before, long met, long unseen, double max, boolean refused) {
    assertThat(new UnseenRowSweeper.Count(before, met, unseen).shrinksBeyond(max)).isEqualTo(refused);
  }
}
