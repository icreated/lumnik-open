package io.lumnik.identity.role;

import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import static lombok.AccessLevel.NONE;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Setter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(schema = "identity", name = "app_role", uniqueConstraints = {
    @UniqueConstraint(name = "uq_role_name", columnNames = {"tenant_id", "name"})
})
public class Role extends TenantedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_role_seq")
    @SequenceGenerator(name = "app_role_seq", sequenceName = "identity.app_role_id_seq", allocationSize = 1)
    @Column(name = "id")
    @Setter(NONE)
    private Long id;

    @NotBlank
    @Size(max = 60)
    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Size(max = 255)
    @Column(name = "description", length = 255)
    private String description;

    @NotNull
    @Convert(converter = UserLevelConverter.class)
    @Column(name = "user_level", nullable = false, length = 2)
    private UserLevel userLevel = UserLevel.CLIENT_ORG;

    @Column(name = "is_manual", nullable = false)
    private boolean manual = false;

    public Role(@NotNull Long tenantId, String name) {
        setTenantId(tenantId);
        this.name = name.strip();
    }

    public Role(@NotNull Long tenantId, String name, UserLevel userLevel) {
        setTenantId(tenantId);
        this.name = name.strip();
        this.userLevel = userLevel;
    }
}
