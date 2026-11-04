package io.lumnik.hub.manifest;

import io.lumnik.hub.app.AppManifestParser;
import io.lumnik.hub.export.ExportManifestParser;
import io.lumnik.hub.process.ProcessManifestParser;
import io.lumnik.hub.view.ViewManifestParser;
import io.lumnik.hub.workflow.WorkflowManifestParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each manifest kind with a {@code spec.scope} does with the same written value (#1691).
 *
 * <p>One rule for all five: strip, then validate. {@code " crm "} is accepted and stored as
 * {@code crm}; {@code CRM}, an empty or blank value, {@code null} and a non-string are refused.
 * Stripping accepts a superset of what validating-as-written did, so no manifest that was accepted
 * (or already stored) becomes refused.
 */
class ScopeRuleAcrossKindsTest {

  private static final String REFUSED = "refused";

  /** One kind: a valid manifest with {@code @@SCOPE@@} where the scope goes, and how to read it back. */
  private record Kind(String name, String yaml, Function<String, String> scopeOf) {}

  private static final Kind APP = new Kind("App", """
      kind: App
      metadata:
        name: lumilait
      spec:
        title: Lumilait
        scope: @@SCOPE@@
        icon: generic
      """, y -> new AppManifestParser().parse(y).scope());

  private static final Kind VIEW = new Kind("View", """
      apiVersion: apps.lumnik.io/v1
      kind: View
      metadata:
        name: clients-desk
      spec:
        scope: @@SCOPE@@
        table: connector.v_customer
        columns: [customer_code, name]
      """, y -> new ViewManifestParser().parse(y).scope());

  private static final Kind PROCESS = new Kind("Process", """
      apiVersion: apps.lumnik.io/v1
      kind: Process
      metadata:
        name: gold-client-arrive
      spec:
        scope: @@SCOPE@@
        on:
          event: data.row.changed
          table: connector.t_cust_csv
        when:
          - segment=GOLD
        emit:
          type: decision.gold-client-arrive
          message: "Nouveau client GOLD"
      """, y -> new ProcessManifestParser().parse(y).scope());

  private static final Kind WORKFLOW = new Kind("Workflow", """
      apiVersion: apps.lumnik.io/v1
      kind: Workflow
      metadata:
        name: commande-lifecycle
      spec:
        scope: @@SCOPE@@
        provenance: declared
        on:
          table: connector.t_commandes_csv
          column: statut
        states:
          - code: BROUILLON
            initial: true
          - code: TERMINE
            terminal: true
        transitions:
          - { from: [BROUILLON], to: TERMINE }
      """, y -> new WorkflowManifestParser().parse(y).scope());

  private static final Kind EXPORT = new Kind("Export", """
      apiVersion: apps.lumnik.io/v1
      kind: Export
      metadata:
        name: customers-nightly
      spec:
        scope: @@SCOPE@@
        source:
          entity: AcmeCustomer
        columns: [code, name]
        order: [code]
        format:
          kind: csv
          delimiter: ";"
          encoding: UTF-8
          header: true
        destination:
          kind: sftp
          host: exchange.example.com
          port: 22
          user: lumnik
          path: /out/customers/customers-${date}.csv
          key_env: CUSTOMER_EXPORT_SFTP_KEY
          host_key_fingerprint: "SHA256:AAAA"
        mode: snapshot
        schedule: "0 2 * * *"
      """, y -> new ExportManifestParser().parse(y).scope());

  private static Arguments row(Kind kind, String written, String outcome) {
    return Arguments.of(kind.name() + " ← " + written, kind, written, outcome);
  }

  static Stream<Arguments> table() {
    Stream.Builder<Arguments> rows = Stream.builder();
    for (Kind k : new Kind[] {APP, VIEW, PROCESS, WORKFLOW, EXPORT}) {
      rows.add(row(k, "crm", "crm"));
      rows.add(row(k, "\" crm \"", "crm"));
      rows.add(row(k, "\"crm \"", "crm"));
      rows.add(row(k, "CRM", REFUSED));
      rows.add(row(k, "\"\"", REFUSED));
      rows.add(row(k, "\"   \"", REFUSED));
      rows.add(row(k, "~", REFUSED));
      rows.add(row(k, "[crm]", REFUSED));
      rows.add(row(k, "42", REFUSED));
    }
    return rows.build();
  }

  @ParameterizedTest(name = "{0} → {3}")
  @MethodSource("table")
  void each_kind_does_with_a_written_scope_what_this_table_says(
      String label, Kind kind, String written, String expected) {
    String outcome;
    try {
      outcome = kind.scopeOf().apply(kind.yaml().replace("@@SCOPE@@", written));
    } catch (IllegalArgumentException e) {
      outcome = REFUSED;
    }
    assertThat(outcome).isEqualTo(expected);
  }
}
