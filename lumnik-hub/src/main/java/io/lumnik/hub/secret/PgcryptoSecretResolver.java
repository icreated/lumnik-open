package io.lumnik.hub.secret;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.github.benmanes.caffeine.cache.Expiry;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

/**
 * Default {@link SecretResolver} that decrypts secrets stored in {@code platform.secret}
 * using PostgreSQL pgcrypto ({@code pgp_sym_decrypt}) and caches results for 5 minutes
 * via Caffeine to avoid repeated DB round-trips.
 *
 * <p><b>A miss is cached too.</b> Every {@code *_env} key in every manifest resolves through here
 * now, and a connector run rebuilds its reader once per CHUNK — so on the deployment the docs
 * recommend (secrets exported into the hub's environment, nothing in the registry) a name this
 * registry does not hold used to cost one connection acquisition and one zero-row query per chunk,
 * per key, forever. That is what {@code Optional} in the cache value is for: absent means "never
 * asked", {@code Optional.empty()} means "asked, and nothing holds it".
 *
 * <p>Remembering a miss is only safe because every write flushes — <b>in the JVM that served the
 * write</b>. {@code SecretResource} calls {@link #invalidate()} on set, update and delete, so on
 * that instance {@code lm secret set NAME} is visible on the next read. This cache is per-instance
 * and there is no cross-node invalidation, so a second hub replica ({@code hub.replicas} in the
 * Helm values) that had already asked for the name keeps answering from its own copy until the
 * entry expires. That is why a miss expires far sooner than a hit: a stale VALUE was always
 * possible after a rotation, but "this name exists now" used to be visible everywhere at once, and
 * remembering misses is what took that away. {@link #MISS_TTL} is the bound put back on it.
 *
 * <p>Uses the {@code admin} datasource (BYPASSRLS) so that reads work even when RLS
 * is active. Filtering is always explicit via {@code WHERE tenant_id = ?}.
 */
@ApplicationScoped
public class PgcryptoSecretResolver implements SecretResolver {

    private static final Logger LOG = Logger.getLogger(PgcryptoSecretResolver.class);

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    @Inject
    SecretEvents events;

    @Inject
    TransactionSynchronizationRegistry transactions;

    @ConfigProperty(name = "lumnik.secret.master-key")
    String masterKey;

    /** How long a resolved value is reused. Unchanged: this is what the cache has always done. */
    static final Duration HIT_TTL = Duration.ofMinutes(5);

    /**
     * How long "nothing holds this name" is reused. Short on purpose — it only has to outlive a
     * chunk loop, not a work session. At five minutes a replica that had not served the write would
     * refuse a connector run for five minutes after {@code lm secret set}, which is the very
     * complaint this whole change answers.
     */
    static final Duration MISS_TTL = Duration.ofSeconds(30);

    /** A miss expires far sooner than a hit — see the class javadoc for why. */
    static final class SecretTtl implements Expiry<String, Optional<String>> {
        @Override public long expireAfterCreate(String key, Optional<String> value, long now) {
            return (value.isPresent() ? HIT_TTL : MISS_TTL).toNanos();
        }
        @Override public long expireAfterUpdate(String key, Optional<String> value, long now, long current) {
            return expireAfterCreate(key, value, now);
        }
        @Override public long expireAfterRead(String key, Optional<String> value, long now, long current) {
            return current;   // reading does not extend either life
        }
    }

    private final Cache<String, Optional<String>> cache = Caffeine.newBuilder()
        .expireAfter(new SecretTtl())
        .maximumSize(1000)
        .build();

    @Override
    public Optional<String> resolve(String name) {
        Long tenantId = CurrentTenantHolder.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                "SecretResolver.resolve() called without an active tenant context. " +
                "Set a tenant via CurrentTenantHolder.set(tenantId) or TenantContext.callAs(...).");
        }

        String cacheKey = tenantId + ":" + name;
        Optional<String> cached = cache.getIfPresent(cacheKey);
        if (cached != null) return cached;   // null = never asked; empty = asked, held by nothing

        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT pgp_sym_decrypt(value_encrypted, ?, 'cipher-algo=aes256') " +
                 "FROM platform.secret " +
                 "WHERE tenant_id = ? AND name = ? AND deleted_at IS NULL")) {
            ps.setString(1, masterKey);
            ps.setLong(2, tenantId);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    cache.put(cacheKey, Optional.empty());
                    return Optional.empty();   // no secret was read, so there is nothing to audit
                }
                Optional<String> value = Optional.of(rs.getString(1));
                cache.put(cacheKey, value);
                events.emitRead(tenantId, name, null);  // SOC 2 audit trail — only on DB read, not cache hit
                return value;
            }
        } catch (SQLException e) {
            throw new RuntimeException("SecretResolver failed for name=" + name + " tenant=" + tenantId, e);
        }
    }

    /**
     * Flush every entry, hit and miss alike — now, and again once the caller's transaction commits.
     *
     * <p>Flushing once is not enough, and only became not enough when misses started being cached.
     * {@code SecretResource} calls this from INSIDE its {@code @Transactional} write, before commit,
     * and this resolver reads on its own {@code admin} connection, which cannot see an uncommitted
     * row. A {@code resolve()} landing in that window therefore reads "held by nothing" — and would
     * then answer that for the full five minutes to a connector whose secret had in fact just been
     * written. Before misses were remembered the same race healed itself on the next read.
     *
     * <p>Registering the second flush here rather than at the three call sites keeps the guarantee
     * with the cache that needs it: any future writer gets it by calling {@code invalidate()}.
     * Both flushes are local to this instance — {@link #MISS_TTL} is what bounds the others.
     */
    public void invalidate() {
        cache.invalidateAll();
        // getTransactionKey() != null is how CsvIngestService and HybridJsonbWriter ask the same
        // question; it answers for STATUS_UNKNOWN too, which a status comparison does not.
        if (transactions.getTransactionKey() == null) return;
        transactions.registerInterposedSynchronization(new Synchronization() {
            @Override public void beforeCompletion() { /* the flush belongs after the commit */ }
            // Rolled back too: this JVM may have cached a miss that another node's write filled in.
            @Override public void afterCompletion(int status) { cache.invalidateAll(); }
        });
    }
}
