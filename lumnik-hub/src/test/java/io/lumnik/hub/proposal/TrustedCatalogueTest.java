package io.lumnik.hub.proposal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedCatalogueTest {

    private static String resource(String name) throws IOException {
        return Files.readString(Repo.root().resolve("lumnik-hub/src/main/resources/proposal").resolve(name));
    }

    @Test
    void a_version_the_hub_does_not_hold_is_not_trusted() {
        assertThat(TrustedCatalogue.load().held("2026.9.0")).isEmpty();
        assertThat(TrustedCatalogue.load().rule("stalled-documents@2")).isEmpty();
    }

    @Test
    void a_catalogue_altered_after_its_digest_was_locked_is_not_trusted() throws IOException {
        String altered = resource("catalogue.json").replace("Que voulez-vous obtenir ?", "Que voulez-vous obtenir ??");
        TrustedCatalogue trusted = new TrustedCatalogue(altered, resource("catalogue.lock.json"));

        assertThat(trusted.held("2026.10.0")).isEmpty();
    }

    @Test
    void a_rule_altered_after_its_digest_was_locked_is_not_trusted() throws IOException {
        String altered = resource("catalogue.json").replace("Une colonne de statut existe", "Une colonne de statut existe bien");
        TrustedCatalogue trusted = new TrustedCatalogue(altered, resource("catalogue.lock.json"));

        assertThat(trusted.rule("stalled-documents@1")).isEmpty();
    }

    @Test
    void a_lock_without_the_entry_trusts_nothing() throws IOException {
        TrustedCatalogue trusted = new TrustedCatalogue(resource("catalogue.json"), "{}");

        assertThat(trusted.held("2026.10.0")).isEmpty();
        assertThat(trusted.rule("stalled-documents@1")).isEmpty();
    }
}
