package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.entity.EntityManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #1974: the card offers a point only while both its columns are numbers in the compiled view. */
class EntitySchemaBuilderPointsTest {

    private static final EntityManifest M = new EntityManifest("Entity", "GeoSite", null, List.of("site_no"),
            List.of(), List.of("geo"), null, Map.of(), null, List.of(), null,
            List.of(new EntityManifest.Point("site", "latitude", "longitude")));

    @Test
    void twoNumbersAreAUsablePoint() {
        assertThat(EntitySchemaBuilder.usablePoints(M, Map.of("latitude", "numeric", "longitude", "double precision")))
                .containsExactly(new SchemaCard.Point("site", "latitude", "longitude"));
    }

    @Test
    void aColumnARunRetypedToTextTakesThePointOffTheCard() {
        assertThat(EntitySchemaBuilder.usablePoints(M, Map.of("latitude", "text", "longitude", "numeric"))).isEmpty();
    }

    @Test
    void aViewNotCompiledYetOffersNoPoint() {
        assertThat(EntitySchemaBuilder.usablePoints(M, Map.of())).isEmpty();
    }
}
