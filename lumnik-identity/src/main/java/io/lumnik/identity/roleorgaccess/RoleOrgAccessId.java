package io.lumnik.identity.roleorgaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static lombok.AccessLevel.PROTECTED;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = PROTECTED)
public class RoleOrgAccessId {

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    public RoleOrgAccessId(Long roleId, Long orgId) {
        this.roleId = roleId;
        this.orgId = orgId;
    }
}
