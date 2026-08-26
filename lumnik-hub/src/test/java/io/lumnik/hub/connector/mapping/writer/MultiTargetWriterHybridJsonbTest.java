package io.lumnik.hub.connector.mapping.writer;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.mapping.spec.MappingTarget;
import io.lumnik.hub.connector.mapping.spec.MappingTarget.HybridJsonb;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class MultiTargetWriterHybridJsonbTest {

    @Inject MultiTargetWriter writer;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    @Test
    void dispatches_to_hybrid_jsonb() throws Exception {
        UUID connId = UUID.randomUUID();
        UUID epId   = UUID.randomUUID();
        UUID runId  = UUID.randomUUID();
        String table = "ext.mtw_test_" + System.nanoTime();

        MappingTarget target = new MappingTarget(null, null, null,
            new HybridJsonb(table, "$.id",
                List.of(new PromotedColumn("amount", "$.amount", SqlType.BIGINT, Optional.empty())),
                epId));

        TenantContext.runAsSystem(() -> {
            try {
                utx.begin();
                writer.write(target, List.of(Map.of("id", "x_1", "amount", 99)),
                    1L, runId, "m1", 1, "rest", connId, List.of("billing"));
                utx.commit();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Number n = (Number) em.createNativeQuery(
            "SELECT count(*) FROM " + table + " WHERE external_id = 'x_1'"
        ).getSingleResult();
        assertEquals(1L, n.longValue());
    }
}
