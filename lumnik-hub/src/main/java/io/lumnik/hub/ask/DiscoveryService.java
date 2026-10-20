package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.api.helpers.CredentialResolver;
import io.lumnik.hub.connector.api.types.ResolvedCredential;
import io.lumnik.extraction.spi.ask.DiscoveryPort;
import io.lumnik.extraction.spi.ask.DiscoveryResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * Orchestrates SchemaCard discovery: level-1 registry card, then
 * best-effort level-2 AD enrichment per JDBC connector, then persist. Implements the
 * DiscoveryPort SPI served by {@link SchemaCardAdminResource}.
 */
@ApplicationScoped
public class DiscoveryService implements DiscoveryPort {

    private static final Logger LOG = Logger.getLogger(DiscoveryService.class);

    @Inject RegistrySchemaBuilder registry;
    @Inject AdLabelProvider adLabels;
    @Inject SchemaCardStore store;
    @Inject CredentialResolver credentials;
    @Inject EntitySchemaBuilder entityViews;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    /**
     * Level-1 registry card, best-effort level-2 AD enrichment, then persist. Two passes over two
     * sources share one dedup ledger and one running tally, so each pass reports what it
     * contributed. (S3776: two collection loops and the assembly in one method.)
     */
    @Override
    public DiscoveryResult rediscover(long tenantId, String scope) {
        Set<String> seenFqn = new HashSet<>();
        Collected hub = hubTables(tenantId, scope, seenFqn);
        Collected views = entityViewTables(scope, seenFqn);

        List<SchemaCard.Table> tables = new ArrayList<>(hub.tables());
        tables.addAll(views.tables());

        String sourceType = hub.enriched() ? "jdbc-ad" : "registry";
        // A scope whose last table went (a connector deleted or moved out) keeps no card: the ask
        // then says there is nothing to ask, instead of writing SQL against what is gone (#1188).
        if (tables.isEmpty()) store.delete(tenantId, scope);
        else store.save(tenantId, sourceType, new SchemaCard(scope, tables));
        return new DiscoveryResult(scope, tables.size(),
            hub.columnCount() + views.columnCount(), hub.enriched(), sourceType);
    }

    /** What one pass contributed: its tables, their columns, and whether AD labelled anything. */
    private record Collected(List<SchemaCard.Table> tables, int columnCount, boolean enriched) {}

    /**
     * The hub's own tables, each column labelled from AD when the scope holds a JDBC connector whose
     * credentials resolve. Absent credentials is the ordinary case, not a failure: the column keeps
     * its own name and the card stays a registry card.
     */
    private Collected hubTables(long tenantId, String scope, Set<String> seenFqn) {
        String[] creds = sourceCreds(tenantId, scope);
        boolean enriched = false;
        int columnCount = 0;
        List<SchemaCard.Table> tables = new ArrayList<>();

        for (RegistrySchemaBuilder.HubTable h : registry.resolve(tenantId, scope)) {
            String fqn = h.schema() + "." + h.name();
            if (!seenFqn.add(fqn)) continue;

            Map<String,String> adLabels = adLabelsFor(h, creds);
            List<SchemaCard.Column> cols = new ArrayList<>();
            for (Map.Entry<String,String> e : h.columns().entrySet()) {
                String col = e.getKey();
                // AD keys its map with lower(columnName) — the two sides must agree, or enrichment
                // degrades in silence (no error, just an unlabelled column and a registry card).
                String label = adLabels.get(col.toLowerCase(Locale.ROOT));
                if (label != null) enriched = true; else label = col;
                cols.add(new SchemaCard.Column(col, e.getValue(), label));
                columnCount++;
            }
            tables.add(new SchemaCard.Table(fqn, h.name(), cols));
        }
        return new Collected(tables, columnCount, enriched);
    }

    private Map<String,String> adLabelsFor(RegistrySchemaBuilder.HubTable h, String[] creds) {
        String sourceTable = sourceTableOf(h.sourceEntity());
        if (sourceTable == null || creds == null) return Map.of();
        return adLabels.labelsForTable(creds[0], creds[1], creds[2], sourceTable);
    }

    /** The fused entity views, skipping any whose name a hub table already claimed. */
    private Collected entityViewTables(String scope, Set<String> seenFqn) {
        int columnCount = 0;
        List<SchemaCard.Table> tables = new ArrayList<>();
        for (SchemaCard.Table t : entityViews.resolve(scope)) {
            if (!seenFqn.add(t.name())) continue;
            tables.add(t);
            columnCount += t.columns().size();
        }
        return new Collected(tables, columnCount, false);
    }

    @Override
    public Optional<SchemaCard> load(long tenantId, String scope) {
        return store.load(tenantId, scope);
    }

    /** "public.c_order" → "c_order"; null when not resolvable. */
    private String sourceTableOf(String targetEntity) {
        if (targetEntity == null || targetEntity.isBlank()) return null;
        int dot = targetEntity.lastIndexOf('.');
        return dot >= 0 ? targetEntity.substring(dot + 1) : targetEntity;
    }

    /**
     * Resolve [url, user, password] of the first JDBC connector in the scope that has a
     * url + credential; null if none. Best-effort (returns null on any failure).
     */
    private String[] sourceCreds(long tenantId, String scope) {
        String sql =
            "SELECT c.config->>'url' AS url, c.config->>'user' AS usr, c.credential_id::text AS cid " +
            "FROM connector.connector c " +
            "WHERE c.tenant_id = ? AND ? = ANY(c.tags) AND c.deleted_at IS NULL " +
            "  AND c.type_id LIKE 'jdbc%' AND (c.config->>'url') IS NOT NULL " +
            "  AND c.credential_id IS NOT NULL LIMIT 1";
        try {
            String url, usr, cid;
            try (Connection cx = adminDs.getConnection();
                 PreparedStatement ps = cx.prepareStatement(sql)) {
                ps.setLong(1, tenantId);
                ps.setString(2, scope);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    url = rs.getString("url");
                    usr = rs.getString("usr");
                    cid = rs.getString("cid");
                }
            }
            if (url == null || cid == null) return null;
            // Resolved after the admin connection is released: the resolver opens its own short
            // transaction, so this works for a caller with none (entity apply) and leaves the AD reads
            // that follow with nothing open (#938). Anything but a Basic secret — absent, malformed,
            // another kind — is "no enrichment".
            if (!(credentials.resolve(UUID.fromString(cid)) instanceof ResolvedCredential.Basic b)) return null;
            return new String[]{ url, usr != null ? usr : b.username(), b.password() };
        } catch (Exception e) {
            LOG.warnf("DiscoveryService.sourceCreds failed for scope %s: %s", scope, e.getMessage());
            return null;
        }
    }
}
