package io.lumnik.hub.connector.runtime;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #897: an endpoint waiting for a scheduled-run permit has no run row yet, so the connector still
 * reads as due on the next tick. The scheduler must not stack a second fan-out on the first one,
 * which would run every endpoint again once the first had finished with it.
 */
class ConnectorSchedulerPendingFanOutTest {

    private final UUID connectorId = UUID.randomUUID();
    private final ConnectorScheduler scheduler = new ConnectorScheduler() {
        @Override
        List<DueConnector> listDueConnectors() {
            // Created at the epoch, never run: due on every-minute cron, no window.
            return List.of(new DueConnector(connectorId, "* * * * *", null, Instant.EPOCH, null));
        }
    };

    @Test
    void a_due_connector_whose_fan_out_is_still_waiting_is_not_submitted_again() {
        scheduler.engine = mock(ConnectorEngine.class);
        when(scheduler.engine.hasPendingFanOut(connectorId)).thenReturn(true);

        assertThat(scheduler.tick()).isZero();
        verify(scheduler.engine, never()).submitAsync(any(), any());
    }

    @Test
    void a_due_connector_with_no_pending_fan_out_is_submitted() {
        scheduler.engine = mock(ConnectorEngine.class);

        assertThat(scheduler.tick()).isEqualTo(1);
        verify(scheduler.engine).submitAsync(eq(connectorId), any());
    }
}
