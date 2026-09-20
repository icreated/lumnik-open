package io.lumnik.hub.bff;

import io.lumnik.hub.edition.EditionMarker;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class BffResourceEditionTest {

    @Test
    void noMarkerMeansOpenEdition() {
        assertThat(BffResource.editionOf(markerResolved(false))).isEqualTo("open");
    }

    @Test
    void resolvedMarkerMeansCommercialEdition() {
        assertThat(BffResource.editionOf(markerResolved(true))).isEqualTo("commercial");
    }

    @SuppressWarnings("unchecked")
    private static Instance<EditionMarker> markerResolved(boolean resolved) {
        Instance<EditionMarker> marker = Mockito.mock(Instance.class);
        Mockito.when(marker.isUnsatisfied()).thenReturn(!resolved);
        return marker;
    }
}
