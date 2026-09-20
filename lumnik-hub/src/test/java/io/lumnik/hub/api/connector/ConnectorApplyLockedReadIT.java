package io.lumnik.hub.api.connector;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connector apply door's re-homing check, held against the row it judged (#585).
 *
 * <p>{@code ConnectorApplyResource.apply} is {@code @Transactional}, and both the guard's read and
 * the upsert's read reach the row through the same {@code ConnectorRepository}. That is NOT enough:
 * under READ COMMITTED an unlocked {@code SELECT} takes no lock, and {@code Connector} carries no
 * {@code @Version}, so the {@code UPDATE … SET tags} Hibernate dirty-checks at flush re-checks
 * nothing. A concurrent apply committing in between is silently overwritten, and the authorization
 * that let this write through was decided on a state that no longer exists.
 *
 * <p><b>The property is a lock, not a refusal</b> — which is why this test is shaped unlike its
 * {@code *NameOverwriteScopeIT} siblings from #582. A lock is only observable through contention, so
 * the second transaction runs on ANOTHER connection (the admin datasource) with a short
 * {@code lock_timeout} and must be turned away with SQLSTATE {@code 55P03} (lock_not_available).
 *
 * <p>It is deterministic all the same: the door's transaction holds the row for the whole time the
 * second one is trying, so the outcome depends on the lock and not on who wins a race. The timeout
 * is what keeps a missing lock from hanging the build — without it, a failure would be a hang.
 */
@QuarkusTest
class ConnectorApplyLockedReadIT {

    private static final long TENANT = 5851L;
    private static final String OWNER = "proprietaire-conn-585";
    private static final String INTRUDER = "intrus-conn-585";

    @Inject ConnectorRepository connectors;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    /** A connector of {@link #OWNER}'s, as an unscoped integrator legitimately applied it. */
    private String seed(String name) {
        QuarkusTransaction.requiringNew().run(() -> {
            Connector c = new Connector();
            c.setTenantId(TENANT);
            c.setName(name);
            c.setTypeId("hello-world");
            c.setTags(List.of(OWNER));
            c.setConfig(Map.of("kind", "hello-world"));
            connectors.persist(c);
            connectors.flush();
        });
        return name;
    }

    /**
     * The concurrent apply, reduced to the one statement that re-homes: another connection, its own
     * transaction, a short fuse. Returns the SQLSTATE it was refused with, or {@code null} when the
     * write went through — which is the answer that means the row was NOT held.
     */
    private String tryRehomeFromAnotherTransaction(String name) {
        try (Connection c = adminDs.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement st = c.createStatement()) {
                    // Without this the UPDATE would wait for the door's transaction forever, and a
                    // missing lock would read as a hung build instead of a red test.
                    st.execute("SET LOCAL lock_timeout = '750ms'");
                }
                try (PreparedStatement ps = c.prepareStatement(
                         "UPDATE connector.connector SET tags = ? "
                             + "WHERE tenant_id = ? AND name = ?")) {
                    ps.setArray(1, c.createArrayOf("text", new Object[]{INTRUDER}));
                    ps.setLong(2, TENANT);
                    ps.setString(3, name);
                    ps.executeUpdate();
                }
                c.commit();
                return null;
            } catch (SQLException refused) {
                c.rollback();
                return refused.getSQLState();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the second transaction could not run at all: " + e, e);
        }
    }

    private List<String> storedTags(String name) {
        return QuarkusTransaction.requiringNew().call(() ->
            connectors.lockedScopesOf(TENANT, name).orElseThrow());
    }

    @Test
    void theDoorsReadHoldsTheRowUntilItsTransactionEnds() {
        String name = seed("conn-verrou-585");
        assertThat(storedTags(name)).containsExactly(OWNER);

        // The assertions sit OUTSIDE the transaction on purpose: an AssertionFailedError raised
        // inside the lambda comes back wrapped by QuarkusTransaction, and a future failure would
        // read as an anonymous error rather than as the sentence written below it.
        String refusal = QuarkusTransaction.requiringNew().call(() -> {
            connectors.lockedScopesOf(TENANT, name);   // the door's guard read: takes the row
            return tryRehomeFromAnotherTransaction(name);
        });

        assertThat(refusal)
            .as("a concurrent apply must not be able to re-home the row this door already judged "
                + "— it waits for this transaction, and its short fuse burns first")
            .isEqualTo("55P03");
        assertThat(storedTags(name))
            .as("and nothing was re-homed on the way out")
            .containsExactly(OWNER);
    }

    @Test
    void aRowTheDoorDidNotReadStaysWritable() {
        // Half a rule proven is a rule that could be enforced by freezing the whole table. The lock
        // must be on the ROW the door judged, and on no other.
        String judged = seed("conn-juge-585");
        String other = seed("conn-voisin-585");

        String refusal = QuarkusTransaction.requiringNew().call(() -> {
            connectors.lockedScopesOf(TENANT, judged);
            return tryRehomeFromAnotherTransaction(other);
        });

        assertThat(refusal).as("a different connector is nobody's business here").isNull();
        assertThat(storedTags(other)).containsExactly(INTRUDER);
    }

    @Test
    void readingAFreeNameHoldsNothing() {
        // The third case, and the one that says the lock costs nothing when no row is judged: a
        // door that reads a FREE name (a create) holds nothing, so a concurrent write elsewhere is
        // untouched. The create race is the unique constraint's job, not this lock's.
        String name = seed("conn-libre-585");

        String refusal = QuarkusTransaction.requiringNew().call(() -> {
            assertThat(connectors.lockedScopesOf(TENANT, "conn-inexistant-585"))
                .as("no row at that address — nothing to hold")
                .isEmpty();
            return tryRehomeFromAnotherTransaction(name);
        });

        assertThat(refusal).as("reading a free name must not have locked some other row").isNull();
    }
}
