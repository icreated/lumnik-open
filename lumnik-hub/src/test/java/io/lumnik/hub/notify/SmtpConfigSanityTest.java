package io.lumnik.hub.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anti-silence guard for email delivery (chantier C3): mock stays on by default
 * (fail-safe — no 5-second failure loop against a missing SMTP host), but a host
 * configured while mock is still on is the classic silent failure and must be named
 * loudly at boot.
 */
class SmtpConfigSanityTest {

    @Test
    void host_set_with_mock_still_on_is_the_named_silent_failure() {
        assertThat(SmtpConfigSanity.verdict(true, true))
            .isEqualTo(SmtpConfigSanity.Verdict.MOCKED_WITH_HOST);
    }

    @Test
    void host_set_and_mock_off_is_live() {
        assertThat(SmtpConfigSanity.verdict(true, false))
            .isEqualTo(SmtpConfigSanity.Verdict.LIVE);
    }

    @Test
    void no_host_with_mock_on_is_plain_mocked() {
        assertThat(SmtpConfigSanity.verdict(false, true)).isEqualTo(SmtpConfigSanity.Verdict.MOCKED);
    }

    @Test
    void mock_off_without_host_is_the_footgun_not_mocked() {
        // quarkus.mailer.host defaults to localhost: with mock off and no host set the
        // mailer goes LIVE against localhost and the 5-second consumer loops on failures —
        // the exact loop the fail-safe default exists to prevent. Never "mocked".
        assertThat(SmtpConfigSanity.verdict(false, false))
            .isEqualTo(SmtpConfigSanity.Verdict.LIVE_WITHOUT_HOST);
    }

    @Test
    void the_self_host_defaults_boot_plain_mocked_without_a_warning() throws IOException {
        // A default self-host install sets no SMTP knob: the compose defaults alone must give
        // the quiet MOCKED verdict, not a WARN nobody can act on. The host stays UNSET (a
        // value-less entry, null here) — never empty: the mailer refuses a defined-but-empty
        // host at boot, where an absent one takes its own default.
        JsonNode env = new YAMLMapper()
            .readTree(Files.readString(Path.of("../docker-compose.selfhost.yml")))
            .path("services").path("hub").path("environment");
        assertThat(env.has("LUMNIK_SMTP_HOST")).as("the compose still passes the host through").isTrue();
        JsonNode host = env.get("LUMNIK_SMTP_HOST");
        assertThat(host.isNull()).as("LUMNIK_SMTP_HOST default in the compose: %s", host).isTrue();
        assertThat(env.path("LUMNIK_SMTP_MOCK").asText()).isEqualTo("${LUMNIK_SMTP_MOCK:-true}");

        assertThat(SmtpConfigSanity.verdict(false, true)).isEqualTo(SmtpConfigSanity.Verdict.MOCKED);
    }
}
