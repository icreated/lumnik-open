package io.lumnik.identity.org;

import java.math.BigDecimal;
import java.time.Instant;

public record OrgDto(
    Long id,
    Long tenantId,
    Long orgId,
    String code,
    String name,
    String description,
    Long parentOrgId,
    boolean summary,
    boolean active,
    Instant createdAt,
    Instant updatedAt,
    String addressLine1,
    String addressLine2,
    String postalCode,
    String city,
    String country,
    BigDecimal latitude,
    BigDecimal longitude,
    Instant geocodedAt
) {
    static OrgDto from(Organization o) {
        return new OrgDto(
            o.getId(), o.getTenantId(), o.getOrgId(),
            o.getCode(), o.getName(), o.getDescription(),
            o.getParentOrgId(), o.isSummary(), o.isActive(),
            o.getCreatedAt(), o.getUpdatedAt(),
            o.getAddressLine1(), o.getAddressLine2(), o.getPostalCode(),
            o.getCity(), o.getCountry(),
            o.getLatitude(), o.getLongitude(), o.getGeocodedAt()
        );
    }
}
