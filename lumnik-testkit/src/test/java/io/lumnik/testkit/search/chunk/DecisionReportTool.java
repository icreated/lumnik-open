package io.lumnik.testkit.search.chunk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Assembles the #675 decision report from the three per-candidate result files. Manual (gated; the name does not match
 * surefire's defaults):
 * {@code mvn test -Dtest=DecisionReportTool -pl lumnik-testkit -am -Dsurefire.failIfNoSpecifiedTests=false
 * -Dlumnik.chunkbench.report=true -Dlumnik.chunkbench.baseline=<json> -Dlumnik.chunkbench.pg=<json>
 * -Dlumnik.chunkbench.cluster=<json>}. It REFUSES results that were not produced by the frozen protocol, or on different
 * corpus versions — a verdict built on mixed runs is worse than none.
 */
class DecisionReportTool {

    @Test
    @EnabledIfSystemProperty(named = "lumnik.chunkbench.report", matches = "true")
    void assembleTheReport() throws Exception {
        ChunkBenchResult baseline = read("lumnik.chunkbench.baseline");
        ChunkBenchResult pg = read("lumnik.chunkbench.pg");
        ChunkBenchResult cluster = read("lumnik.chunkbench.cluster");

        ChunkBenchEvaluator.Protocol frozen = ChunkBenchEvaluator.Protocol.FROZEN;
        for (ChunkBenchResult r : List.of(baseline, pg, cluster)) {
            if (r.warmupPasses() != frozen.warmupPasses() || r.measuredPasses() != frozen.measuredPasses()) {
                throw new IllegalStateException(r.candidate() + " was not measured with the frozen protocol ("
                    + frozen.warmupPasses() + " warm-up + " + frozen.measuredPasses()
                    + " measured passes); re-run with -Dlumnik.chunkbench.frozen=true");
            }
            if (!r.corpusFingerprint().equals(baseline.corpusFingerprint())) {
                throw new IllegalStateException(r.candidate() + " ran on a different corpus version than the baseline");
            }
        }

        ChunkCorpus corpus = ChunkBenchFixtures.loadCorpus().corpus();
        ChunkFixture.Manifest manifest = ChunkBenchFixtures.loadVectors(corpus).manifest();
        DecisionReport.requireSameFixture(manifest, List.of(baseline, pg, cluster));
        String md = DecisionReport.render(DecisionRule.decide(baseline, pg, cluster), baseline, pg, cluster, manifest);

        Path out = Path.of("target/chunk-bench/decision-report.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md, StandardCharsets.UTF_8);
        System.out.println("decision report written to " + out.toAbsolutePath());
    }

    private static ChunkBenchResult read(String property) {
        String path = System.getProperty(property);
        if (path == null) {
            throw new IllegalStateException("missing -D" + property + "=<result json>");
        }
        return ChunkBenchResult.read(Path.of(path));
    }
}
