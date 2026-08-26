package io.lumnik.hub.notify;

import jakarta.persistence.*;
import java.time.Instant;

/** Tenant-scoped email rule: events matching event_pattern go to recipient. */
@Entity
@Table(schema = "core", name = "notification_rule")
public class NotificationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "nr_seq")
    @SequenceGenerator(name = "nr_seq", sequenceName = "core.notification_rule_id_seq",
                       allocationSize = 1)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "event_pattern", nullable = false, length = 120)
    private String eventPattern;

    @Column(name = "entity_filter", length = 120)
    private String entityFilter;

    @Column(name = "recipient", nullable = false, length = 320)
    private String recipient;

    @Column(name = "template", nullable = false, length = 120)
    private String template = "event-notification";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Same matching contract as WebhookEndpoint.matches: exact, or "prefix.*". */
    public boolean matches(String eventType) {
        if (!enabled || eventPattern == null) return false;
        return eventPattern.equals(eventType)
            || (eventPattern.endsWith(".*")
                && eventType.startsWith(eventPattern.substring(0, eventPattern.length() - 2)));
    }

    public Long getId()            { return id; }
    public String getEventPattern() { return eventPattern; }
    public boolean isEnabled()     { return enabled; }
    public void setTenantId(Long v)      { this.tenantId = v; }
    public void setEventPattern(String v) { this.eventPattern = v; }
    public void setEntityFilter(String v) { this.entityFilter = v; }
    public void setRecipient(String v)   { this.recipient = v; }
    public void setTemplate(String v)    { this.template = v; }
    public void setEnabled(boolean v)    { this.enabled = v; }
    public Long getTenantId()      { return tenantId; }
    public String getRecipient()   { return recipient; }
    public String getTemplate()    { return template; }
    public String getEntityFilter() { return entityFilter; }
}
