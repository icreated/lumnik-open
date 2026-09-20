package io.lumnik.hub.api;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Set;

/**
 * What a scope-guarded upsert was allowed to do — the STORE-side half of {@link ApplyScopeBoundary}.
 *
 * <p>{@code ApplyScopeBoundary} is the door's ordered check: read the scope stored on the name, then
 * write. It is correct single-threaded and POROUS under concurrency — the read takes its own
 * autocommit connection and the upsert runs later on another, against whichever row occupies that
 * name at THAT instant. Between the two, a concurrent apply can put the name in a métier the caller
 * does not hold, and the unguarded {@code ON CONFLICT (…) DO UPDATE} would re-home it: the exact
 * cross-métier write the boundary exists to refuse (#509, #582).
 *
 * <p>The fix is to carry the caller's grants into the write's own {@code WHERE}, so check and write
 * are one atomic statement. This type is what the store then reports back; every store that answers
 * it points here rather than restating the argument, so there is ONE place to correct it.
 *
 * <p>The door turns the refusal into its 403 through
 * {@link ApplyScopeBoundary#refuseUnlessApplied}, which is {@code void} so the answer cannot be had
 * and ignored.
 */
public sealed interface ScopeGuardedUpsert {

    /** Written and committed. */
    record Applied() implements ScopeGuardedUpsert {}

    /**
     * Nothing written. The name is already taken by a row whose scope is outside the caller's
     * grants, so the upsert's conflict arm would have re-homed another métier's manifest.
     *
     * <p><b>Not a refusal RESPONSE</b> — access control lives in the resources, and these stores
     * keep that posture. It reports a FACT about the write: the statement met a row it was not
     * permitted to touch, and nothing was committed.
     *
     * <p><b>The record is empty on purpose.</b> Carrying the refused row's scope would name the
     * métier that owns the name — the one bit {@code ApplyScopeBoundary}'s javadoc is careful to
     * withhold — and such a field sits one careless {@code put} away from an audit snapshot or a
     * response body.
     */
    record NameHeldOutOfScope() implements ScopeGuardedUpsert {}

    /**
     * Reads an affected-row count as an outcome — the single-predicate case, which is every kind but
     * {@code kind:App}.
     *
     * <p>Zero rows is unambiguous there: an {@code INSERT … ON CONFLICT} either inserts or takes the
     * conflict arm, and the only thing that can then refuse it is the guard's {@code WHERE}. So the
     * reading lives here, once, rather than as the same ternary in every store — a second predicate
     * (kind:App's "at least one default" floor) is what makes zero rows ambiguous, and a store that
     * grows one must stop calling this and say why.
     */
    static ScopeGuardedUpsert of(int rowsWritten) {
        return rowsWritten == 0 ? new NameHeldOutOfScope() : new Applied();
    }

    /**
     * Binds the caller's grants to the TWO consecutive placeholders of a guard predicate, starting
     * at {@code first}:
     *
     * <pre>{@code
     *   -- a single-scope kind (View, Process, Workflow)
     *   WHERE (?::text[] IS NULL OR <table>.scope = ANY (?::text[]))
     *   -- a multi-scope kind (Entity)
     *   WHERE (?::text[] IS NULL
     *          OR (<table>.scopes <@ ?::text[] AND cardinality(<table>.scopes) > 0))
     * }</pre>
     *
     * <p>{@code null} grants = unscoped caller = tenant-wide, the same {@code null} every door
     * writes as {@code ScopeGrants.granted().orElse(null)}. The array goes in TWICE because SQL has
     * no named parameters: one occurrence says "unscoped caller, no restriction", the other is the
     * restriction itself. The casts are what let {@code IS NULL} be asked of a parameter at all — an
     * untyped placeholder has no type for Postgres to plan against.
     *
     * <p>Shared by every guarded address so they cannot drift apart in how they read the same
     * {@code null} — five copies of six lines is five chances to invert one of them.
     *
     * <p>The predicates themselves are deliberately NOT built here. A helper returning SQL text
     * would take the column name as a string and hand a reader a statement they have to assemble in
     * their head; written out in the store, each placeholder sits in the same expression as the
     * value bound to it — the SavedViewStore.list reasoning, and what a static analyser can follow.
     *
     * <p><b>Both shapes refuse a row whose scope is NULL or whose scopes are empty</b>, matching
     * {@code ScopeGrants.allowsAll}: {@code NULL = ANY (…)} is NULL, {@code NULL <@ …} is NULL, and
     * neither is true. The {@code cardinality} clause is what makes the array shape agree —
     * {@code '{}' <@ anything} is true, but a scopeless resource is not a bound caller's to
     * overwrite, by the same rule that stops them creating one.
     */
    static void bindGrants(PreparedStatement ps, int first, Set<String> callerScopes)
            throws SQLException {
        if (callerScopes == null) {
            ps.setNull(first, Types.ARRAY);
            ps.setNull(first + 1, Types.ARRAY);
        } else {
            Array grants = ps.getConnection().createArrayOf("text", callerScopes.toArray());
            ps.setArray(first, grants);
            ps.setArray(first + 1, grants);
        }
    }
}
