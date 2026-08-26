package io.lumnik.identity.userrole;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

import static lombok.AccessLevel.PROTECTED;

@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(schema = "identity", name = "user_role")
public class UserRole {

    @EmbeddedId
    private UserRoleId id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

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

    public UserRole(Long tenantId, Long userId, Long roleId) {
        this.id = new UserRoleId(userId, roleId);
        this.tenantId = tenantId;
    }

    public Long getUserId() { return id.getUserId(); }
    public Long getRoleId() { return id.getRoleId(); }
}
