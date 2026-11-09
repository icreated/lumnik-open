package io.lumnik.hub.process;

import static org.assertj.core.api.Assertions.assertThat;

import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * #1845: {@code findReloadingIfMissing} re-reads every manifest on a miss. A process left enabled on the view of an Entity that
 * is gone makes every table of every write batch a miss — a full {@code entity_manifest} read each time. The miss is
 * remembered for a while; a hit is never.
 */
class EntityProcessTargetsMissTest {

    private final AtomicInteger reloads = new AtomicInteger();
    private final AtomicLong now = new AtomicLong(1_000);
    private final EntityProcessTargets targets = new EntityProcessTargets();

    EntityProcessTargetsMissTest() {
        targets.entities = new EntityRegistry() {
            @Override public EntityManifest find(String name) { return null; }
            @Override public EntityManifest findReloadingIfMissing(String name) {
                reloads.incrementAndGet();
                return null;
            }
        };
        targets.store = new ProcessManifestStore() {
            @Override public List<String> enabledEntityViews(long tenantId) { return List.of("connector.v_gone"); }
        };
        targets.nowMillis = now::get;
    }

    @Test
    void aMissIsReloadedOnceNotOncePerTableOfEveryBatch() {
        for (int i = 0; i < 5; i++) {
            assertThat(targets.anyObserving(1L, "connector.t_orders" + i)).isFalse();
        }

        assertThat(reloads.get()).as("one reload for five tables, each asked for as a source and as a lookup").isEqualTo(1);
    }

    @Test
    void theMissIsAskedAgainOnceItHasExpired() {
        targets.anyObserving(1L, "connector.t_orders");
        now.addAndGet(EntityProcessTargets.MISS_MILLIS + 1);
        targets.anyObserving(1L, "connector.t_orders");

        assertThat(reloads.get()).isEqualTo(2);
    }
}
