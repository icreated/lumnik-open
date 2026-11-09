package io.lumnik.hub.rag;

import io.lumnik.common.security.ScopeAccess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * What {@code rag.chunk} holds that the caller may see: the store itself, not the counters of this
 * JVM's platform indexing. The caller's tenant is named explicitly, as the corpora and scopes
 * listings do, and a scope-bound caller counts only the chunks tagged with a scope it holds, so the
 * volume of another scope of the same tenant does not leak. With the AI switched off the rows are
 * still there (#1895).
 */
@ApplicationScoped
public class StoredChunks {

    private static final String TENANT =
        "tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::bigint";

    @Inject EntityManager em;

    @Transactional
    public long countVisible() {
        var granted = ScopeAccess.granted();
        var query = granted.isPresent()
            ? em.createNativeQuery("SELECT count(*) FROM rag.chunk WHERE " + TENANT
                + " AND tags && CAST(string_to_array(?1, ',') AS text[])")
                .setParameter(1, String.join(",", granted.get()))
            : em.createNativeQuery("SELECT count(*) FROM rag.chunk WHERE " + TENANT);
        return ((Number) query.getSingleResult()).longValue();
    }
}
