package io.lumnik.hub.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agroal.api.AgroalDataSource;
import io.lumnik.common.sql.Jdbc;
import io.lumnik.hub.api.ScopeGuardedUpsert;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Declared kind:App manifests — the registry the PWA reads its own identity from. Direct SQL over
 * the admin datasource (the SavedViewStore shape; no cache, the list is small and cold-path). Rows
 * are deployment-global; access control lives in the resources.
 */
@ApplicationScoped
public class AppRegistrationStore {

    /**
     * The wire shape of one app. {@code default} is a Java keyword, so the record component cannot
     * carry that name and the annotation is what holds the contract the PWA branches on.
     */
    public record Row(String name, String scope, String title, String icon,
                      String accentColor, String backgroundColor,
                      @JsonProperty("default") boolean isDefault) {}

    /**
     * What {@link #upsert} was allowed to do. A sealed family rather than one record carrying a
     * boolean: the demoted name can only be read by naming every refused case too, so a caller
     * cannot reach the field it wants and quietly skip the cases it does not.
     *
     * <p>No arm is a refusal RESPONSE — access control lives in the resources, and this store
     * keeps that posture. Each reports a FACT about the write: the statement met a row it was not
     * permitted to touch, and nothing was committed.
     *
     * <p><b>The refusals are separate records rather than one carrying the refused address.</b>
     * They are separate FACTS — "the default flag is held out of reach", "the name is held out of
     * reach" and "this write would leave no default at all" are different writes refused for
     * different reasons — and a record with no components offers nothing to read. That matters
     * here: the refusal the door speaks is byte-identical for the two SCOPE arms precisely so a
     * caller cannot learn WHICH address exists, and a field naming it would be a value sitting one
     * careless {@code snapshot.put} away from the audit record or the response body. Naming the
     * arms costs one more {@code case} and leaks nothing.
     */
    public sealed interface Upserted {

        /** Written and committed; {@code demotedDefault} names the app that lost the flag, if any. */
        record Applied(Optional<String> demotedDefault) implements Upserted {}

        /**
         * Nothing written — rolled back. The default flag is held by a row whose scope is outside
         * the caller's grants, so demoting it would have been a cross-métier write.
         */
        record DefaultHeldOutOfScope() implements Upserted {}

        /**
         * Nothing written — rolled back. The name is already taken by a row whose scope is outside
         * the caller's grants, so the upsert's conflict arm would have re-homed another métier's
         * app.
         */
        record NameHeldOutOfScope() implements Upserted {}

        /**
         * Nothing written — rolled back. The row this name addresses currently carries the default
         * flag and the incoming manifest does not, so the write would have left the deployment
         * with NO default and an absent {@code ?m=} resolving to nothing.
         *
         * <p>Not a scope refusal, and deliberately a separate arm rather than a third dress on the
         * frozen 403: it says nothing about a row the caller cannot see — the row is inside their
         * grants, which is the only way this arm is ever reached — so the door may, and does,
         * answer it in plain words.
         */
        record LastDefaultWouldBeRemoved() implements Upserted {}
    }

    /**
     * What {@link #delete} was allowed to do — the delete door's twin of {@link Upserted}. No arm
     * is a refusal RESPONSE, for the same reason {@code Upserted}'s javadoc gives: access control
     * lives in the resources, and each arm here reports a FACT about the write.
     */
    public sealed interface Deleted {

        /** Removed and committed. */
        record Removed() implements Deleted {}

        /** Nothing to delete — no row carries this name. */
        record NotFound() implements Deleted {}

        /** Nothing deleted. The row's scope is outside the caller's grants. */
        record HeldOutOfScope() implements Deleted {}

        /**
         * Nothing deleted. This row currently carries the default flag, and removing it would
         * leave the deployment with NO default and an absent {@code ?m=} resolving to nothing —
         * the same floor {@link Upserted.LastDefaultWouldBeRemoved} protects on the write side.
         */
        record IsTheLastDefault() implements Deleted {}
    }

    private static final String COLUMNS =
        "name, scope, title, icon, accent_color, background_color, is_default";

    @Inject
    @DataSource("admin")
    Instance<AgroalDataSource> adminDs;

    /** scopes == null → all (unscoped caller); empty set → none (bound to nothing). */
    public List<Row> list(Set<String> scopes) {
        if (scopes != null && scopes.isEmpty()) return List.of();
        // Two whole statements rather than one with a conditionally-appended WHERE, the
        // SavedViewStore.list reasoning: each placeholder sits in the same expression as the value
        // bound to it, which is what a reader — and a static analyser — can follow.
        try (Connection c = adminDs.get().getConnection()) {
            if (scopes == null) {
                try (PreparedStatement ps = c.prepareStatement(
                         "SELECT " + COLUMNS + " FROM connector.app_registration ORDER BY name")) {
                    return rows(ps);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                     + " FROM connector.app_registration WHERE scope = ANY (?) ORDER BY name")) {
                ps.setArray(1, c.createArrayOf("text", scopes.toArray()));
                return rows(ps);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("AppRegistrationStore.list: " + e, e);
        }
    }

    private static List<Row> rows(PreparedStatement ps) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(read(rs));
        }
        return out;
    }

    private static Row read(ResultSet rs) throws SQLException {
        return new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getBoolean(7));
    }

    /** Lowercased on read because {@link #upsert} lowercases on write — one row per name. */
    public Optional<Row> find(String name) {
        return Jdbc.queryOne(adminDs.get(), "AppRegistrationStore.find",
            "SELECT " + COLUMNS + " FROM connector.app_registration WHERE name = ?",
            ps -> ps.setString(1, name.toLowerCase(Locale.ROOT)),
            AppRegistrationStore::read);
    }

    /** The scope of the row this name addresses, or empty when the name is free — the apply
     *  door's re-homing check wants one column, not the whole row. */
    public Optional<String> findScope(String name) {
        return Jdbc.queryOne(adminDs.get(), "AppRegistrationStore.findScope",
            "SELECT scope FROM connector.app_registration WHERE name = ?",
            ps -> ps.setString(1, name.toLowerCase(Locale.ROOT)),
            rs -> rs.getString(1));
    }

    /** The app an absent {@code ?m=} resolves to, deployment-wide. */
    public Optional<String> findDefaultName() {
        return Jdbc.queryOne(adminDs.get(), "AppRegistrationStore.findDefaultName",
            "SELECT name FROM connector.app_registration WHERE is_default",
            ps -> { },
            rs -> rs.getString(1));
    }

    /**
     * Deletes this app, refusing atomically — in the DELETE statement's own {@code WHERE}, not a
     * read-then-act check — when the row is outside the caller's grants or currently carries the
     * default flag. One statement is enough here, unlike {@link #upsert}: there is nothing to
     * compose with the delete, so this needs none of {@code upsert}'s hand-managed transaction,
     * and goes through the ordinary {@link Jdbc} helpers every other read in this store uses.
     *
     * <p>Zero rows affected is ambiguous the same way the conflict arm's is — unknown name, scope
     * refused, or the floor refused — so a disambiguating read follows on a zero result, same
     * priority as {@link #whyTheConflictArmRefused}: the scope answer comes first, because a row
     * outside the caller's grants must not learn it exists AND carries the flag.
     *
     * @param callerScopes an authorization predicate, not a filter — {@code null} = unscoped caller
     */
    public Deleted delete(String name, Set<String> callerScopes) {
        String key = name.toLowerCase(Locale.ROOT);
        int rows = Jdbc.update(adminDs.get(), "AppRegistrationStore.delete",
            "DELETE FROM connector.app_registration WHERE name = ? "
                + "AND (?::text[] IS NULL OR scope = ANY (?::text[])) AND NOT is_default",
            ps -> {
                ps.setString(1, key);
                ScopeGuardedUpsert.bindGrants(ps, 2, callerScopes);
            });
        if (rows > 0) return new Deleted.Removed();
        return whyTheDeleteRefused(key, callerScopes);
    }

    private Deleted whyTheDeleteRefused(String key, Set<String> callerScopes) {
        return Jdbc.queryOne(adminDs.get(), "AppRegistrationStore.delete",
            "SELECT scope, is_default FROM connector.app_registration WHERE name = ?",
            ps -> ps.setString(1, key),
            rs -> {
                if (callerScopes != null && !callerScopes.contains(rs.getString(1))) {
                    return new Deleted.HeldOutOfScope();
                }
                if (rs.getBoolean(2)) return new Deleted.IsTheLastDefault();
                throw new IllegalStateException("AppRegistrationStore.delete: the delete refused "
                    + key + " for neither of its two predicates — scope and flag both read as "
                    + "deletable, so the WHERE and this read disagree");
            }).orElseGet(Deleted.NotFound::new);
    }

    /**
     * Upsert this app and, when it claims the default flag, demote whoever held it — in ONE unit of
     * work. Reports the demoted app's name, so the audit record can say what this apply cost
     * someone else.
     *
     * <p><b>{@code callerScopes} is an authorization predicate, not a filter</b> ({@code null} =
     * unscoped caller, tenant-wide), and it guards BOTH addresses this method writes to: the row
     * that loses the default flag, and the row the name overwrites. The apply door checks each of
     * them before calling here, and both checks are correct single-threaded and POROUS under
     * concurrency: they read on their own autocommit connection, while the statements below run
     * later in a different transaction and hit whichever row occupies that address at THAT instant.
     * Between the two, another transaction can promote — or create under this name — an app in a
     * métier the caller does not hold, and the write would then rewrite a row it can neither read
     * nor write. Carrying the caller's grants into each statement's own WHERE makes check and write
     * one atomic act, so the window has nothing to open onto. Do not "simplify" the predicates away
     * because the resource already asked: the resource asks earlier, these ask at the moment of
     * writing, and only the second one cannot be jumped.
     *
     * <p><b>The conflict arm carries a second, non-scope predicate: the "at least one default"
     * floor.</b> The door refuses an apply that clears the flag from the current default, and that
     * check is porous in exactly the same way — it reads the holder on its own connection, and a
     * concurrent apply can promote THIS name to default in between, after which the conflict arm
     * would clear the flag it just acquired and commit a deployment with zero defaults. So the arm
     * also refuses {@code existing.is_default AND NOT excluded.is_default}. A row carrying the flag
     * is necessarily the only one carrying it ({@code app_registration_one_default} is UNIQUE), so
     * that shape is always the last default, never merely one of several.
     *
     * <p>The admin datasource is configured {@code transactions=disabled} (no JTA enlistment), so
     * {@code @Transactional} would do nothing here and the unit of work is taken by hand — the
     * WorkflowObservationStore.recordTransition idiom. <b>Demote before insert, never after:</b>
     * {@code app_registration_one_default} is a partial UNIQUE index, so two rows carrying
     * {@code is_default} is a constraint violation even for the length of one statement, not a
     * transient state Postgres will tolerate until commit.
     *
     * <p><b>Two concurrent {@code default: true} applies end in a 500, not a 409</b>, and that is
     * known rather than overlooked. R1 demotes the old holder and inserts its own default, then
     * commits. R2's demote was blocked on that same old row; when the lock is released READ
     * COMMITTED re-evaluates its predicate against the new row version (EvalPlanQual), where
     * {@code is_default} is now false — so the row no longer matches — and R1's brand-new default
     * row is invisible to R2's statement snapshot. R2's demote therefore touches ZERO rows, and
     * its own insert trips the partial unique index. The outcome is an ugly error for one
     * integrator; there is no corruption and no window with zero or two defaults, because both
     * transactions are atomic. Taking {@code SELECT … WHERE is_default FOR UPDATE} before the
     * demote would serialise the pair and turn the 500 into an ordinary second apply — deliberately
     * NOT done here: changing locking behaviour deserves its own change, and this is a rare,
     * integrator-only path.
     */
    public Upserted upsert(AppManifest m, String yaml, Set<String> callerScopes) {
        String key = m.name().toLowerCase(Locale.ROOT);
        try (Connection c = adminDs.get().getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Optional<String> demoted = Optional.empty();
                if (m.isDefault()) {
                    demoted = demoteCurrentDefault(c, key, callerScopes);
                    // Empty RETURNING says one of two things: nothing held the flag, or something
                    // did and the scope predicate refused it. This second read — same transaction,
                    // so it cannot be raced by the very window the predicate closes — tells them
                    // apart. A holder that survived our UPDATE is a holder we were not allowed to
                    // touch. Skipped for an unscoped caller, whose predicate refuses nothing: there
                    // the empty result can only mean an empty flag, and asking again would turn the
                    // known concurrent-apply 500 below into a mystery 403.
                    if (demoted.isEmpty() && callerScopes != null && defaultIsHeld(c, key)) {
                        c.rollback();
                        return new Upserted.DefaultHeldOutOfScope();
                    }
                }
                // The conflict arm carries the same predicate, against the EXISTING row — which in
                // a DO UPDATE is the one spelled with the table name, `excluded` being the row we
                // proposed. Re-homing a name is the write ApplyScopeBoundary exists to refuse, and
                // the door's ordered findScope ahead of this call is porous for the same reason the
                // demote's was.
                //
                // …and a SECOND predicate, the "at least one default" floor, porous at the door for
                // the same reason again. `is_default AND NOT excluded.is_default` is the existing
                // row losing the flag with nothing taking it — the last default, since the partial
                // unique index admits no second holder. When the manifest itself says default:true
                // the clause cannot fire, so it never interferes with the demote above.
                try (PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO connector.app_registration (" + COLUMNS + ", yaml_text) "
                             + "VALUES (?,?,?,?,?,?,?,?) "
                             + "ON CONFLICT (name) DO UPDATE SET scope = EXCLUDED.scope, "
                             + "title = EXCLUDED.title, icon = EXCLUDED.icon, "
                             + "accent_color = EXCLUDED.accent_color, "
                             + "background_color = EXCLUDED.background_color, "
                             + "is_default = EXCLUDED.is_default, "
                             + "yaml_text = EXCLUDED.yaml_text, updated_at = now() "
                             + "WHERE (?::text[] IS NULL "
                             + "OR connector.app_registration.scope = ANY (?::text[])) "
                             + "AND NOT (connector.app_registration.is_default "
                             + "AND NOT EXCLUDED.is_default)")) {
                    ps.setString(1, key);
                    ps.setString(2, m.scope());
                    ps.setString(3, m.title());
                    ps.setString(4, m.icon());
                    ps.setString(5, m.accentColor());
                    ps.setString(6, m.backgroundColor());
                    ps.setBoolean(7, m.isDefault());
                    ps.setString(8, yaml);
                    ScopeGuardedUpsert.bindGrants(ps, 9, callerScopes);
                    // Zero rows used to be UNAMBIGUOUS here — one predicate, one reason — and the
                    // comment that stood in this place said so, and said no second read was needed.
                    // The floor clause made that false: zero rows now means the name was taken and
                    // ONE OF THE TWO predicates refused, and the door owes a different answer for
                    // each (a frozen 403 for the scope, a plain 400 for the floor). What survives
                    // from the old reasoning is why no read is needed on the SUCCESS path: the
                    // conflict itself proves the row exists, so the ambiguity is only ever about
                    // WHICH clause said no, never about whether a row was there.
                    if (ps.executeUpdate() == 0) {
                        Upserted refusal =
                            whyTheConflictArmRefused(c, key, callerScopes, m.isDefault());
                        c.rollback();
                        return refusal;
                    }
                }
                c.commit();
                return new Upserted.Applied(demoted);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("AppRegistrationStore.upsert: " + e, e);
        }
    }

    /**
     * Clears the default flag from whoever holds it, within the caller's reach. RETURNING reports
     * what this UPDATE actually demoted, rather than what a separate read saw a moment earlier; at
     * most one row can carry {@code is_default} (the partial unique index), so a single
     * {@code rs.next()} is right.
     *
     * <p>The grants are bound TWICE, to the same array — see
     * {@link ScopeGuardedUpsert#bindGrants}.
     */
    private static Optional<String> demoteCurrentDefault(Connection c, String key,
                                                         Set<String> callerScopes)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                 "UPDATE connector.app_registration SET is_default = false, updated_at = now() "
                     + "WHERE is_default AND name <> ? "
                     + "AND (?::text[] IS NULL OR scope = ANY (?::text[])) "
                     + "RETURNING name")) {
            ps.setString(1, key);
            ScopeGuardedUpsert.bindGrants(ps, 2, callerScopes);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    /**
     * Which of the conflict arm's two predicates refused? Read on the REFUSAL PATH ONLY, and inside
     * the same transaction as the statement that refused — the sibling comment above used to say
     * this arm needed no disambiguating read, and that stopped being true the moment a second
     * clause joined the first.
     *
     * <p><b>This read cannot itself be raced.</b> {@code ON CONFLICT DO UPDATE} locks the
     * conflicting row even when its {@code WHERE} is not satisfied, so by the time we ask, the row
     * is held by this transaction until it ends; no concurrent apply can change its scope or its
     * flag underneath the answer. That is the same property the demote's disambiguating read
     * relies on, reached by a different route.
     *
     * <p>The scope answer is given FIRST when both clauses could have refused. A row outside the
     * caller's grants must stay behind the frozen 403: telling such a caller "that would remove the
     * last default" would say that a row exists at this name AND that it carries the flag — the two
     * bits the uniform refusal exists to withhold.
     *
     * <p>A third outcome is a bug, not a silent success. Falling through to "applied" after a
     * statement wrote nothing is the worst reading of this result, so it throws.
     */
    private static Upserted whyTheConflictArmRefused(Connection c, String key,
                                                     Set<String> callerScopes,
                                                     boolean incomingIsDefault)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                 "SELECT scope, is_default FROM connector.app_registration WHERE name = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("AppRegistrationStore.upsert: the conflict arm "
                        + "refused but no row holds the name " + key + " — the row it conflicted "
                        + "with should still be locked by this transaction");
                }
                String scope = rs.getString(1);
                boolean holdsDefault = rs.getBoolean(2);
                if (callerScopes != null && !callerScopes.contains(scope)) {
                    return new Upserted.NameHeldOutOfScope();
                }
                if (holdsDefault && !incomingIsDefault) {
                    return new Upserted.LastDefaultWouldBeRemoved();
                }
                throw new IllegalStateException("AppRegistrationStore.upsert: the conflict arm "
                    + "refused " + key + " for neither of its two predicates — scope and flag both "
                    + "read as writable, so the WHERE and this read disagree");
            }
        }
    }

    /** Does any app other than this one hold the default flag? No scope predicate: the question is
     *  whether a holder EXISTS, which is exactly what the demote's empty result cannot say. */
    private static boolean defaultIsHeld(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                 "SELECT name FROM connector.app_registration WHERE is_default AND name <> ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
