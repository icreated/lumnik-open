package io.lumnik.hub.api;

import io.lumnik.common.security.ScopeGrants;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ApplyScopeBoundary#refuseUnlessApplied} — the arm that turns a guarded write's own answer
 * into the door's refusal (#582).
 *
 * <p>Pinned HERE rather than at each door, because there is one implementation and four callers: a
 * per-door copy would prove the same branch four times and still say nothing about the fifth door
 * someone adds. What each door owes on top of this is that it CALLS it, and that it carries the
 * caller's grants into the write — pinned where those differ, in the door's own test.
 *
 * <p>The conversion of the thrown exception into a real {@code 403} on the wire is a framework fact
 * ({@code ScopeRefusalOnTheWireIT}), true for every door at once; this asserts the response the
 * exception carries, which is what that conversion then serves.
 */
class ApplyScopeBoundaryTest {

    @Test
    @SuppressWarnings("unchecked")
    void aRefusedWriteBecomesTheFrozen403() {
        assertThatThrownBy(() ->
            ApplyScopeBoundary.refuseUnlessApplied(new ScopeGuardedUpsert.NameHeldOutOfScope()))
            .isInstanceOf(WebApplicationException.class)
            .satisfies(e -> {
                Response r = ((WebApplicationException) e).getResponse();
                assertThat(r.getStatus()).isEqualTo(403);
                assertThat(((Map<String, Object>) r.getEntity()).get("error"))
                    .as("byte-identical to the ordered check's refusal, so a caller cannot tell "
                        + "which of the two answered")
                    .isEqualTo(ScopeGrants.REFUSAL);
            });
    }

    @Test
    void anAppliedWritePassesThrough() {
        // The other half: a guard that refused everything would pass the test above.
        assertThatCode(() ->
            ApplyScopeBoundary.refuseUnlessApplied(new ScopeGuardedUpsert.Applied()))
            .doesNotThrowAnyException();
    }

    @Test
    void zeroRowsIsTheRefusalAndOneIsNot() {
        // The store's reading of its own row count, which is what reaches the arm above.
        assertThat(ScopeGuardedUpsert.of(0)).isInstanceOf(ScopeGuardedUpsert.NameHeldOutOfScope.class);
        assertThat(ScopeGuardedUpsert.of(1)).isInstanceOf(ScopeGuardedUpsert.Applied.class);
    }
}
