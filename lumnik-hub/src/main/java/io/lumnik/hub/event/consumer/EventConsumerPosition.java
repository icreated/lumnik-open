package io.lumnik.hub.event.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One cursor per outbox consumer. Rewinding last_event_id = replay. */
@Entity
@Table(schema = "core", name = "event_consumer")
public class EventConsumerPosition {

    @Id
    @Column(name = "consumer_name", length = 80)
    private String consumerName;

    @Column(name = "last_event_id", nullable = false)
    private long lastEventId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EventConsumerPosition() {}

    public String getConsumerName() { return consumerName; }
    public long getLastEventId()    { return lastEventId; }

    public void advanceTo(long eventId) {
        this.lastEventId = eventId;
        this.updatedAt = Instant.now();
    }
}
