package io.lumnik.identity.geo;

import io.lumnik.common.text.Text;

public record AddressInput(String line1, String postalCode, String city, String country) {
    public boolean isUsable() {
        return Text.present(city)
            || Text.present(postalCode)
            || Text.present(line1);
    }
}
