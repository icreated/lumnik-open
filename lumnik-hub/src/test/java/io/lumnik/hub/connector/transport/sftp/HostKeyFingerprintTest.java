package io.lumnik.hub.connector.transport.sftp;

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fixtures are real ed25519 keys and their fingerprints as computed by {@code ssh-keygen -lf},
 * not by this code — an oracle that cannot agree with a bug by construction.
 */
class HostKeyFingerprintTest {

    private static final String KEY_A =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIP9bDgsRYns31dqtYeaoGVC3lY4h+gRZbHf6KXOvhfuA lumnik-test";
    private static final String FP_A = "SHA256:2Mc+KLpMQYrIUixOVE1reT7p7X1jhTEtbSO037R7vos";
    private static final String FP_B = "SHA256:PnqbSTfTnUIsj9hthQnLoFIT3BRkCbegRFVt/1XwEqg";

    private static PublicKey keyA() throws Exception {
        return AuthorizedKeyEntry.parseAuthorizedKeyEntry(KEY_A).resolvePublicKey(null, null, null);
    }

    @Test
    void renders_the_openssh_sha256_form() throws Exception {
        assertThat(HostKeyFingerprint.of(keyA())).isEqualTo(FP_A);
    }

    @Test
    void matches_the_declared_fingerprint() throws Exception {
        assertThat(HostKeyFingerprint.matches(FP_A, keyA())).isTrue();
    }

    @Test
    void accepts_the_fingerprint_without_its_prefix() throws Exception {
        assertThat(HostKeyFingerprint.matches("2Mc+KLpMQYrIUixOVE1reT7p7X1jhTEtbSO037R7vos", keyA()))
            .isTrue();
    }

    @Test
    void tolerates_surrounding_whitespace_and_prefix_case() throws Exception {
        assertThat(HostKeyFingerprint.matches("  sha256:2Mc+KLpMQYrIUixOVE1reT7p7X1jhTEtbSO037R7vos  ",
            keyA())).isTrue();
    }

    @Test
    void refuses_another_servers_key() throws Exception {
        assertThat(HostKeyFingerprint.matches(FP_B, keyA())).isFalse();
    }

    @Test
    void a_blank_declaration_is_never_a_match() throws Exception {
        assertThat(HostKeyFingerprint.matches(null, keyA())).isFalse();
        assertThat(HostKeyFingerprint.matches("   ", keyA())).isFalse();
    }

    @Test
    void rejects_a_null_key_rather_than_matching_nothing() {
        assertThatThrownBy(() -> HostKeyFingerprint.of(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
