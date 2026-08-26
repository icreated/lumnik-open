package io.lumnik.hub.list;

import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import static lombok.AccessLevel.NONE;
import static lombok.AccessLevel.PROTECTED;

@Getter @Setter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(schema = "core", name = "list_definition",
       uniqueConstraints = @UniqueConstraint(name = "uq_list_definition_code",
                                             columnNames = {"tenant_id", "code"}))
public class ListDefinition extends TenantedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ld_seq")
    @SequenceGenerator(name = "ld_seq", sequenceName = "core.list_definition_id_seq",
                       allocationSize = 1)
    @Setter(NONE)
    private Long id;

    @NotBlank @Column(name = "code", nullable = false, length = 80)
    private String code;

    @NotBlank @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "system_owned", nullable = false)
    private boolean systemOwned;
}
