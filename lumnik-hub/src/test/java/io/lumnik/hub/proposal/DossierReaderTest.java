package io.lumnik.hub.proposal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DossierReaderTest {

    static String fixture(String relative) {
        try {
            return Files.readString(Repo.root().resolve("lumnik-hub/src/test/resources/proposal/fixtures").resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void a_valid_fixture_becomes_a_model() {
        Dossier d = DossierReader.read(fixture("valid/minimal.md"));

        assertThat(d.catalogue()).isEqualTo("2026.10.0");
        assertThat(d.language()).isEqualTo("fr");
        assertThat(d.answers()).extracting(Dossier.Answer::id).containsExactly("goal", "has-status", "has-stable-id");
        assertThat(d.leads()).hasSize(1);
        Dossier.Lead lead = d.leads().get(0);
        assertThat(lead.rule()).isEqualTo("stalled-documents@1");
        assertThat(lead.conditions()).extracting(Dossier.Condition::id).containsExactly("C1", "C2", "C3", "C4", "C5");
        assertThat(lead.conditions().get(4).backedBy()).isNull();            // C5: no question asks it
        assertThat(lead.conditions().get(3).note()).isNotNull();             // C4 carries its note
        assertThat(lead.conditions().get(0).evidence()).isNull();            // absent, not empty
        assertThat(d.carriesEvidence()).isFalse();
    }

    @Test
    void carried_evidence_is_kept_as_it_was_written() {
        Dossier d = DossierReader.read(fixture("valid-recheck/confirmed-with-evidence.md"));

        Dossier.Condition c1 = d.leads().get(0).conditions().get(0);
        assertThat(c1.check()).isEqualTo("confirmed");
        assertThat(c1.evidence()).containsExactly(new Dossier.Evidence(
            "discovery", "workflow-data-truth", "connector.t_commandes.statut", "checkUnavailable false, tableMissing false"));
        assertThat(d.carriesEvidence()).isTrue();
    }

    @Test
    void a_refusal_carries_the_contracts_reason() {
        assertThatThrownBy(() -> DossierReader.read(fixture("invalid/duplicate-key.md")))
            .isInstanceOf(DossierReader.Refused.class)
            .extracting(e -> ((DossierReader.Refused) e).reason().code())
            .isEqualTo("duplicate-key");
    }

    @Test
    void bytes_are_decoded_as_utf8_and_malformed_utf8_is_refused_not_replaced() {
        String text = "terminé… — 😀";

        assertThat(DossierReader.decode(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);
        assertThatThrownBy(() -> DossierReader.decode(text.getBytes(StandardCharsets.ISO_8859_1)))
            .isInstanceOf(DossierReader.Refused.class)
            .extracting(e -> ((DossierReader.Refused) e).reason().code())
            .isEqualTo("malformed-header");
    }
}
