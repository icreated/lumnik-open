package io.lumnik.hub.export.run;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExportRunStatusTest {

    @Test
    void everyStatusRoundTripsThroughItsColumnValue() {
        for (ExportRunStatus s : ExportRunStatus.values())
            assertThat(ExportRunStatus.fromSql(s.sqlValue())).isEqualTo(s);
        assertThat(ExportRunStatus.RUNNING.sqlValue()).isEqualTo("Running");
        assertThat(ExportRunStatus.QUEUED.sqlValue()).isEqualTo("Queued");
    }

    @Test
    void everyFailureCodeRoundTripsAsItsLowercaseName() {
        for (ExportFailureCode c : ExportFailureCode.values()) {
            assertThat(c.sqlValue()).isEqualTo(c.name().toLowerCase(Locale.ROOT));
            assertThat(ExportFailureCode.fromSql(c.sqlValue())).isEqualTo(c);
        }
        for (ExportTrigger t : ExportTrigger.values())
            assertThat(ExportTrigger.fromSql(t.sqlValue())).isEqualTo(t);
    }

    @Test
    void anUnknownValueThrows() {
        assertThatThrownBy(() -> ExportRunStatus.fromSql("running"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportFailureCode.fromSql("DEFINITION"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportTrigger.fromSql("cron"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
