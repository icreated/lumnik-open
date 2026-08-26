package io.lumnik.identity.roleorgaccess;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(schema = "identity", name = "role_org_access")
public class RoleOrgAccess {

    @EmbeddedId
    private RoleOrgAccessId id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Setter
    @Column(name = "is_read_only", nullable = false)
    private boolean readOnly = false;

    @Setter
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy = 0L;

    @PrePersist
    protected void prePersist() {
        createdAt = Instant.now();
    }

    public RoleOrgAccess(Long tenantId, Long roleId, Long orgId, boolean readOnly) {
        this.id = new RoleOrgAccessId(roleId, orgId);
        this.tenantId = tenantId;
        this.readOnly = readOnly;
    }

    public Long getRoleId() { return id.getRoleId(); }
    public Long getOrgId()  { return id.getOrgId(); }
}
