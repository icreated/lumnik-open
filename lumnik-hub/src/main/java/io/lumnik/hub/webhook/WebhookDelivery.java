package io.lumnik.hub.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Mutability;
import io.lumnik.hub.connector.persistence.converters.ImmutableJsonNodePlan;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

import static lombok.AccessLevel.PROTECTED;

@Getter @Setter
@NoArgsConstructor(access = PROTECTED)
@Entity
@Table(schema = "core", name = "webhook_delivery")
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "wd_seq")
    @SequenceGenerator(name = "wd_seq", sequenceName = "core.webhook_delivery_id_seq",
                       allocationSize = 1)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Mutability(ImmutableJsonNodePlan.class)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "attempted_at", nullable = false)
    private OffsetDateTime attemptedAt = OffsetDateTime.now();

    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "attempts", nullable = false)
    private int attempts = 1;

    @Column(name = "succeeded", nullable = false)
    private boolean succeeded;

    /** Retry lease (V37): a sweep owns this row until this instant. NULL = unclaimed. */
    @Column(name = "claimed_until")
    private OffsetDateTime claimedUntil;
}
