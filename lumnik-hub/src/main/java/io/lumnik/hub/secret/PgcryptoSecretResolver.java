package io.lumnik.hub.secret;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Types;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Default {@link SecretResolver} that decrypts secrets stored in {@code platform.secret}
 * using PostgreSQL pgcrypto ({@code pgp_sym_decrypt}).
 *
 * <p><b>Each read compares the row's ciphertext with the one decrypted last; only a new one is decrypted</b>
 * (#2020). {@code pgp_sym_encrypt} salts every write, so a set or a rotation always writes new bytes, and the same
 * bytes, under the one master key, always decrypt to the same value: the ciphertext is the row's exact version,
 * whichever code wrote it. A read is one indexed lookup, which returns the ciphertext, whether the secret serves the
 * reader, and the decrypted value only when the ciphertext is not the one this replica holds. So a write on any hub
 * replica reaches every reader of every replica at its next read: a rotation, a narrowed scope, a deletion, a secret
 * set after a read found nothing. There is no signal to miss and nothing to flush; nothing is remembered of a miss or
 * a refusal. A reader reads most secrets once per chunk, a SOAP body once per request, an S3 transport or an export
 * destination once per run, an SFTP transport when it opens a session; a value it already holds stays in use until it
 * reads again.
 *
 * <p>What was decrypted is kept per tenant and name, at most {@link #KEPT_TTL} after its decrypt: whether the secret
 * serves the reader is asked on every read, so every reader of a secret shares it. The bound is this cache's: a
 * reader keeps the value it was handed for as long as it uses it (a JDBC pool, a transport, a run).
 *
 * <p><b>A read is audited once per value, per reader's scopes and per {@link #KEPT_TTL}</b>
 * ({@code platform.secret.read}): a run reads under {@code TenantScope}, whose tenant the outbox records under, and
 * one event per secret per chunk would drown the trail. A rotated value is a new one, so its first read is recorded. The
 * mark is set once the outbox reports the event written: a read it did not write leaves the next one to record. Two
 * reads at once may both be recorded; a read recorded twice is harmless, one lost is not. The mark is local, and an
 * event written in the caller's transaction is lost with it if that transaction rolls back, the mark kept.
 *
 * <p><b>A reader reads a secret only when it carries every scope of the secret</b> ({@link SecretAudience},
 * #2015); a scopeless reader reads every secret, a scopeless secret serves the scopeless readers only. A name
 * the reader cannot use is refused by name ({@link SecretNotServedException}), not answered as a miss:
 * {@code lm secret list} shows it, and "not set" would send the admin looking for the wrong thing.
 *
 * <p>Uses the {@code admin} datasource (BYPASSRLS) so that reads work even when RLS
 * is active. Filtering is always explicit via {@code WHERE tenant_id = ?}.
 */
@ApplicationScoped
public class PgcryptoSecretResolver implements SecretResolver {

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Inject
    SecretEvents events;

    @ConfigProperty(name = "lumnik.secret.master-key")
    String masterKey;

    /** How long a decrypted value is kept, and a read's audit mark. */
    static final Duration KEPT_TTL = Duration.ofMinutes(5);

    /** Room for every secret a hub reads in rotation: evicted, a value is decrypted and its read recorded again. */
    private static final int KEPT_MAX = 10_000;

    /** A record, not a joined string: no name, whatever it holds, can spell another secret's key. */
    record Key(long tenantId, String name) {}

    /** One reader's reads of a secret: what an audit mark is kept for. */
    private record Reader(long tenantId, List<String> audience, String name) {}

    /** What was decrypted, and the ciphertext it came from. */
    private record Kept(byte[] ciphertext, String value) {}

    /** One read: the row's ciphertext, whether it serves the reader, its value if it was decrypted. */
    private record Row(byte[] ciphertext, boolean served, String decrypted) {}

    private final Cache<Key, Kept> kept = Caffeine.newBuilder()
        .expireAfterWrite(KEPT_TTL)
        .maximumSize(KEPT_MAX)
        .build();

    /** The ciphertext each reader's read was last recorded for, within {@link #KEPT_TTL}. */
    private final Cache<Reader, byte[]> audited = Caffeine.newBuilder()
        .expireAfterWrite(KEPT_TTL)
        .maximumSize(KEPT_MAX)
        .build();

    /**
     * The row's ciphertext, whether it serves the reader, and its value decrypted only when it serves and its ciphertext
     * is not the one held (the first parameter, NULL when none is). ScopeGrants' rule, the reader as the holder: a
     * scopeless reader is served by every secret, a scoped one by a secret with scopes, all of which it carries; a
     * secret that does not serve the reader is never decrypted.
     */
    private static final String READ_SQL = "SELECT value_encrypted, served, CASE WHEN served "
        + "AND value_encrypted IS DISTINCT FROM ? THEN pgp_sym_decrypt(value_encrypted, ?, 'cipher-algo=aes256') END "
        + "FROM (SELECT value_encrypted, (? OR (scopes <> '{}' AND scopes <@ ?)) AS served "
        + "FROM platform.secret WHERE tenant_id = ? AND name = ? AND deleted_at IS NULL) s";

    @Override
    public Optional<String> resolve(String name) {
        Long tenantId = CurrentTenantHolder.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                "SecretResolver.resolve() called without an active tenant context. " +
                "Set a tenant via CurrentTenantHolder.set(tenantId) or TenantContext.callAs(...).");
        }

        List<String> audience = SecretAudience.current();
        Key key = new Key(tenantId, name);
        Kept held = kept.getIfPresent(key);
        // Named in the label: a wrong master key or a damaged row says which secret of which tenant.
        Optional<Row> read = Jdbc.queryOne(adminDs, "reading secret " + name + " (tenant " + tenantId + ")",
            READ_SQL, ps -> {
                ps.setObject(1, held == null ? null : held.ciphertext(), Types.BINARY);
                ps.setString(2, masterKey);
                ps.setBoolean(3, audience.isEmpty());
                ps.setArray(4, ps.getConnection().createArrayOf("text", audience.toArray()));
                ps.setLong(5, tenantId);
                ps.setString(6, name);
            }, rs -> new Row(rs.getBytes(1), rs.getBoolean(2), rs.getString(3)));
        if (read.isEmpty()) {
            kept.invalidate(key);
            return Optional.empty();   // no secret was read, so there is nothing to audit
        }
        Row row = read.get();
        if (!row.served()) throw new SecretNotServedException(name, audience);
        String value;
        if (row.decrypted() != null) {
            value = row.decrypted();
            kept.put(key, new Kept(row.ciphertext(), value));
        } else {
            value = held.value();   // the ciphertext held: not decrypted again
        }
        // SOC 2 audit trail, once the admin connection is back: publish takes one of its own.
        Reader reader = new Reader(tenantId, audience, name);
        if (!Arrays.equals(audited.getIfPresent(reader), row.ciphertext()) && events.emitRead(tenantId, name, null)) {
            audited.put(reader, row.ciphertext());   // marked once written: an unwritten read leaves the next to record
        }
        return Optional.of(value);
    }
}
