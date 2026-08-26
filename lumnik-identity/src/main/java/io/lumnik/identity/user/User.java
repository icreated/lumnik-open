package io.lumnik.identity.user;

import io.lumnik.common.audit.Auditable;
import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

import static lombok.AccessLevel.NONE;
import static lombok.AccessLevel.PROTECTED;

@Getter
@Setter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Auditable(fields = {"login", "email", "locked", "defaultScope"})
@Table(schema = "identity", name = "app_user", uniqueConstraints = {
    @UniqueConstraint(name = "uq_user_login", columnNames = {"tenant_id", "login"})
})
public class User extends TenantedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_user_seq")
    @SequenceGenerator(name = "app_user_seq", sequenceName = "identity.app_user_id_seq", allocationSize = 1)
    @Column(name = "id")
    @Setter(NONE)
    private Long id;

    @NotBlank
    @Size(max = 255)
    @Column(name = "login", nullable = false, length = 255)
    private String login;

    @NotBlank
    @Size(max = 60)
    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Email
    @Size(max = 120)
    @Column(name = "email", length = 120)
    private String email;

    @Size(max = 255)
    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "is_locked", nullable = false)
    private boolean locked = false;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "default_scope", length = 64)
    private String defaultScope;

    @Size(max = 255)
    @Column(name = "idp_issuer", length = 255)
    private String idpIssuer;

    @Size(max = 255)
    @Column(name = "idp_subject", length = 255)
    private String idpSubject;

    public User(@NotNull Long tenantId, String login, String name) {
        setTenantId(tenantId);
        this.login = login.strip();
        this.name = name.strip();
    }

    /** True iff this user may authenticate: not archived (SCIM active:false), not soft-deleted, not locked. */
    public boolean isLoginActive() {
        return !isArchived() && !isDeleted() && !locked;
    }

    public void unlock() {
        this.locked = false;
    }
}
