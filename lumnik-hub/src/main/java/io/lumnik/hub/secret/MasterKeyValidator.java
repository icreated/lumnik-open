package io.lumnik.hub.secret;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Startup
@Singleton
public class MasterKeyValidator {

    @ConfigProperty(name = "lumnik.secret.master-key")
    String masterKey;

    @PostConstruct
    void validate() {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException(
                "lumnik.secret.master-key is not set. Generate one with `openssl rand -base64 32` " +
                "and export LUMNIK_SECRET_MASTER_KEY before starting lumnik.");
        }
        if ("CHANGE_ME_AT_DEPLOY_TIME_OR_STARTUP_WILL_FAIL".equals(masterKey)) {
            throw new IllegalStateException(
                "lumnik.secret.master-key is still the placeholder. Set a real master key " +
                "via env LUMNIK_SECRET_MASTER_KEY.");
        }
        if (masterKey.length() < 16) {
            throw new IllegalStateException(
                "lumnik.secret.master-key is too short (< 16 chars). Use `openssl rand -base64 32`.");
        }
    }
}
