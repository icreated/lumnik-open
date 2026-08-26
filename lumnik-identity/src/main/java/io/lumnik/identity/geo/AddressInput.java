package io.lumnik.identity.geo;

public record AddressInput(String line1, String postalCode, String city, String country) {
    public boolean isUsable() {
        return (city != null && !city.isBlank())
            || (postalCode != null && !postalCode.isBlank())
            || (line1 != null && !line1.isBlank());
    }
}
