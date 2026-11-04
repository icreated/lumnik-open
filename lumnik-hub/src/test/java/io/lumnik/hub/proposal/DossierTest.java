package io.lumnik.hub.proposal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

import static org.assertj.core.api.Assertions.assertThat;

class DossierTest {

    /** The hub established a check only by confirming or refuting it; the other two are claims it has not tested. */
    @ParameterizedTest(name = "{0} -> established: {1}")
    @CsvSource({"confirmed, true", "refuted, true", "unverified, false", "missing, false", "Confirmed, false"})
    void aCheckIsEstablishedOnlyWhenTheHubConfirmedOrRefutedIt(String check, boolean established) {
        assertThat(Dossier.established(check)).isEqualTo(established);
    }

    @ParameterizedTest
    @NullSource
    void noCheckIsNotEstablished(String check) {
        assertThat(Dossier.established(check)).isFalse();
    }
}
