package io.lumnik.hub.app;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V48 seeds the registry rather than leaving it empty, because it REPLACES the compiled METIERS
 * map: after this migration an app that is not a row here does not exist, Clients and Stock
 * included. A migration that ran is not the same claim as a migration that wrote what it meant to
 * — so the seed is read back, not assumed.
 */
@QuarkusTest
class AppRegistrationSeedIT {

    @Inject AppRegistrationStore store;

    @Test
    void v48SeedsTheTwoBuiltInsWithTodaysShippedIdentity() {
        List<AppRegistrationStore.Row> all = store.list(null);
        assertThat(all).extracting(AppRegistrationStore.Row::name)
            .contains("clients", "stock");

        AppRegistrationStore.Row clients = store.find("clients").orElseThrow();
        assertThat(clients.title()).isEqualTo("Clients");
        assertThat(clients.icon()).isEqualTo("clients");
        assertThat(clients.scope()).isEqualTo("clients");
        assertThat(clients.accentColor()).as("today's --accent").isEqualTo("#8b7ff0");
        assertThat(clients.backgroundColor()).as("today's --bg").isEqualTo("#14121f");
        assertThat(clients.isDefault()).as("today's hardcoded fallback").isTrue();

        assertThat(store.find("stock").orElseThrow().isDefault()).isFalse();
        assertThat(store.findDefaultName()).contains("clients");
    }

    @Test
    void theSeededAccentPassesTheRuleTheSameReleaseIntroduces() {
        assertThat(WcagContrast.ratio(store.find("clients").orElseThrow().accentColor(),
                                      WcagContrast.BUBBLE_TEXT))
            .isGreaterThanOrEqualTo(WcagContrast.AA_NORMAL_TEXT);
    }
}
