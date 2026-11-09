package io.lumnik.hub.process;

import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The processes a change in a source table reaches through a {@code match:} Entity (#1833): the enabled ones whose
 * {@code spec.on.table} is the view of an Entity that fuses the table. Asked by the emission gate (does anyone want
 * {@code data.row.changed} for this table?) and by the consumer (which fused rows must be evaluated?), so both answer
 * from the same rule.
 *
 * <p>A change in a LOOKUP table of the Entity ({@code joins:}, #1834) reaches the fused rows through {@link #joining}:
 * durable work, not an evaluation in the event.
 *
 * <p>An Entity this cannot serve is not a target: a {@code resolution:} one — the apply door refuses it, and an Entity
 * re-applied into that shape afterwards leaves its process listening on nothing, which is said once per lookup, not
 * silently.
 */
@ApplicationScoped
public class EntityProcessTargets {

    private static final Logger LOG = Logger.getLogger(EntityProcessTargets.class);

    @Inject ProcessManifestStore store;
    @Inject EntityRegistry entities;

    /** A process and the Entity whose fused rows it evaluates. */
    public record Target(ProcessManifestStore.StoredProcess process, EntityManifest entity) {}

    /**
     * @param sourceTable schema-qualified, as the event names it ({@code connector.t_orders})
     */
    public List<Target> observing(long tenantId, String sourceTable) {
        return targets(tenantId, view -> fusing(view, sourceTable));
    }

    /**
     * The processes a change in a lookup table reaches (#1834): the enabled ones on an Entity that joins it.
     *
     * @param lookupTable schema-qualified, as the event names it ({@code connector.t_instruments})
     */
    public List<Target> joining(long tenantId, String lookupTable) {
        return targets(tenantId, view -> joined(view, lookupTable));
    }

    /** The tenant's enabled processes whose Entity {@code matching} answers for: the servable ones are targets. */
    private List<Target> targets(long tenantId, Function<String, EntityManifest> matching) {
        List<Target> out = new ArrayList<>();
        for (ProcessManifestStore.StoredProcess p : store.findEnabledOnEntityViews(tenantId)) {
            EntityManifest m = matching.apply(p.onTable());
            if (m == null) continue;
            if (!servable(m)) {
                warnUnserved(p);
                continue;
            }
            out.add(new Target(p, m));
        }
        return out;
    }

    private static void warnUnserved(ProcessManifestStore.StoredProcess p) {
        LOG.warnf("process '%s' listens on %s, an Entity with resolution: — it is not evaluated (declare it on a match: Entity)",
            p.name(), p.onTable());
    }

    /**
     * Does any enabled process of the tenant listen on an Entity that fuses {@code sourceTable}? The emission gate asks
     * it for every table of every write batch, so it reads the views the processes name and no manifest.
     */
    public boolean anyObserving(long tenantId, String sourceTable) {
        for (String view : store.enabledEntityViews(tenantId)) {
            EntityManifest m = fusing(view, sourceTable);
            if (m == null) m = joined(view, sourceTable);   // a lookup of the Entity: its changes fan out (#1834)
            if (m != null && servable(m)) return true;
        }
        return false;
    }

    /** How long a name the registry could not find stays unasked: it reloads EVERY manifest to say so. */
    static final long MISS_MILLIS = 30_000;

    /** The clock of the miss memory; a field so a test can move it. */
    LongSupplier nowMillis = System::currentTimeMillis;

    private final Map<String, Long> missingUntil = new ConcurrentHashMap<>();

    /**
     * The Entity behind {@code view}, or null. The emission gate asks it for every table of every write batch, and a process
     * left enabled on the view of an Entity that is gone would make each ask a reload of every manifest (#1845): the miss is
     * remembered for {@link #MISS_MILLIS}. A hit is never remembered, and an Entity applied on this node is in the registry
     * at once; one applied on another node is in it within the registry's refresh (#1836), and seen here after at most
     * that long more.
     */
    private EntityManifest entityOf(String view) {
        String name = EntityViewCompiler.entityNameFromView(view);
        if (name == null) return null;
        EntityManifest known = entities.find(name);
        if (known != null) return known;
        Long until = missingUntil.get(name);
        if (until != null && nowMillis.getAsLong() < until) return null;
        EntityManifest m = entities.findReloadingIfMissing(name);
        if (m == null) missingUntil.put(name, nowMillis.getAsLong() + MISS_MILLIS);
        else missingUntil.remove(name);
        return m;
    }

    /** The Entity behind {@code view} when it fuses {@code sourceTable}; null otherwise. */
    private EntityManifest fusing(String view, String sourceTable) {
        EntityManifest m = entityOf(view);
        boolean fuses = m != null && m.sources() != null && m.sources().stream().anyMatch(s -> sourceTable.equals(s.fqn()));
        return fuses ? m : null;
    }

    /** The Entity behind {@code view} when one of its {@code joins:} reads {@code lookupTable}; null otherwise. */
    private EntityManifest joined(String view, String lookupTable) {
        EntityManifest m = entityOf(view);
        boolean joins = m != null && m.joins() != null
            && m.joins().stream().anyMatch(j -> lookupTable.equals(EntityViewCompiler.SCHEMA + "." + j.table()));
        return joins ? m : null;
    }

    /** What a Process can be declared on: a {@code match:} Entity, with or without lookups. The apply door asks it too. */
    public static boolean servable(EntityManifest m) {
        return m.resolution() == null;
    }

}
