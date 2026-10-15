package io.lumnik.hub.export.run;

import io.lumnik.hub.export.ExportManifestStore;
import io.lumnik.hub.run.RunLock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The delivery scenarios every transport IT runs, over {@link ExportRunITSupport}'s helpers. */
abstract class ExportRunScenarios extends ExportRunITSupport {
    @Test
    void aRunDeliversTheSnapshotAndRecordsItsReceiptAndEgress() throws Exception {
        String name = applyExport("dated-${date}.csv", false);
        String target = "dated-2026-09-24.csv";

        ExportRunView run = run(name, AT);

        Local local = local();
        assertThat(run.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(run.failureReason()).isNull();
        assertThat(run.rowCount()).isEqualTo(local.stats().rowCount()).isEqualTo(3L);
        assertThat(run.byteCount()).isEqualTo(local.stats().byteCount());
        assertThat(run.sha256()).isEqualTo(local.stats().sha256Hex());
        assertThat(run.location()).isEqualTo(location(target));
        assertReceipt(run);
        assertThat(remote(target)).isEqualTo(local.bytes());
        assertNoLeftover(target);

        List<String> audit = audit(name).stream().filter(r -> r.startsWith("EGRESS ")).toList();
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).startsWith("EGRESS ")
            .contains("\"run_id\": \"" + run.id() + "\"")
            .contains("\"sha256\": \"" + run.sha256() + "\"");
    }

    @Test
    void aStartExecutesTheDefinitionItWasAuthorizedOnNotALaterReapply() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = applyExport("authorized-" + suffix + ".csv", false);
        ExportManifestStore.Row authorized = row(name);
        reapply(name, "reapplied-" + suffix + ".csv", false);

        ExportRunView run = await(name, runner.launch(
            new ExportRunRequest(TENANT, name, ExportTrigger.MANUAL, 7L, null, AT, 1, null), authorized));

        assertThat(run.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(run.location()).isEqualTo(location("authorized-" + suffix + ".csv"));
    }

    @Test
    void aRetryExecutesTheDefinitionItWasAuthorizedOnNotALaterReapply() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = applyExport("authorized-" + suffix + ".csv", true);
        ExportRunView failed = run(name, AT);
        assertThat(failed.status()).isEqualTo(ExportRunStatus.FAILED);
        reapply(name, "authorized-" + suffix + ".csv", false);
        ExportManifestStore.Row authorized = row(name);
        reapply(name, "reapplied-" + suffix + ".csv", false);

        ExportRunView retry = await(name, runner.launch(new ExportRunRequest(TENANT, name,
            ExportTrigger.MANUAL, 7L, null, AT, 2, failed.id()), authorized));

        assertThat(retry.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(retry.retryOf()).isEqualTo(failed.id());
        assertThat(retry.location()).isEqualTo(location("authorized-" + suffix + ".csv"));
    }

    @Test
    void aLiteralFilenameRunTwiceCollidesAndNeverOverwrites() throws Exception {
        String target = "literal-" + UUID.randomUUID().toString().substring(0, 8) + ".csv";
        String name = applyExport(target, false);

        assertThat(run(name, AT).status()).isEqualTo(ExportRunStatus.COMPLETED);
        byte[] first = remote(target);
        seed("('4', 'Dee')");

        ExportRunView second = run(name, AT);
        assertThat(second.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(second.failureCode()).isEqualTo(ExportFailureCode.COLLISION);
        assertThat(second.location()).isNull();
        assertThat(remote(target)).isEqualTo(first);
        assertThat(audit(name)).filteredOn(r -> r.startsWith("EGRESS ")).hasSize(1);
    }

    @Test
    void aWrongCredentialFailsAsPublicationWithoutLeakingTheSecret() throws Exception {
        String target = "wrong-cred-" + UUID.randomUUID().toString().substring(0, 8) + ".csv";
        String name = applyExport(target, true);

        ExportRunView run = run(name, AT);

        assertThat(run.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(run.failureCode()).isEqualTo(ExportFailureCode.PUBLICATION);
        assertThat(run.failureReason()).isNotBlank().doesNotContain(WRONG_SECRET);
        assertThat(audit(name)).noneMatch(row -> row.contains(WRONG_SECRET) || row.startsWith("EGRESS"));
        assertThat(remote(target)).isNull();
        assertNoLeftover(target);
    }

    @Test
    void anEntityScopeRemovedAfterApplyFailsAsDefinition() throws Exception {
        String target = "narrowed-" + UUID.randomUUID().toString().substring(0, 8) + ".csv";
        String name = applyExport(target, false);
        applyEntity("ops");

        ExportRunView run = run(name, AT);

        assertThat(run.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(run.failureCode()).isEqualTo(ExportFailureCode.DEFINITION);
        assertThat(run.failureReason()).contains("spec.scope 'sales'");
        assertThat(remote(target)).isNull();
        assertThat(audit(name)).noneMatch(row -> row.startsWith("EGRESS"));
    }

    @Test
    void aHeldLockAnswersAlreadyRunningAndWritesNoRow() throws Exception {
        String name = applyExport("locked.csv", false);

        try (RunLock held = RunLock.tryAcquire(adminDs, exportId(name))) {
            assertThat(held).isNotNull();
            StartResult result = runner.start(ExportRunRequest.manual(TENANT, name, 7L));
            assertThat(result).isEqualTo(new StartResult.AlreadyRunning(null));
        }
        assertThat(runRows(name)).isZero();
    }

    @Test
    void aDeclaredDelimiterAndNoHeaderAreWhatIsDelivered() throws Exception {
        String target = "shaped-" + UUID.randomUUID().toString().substring(0, 8) + ".csv";
        String name = applyExport(target, false,
            "    delimiter: \";\"\n    header: false");

        ExportRunView run = run(name, AT);

        assertThat(run.status()).isEqualTo(ExportRunStatus.COMPLETED);
        byte[] expected = "\"1\";\"Ann\"\r\n\"2\";\"Bob\"\r\n\"3\";\"Cid\"\r\n"
            .getBytes(StandardCharsets.UTF_8);
        assertThat(remote(target)).isEqualTo(expected);
        assertThat(run.byteCount()).isEqualTo(expected.length);
    }

    /** #1090: header names, formatters and a filter shape the delivered file; the same data gives
     *  the same bytes twice, so the receipt stays checkable. */
    @Test
    void aShapedExportDeliversThePartnersFileAndTheSameBytesTwice() throws Exception {
        String name = applyShapedExport("partner-${timestamp}.csv", """
              columns:
                - name: code
                  as: CUST_NO
                  format: [{lookup: {table: {"1": C-001, "2": C-002}, on-missing: error}}]
                - name: name
                  as: NAME
                  format: [{mask: {keep-first: 1}}]
              filter: [{column: name, in: [Ann, Bob]}]""");

        ExportRunView first = run(name, AT);
        ExportRunView second = run(name, AT.plusSeconds(1));

        byte[] expected = "\"CUST_NO\",\"NAME\"\r\n\"C-001\",\"A**\"\r\n\"C-002\",\"B**\"\r\n"
            .getBytes(StandardCharsets.UTF_8);
        assertThat(first.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(first.rowCount()).isEqualTo(2L);
        assertThat(remote("partner-20260924T040000Z.csv")).isEqualTo(expected);
        assertThat(second.status()).isEqualTo(ExportRunStatus.COMPLETED);
        assertThat(second.sha256()).isEqualTo(first.sha256());
    }

    /** #1090: a value a formatter cannot write fails the run — no half-formatted file — and the
     *  reason names the column and the formatter, never the value. */
    @Test
    void aValueAFormatterCannotWriteFailsAsGenerationWithoutTheValue() throws Exception {
        String target = "unwritable-" + UUID.randomUUID().toString().substring(0, 8) + ".csv";
        String name = applyShapedExport(target, """
              columns: [code, {name: name, format: [{format-number: {}}]}]""");

        ExportRunView run = run(name, AT);

        assertThat(run.status()).isEqualTo(ExportRunStatus.FAILED);
        assertThat(run.failureCode()).isEqualTo(ExportFailureCode.GENERATION);
        assertThat(run.failureReason())
            .isEqualTo("column 'name', formatter 'format-number': not a decimal number")
            .doesNotContain("Ann");
        assertThat(remote(target)).isNull();
        assertNoLeftover(target);
    }

    @Test
    void anUnknownExportIsNotFound() {
        assertThat(runner.start(ExportRunRequest.manual(TENANT, "no-such-export-680", 7L)))
            .isEqualTo(new StartResult.NotFound());
    }

}
