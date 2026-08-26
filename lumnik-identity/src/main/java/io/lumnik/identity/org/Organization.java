package io.lumnik.identity.org;

import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

import static lombok.AccessLevel.NONE;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Setter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(schema = "identity", name = "org", uniqueConstraints = {
    @UniqueConstraint(name = "uq_org_tenant_code", columnNames = {"tenant_id", "code"})
})
public class Organization extends TenantedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "org_seq")
    @SequenceGenerator(name = "org_seq", sequenceName = "identity.org_id_seq", allocationSize = 1)
    @Column(name = "id")
    @Setter(NONE)
    private Long id;

    @NotBlank
    @Size(max = 40)
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @NotBlank
    @Size(max = 60)
    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Size(max = 255)
    @Column(name = "description", length = 255)
    private String description;

    // Custom setter: validated to prevent self-reference
    @Setter(NONE)
    @Column(name = "parent_org_id")
    private Long parentOrgId;

    @Column(name = "is_summary", nullable = false)
    private boolean summary = false;

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "geocoded_at")
    private Instant geocodedAt;

    public Organization(@NotNull Long tenantId, String code, String name) {
        setTenantId(tenantId);
        this.code = code.strip();
        this.name = name.strip();
    }

    public void setParentOrgId(Long parentOrgId) {
        if (id != null && id.equals(parentOrgId))
            throw new IllegalArgumentException("Organization cannot be its own parent");
        this.parentOrgId = parentOrgId;
    }

    @PrePersist
    @PreUpdate
    private void validate() {
        if (id != null && id.equals(parentOrgId))
            throw new IllegalStateException("Organization cannot be its own parent");
    }
}
