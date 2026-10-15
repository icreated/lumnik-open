package io.lumnik.hub.connector.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ReadContext;
import io.agroal.api.AgroalDataSource;
import io.lumnik.hub.connector.mapping.spec.MappingLoader;
import io.lumnik.hub.connector.mapping.spec.MappingSpec;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.PromotedColumn;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbSpec.SqlType;
import io.lumnik.hub.connector.mapping.writer.HybridJsonbWriter;
import io.lumnik.hub.connector.mapping.writer.RowHasher;
import io.lumnik.hub.connector.rest.pagination.JsonPathExtractor;
import io.lumnik.hub.connector.rest.pagination.PaginationSupport;
import io.lumnik.hub.testsupport.EventItSupport;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual benchmark for #910, two halves. {@link #transform_rows_per_second}: rows/s of
 * {@link MappingEngine#transform} over a synthetic 100k-row, 10-column source, chunked by 1,000
 * with a fresh {@link MappingState} per chunk (the production shape, {@code MappingEngine.prepare}),
 * plus {@link RowHasher#hash} per row (what {@code MultiTargetWriter} adds on every write); the
 * per-hook lines run that hook alone, so {@code hook − empty} is the hook's own cost.
 * {@link #rest_json_round_trips_per_page}: the REST path's JSON round trips per page.
 *
 * <p>Deliberately named without a {@code Test}/{@code IT} suffix so neither surefire nor failsafe
 * picks it up on a normal {@code mvn test}/{@code verify}: it reports numbers rather than asserting a
 * budget. Run explicitly, without the coverage agent (JaCoCo instruments the very code being timed):
 *
 * <pre>mvn test -pl lumnik-hub -am -Dtest=MappingTransformBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dquarkus.http.test-port=0 -Djacoco.skip=true</pre>
 *
 * Every line is the median of {@value #RUNS} timed passes after {@value #WARMUP} warm-up passes,
 * each pass building its input outside the clock (rows are fresh copies: cell hooks mutate in place).
 */
@QuarkusTest
class MappingTransformBenchmark {

  private static final int ROWS = 100_000;
  private static final int CHUNK = 1_000;
  private static final int WARMUP = 5;
  private static final int RUNS = 7;
  private static final int PAGE_ITEMS = 100;
  private static final int PAGE_REPEATS = 500;
  private static final int DB_PAGES = 10;

  private static final String REGEX_EXTRACT_REF =
      "{ regex-extract: { column: ref, pattern: '(\\d{4})-(\\d{2})', groups: { '1': ref_year, '2': ref_month } } }";
  private static final String REGEX_EXTRACT_SKU =
      "{ regex-extract: { column: sku, pattern: '([A-Z]+)-(\\d+)', groups: { '1': sku_family, '2': sku_code } } }";

  /** The hooks #910 names, each over its own column; parse-date's first two formats miss. */
  private static final List<String> HOOKS = List.of(
      "{ parse-date: { column: created, formats: ['dd/MM/yyyy', 'yyyyMMdd', 'yyyy-MM-dd'] } }",
      "{ regex-replace: { column: phone, pattern: '[^0-9+]', replacement: '' } }",
      "{ split: { column: fullname, by: ' ', into: [first_name, last_name] } }",
      "{ parse-number: { column: amount } }",
      REGEX_EXTRACT_REF,
      REGEX_EXTRACT_SKU,
      "{ to-uuid: { column: id } }");

  /** Keeps the timed results observable, so the JIT cannot drop the work that produced them. */
  private static long sink;

  @Inject MappingEngine engine;
  @Inject RowHasher hasher;
  @Inject HybridJsonbWriter hybridWriter;
  @Inject UserTransaction utx;
  @Inject @DataSource("admin") AgroalDataSource adminDs;

  @Test
  void transform_rows_per_second() throws Exception {
    List<Map<String, Object>> source = sourceRows();
    System.out.printf(Locale.ROOT, "[#910] %d rows x %d columns, chunks of %d%n", ROWS, source.get(0).size(), CHUNK);
    report("all hooks + RowHasher", source, spec(HOOKS), true);
    report("all hooks", source, spec(HOOKS), false);
    report("empty spec + RowHasher", source, spec(List.of()), true);
    report("empty spec", source, spec(List.of()), false);
    for (String hook : HOOKS) {
      report(hook.substring(2, hook.indexOf(':')), source, spec(List.of(hook)), false);
    }
    report("both regex-extract", source, spec(List.of(REGEX_EXTRACT_REF, REGEX_EXTRACT_SKU)), false);
    report("parse-date, 1st fmt hits", source,
        spec(List.of("{ parse-date: { column: created, formats: ['yyyy-MM-dd'] } }")), false);
  }

  /**
   * #910's REST half, per page of {@value #PAGE_ITEMS} items: what {@code JsonPathExtractor.read}
   * (re-serialises the whole page on every read; 2–6 reads per page), the watermark (one read per
   * item) and {@code HybridJsonbWriter.bind} (serialises each row — needed for {@code data} — then
   * re-parses it for its JsonPaths) cost, next to the page parse, the hash and the real write.
   */
  @Test
  void rest_json_round_trips_per_page() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String body = page();
    JsonNode page = mapper.readTree(body);
    List<Map<String, Object>> items = PaginationSupport.items(page, "$.data");
    assertThat(items).hasSize(PAGE_ITEMS);
    // HybridJsonbWriter's own JsonPath configuration and per-row reads (bind is private).
    Configuration jp = Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS);
    List<JsonPath> bindPaths = Stream.of("$.id", "$.status", "$.customer.name", "$.updated_at")
        .map(JsonPath::compile).toList();
    List<String> serialised = new ArrayList<>();
    for (Map<String, Object> item : items) serialised.add(mapper.writeValueAsString(item));
    System.out.printf(Locale.ROOT, "[#910] REST page of %d items, %,d bytes%n", PAGE_ITEMS, body.length());

    perPage("page parse (readTree)", () -> mapper.readTree(body).size());
    perPage("1 JsonPathExtractor.read", () -> JsonPathExtractor.<Boolean>read(page, "$.meta.has_more") ? 1 : 0);
    perPage("watermark, all items", () -> {
      int n = 0;
      for (Map<String, Object> item : items) {
        n += JsonPathExtractor.<String>read(mapper.valueToTree(item), "$.updated_at").length();
      }
      return n;
    });
    perPage("watermark, items as they are", () -> {
      int n = 0;
      for (Map<String, Object> item : items) n += JsonPathExtractor.<String>readParsed(item, "$.updated_at").length();
      return n;
    });
    perPage("bind serialise, all items", () -> {
      int n = 0;
      for (Map<String, Object> item : items) n += mapper.writeValueAsString(item).length();
      return n;
    });
    perPage("bind re-parse+4 reads, all", () -> {
      int n = 0;
      for (String json : serialised) {
        ReadContext ctx = JsonPath.using(jp).parse(json);
        for (JsonPath p : bindPaths) n += ctx.read(p, Object.class).hashCode();
      }
      return n;
    });
    perPage("RowHasher, all items", () -> {
      int n = 0;
      for (Map<String, Object> item : items) n += hasher.hash(item).charAt(0);
      return n;
    });

    // The denominator bind's re-parse sits in: the real write of each page (one transaction, like a
    // chunk) into the DevServices Postgres — fresh ids every pass, so every row is an insert.
    HybridJsonbSpec spec = new HybridJsonbSpec("ext.bench910_" + System.nanoTime(), "$.id", List.of(
        new PromotedColumn("status", "$.status", SqlType.TEXT, Optional.empty()),
        new PromotedColumn("customer", "$.customer.name", SqlType.TEXT, Optional.empty()),
        new PromotedColumn("updated_at", "$.updated_at", SqlType.TEXT, Optional.empty())));
    int[] nextId = {0};
    try {
      sample("HybridJsonbWriter.write (DB)", "page", DB_PAGES, () -> {
        List<Map<String, Object>> rows = new ArrayList<>(DB_PAGES * PAGE_ITEMS);
        List<Map<String, Object>> sys = new ArrayList<>(DB_PAGES * PAGE_ITEMS);
        for (int p = 0; p < DB_PAGES; p++) {
          for (Map<String, Object> item : items) {
            Map<String, Object> row = new HashMap<>(item);
            row.put("id", nextId[0]++);
            rows.add(row);
            sys.add(Map.of("_row_hash", hasher.hash(row)));
          }
        }
        long t0 = System.nanoTime();
        for (int from = 0; from < rows.size(); from += PAGE_ITEMS) {
          int to = from + PAGE_ITEMS;
          List<Map<String, Object>> pageRows = rows.subList(from, to);
          List<Map<String, Object>> pageSys = sys.subList(from, to);
          EventItSupport.runInTxAsSystem(utx, () ->
              hybridWriter.write(spec, pageRows, pageSys, 1L, UUID.randomUUID(), UUID.randomUUID()));
        }
        return System.nanoTime() - t0;
      });
    } finally {
      try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
        st.execute("DROP TABLE IF EXISTS " + spec.table());
      }
    }
  }

  /** Each chunk is copied just before its transform — young and cache-warm, like a reader's. */
  private void report(String label, List<Map<String, Object>> source, MappingSpec spec, boolean hash)
      throws Exception {
    sample(label, "row", ROWS, () -> {
      System.gc();
      long timed = 0;
      for (int from = 0; from < ROWS; from += CHUNK) {
        List<Map<String, Object>> chunk = new ArrayList<>(CHUNK);
        for (Map<String, Object> r : source.subList(from, from + CHUNK)) chunk.add(new HashMap<>(r));
        long t0 = System.nanoTime();
        var out = engine.transform(spec, chunk, new MappingState(), false);
        if (hash) {
          for (Map<String, Object> row : out) sink += hasher.hash(row).charAt(0);
        } else {
          sink += out.size();
        }
        timed += System.nanoTime() - t0;
      }
      return timed;
    });
  }

  private static void perPage(String label, Callable<Integer> work) throws Exception {
    sample(label, "page", PAGE_REPEATS, () -> {
      long t0 = System.nanoTime();
      for (int i = 0; i < PAGE_REPEATS; i++) sink += work.call();
      return System.nanoTime() - t0;
    });
  }

  /** {@code pass} times {@code perPass} units and returns the nanoseconds it timed. */
  private static void sample(String label, String unit, int perPass, Callable<Long> pass) throws Exception {
    for (int i = 0; i < WARMUP; i++) pass.call();
    long[] ns = new long[RUNS];
    for (int i = 0; i < RUNS; i++) ns[i] = pass.call() / perPass;
    Arrays.sort(ns);
    long median = ns[RUNS / 2];
    System.out.printf(Locale.ROOT, "[#910] %-28s %,11d ns/%-4s %,11d %s/s  (min %,d / max %,d)%n",
        label, median, unit, 1_000_000_000L / Math.max(median, 1), unit, ns[0], ns[RUNS - 1]);
  }

  private static MappingSpec spec(List<String> hooks) {
    StringBuilder yaml = new StringBuilder("id: bench910\nversion: 1\ntarget: x\n");
    if (!hooks.isEmpty()) {
      yaml.append("cell:\n");
      for (String h : hooks) yaml.append("  - ").append(h).append('\n');
    }
    return MappingLoader.load(yaml.toString());
  }

  private static List<Map<String, Object>> sourceRows() {
    List<Map<String, Object>> rows = new ArrayList<>(ROWS);
    LocalDate epoch = LocalDate.of(2020, 1, 1);
    for (int i = 0; i < ROWS; i++) {
      Map<String, Object> r = new HashMap<>();
      r.put("id", new UUID(0x910L, i).toString());
      r.put("created", epoch.plusDays(i % 2_000).toString());
      r.put("phone", "+33 6 12 34 " + (10 + i % 90) + " " + (10 + i % 89));
      r.put("fullname", "Jeanne Dupont" + i);
      r.put("amount", (i % 10_000) + ",5" + (i % 10) + " €");
      r.put("ref", "INV-" + (2000 + i % 30) + "-" + (10 + i % 3) + "-" + i);
      r.put("sku", "WID-" + i);
      r.put("email", "user" + i + "@example.com");
      r.put("country", i % 3 == 0 ? "FR" : "DE");
      r.put("status", i % 20 == 0 ? "inactive" : "active");
      rows.add(r);
    }
    return rows;
  }

  /** A paged ERP-style body: {@code data} items with a nested object and lines, plus {@code meta}. */
  private static String page() {
    StringBuilder b = new StringBuilder("{\"data\":[");
    for (int i = 0; i < PAGE_ITEMS; i++) {
      if (i > 0) b.append(',');
      b.append("{\"id\":").append(100_000 + i)
          .append(",\"number\":\"SO-2026-").append(i).append('"')
          .append(",\"status\":\"").append(i % 7 == 0 ? "closed" : "open").append('"')
          .append(",\"customer\":{\"id\":").append(i % 50).append(",\"name\":\"Customer ").append(i % 50).append("\"}")
          .append(",\"lines\":[{\"sku\":\"WID-").append(i).append("\",\"qty\":2,\"price\":19.9},")
          .append("{\"sku\":\"GAD-").append(i).append("\",\"qty\":1,\"price\":149.0}]")
          .append(",\"total\":").append(188.8 + i)
          .append(",\"currency\":\"EUR\"")
          .append(",\"created_at\":\"2026-03-").append(10 + i % 18).append("T08:15:00Z\"")
          .append(",\"updated_at\":\"2026-04-").append(10 + i % 18).append("T17:42:").append(10 + i % 50).append("Z\"")
          .append(",\"notes\":\"Deliver to the loading dock, ring twice\"}");
    }
    return b.append("],\"meta\":{\"total\":12345,\"has_more\":true,\"next_cursor\":\"c-0042\"}}").toString();
  }
}
