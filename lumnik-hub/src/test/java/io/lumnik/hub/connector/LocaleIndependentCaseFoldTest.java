package io.lumnik.hub.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.mapping.MappingState;
import io.lumnik.hub.connector.mapping.hooks.cell.ParseEmailHook;
import io.lumnik.hub.connector.transport.AfterProcessAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1622: under a Turkish default locale, {@code "I".toLowerCase()} is the dotless {@code ı} and {@code "i".toUpperCase()}
 * the dotted {@code İ}. Data and manifest names fold the same on every server.
 */
class LocaleIndependentCaseFoldTest {

  private Locale previous;

  @BeforeEach
  void turkish() {
    previous = Locale.getDefault();
    Locale.setDefault(Locale.forLanguageTag("tr"));
  }

  @AfterEach
  void restore() {
    Locale.setDefault(previous);
  }

  @Test
  void parse_email_lowers_an_address_as_ascii() throws Exception {
    Map<String, Object> row = new HashMap<>(Map.of("email", "INFO@ISTANBUL.COM"));
    new ParseEmailHook().apply(row, new ObjectMapper().readTree("{\"column\":\"email\",\"lower\":true}"), new MappingState());
    assertThat(row).containsEntry("email", "info@istanbul.com");
  }

  @Test
  void after_process_archive_still_archives() {
    assertThat(AfterProcessAction.parse("archive")).isEqualTo(AfterProcessAction.MOVE);
  }
}
