package io.lumnik.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@Getter
@MappedSuperclass
@FilterDef(
    name = "tenantFilter",
    parameters = @ParamDef(name = "tenantId", type = Long.class)
)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class TenantedEntity extends AuditedEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Setter
    @Column(name = "org_id", nullable = false)
    private Long orgId = 0L;

    // Custom setter: tenantId is immutable after first assignment
    public void setTenantId(Long tenantId) {
        if (this.tenantId != null) throw new IllegalStateException("tenantId is immutable");
        this.tenantId = tenantId;
    }
}
