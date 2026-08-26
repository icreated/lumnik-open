package io.lumnik.identity.tenant;

import io.lumnik.common.domain.AuditedEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import static lombok.AccessLevel.PROTECTED;

@Getter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(schema = "identity", name = "tenant", uniqueConstraints = {
    @UniqueConstraint(name = "uq_tenant_code", columnNames = "code")
})
public class Tenant extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "tenant_seq")
    @SequenceGenerator(name = "tenant_seq", sequenceName = "identity.tenant_id_seq", allocationSize = 1)
    @Column(name = "id")
    private Long id;

    @Setter
    @NotBlank
    @Size(max = 40)
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Setter
    @NotBlank
    @Size(max = 60)
    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Setter
    @Size(max = 255)
    @Column(name = "description", length = 255)
    private String description;

    public Tenant(String code, String name) {
        this.code = code.strip();
        this.name = name.strip();
    }
}
