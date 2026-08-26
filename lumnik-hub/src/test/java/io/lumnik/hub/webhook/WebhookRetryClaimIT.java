package io.lumnik.hub.webhook;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-instance exclusion for the retry sweep. {@code @Scheduled(SKIP)} is per-JVM, so on a
 * multi-replica deployment two sweeps run at once and only the claim stops a customer endpoint
 * receiving the same POST twice. The claim is a COLUMN ({@code claimed_until}), not a row lock:
 * it must hold after the claiming transaction commits — that is what lets the delivery happen
 * outside any transaction — and it must expire, or a process that dies mid-delivery strands the
 * row forever.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class WebhookRetryClaimIT {

    private static final long TIMEOUT_S = 20;
    private static final int LEASE_S = 60;

    @Inject WebhookDeliveryRepository deliveryRepo;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void clean() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // claimNext is global — this test asserts on the global retryable set, so clear any
        // retryable row other tests left behind (e.g. WebhookDeliveryRepositoryIT's 3xx/5xx).
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE succeeded = FALSE AND attempts < 3").executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_delivery WHERE event_type LIKE 'clm.test.%'")
          .executeUpdate();
        em.createNativeQuery("DELETE FROM core.webhook_endpoint WHERE name = 'clm-ep'").executeUpdate();
    }

    @Transactional
    void seedRetryable(String... eventTypes) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.webhook_endpoint (tenant_id, name, target_url, platform, auth_mode, event_types, enabled)
            VALUES (1, 'clm-ep', 'http://localhost:1/x', 'generic', 'NONE', '{clm.test.*}', TRUE)
            """).executeUpdate();
        for (String type : eventTypes) {
            em.createNativeQuery("""
                INSERT INTO core.webhook_delivery
                    (tenant_id, endpoint_id, event_type, payload, attempted_at, attempts, succeeded)
                SELECT 1, e.id, :etype, '{}'::jsonb, NOW(), 1, FALSE
                FROM core.webhook_endpoint e WHERE e.name = 'clm-ep'
                """).setParameter("etype", type).executeUpdate();
        }
    }

    /** Claims in its OWN committed transaction — the claim must outlive it. */
    private Long claimInCommittedTx() {
        return TenantContext.callAsSystem(() -> QuarkusTransaction.requiringNew().call(() -> {
            em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
            WebhookDelivery d = deliveryRepo.claimNext(0, LEASE_S);
            return d == null ? null : d.getId();
        }));
    }

    @Transactional
    void expireClaim(long id) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("UPDATE core.webhook_delivery SET claimed_until = now() - INTERVAL '1 second' WHERE id = :id")
          .setParameter("id", id).executeUpdate();
    }

    @Test
    void aLiveClaimSurvivesItsTransactionAndHidesTheRow() {
        seedRetryable("clm.test.solo");

        Long first = claimInCommittedTx();
        assertNotNull(first, "the seeded retryable row must be claimable");

        // The claiming transaction has COMMITTED. A row lock would be gone by now; the lease is not.
        assertNull(claimInCommittedTx(),
            "a row whose claim is still live must be invisible to a second sweep, even after the "
            + "claiming transaction committed — that is what lets delivery run outside any transaction");
    }

    @Test
    void anExpiredClaimIsReclaimable() {
        seedRetryable("clm.test.expired");

        Long first = claimInCommittedTx();
        assertNotNull(first);
        expireClaim(first); // simulate a process that died mid-delivery: the lease ran out

        assertEquals(first, claimInCommittedTx(),
            "an expired claim must be reclaimable — otherwise a process dying mid-delivery "
            + "strands that delivery forever");
    }

    @Test
    void concurrentClaimsTakeDisjointRows() throws Exception {
        seedRetryable("clm.test.a", "clm.test.b");

        Set<Long> claimedByA = ConcurrentHashMap.newKeySet();
        Set<Long> claimedByB = ConcurrentHashMap.newKeySet();
        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "retry-claim-it");
            t.setDaemon(true); // never hang the build if a transaction wedges
            return t;
        });
        try {
            // A claims under FOR UPDATE SKIP LOCKED and holds its transaction open while B runs.
            Future<?> fa = pool.submit(() -> TenantContext.runAsSystem(() ->
                QuarkusTransaction.requiringNew().run(() -> {
                    em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
                    claimedByA.add(deliveryRepo.claimNext(0, LEASE_S).getId());
                    aClaimed.countDown();
                    awaitOrFail(bDone, "B to finish");
                })));
            // B runs only after A holds its lock; with SKIP LOCKED it must skip A's row.
            Future<?> fb = pool.submit(() -> TenantContext.runAsSystem(() -> {
                awaitOrFail(aClaimed, "A to claim");
                QuarkusTransaction.requiringNew().run(() -> {
                    em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
                    claimedByB.add(deliveryRepo.claimNext(0, LEASE_S).getId());
                });
                bDone.countDown();
            }));

            // get() asserts each task actually finished and surfaces any in-thread failure;
            // a deadlock fails the test via TimeoutException instead of hanging the build.
            fb.get(TIMEOUT_S, TimeUnit.SECONDS);
            fa.get(TIMEOUT_S, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(Collections.disjoint(claimedByA, claimedByB),
            "two concurrent retry sweeps claimed the same row: a=" + claimedByA + " b=" + claimedByB);
        assertEquals(2, claimedByA.size() + claimedByB.size(),
            "each retryable row must be claimed by exactly one sweep");
    }

    private static void awaitOrFail(CountDownLatch latch, String what) {
        try {
            if (!latch.await(TIMEOUT_S, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for " + what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + what, e);
        }
    }
}
