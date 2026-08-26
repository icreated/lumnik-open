package io.lumnik.hub.list;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.OffsetDateTime;

import static lombok.AccessLevel.NONE;
import static lombok.AccessLevel.PROTECTED;

@Getter @Setter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(schema = "core", name = "list_value",
       uniqueConstraints = @UniqueConstraint(name = "uq_list_value",
                                             columnNames = {"list_id", "value"}))
public class ListValue {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "lv_seq")
    @SequenceGenerator(name = "lv_seq", sequenceName = "core.list_value_id_seq",
                       allocationSize = 1)
    @Setter(NONE)
    private Long id;

    @Column(name = "list_id", nullable = false)
    private Long listId;

    @NotBlank @Column(name = "value", nullable = false, length = 80, updatable = false)
    private String value;

    @NotBlank @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "color", length = 16)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
