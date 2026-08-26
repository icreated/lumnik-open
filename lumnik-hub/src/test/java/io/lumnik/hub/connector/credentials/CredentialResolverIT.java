package io.lumnik.hub.connector.credentials;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.connector.api.helpers.CredentialResolver;
import io.lumnik.hub.connector.api.types.ResolvedCredential;
import io.lumnik.hub.connector.domain.ConnectorCredential;
import io.lumnik.hub.connector.repository.ConnectorCredentialRepository;
import io.lumnik.hub.webhook.SecretCipher;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class CredentialResolverIT {

    @Inject CredentialResolver resolver;
    @Inject ConnectorCredentialRepository repo;
    @Inject SecretCipher cipher;

    @Test
    @TestTransaction
    void resolve_api_key_round_trip() {
        TenantContext.runAsSystem(() -> {
            var ct = new ConnectorCredential();
            ct.setTenantId(1L);
            ct.setName("test-key");
            ct.setKind("ApiKey");
            ct.setCiphertext(cipher.encrypt("sk_secret_123"));
            ct.setIv(new byte[0]);
            repo.persist(ct);
            repo.flush();

            var r = resolver.resolve(ct.getId());
            assertThat(r).isInstanceOf(ResolvedCredential.ApiKey.class);
            assertThat(((ResolvedCredential.ApiKey) r).key()).isEqualTo("sk_secret_123");
        });
    }

    @Test
    @TestTransaction
    void resolve_basic_round_trip() {
        TenantContext.runAsSystem(() -> {
            var ct = new ConnectorCredential();
            ct.setTenantId(1L);
            ct.setName("test-basic");
            ct.setKind("Basic");
            ct.setCiphertext(cipher.encrypt("alice:s3cr3t"));
            ct.setIv(new byte[0]);
            repo.persist(ct);
            repo.flush();

            var r = resolver.resolve(ct.getId());
            assertThat(r).isInstanceOf(ResolvedCredential.Basic.class);
            var basic = (ResolvedCredential.Basic) r;
            assertThat(basic.username()).isEqualTo("alice");
            assertThat(basic.password()).isEqualTo("s3cr3t");
        });
    }

    @Test
    @TestTransaction
    void resolve_missing_throws() {
        TenantContext.runAsSystem(() ->
            assertThatThrownBy(() -> resolver.resolve(java.util.UUID.randomUUID()))
                .isInstanceOf(io.lumnik.hub.connector.exceptions.CredentialNotFoundException.class)
        );
    }
}
