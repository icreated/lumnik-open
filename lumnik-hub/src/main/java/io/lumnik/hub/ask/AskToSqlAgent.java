package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.NoSchemaException;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.lumnik.extraction.spi.ask.AskPort;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlGuard;
import io.lumnik.hub.workflow.WorkflowManifestStore;
import io.lumnik.hub.sql.TenantRlsScope;
import io.lumnik.hub.sql.SqlFailureText;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Orchestrates NL to SQL to SqlGuard to read-only query to NL answer. */
@ApplicationScoped
public class AskToSqlAgent implements AskPort {

    private static final Logger LOG = Logger.getLogger(AskToSqlAgent.class);
    private static final int MAX_ROWS = 500;
    private static final int TIMEOUT_S = 5;

    @Inject @Analytic Instance<ChatPort> chatPorts;
    @Inject SchemaCardStore store;
    @Inject ScopeFreshness freshness;
    @Inject DomainGuard domainGuard;
    @Inject UndocumentedColumnGuard undocumentedColumnGuard;
    @Inject LeakGuard leakGuard;
    @Inject AbstentionGuard abstentionGuard;
    @Inject Instance<WorkflowManifestStore> workflows;

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private final SqlGuard guard = new SqlGuard(MAX_ROWS);

    /**
     * Does any kind:Workflow declare a lifecycle in this scope? ONE scalar read — not
     * {@code WorkflowRegistry.all(tenant)}, which is the serving path and re-parses every stored
     * manifest's YAML by contract. A signpost is a boolean; it must not cost N parses.
     *
     * <p>Through {@code Instance}: the store is an app bean, and the SPI tests that exercise this
     * agent do not boot one.
     */
    private boolean scopeDeclaresALifecycle(long tenantId, String scope) {
        return !workflows.isUnsatisfied() && workflows.get().anyInScope(tenantId, scope);
    }

    @Override
    public AskResult ask(long tenantId, String scope, String question) {
        SchemaCard card = store.load(tenantId, scope).orElseThrow(
            // Typed (SPI) so the chat door can translate it into an honest 409 refusal —
            // as an IllegalStateException it escaped to the tester as an anonymous 500.
            () -> new NoSchemaException(scope));

        // Abstention floor: an empty schema has nothing to answer from — abstain before calling the model.
        if (abstentionGuard.hasNoSchema(card)) {
            return new AskResult(AbstentionGuard.NO_SCHEMA_MESSAGE, "", List.of(), 0);
        }

        String genPrompt = """
            You are a careful PostgreSQL analyst. Translate the question into ONE PostgreSQL SELECT.
            Use ONLY these tables and columns (the text after -- is the business meaning):

            %s
            Rules:
            - If the question cannot be answered using ONLY the listed tables and columns, output exactly
              %s (and nothing else) — do not guess.
            - Use only the exact table and column names listed above; never invent names.
              Qualify each table with its schema exactly as written. Every name is listed
              double-quoted — copy each one with its quotes.
            - Do NOT add a WHERE filter unless the question explicitly asks for one, and never
              guess literal values to filter on (e.g. do not invent status/type codes).
            - "how many" -> COUNT(*). A monetary "value/total/amount/revenue" -> SUM of the column
              whose meaning is that amount (prefer one labelled like "Grand Total"); never multiply
              unrelated columns to fabricate an amount, and do NOT use balance/credit/limit/volume/
              weight columns as an amount unless the question names them.
            - To rank or break down "by <thing>", GROUP BY that thing's id column (a *_id column),
              not a free-text name.
            - Use the fewest tables needed: query a single table and GROUP BY its own *_id column
              when the answer needs only that table's amounts; JOIN another table only to return a
              column that lives there.
            - One SELECT only. No DDL/DML, no semicolons, no comments.
            Output ONLY the SQL — or exactly %s if the question cannot be answered — and nothing else.

            Question: %s
            """.formatted(card.toPrompt(), AbstentionGuard.SENTINEL, AbstentionGuard.SENTINEL, question);

        String rawSql = chatPorts.get().chat(genPrompt);

        // Honor the model's honest exit: an abstention, not the generic "couldn't translate" —
        // and, when this scope holds something text-to-SQL structurally cannot read, say which door
        // does (#494 the declared lifecycle, #495 the fusion ledger). The refusal is unchanged; only
        // the dead end is.
        if (abstentionGuard.isAbstention(rawSql)) {
            var abstention = abstentionGuard.cannotAnswer(
                scopeDeclaresALifecycle(tenantId, scope), abstentionGuard.hasAFusedEntity(card));
            return new AskResult(abstention.answer(), "", List.of(), 0, null, abstention.otherDoors());
        }

        String safeSql;
        try {
            safeSql = guard.check(rawSql);
        } catch (SqlGuard.SqlRejectedException e) {
            LOG.warnf("SqlGuard rejected generated SQL: %s", e.getMessage());
            return new AskResult("I couldn't translate that into a safe query.",
                                 rawSql, List.of(), 0);
        }

        // Scope boundary: refuse a query that reads a table outside this scope's declared schema.
        var leak = leakGuard.check(safeSql, card);
        if (leak.isPresent()) {
            LOG.infof("LeakGuard refused: %s", leak.get().message());
            return new AskResult(leak.get().message(), safeSql, List.of(), 0);
        }

        // Honesty: refuse a filter on a column the SchemaCard never declared — before executing.
        var unknownColumn = undocumentedColumnGuard.check(safeSql, card);
        if (unknownColumn.isPresent()) {
            LOG.infof("UndocumentedColumnGuard refused: %s", unknownColumn.get().message());
            return new AskResult(unknownColumn.get().message(), safeSql, List.of(), 0);
        }

        // Honesty over a deceptive "none": if the query filters a column on a value outside its
        // declared domain, REFUSE before executing — never let a query-failure pose as a fact.
        var violation = domainGuard.check(safeSql, card);
        if (violation.isPresent()) {
            LOG.infof("DomainGuard refused: %s", violation.get().message());
            return new AskResult(violation.get().message(), safeSql, List.of(), 0);
        }

        // Execute, but turn any read failure (syntax, permission, timeout, or — with the empty search_path
        // — an unqualified table name) into an honest degraded result rather than letting the exception
        // surface as a 500 at the REST boundary. Log the cause with its stack trace for debugging.
        List<Map<String,Object>> rows;
        try {
            rows = runReadOnly(tenantId, safeSql);
        } catch (RuntimeException e) {
            LOG.warn("AskToSqlAgent read failed for scope " + scope, e);
            // A filter that names more than one row is the one failure we can turn into a next
            // step rather than a dead end — see AmbiguousFilterGuard.
            if (AmbiguousFilterGuard.isAmbiguity(e)) {
                var disclosure = discloseAmbiguity(tenantId, safeSql);
                if (disclosure.isPresent()) {
                    return new AskResult(disclosure.get(), safeSql, List.of(), 0);
                }
            }
            // Report the refusal, do not attribute it: this message used to name an unqualified
            // table as the likely cause, and was wrong on the very failure that prompted the fix —
            // a perfectly qualified query, refused for a multi-row subquery.
            return new AskResult("I couldn't run that query — the database refused it: "
                + SqlFailureText.rootMessage(e) + " The SQL is below; if it looks wrong for this scope, re-run "
                + "discovery.", safeSql, List.of(), 0);
        }

        // Honesty over a deceptive "none": never let a 0-row result be phrased as a confident negative.
        if (rows.isEmpty()) {
            return new AskResult(ZeroRowGuard.CAVEAT, safeSql, List.of(), 0, dataAsOf(tenantId, scope));
        }

        String answerPrompt = """
            Question: %s
            SQL run: %s
            Rows (JSON-ish): %s
            Answer in plain language using ONLY these rows. State the count and key totals.
            """.formatted(question, safeSql, rows);
        String answer = chatPorts.get().chat(answerPrompt);

        // P5: successful asks leave a trace too — counts and timing only, never the
        // question or the SQL (both can carry user values; same posture as chat logging).
        LOG.infof("ask answered: scope=%s rows=%d", scope, rows.size());
        return new AskResult(answer, safeSql, rows, rows.size(), dataAsOf(tenantId, scope));
    }

    private String dataAsOf(long tenantId, String scope) {
        return freshness.lastCompletedRun(tenantId, scope).map(Instant::toString).orElse(null);
    }

    /**
     * Turn a multi-row scalar subquery into a next step: run that subquery on its own and name
     * what it matched, so the asker can put an id where an ambiguous name was.
     *
     * <p>{@code SqlGuard} runs again because the fragment needs its OWN bound — the outer query's
     * LIMIT does not travel into it, and {@code SqlGuard} adds no table or column of its own.
     *
     * <p>{@code LeakGuard} and {@code UndocumentedColumnGuard} do NOT run again, and the reason is
     * an inclusion rather than an equivalence to be argued: the fragment is literally
     * {@code sql.substring(…)} of {@code safeSql} ({@code SqlFilters.scalarSubquery} slices it by
     * token offsets), and both guards walk EVERY position of the token stream they are given —
     * {@code LeakGuard.firstUndeclaredRead} loops the whole list checking each
     * {@code schema.table}, {@code UndocumentedColumnGuard} loops every filter
     * {@code SqlFilters} finds. Neither knows what a clause is. So every qualified table and every
     * literal filter inside this fragment was inspected when they ran over the whole statement at
     * the top of {@code ask}, and a substring can introduce neither a table nor a column that was
     * not there. Re-running them read like rigour and bought nothing.
     *
     * <p>Every failure along the way — unrecognised shape, refusal, empty result — falls back to
     * the plain refusal: a disclosure that cannot be built must never cost the caller the message
     * they were owed.
     */
    private Optional<String> discloseAmbiguity(long tenantId, String safeSql) {
        try {
            Optional<String> fragment = SqlFilters.scalarSubquery(safeSql);
            if (fragment.isEmpty()) return Optional.empty();

            List<Map<String,Object>> matches = runReadOnly(tenantId, guard.check(fragment.get()));
            if (matches.size() < 2) return Optional.empty();   // not the ambiguity we promised to explain

            // One column is guaranteed here, not assumed: Postgres answers a multi-column scalar
            // subquery with "subquery must return only one column" (42601), never the 21000 that
            // put us in this branch — verified against the server. So the first key IS the column.
            String label = matches.get(0).keySet().iterator().next();
            List<String> values = matches.stream().map(r -> String.valueOf(r.get(label))).toList();
            // SqlGuard caps the probe at MAX_ROWS, so a bigger duplicate set comes back truncated;
            // the message must then say "at least" rather than present a ceiling as a total.
            return Optional.of(
                AmbiguousFilterGuard.message(label, values, matches.size() >= MAX_ROWS));
        } catch (RuntimeException e) {
            LOG.debugf("ambiguity disclosure unavailable, falling back: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Runs the guarded SQL in its OWN short transaction, and returns before the answer-synthesis
     * model call — so the tenant binding holds without a connection being pinned across the LLM.
     *
     * <p>The transaction is not optional: {@link TenantRlsScope#applyTo} uses {@code SET LOCAL},
     * which is transaction-scoped and silently discarded on an autoCommit connection. The chat
     * door used to supply an ambient transaction; it stopped being {@code @Transactional} on
     * 2026-08-11 to keep a connection off the model call, which left this path unbound until
     * {@code TenantRlsScope}'s fail-fast guard surfaced it. Same idiom as
     * {@code WorkflowApplyResource}, which wraps its own data-truth read this way.
     */
    private List<Map<String,Object>> runReadOnly(long tenantId, String sql) {
        return QuarkusTransaction.requiringNew().call(() -> readReadOnly(tenantId, sql));
    }

    private List<Map<String,Object>> readReadOnly(long tenantId, String sql) {
        List<Map<String,Object>> out = new ArrayList<>();
        try (Connection c = readonlyDs.getConnection()) {
            // Read-only + tenant-scoped RLS + empty search_path (the shared boundary; see TenantRlsScope).
            TenantRlsScope.applyTo(c, tenantId);
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(TIMEOUT_S);
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                        out.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("AskToSqlAgent.runReadOnly: " + e.getMessage(), e);
        }
        return out;
    }

    /** Test helper: run a DDL/DML statement on the admin datasource (seeding only). */
    public void seedDdl(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new RuntimeException("seedDdl: " + e.getMessage(), e);
        }
    }
}
