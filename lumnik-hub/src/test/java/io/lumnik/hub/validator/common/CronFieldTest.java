package io.lumnik.hub.validator.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class CronFieldTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void validCron_5fields_returnsEmpty() throws Exception {
        Optional<String> err = CronField.validCron().apply(mapper.readTree("\"0 6 * * *\""));
        assertTrue(err.isEmpty(), () -> "expected valid, got: " + err.orElse(""));
    }

    @Test void blank_returnsError() throws Exception {
        Optional<String> err = CronField.validCron().apply(mapper.readTree("\"\""));
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("blank") || err.get().contains("cron"));
    }

    @Test void garbage_returnsError() throws Exception {
        Optional<String> err = CronField.validCron().apply(mapper.readTree("\"not a cron\""));
        assertTrue(err.isPresent());
    }

    @Test void garbageNumbers_returnsError() throws Exception {
        Optional<String> err = CronField.validCron().apply(mapper.readTree("\"99 99 99 99 99\""));
        assertTrue(err.isPresent(),
            () -> "expected error for out-of-range numbers, got empty");
    }
}
