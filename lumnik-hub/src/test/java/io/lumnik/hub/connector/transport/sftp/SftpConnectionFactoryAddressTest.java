package io.lumnik.hub.connector.transport.sftp;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.testsupport.TestOutbound;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #2002: an SFTP host is judged like any address a tenant chose, before the SSH client starts. */
class SftpConnectionFactoryAddressTest {

    @Test
    void a_host_at_a_refused_address_is_never_connected_to() {
        assertThatThrownBy(() -> SftpConnectionFactory.connect(TestOutbound.ADDRESSES, "169.254.169.254", 22, "u",
                new SftpAuthMethod.Password("p"), false, null))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("never connects to");
    }

    /** #2002: the client offers only the identities the transport declared, and dials the host it was given. */
    @Test
    void the_client_reads_nothing_of_the_hubs_own_ssh() {
        SshClient client = SftpConnectionFactory.withoutTheHubsOwnSsh(SshClient.setUpDefaultClient());
        assertThat(client.getKeyIdentityProvider()).isSameAs(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        assertThat(client.getHostConfigEntryResolver()).isSameAs(HostConfigEntryResolver.EMPTY);
    }
}
