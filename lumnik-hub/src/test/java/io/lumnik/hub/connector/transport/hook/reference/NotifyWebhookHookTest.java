package io.lumnik.hub.connector.transport.hook.reference;

import io.lumnik.hub.connector.transport.FileDescriptor;
import io.lumnik.hub.connector.transport.hook.FileSourceHookRunner;
import io.lumnik.hub.connector.transport.hook.HookSpec;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class NotifyWebhookHookTest {

    @Inject
    FileSourceHookRunner runner;

    @Test
    void noop_when_webhook_url_env_not_set() {
        // Without INGEST_WEBHOOK_URL env, hook is no-op (early return) — should not throw
        var hook = new HookSpec("kotlin", "reference/notify-webhook.kt");
        var file = new FileDescriptor("/notif.csv", 100, Instant.now(), null);

        // Smoke test: ensures script compiles + runs without error
        assertDoesNotThrow(() -> runner.postProcess(hook, file, null));
    }
}
