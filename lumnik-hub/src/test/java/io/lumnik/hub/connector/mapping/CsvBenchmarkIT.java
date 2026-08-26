package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.csv.CsvFileReader;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@Tag("benchmark")
class CsvBenchmarkIT {

  @Inject MappingEngine engine;

  /**
   * Streams 1M rows through a minimal pipeline (clean + parse-email).
   * Validates that:
   *  - Throughput is reasonable (≥ 10k rows/sec on dev hardware — generous floor)
   *  - Heap stays bounded (engine uses chunk semantics, not full-batch)
   *
   * Note: this is NOT a strict performance gate — JIT warmup, GC, and shared dev resources
   * make sub-second numbers volatile. The goal is to detect catastrophic regressions
   * (e.g. accidental List<Map> retention across chunks).
   */
  @Test
  void million_rows_streaming_completes_with_bounded_heap(@TempDir Path tmp) throws Exception {
    // 1. Generate 1M-row CSV (~30 MB)
    Path csv = tmp.resolve("big.csv");
    int rowCount = 1_000_000;
    long writeStart = System.currentTimeMillis();
    try (BufferedWriter w = Files.newBufferedWriter(csv)) {
      w.write("email,name,country\n");
      for (int i = 0; i < rowCount; i++) {
        w.write("user" + i + "@example.com,User" + i + ",FR\n");
      }
    }
    long writeMs = System.currentTimeMillis() - writeStart;
    System.out.println("CSV written in " + writeMs + " ms — size: " + Files.size(csv) + " bytes");

    // 2. Mapping with cell hooks (the heavy path)
    MappingSpec spec = MappingLoader.load("""
      id: bench
      version: 1
      target: x
      cell:
        - { clean: { columns: ['*'], strip: true } }
        - { parse-email: { column: email, lower: true } }
      """);

    // 3. Stream through engine in chunks of 1000
    var reader = new CsvFileReader();
    MappingState state = new MappingState();
    AtomicLong totalOut = new AtomicLong();
    int chunkSize = 1000;

    // Warmup
    System.gc();
    long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    long start = System.currentTimeMillis();

    List<Map<String, Object>> buffer = new ArrayList<>(chunkSize);
    var rowsRead = new long[]{0};
    long[] peakHeap = {0};
    int[] chunkCount = {0};

    reader.readAll(csv, null, row -> {
      buffer.add(row);
      rowsRead[0]++;
      if (buffer.size() >= chunkSize) {
        var out = engine.transform(spec, new ArrayList<>(buffer), state, false);
        totalOut.addAndGet(out.size());
        buffer.clear();
        chunkCount[0]++;
        // Sample heap every 100 chunks
        if (chunkCount[0] % 100 == 0) {
          long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
          if (heap > peakHeap[0]) peakHeap[0] = heap;
        }
      }
    });
    // Final partial chunk + flush
    if (!buffer.isEmpty()) {
      var out = engine.transform(spec, buffer, state, true);
      totalOut.addAndGet(out.size());
    } else {
      // empty flush to drain any aggregator state
      engine.transform(spec, List.of(), state, true);
    }
    long elapsedMs = System.currentTimeMillis() - start;
    long heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

    System.out.println("Processed " + totalOut.get() + " rows in " + elapsedMs + " ms = "
        + (totalOut.get() * 1000L / Math.max(elapsedMs, 1)) + " rows/sec");
    System.out.println("Heap before: " + (heapBefore / 1024 / 1024) + " MB, peak: "
        + (peakHeap[0] / 1024 / 1024) + " MB, after: " + (heapAfter / 1024 / 1024) + " MB");

    // Assertions
    assertThat(totalOut.get()).isEqualTo(rowCount);
    assertThat(state.counters().rowsIn().get()).isEqualTo(rowCount);
    assertThat(state.counters().rowsOut().get()).isEqualTo(rowCount);

    // Throughput floor: 10k rows/sec is very generous (dev hardware, JIT cold, no warmup)
    long throughput = totalOut.get() * 1000L / Math.max(elapsedMs, 1);
    assertThat(throughput).as("throughput should be ≥ 10k rows/sec on dev hardware")
        .isGreaterThanOrEqualTo(10_000);

    // Heap growth check: peak shouldn't blow up to full-file size.
    // NOTE: heapBefore is measured post-gc but the JVM still carries Quarkus app infrastructure
    // (~150-200 MB), JIT compilation buffers, and Commons CSV parser internals that load during
    // the first chunk. These inflate the "growth" metric. The real guard is:
    //   (a) no OOM at 512m heap (test would crash), and
    //   (b) peak < 512m ceiling, confirming chunk semantics prevent full-file retention.
    // We use 400 MB as the ceiling — the 36 MB input file would only exceed this if we
    // retained every row as an object (each Map<String,Object> row is ~1-2 KB, 1M rows = ~1 GB).
    // (precise bounds require JFR/jstat instrumentation outside the JVM process)
    long heapGrowthMb = (peakHeap[0] - heapBefore) / 1024 / 1024;
    System.out.println("Heap growth: " + heapGrowthMb + " MB");
    // 500 MB ceiling: raised from 400 to account for JaCoCo agent probe buffers (~50-100 MB).
    // Still catches catastrophic full-file retention (36 MB file × many chunks > 500 MB).
    assertThat(heapGrowthMb).as("heap growth bounded — no full-file retention (chunk semantics)")
        .isLessThan(500);
  }
}
