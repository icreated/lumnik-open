package io.lumnik.identity;

import io.lumnik.common.domain.TenantedEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.ws.rs.NotFoundException;

/**
 * The path guard shared by every identity resource: resolve by id, or 404 — and for a tenanted
 * aggregate, a foreign tenant is indistinguishable from absence (same message, same status), so a
 * caller can never probe another tenant's id space.
 */
public final class Require {

    private Require() {}

    /** The row must exist. */
    public static <T> T found(PanacheRepository<T> repo, Long id, String label) {
        return repo.findByIdOptional(id).orElseThrow(() -> notFound(label, id));
    }

    /** The row must exist AND belong to {@code tenantId}; a mismatch 404s exactly like absence. */
    public static <T extends TenantedEntity> T owned(PanacheRepository<T> repo, Long id,
                                                     Long tenantId, String label) {
        T entity = found(repo, id, label);
        if (!entity.getTenantId().equals(tenantId)) throw notFound(label, id);
        return entity;
    }

    private static NotFoundException notFound(String label, Long id) {
        return new NotFoundException(label + " not found: " + id);
    }
}
