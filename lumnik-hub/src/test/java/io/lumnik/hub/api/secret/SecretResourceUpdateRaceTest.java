package io.lumnik.hub.api.secret;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.secret.PgcryptoSecretResolver;
import io.lumnik.hub.secret.Secret;
import io.lumnik.hub.secret.SecretEvents;
import io.lumnik.hub.secret.SecretRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * F1 (row-count half). {@link SecretResource#update} checks the secret exists via
 * {@code findByName}, then — in the same request but a moment later — runs the native
 * {@code UPDATE ... WHERE ... AND deleted_at IS NULL}. In the window between those two
 * statements, a concurrent {@code DELETE /api/platform/secrets/{name}} can delete the same row:
 * the {@code UPDATE} then matches zero rows, but nothing downstream checked
 * that — {@code resolver.invalidate()}, {@code events.emitUpdated()} and
 * {@code recordAudit("UPDATE", ...)} all ran unconditionally, writing an audit row (and firing an
 * event) for a write that never touched a row.
 *
 * <p>This is a pure unit test — no Quarkus container, no database — driving
 * {@link SecretResource#update} directly with a mocked {@link EntityManager} whose native
 * {@code executeUpdate()} returns 0, exactly what the race produces. It reaches {@code repo} and
 * {@code em} directly (package-private fields, same package) rather than going through CDI.
 */
class SecretResourceUpdateRaceTest {

    @AfterEach
    void clearTenant() {
        CurrentTenantHolder.clear();
    }

    @Test
    void update_does_not_invalidate_emit_or_audit_when_zero_rows_are_affected() {
        CurrentTenantHolder.set(1L);

        SecretResource resource = new SecretResource();
        resource.repo = mock(SecretRepository.class);
        resource.em = mock(EntityManager.class);
        resource.resolver = mock(PgcryptoSecretResolver.class);
        resource.events = mock(SecretEvents.class);
        resource.mapper = new ObjectMapper();
        resource.adminDs = mock(AgroalDataSource.class);

        Secret existing = new Secret();
        existing.setId(UUID.randomUUID());
        existing.setName("race-secret");
        existing.setRotatedAt(Instant.now());
        when(resource.repo.findByName(1L, "race-secret")).thenReturn(Optional.of(existing));

        Query query = mock(Query.class);
        when(resource.em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        // The race: another request deleted the row after findByName ran above, so the UPDATE
        // matches nothing.
        when(query.executeUpdate()).thenReturn(0);

        Response response = resource.update("race-secret", new SecretUpdateRequest("new-value", null));

        assertEquals(404, response.getStatus(),
            "zero rows changed means the secret was gone by the time the UPDATE ran — that must "
            + "surface as a 404, not a silent no-op 204");
        verifyNoInteractions(resource.resolver);
        verifyNoInteractions(resource.events);
        verifyNoInteractions(resource.adminDs);
    }
}
