package io.lumnik.hub.proposal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DossierWriterTest {

    private static String siteOutput(String name) throws IOException {
        return Files.readString(Repo.root().resolve("lumnik-hub/src/test/resources/proposal/site-output").resolve(name));
    }

    /** The hub's serialisation equals the site's, byte for byte, on what the site wrote. */
    @ParameterizedTest
    @ValueSource(strings = {"minimal.md", "history-kept.md"})
    void the_writer_reproduces_what_the_site_wrote(String name) throws IOException {
        String file = siteOutput(name);
        assertThat(DossierWriter.write(DossierReader.read(file))).isEqualTo(file);
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid/minimal.md", "valid/no-body.md", "valid/unanswered.md", "valid/answer-no-lead-kept.md",
        "valid-recheck/confirmed-with-evidence.md", "valid-recheck/downgraded-evidence-kept.md", "valid-recheck/refuted-over-unknown.md"})
    void every_valid_fixture_survives_a_round_trip_and_is_idempotent(String fixture) {
        Dossier read = DossierReader.read(DossierReaderTest.fixture(fixture));

        String once = DossierWriter.write(read);

        assertThat(DossierReader.judge(once).verdict()).isNotEqualTo(DossierReader.Verdict.INVALID);
        assertThat(DossierReader.read(once)).isEqualTo(read);
        assertThat(DossierWriter.write(DossierReader.read(once))).isEqualTo(once);
    }

    @Test
    void a_language_without_presentation_strings_gets_the_header_and_the_generated_comment_only() {
        Dossier fr = DossierReader.read(DossierReaderTest.fixture("valid/minimal.md"));
        Dossier en = new Dossier(fr.catalogue(), fr.created(), "en", fr.words(), fr.answers(), fr.leads());

        String text = DossierWriter.write(en);

        assertThat(DossierReader.read(text)).isEqualTo(en);
        assertThat(text).contains("Generated from the header above").doesNotContain("# Dossier lumnik");
    }

    @Test
    void a_condition_the_hub_established_says_so_and_shows_its_evidence_without_claiming_to_have_observed_it_all() {
        Dossier d = DossierReader.read(DossierReaderTest.fixture("valid-recheck/confirmed-with-evidence.md"));
        Dossier.Lead lead = d.leads().get(0);
        Dossier.Condition c1 = lead.conditions().get(0);
        Dossier.Condition established = new Dossier.Condition(c1.id(), c1.needs(), c1.verifier(), c1.label(),
            c1.backedBy(), "confirmed", c1.note(), c1.evidence());
        Dossier checked = new Dossier(d.catalogue(), d.created(), d.language(), d.words(), d.answers(),
            List.of(new Dossier.Lead(lead.id(), lead.rule(), lead.source(), lead.title(), lead.says(),
                lead.doesNotSay(), lead.proposes(),
                List.of(established, lead.conditions().get(1), lead.conditions().get(2), lead.conditions().get(3), lead.conditions().get(4)))));

        String text = DossierWriter.write(checked);

        assertThat(text).contains("Établi par lumnik sur les données").contains("Preuve jointe : connector.t_commandes.statut");
        // schema 1 cannot tell this run's evidence from imported history: no line claims the hub just observed it
        assertThat(text).doesNotContain("Constaté par lumnik").doesNotContain("Preuve transportée");
    }

    /** Well-formed JSON.stringify: a lone surrogate is written as an escape, a valid pair stays as it is. */
    @Test
    void a_lone_surrogate_is_escaped_and_a_pair_is_not() {
        String bs = String.valueOf((char) 92);
        String pair = new String(Character.toChars(0x1F600));

        assertThat(JsonText.quote(String.valueOf((char) 0xD800))).isEqualTo("\"" + bs + "ud800\"");
        assertThat(JsonText.quote(String.valueOf((char) 0xDC00))).isEqualTo("\"" + bs + "udc00\"");
        assertThat(JsonText.quote("a" + (char) 0xD800 + "b")).isEqualTo("\"a" + bs + "ud800b\"");
        assertThat(JsonText.quote(pair)).isEqualTo("\"" + pair + "\"");
        assertThat(JsonText.quote("" + (char) 0xDC00 + (char) 0xD800)).isEqualTo("\"" + bs + "udc00" + bs + "ud800\"");
    }

    /** A header escape Jackson decodes to a lone surrogate must come back as that escape, from the first answer on. */
    @Test
    void a_dossier_whose_text_holds_a_lone_surrogate_is_written_back_stably() throws IOException {
        String bs = String.valueOf((char) 92);
        String file = siteOutput("minimal.md").replace("Nos commandes", "Nos " + bs + "ud800 commandes");

        String once = DossierWriter.write(DossierReader.read(file));

        String header = once.substring(0, once.indexOf("\n---\n", 4));   // the contract; the body is prose, never read back
        assertThat(header).contains("Nos " + bs + "ud800 commandes").doesNotContain(String.valueOf((char) 0xD800));
        assertThat(DossierWriter.write(DossierReader.read(once))).isEqualTo(once);
    }

    @Test
    void json_strings_are_quoted_as_JSON_stringify_does() {
        assertThat(JsonText.quote("a\"b\\c\n\t/é")).isEqualTo("\"a\\\"b\\\\c\\n\\t/é\"");
        assertThat(JsonText.quote(String.valueOf((char) 1))).isEqualTo("\"" + (char) 92 + "u0001\"");
    }
}
