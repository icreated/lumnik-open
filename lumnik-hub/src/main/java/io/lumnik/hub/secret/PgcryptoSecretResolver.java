package io.lumnik.hub.secret;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.lumnik.common.tenant.CurrentTenantHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
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
 * <p>Uses the {@code admin} datasource (BYPASSRLS) so that reads work even when RLS
 * is active. Filtering is always explicit via {@code WHERE tenant_id = ?}.
 */
@ApplicationScoped
public class PgcryptoSecretResolver implements SecretResolver {

    private static final Logger LOG = Logger.getLogger(PgcryptoSecretResolver.class);

    @Inject
    @io.quarkus.agroal.DataSource("admin")
    DataSource adminDs;

    @Inject
    SecretEvents events;

    @ConfigProperty(name = "lumnik.secret.master-key")
    String masterKey;

    private final Cache<String, String> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
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
        String cached = cache.getIfPresent(cacheKey);
        if (cached != null) return Optional.of(cached);

        try (Connection c = adminDs.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT pgp_sym_decrypt(value_encrypted, ?, 'cipher-algo=aes256') " +
                 "FROM platform.secret " +
                 "WHERE tenant_id = ? AND name = ? AND deleted_at IS NULL")) {
            ps.setString(1, masterKey);
            ps.setLong(2, tenantId);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String value = rs.getString(1);
                cache.put(cacheKey, value);
                events.emitRead(tenantId, name, null);  // SOC 2 audit trail — only on DB read, not cache hit
                return Optional.of(value);
            }
        } catch (SQLException e) {
            throw new RuntimeException("SecretResolver failed for name=" + name + " tenant=" + tenantId, e);
        }
    }

    /** Test hook: invalidate the entire cache (e.g. after manual DB mutation in a test). */
    public void invalidate() {
        cache.invalidateAll();
    }
}
