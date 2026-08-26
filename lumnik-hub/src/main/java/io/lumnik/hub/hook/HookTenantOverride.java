package io.lumnik.hub.hook;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

import static lombok.AccessLevel.PROTECTED;

@Getter @Setter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(schema = "core", name = "hook_tenant_override")
@IdClass(HookTenantOverride.PK.class)
public class HookTenantOverride {

    @Id @Column(name = "tenant_id") private Long tenantId;
    @Id @Column(name = "hook_id", length = 120) private String hookId;

    @Column(name = "enabled", nullable = false) private boolean enabled;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt = OffsetDateTime.now();
    @Column(name = "updated_by", nullable = false) private Long updatedBy = 0L;
    @Column(name = "script_content") private String scriptContent;

    public HookTenantOverride(Long tenantId, String hookId, boolean enabled) {
        this.tenantId = tenantId; this.hookId = hookId; this.enabled = enabled;
    }

    public static class PK implements Serializable {
        public Long tenantId;
        public String hookId;
        public PK() {}
        public PK(Long tenantId, String hookId) { this.tenantId = tenantId; this.hookId = hookId; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK p)) return false;
            return Objects.equals(tenantId, p.tenantId) && Objects.equals(hookId, p.hookId);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, hookId); }
    }
}
