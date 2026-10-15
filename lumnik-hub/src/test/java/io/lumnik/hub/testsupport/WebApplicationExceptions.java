package io.lumnik.hub.testsupport;

import jakarta.ws.rs.WebApplicationException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A resource method gated by {@code io.lumnik.hub.entity.ResolutionGate} refuses by throwing a
 * {@link WebApplicationException} — JAX-RS unwraps it into its own {@code Response} in a real
 * container, but a unit test calling the method directly (no container) sees the raised exception
 * itself, never a returned {@code Response}. This is the one place that translation is asserted.
 */
public final class WebApplicationExceptions {

  private WebApplicationExceptions() {}

  public static void assertRefusal(ThrowingCallable call, int status) {
    assertThatThrownBy(call).isInstanceOfSatisfying(WebApplicationException.class,
        e -> assertThat(e.getResponse().getStatus()).isEqualTo(status));
  }
}
