package io.lumnik.hub.process;

import io.lumnik.hub.entity.EntityManifest;
import io.lumnik.hub.entity.EntityManifestParser;
import io.lumnik.hub.entity.EntityRegistry;
import io.lumnik.hub.entity.EntityViewCompiler;
import io.lumnik.hub.entity.UnreadableEntityException;
import io.lumnik.hub.sql.ManagedIndex;
import io.lumnik.hub.sql.ManagedIndex.Spec;
import io.lumnik.hub.sql.ManagedIndex.State;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The indexes a Process on an Entity reads through (#1838): on each source, {@code (tenant_id, <match key>)} — the
 * key written as {@link EntityViewCompiler#matchKeyExpressions} writes it, which is what the view groups by and so what
 * the planner can push {@code view_column = ?} down onto (#1833). A bare {@code (tenant_id, col)} would serve a text
 * key only: for a number or a date the view's column is a cast of the source's.
 *
 * <p>Built in the background ({@link MatchIndexReconciler}, and at once after an apply: a
 * {@code CREATE INDEX CONCURRENTLY} takes minutes on a big table and a request must not wait for it). The read is
 * correct without the index, only slow; the doors say so through {@link #pending}.
 *
 */
@ApplicationScoped
public class MatchIndexes {

    private static final Logger LOG = Logger.getLogger(MatchIndexes.class);

    /** The refused indexes already said, once per node: the pass runs every minute. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    @Inject ManagedIndex indexes;
    @Inject FusedRowReader reader;
    @Inject ProcessManifestStore store;
    @Inject EntityRegistry entities;
    private final EntityManifestParser entityParser = new EntityManifestParser();

    /** The Entity behind an entity view, as stored — not this node's copy (#1836); empty when {@code view} is no entity view. */
    Optional<EntityManifest> storedEntity(String view) {
        String name = EntityViewCompiler.entityNameFromView(view);
        return name == null ? Optional.empty() : entities.stored(name).map(stored -> {
            try {
                return entityParser.parse(stored.yaml());
            } catch (RuntimeException unparsable) {
                // only the PARSE is the stored definition's fault; reaching the row failed above, and stays the server's
                throw new UnreadableEntityException(name, unparsable);
            }
        });
    }

    /**
     * The indexes {@code m}'s sources need; empty for an Entity a process cannot be declared on, and for one whose
     * source is not there yet (a table nobody ingested into): there is nothing to index, and the Entity apply is
     * the door that refuses a missing source.
     */
    List<Spec> wanted(EntityManifest m) {
        if (!EntityProcessTargets.servable(m)) return List.of();
        EntityViewCompiler.Sources sources;
        try {
            sources = reader.catalog(m);
        } catch (RuntimeException e) {
            LOG.debugf(e, "managed indexes: the sources of %s cannot be read yet", m.name());
            return List.of();
        }
        List<Spec> specs = new ArrayList<>();
        for (EntityManifest.Source s : m.sources()) {
            List<String> keys = new ArrayList<>();
            keys.add("tenant_id");
            keys.addAll(EntityViewCompiler.matchKeyExpressions(m, sources, s.table()));
            Spec spec = new Spec("connector", s.table(), keys);
            if (!specs.contains(spec) && indexes.tableExists(spec)) specs.add(spec);
        }
        return specs;
    }

    /**
     * The indexes the reverse read of a lookup fan-out needs (#1834): on each source that a join can be read from,
     * {@code (tenant_id, <the join's from key>)}. Empty for an Entity without joins. The read runs on the admin pool, so
     * these are not held to the LEAKPROOF rule of the others: any key PostgreSQL can index serves them.
     */
    List<Spec> reverse(EntityManifest m) {
        if (!EntityProcessTargets.servable(m) || m.joins().isEmpty()) return List.of();
        EntityViewCompiler.Sources sources;
        try {
            sources = reader.catalog(m);
        } catch (RuntimeException e) {
            LOG.debugf(e, "managed indexes: the sources of %s cannot be read yet", m.name());
            return List.of();
        }
        List<Spec> specs = new ArrayList<>();
        for (LookupReach reach : LookupReach.of(m, sources)) {
            Spec spec = reach.index();
            if (!specs.contains(spec) && indexes.tableExists(spec)) specs.add(spec);
        }
        return specs;
    }

    /** Why a join's key cannot be indexed, or empty: such a lookup would fan out as a sequential scan, so the apply refuses it. */
    Optional<String> reverseRefusal(List<Spec> reverse) {
        return reverse.stream().map(spec -> indexes.refusal(spec, false)).flatMap(Optional::stream).findFirst()
            .map(why -> why + " — join the two columns as text, as numbers, or as two dates or two timestamps");
    }

    /** Why a source's key cannot be indexed, or empty. The Process apply door refuses on it: never run as a sequential scan. */
    Optional<String> refusal(List<Spec> wanted) {
        return wanted.stream().map(indexes::refusal).flatMap(Optional::stream).findFirst()
            .map(why -> why + " — use a text, a whole-number or a timestamptz match key");
    }

    /**
     * The same refusal, for an Entity about to be (re-)applied while a process already watches its view: the new shape
     * must not leave an enabled process on a read that can only scan. Nothing to refuse for an Entity nobody watches.
     */
    public Optional<String> refusalBeforeApply(EntityManifest m) {
        if (!store.anyEnabledOn(EntityViewCompiler.VIEW_PREFIX + m.name().toLowerCase(Locale.ROOT))) return Optional.empty();
        return refusal(wanted(m)).or(() -> reverseRefusal(reverse(m)));
    }

    /** One {@code warnings} entry per expected index that is not valid yet. */
    private List<String> pending(List<Spec> wanted) {
        return wanted.stream().filter(s -> indexes.state(s) == State.PENDING)
            .map(s -> "managed index on " + s.schema() + "." + s.table()
                + " is pending; Entity Process reads may be slower until it is ready")
            .toList();
    }

    /** What the Entity apply says: the pending indexes — when some enabled process watches its view. */
    public List<String> afterEntityApplied(EntityManifest m) {
        if (!store.anyEnabledOn(EntityViewCompiler.VIEW_PREFIX + m.name().toLowerCase(Locale.ROOT))) return List.of();
        return pendingAndBuild(m, wanted(m));
    }

    /**
     * Pending indexes now, then their build in the background — said before it starts, so a "pending" is never a lie.
     * The reverse-lookup indexes are built the same way but never warned about: a fan-out waits for its index
     * ({@code waiting-index}), it does not run slowly without it.
     */
    List<String> pendingAndBuild(EntityManifest m, List<Spec> wanted) {
        try {
            List<String> pending = pending(wanted);
            List<Spec> reverse = reverse(m);
            boolean reverseToBuild = reverse.stream().anyMatch(s -> indexes.state(s) == State.PENDING);
            if (!pending.isEmpty() || reverseToBuild) {
                Thread.ofVirtual().name("managed-index-" + m.name()).start(() -> {
                    ensure(wanted);
                    ensureReverse(reverse);
                });
            }
            return pending;
        } catch (RuntimeException e) {
            // the apply is done; an index that cannot be asked about is the next pass's to build
            LOG.warnf(e, "managed indexes of %s: could not be checked", m.name());
            return List.of();
        }
    }

    /** One pass over everything the Entity's processes read through: its read indexes, and its reverse-lookup ones. */
    void ensureAll(EntityManifest m) {
        ensure(wanted(m));
        ensureReverse(reverse(m));
    }

    /** One pass over the Entity's indexes; a failure is logged, never thrown: the next pass tries again. */
    void ensure(List<Spec> wanted) {
        ensure(wanted, true);
    }

    /** The same pass over the reverse-lookup indexes, whose reader is not under row-level security. */
    void ensureReverse(List<Spec> reverse) {
        ensure(reverse, false);
    }

    private void ensure(List<Spec> wanted, boolean underRls) {
        for (Spec spec : wanted) {
            try {
                // A process applied before #1838 may sit on a key no index can serve: it keeps running, slowly, and the hub
                // builds nothing for it (the apply doors refuse that key from now on).
                Optional<String> refused = indexes.refusal(spec, underRls);
                if (refused.isPresent()) {
                    if (reported.add(spec.name())) LOG.warnf("%s — no index is built for it; change the match key of the Entity", refused.get());
                    continue;
                }
                indexes.ensure(spec);
            } catch (Exception e) {
                LOG.warnf(e, "managed index on %s.%s failed — reads are slower without it, the next pass retries",
                    spec.schema(), spec.table());
            }
        }
    }
}
