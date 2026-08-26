package io.lumnik.hub.api.secret;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.audit.AuditOutboxRow;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1. Every production write to {@code platform.secret} goes through {@link SecretResource} via
 * {@code em.createNativeQuery(...)} — native SQL bypasses the persistence context entirely, so
 * {@code @PostPersist}/{@code @PostUpdate} on {@link io.lumnik.hub.secret.Secret} can never fire
 * on this route. {@code AuditWiringTest}'s Secret cases proved the listener works when a Secret
 * is persisted through Hibernate directly — a real path, but not the one production takes (that
 * is exactly why {@code @Auditable} has been removed from {@link io.lumnik.hub.secret.Secret},
 * see its javadoc).
 * <p>
 * This test exercises the real production route — the REST endpoints — and asserts that each of
 * the three call sites in {@link SecretResource} records explicitly, the same way
 * {@code EntityReadResource.read()} does for reads: CREATE, UPDATE (rotate), DELETE. It also pins
 * the one thing that must never happen: the snapshot must never carry the ciphertext or the
 * plaintext value, in any encoding, under any key.
 * <p>
 * The ciphertext check reads the real {@code value_encrypted} bytes back from
 * {@code platform.secret} (pgcrypto generates them in-DB — this test never sees them any other
 * way) and checks both the raw bytes and their base64 form, because {@code recordAudit()}'s
 * snapshot is serialized to JSONB by Jackson, which base64-encodes a stray {@code byte[]} rather
 * than leaving it as one — a check that only looked for {@code instanceof byte[]} would let a
 * base64-encoded ciphertext leak straight through under a different key name.
 */
@QuarkusTest
class SecretResourceAuditTest {

    @Inject EntityManager em;
    @Inject @io.quarkus.agroal.DataSource("admin") AgroalDataSource adminDs;

    @Test
    void create_records_a_CREATE_row_without_the_ciphertext_or_plaintext() {
        String name = "AUDIT_CREATE_" + System.nanoTime();
        String plaintext = "top-secret-plaintext-value-must-never-leak";

        RestAssured.given()
            .header("X-Tenant-Id", "1")
            .contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"" + plaintext + "\",\"description\":\"audit pin\"}")
            .post("/api/platform/secrets")
            .then().statusCode(201);

        AuditOutboxRow row = latestRowForName(name, "CREATE");
        assertNotNull(row, "expected SecretResource.create() to record a CREATE audit row — "
            + "native SQL bypasses @PostPersist entirely, so nothing else will");
        assertEquals("Secret", row.getEntityType());
        assertNeverLeaks(row.getChanges(), plaintext, fetchCiphertext(name));
    }

    @Test
    void update_rotate_records_an_UPDATE_row_with_rotatedAt_and_no_ciphertext_or_plaintext() {
        String name = "AUDIT_ROTATE_" + System.nanoTime();
        String plaintext = "second-top-secret-plaintext-value";

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"v1\"}")
            .post("/api/platform/secrets").then().statusCode(201);

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"value\":\"" + plaintext + "\",\"description\":\"rotated\"}")
            .put("/api/platform/secrets/" + name).then().statusCode(204);

        AuditOutboxRow row = latestRowForName(name, "UPDATE");
        assertNotNull(row, "expected SecretResource.update() (rotate) to record an UPDATE audit row");
        assertNotNull(row.getChanges().get("rotatedAt"), "the rotation must be visible in the snapshot");
        assertEquals("rotated", row.getChanges().get("description"));
        assertNeverLeaks(row.getChanges(), plaintext, fetchCiphertext(name));
    }

    @Test
    void delete_records_a_DELETE_row() {
        String name = "AUDIT_DELETE_" + System.nanoTime();

        RestAssured.given().header("X-Tenant-Id", "1").contentType("application/json")
            .body("{\"name\":\"" + name + "\",\"value\":\"v1\"}")
            .post("/api/platform/secrets").then().statusCode(201);

        RestAssured.given().header("X-Tenant-Id", "1")
            .delete("/api/platform/secrets/" + name).then().statusCode(204);

        AuditOutboxRow row = latestRowForName(name, "DELETE");
        assertNotNull(row, "expected SecretResource.delete() to record a DELETE audit row");
    }

    /** The security pin: no key in the snapshot may hold the plaintext, the raw ciphertext
     *  bytes, or the ciphertext's base64 encoding — under any name, and not even embedded inside
     *  a larger string — a leak under a different key name (or a different encoding) is still a
     *  leak. */
    private static void assertNeverLeaks(Map<String, Object> snapshot, String plaintext, byte[] ciphertext) {
        String ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext);
        assertFalse(snapshot.containsKey("valueEncrypted"), "snapshot must never carry 'valueEncrypted'");
        for (Map.Entry<String, Object> e : snapshot.entrySet()) {
            Object value = e.getValue();
            assertFalse(value instanceof byte[],
                "snapshot key '" + e.getKey() + "' holds a raw byte[] — ciphertext leak");
            assertNotEquals(plaintext, value,
                "snapshot key '" + e.getKey() + "' holds the plaintext value — that is a leak");
            assertNotEquals(ciphertextBase64, value,
                "snapshot key '" + e.getKey() + "' holds the base64-encoded ciphertext — that is a leak");
            if (value instanceof String s) {
                assertFalse(s.contains(ciphertextBase64),
                    "snapshot key '" + e.getKey() + "' contains the base64-encoded ciphertext embedded "
                        + "in a larger string — that is still a leak");
            }
        }
    }

    /** Reads the real {@code value_encrypted} bytes back from the database — pgcrypto generates
     *  them in-DB, so this is the only way the test can know what ciphertext to check for. Uses
     *  the {@code admin} datasource the same way {@link SecretResource#recordAudit} does. */
    private byte[] fetchCiphertext(String name) {
        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT value_encrypted FROM platform.secret "
               + "WHERE tenant_id = 1 AND name = ? AND deleted_at IS NULL")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "expected a platform.secret row for '" + name + "'");
                return rs.getBytes(1);
            }
        } catch (Exception e) {
            throw new RuntimeException("could not fetch ciphertext for '" + name + "'", e);
        }
    }

    private AuditOutboxRow latestRowForName(String name, String operation) {
        List<AuditOutboxRow> rows = em.createQuery(
                "select r from AuditOutboxRow r where r.entityType = 'Secret' and r.operation = :op "
              + "order by r.id desc", AuditOutboxRow.class)
            .setParameter("op", operation)
            .setMaxResults(50)
            .getResultList();
        return rows.stream().filter(r -> name.equals(r.getChanges().get("name"))).findFirst().orElse(null);
    }
}
