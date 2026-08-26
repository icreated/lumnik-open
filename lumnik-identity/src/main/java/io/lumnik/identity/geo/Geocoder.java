package io.lumnik.identity.geo;

import java.util.Optional;

public interface Geocoder {
    Optional<Coords> resolve(AddressInput addr);
}
