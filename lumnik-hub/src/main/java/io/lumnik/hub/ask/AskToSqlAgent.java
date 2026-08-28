package io.lumnik.hub.ask;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.NoSchemaException;
import io.lumnik.llm.spi.Analytic;
import io.lumnik.llm.spi.ChatPort;
import io.lumnik.extraction.spi.ask.AskPort;
import io.lumnik.extraction.spi.ask.AskResult;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.extraction.spi.ask.SqlGuard;
import io.lumnik.hub.sql.TenantRlsScope;
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

    @Inject
    @DataSource("readonly")
    AgroalDataSource readonlyDs;

    @Inject
    @DataSource("admin")
    AgroalDataSource adminDs;

    private final SqlGuard guard = new SqlGuard(MAX_ROWS);

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
              Qualify each table with its schema exactly as written.
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

        // Honor the model's honest exit: an abstention, not the generic "couldn't translate".
        if (abstentionGuard.isAbstention(rawSql)) {
            return new AskResult(AbstentionGuard.CANNOT_ANSWER_MESSAGE, "", List.of(), 0);
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
            return new AskResult("I couldn't run that query. One likely cause is an unqualified table name "
                + "(tables must be schema-qualified, e.g. connector.<table>); if it persists, re-run "
                + "discovery for this scope.", safeSql, List.of(), 0);
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
