package io.lumnik.hub.webhook;

import io.lumnik.common.domain.TenantedEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

import static lombok.AccessLevel.NONE;
import static lombok.AccessLevel.PROTECTED;

@Getter @Setter
@NoArgsConstructor(access = PROTECTED)
@Entity
// The real rule is NARROWER than JPA can say: since V46 uq_webhook_endpoint_name is a unique index
// partial on deleted_at IS NULL, so a soft-deleted row stops holding its name. The annotation is
// inert here (quarkus.hibernate-orm.database.generation=none — nothing creates or validates from
// it) and kept only for the same reason identity's entities keep theirs: it names the rule. Read
// V46 for what it actually is.
@Table(schema = "core", name = "webhook_endpoint",
       uniqueConstraints = @UniqueConstraint(name = "uq_webhook_endpoint_name",
                                             columnNames = {"tenant_id", "name"}))
public class WebhookEndpoint extends TenantedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "wh_seq")
    @SequenceGenerator(name = "wh_seq", sequenceName = "core.webhook_endpoint_id_seq",
                       allocationSize = 1)
    @Setter(NONE)
    private Long id;

    @NotBlank @Column(name = "name", nullable = false, length = 120)
    private String name;

    @NotBlank @Column(name = "target_url", nullable = false, length = 500)
    private String targetUrl;

    @NotBlank @Column(name = "platform", nullable = false, length = 32)
    private String platform = "generic";

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_mode", nullable = false, length = 16)
    private AuthMode authMode = AuthMode.HMAC;

    @Column(name = "secret_encrypted")
    private byte[] secretEncrypted;

    @NotEmpty
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "event_types", nullable = false, columnDefinition = "text[]")
    private List<String> eventTypes;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "entity_filter", length = 120)
    private String entityFilter;

    public boolean matches(String eventType) {
        return enabled && eventTypes != null && eventTypes.stream()
            .anyMatch(p -> p.equals(eventType) || (p.endsWith(".*")
                && eventType.startsWith(p.substring(0, p.length() - 2))));
    }
}
