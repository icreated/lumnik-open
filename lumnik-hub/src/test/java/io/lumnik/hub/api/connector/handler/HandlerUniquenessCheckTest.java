package io.lumnik.hub.api.connector.handler;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class HandlerUniquenessCheckTest {

    @Inject HandlerUniquenessCheck check;

    @Test void startupBean_isInjectable_andDidNotThrow() {
        assertNotNull(check);
    }
}
