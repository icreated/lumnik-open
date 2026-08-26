package io.lumnik.hub.connector.exceptions;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExceptionsTest {

  @Test
  void all_exceptions_extend_connectorexception() {
    assertThat(new ConfigValidationException("bad")).isInstanceOf(ConnectorException.class);
    assertThat(new CredentialNotFoundException("nope")).isInstanceOf(ConnectorException.class);
    assertThat(new UnknownConnectorTypeException("x")).isInstanceOf(ConnectorException.class);
    assertThat(new MappingException("m", new RuntimeException())).isInstanceOf(ConnectorException.class);
    assertThat(new TransientFailureException("net", new RuntimeException())).isInstanceOf(ConnectorException.class);
  }

  @Test
  void transient_marks_retryable() {
    var e = new TransientFailureException("temp", new RuntimeException());
    assertThat(e.isRetryable()).isTrue();
  }
}
