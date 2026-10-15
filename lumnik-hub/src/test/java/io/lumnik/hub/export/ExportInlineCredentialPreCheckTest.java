package io.lumnik.hub.export;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Its own test, separate from ExportManifestParserTest: this pre-check has to run BEFORE
 *  unknownKeys() is consulted by the door, and the test that matters is not "does it refuse" but
 *  "does the SPECIFIC message win over the generic one" — see ExportApplyResource's ordering. */
class ExportInlineCredentialPreCheckTest {

    private final ExportManifestParser parser = new ExportManifestParser();

    private static String manifestWith(String destinationExtra) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: Export
            metadata:
              name: x
            spec:
              scope: sales
              source:
                entity: AcmeCustomer
              columns: [code]
              order: [code]
              format:
                kind: csv
              destination:
                kind: sftp
                host: h
                user: u
                path: /p
                host_key_fingerprint: "SHA256:AAAA"
                %s
              mode: snapshot
            """.formatted(destinationExtra);
    }

    @Test
    void passwordIsRefusedWithARedirectToPasswordEnv() {
        JsonNode root = parser.readTree(manifestWith("password: hunter2"));
        Optional<Response> refusal = parser.refuseInlineCredentials(root);
        assertThat(refusal).isPresent();
        assertThat(refusal.get().getStatus()).isEqualTo(400);
        assertThat(refusal.get().getEntity().toString()).contains("password_env");
    }

    @Test
    void privateKeyIsRefusedWithARedirectToKeyEnvNotPrivateKeyEnv() {
        JsonNode root = parser.readTree(manifestWith("private_key: \"-----BEGIN...\""));
        Optional<Response> refusal = parser.refuseInlineCredentials(root);
        assertThat(refusal).isPresent();
        assertThat(refusal.get().getEntity().toString()).contains("key_env");
        assertThat(refusal.get().getEntity().toString()).doesNotContain("private_key_env");
    }

    @Test
    void accessKeyIsRefused() {
        JsonNode root = parser.readTree(manifestWith("access_key: AKIAABCD"));
        assertThat(parser.refuseInlineCredentials(root)).isPresent();
    }

    @Test
    void secretKeyIsRefused() {
        JsonNode root = parser.readTree(manifestWith("secret_key: shh"));
        assertThat(parser.refuseInlineCredentials(root)).isPresent();
    }

    @Test
    void anyOtherUnrecognizedKeyIsLeftAlone() {
        JsonNode root = parser.readTree(manifestWith("bogus_field: 1"));
        assertThat(parser.refuseInlineCredentials(root)).isEmpty();
    }

    @Test
    void aCleanManifestIsNotRefused() {
        JsonNode root = parser.readTree(manifestWith("key_env: SOME_KEY"));
        assertThat(parser.refuseInlineCredentials(root)).isEmpty();
    }

    /** The ordering claim itself: a manifest with BOTH an inline password and some other unknown
     *  key still gets the specific message when refuseInlineCredentials runs first — this is what
     *  proves the pre-check, not the generic unknownKeys refusal, is what a caller actually reads. */
    @Test
    void theSpecificMessageWinsOverTheGenericOneWhenBothWouldFire() {
        JsonNode root = parser.readTree(manifestWith("password: hunter2\n    bogus_field: 1"));
        Optional<Response> credRefusal = parser.refuseInlineCredentials(root);
        assertThat(credRefusal).isPresent();
        assertThat(credRefusal.get().getEntity().toString()).contains("password_env");
        // unknownKeys() would ALSO have flagged bogus_field — confirming the door must check
        // refuseInlineCredentials() first, or this message is unreachable.
        assertThat(parser.unknownKeys(root)).isNotEmpty();
    }

    /**
     * The invariant the whole pre-check rests on, made executable: the four inline-credential
     * names are never added to the parser's allowed-keys surface. Adding them to earn a nicer
     * refusal message would make them look like part of the contract — which is the one thing this
     * refusal exists to prevent — and it would silently disarm the pre-check's reason to exist.
     * Until now that rule lived only in prose, where nothing could break it loudly.
     *
     * <p>Token-exact, not substring: the surface legitimately carries {@code password_env},
     * {@code access_key_env} and {@code secret_key_env}, each of which CONTAINS the bare name it
     * redirects away from, so a {@code doesNotContain("password")} over the raw string would fail
     * against a perfectly correct surface.
     */
    @Test
    void theFourInlineCredentialNamesAreNeverInTheHonoredSurface() {
        List<String> honored =
            Arrays.asList(ExportManifestParser.honoredSurface().split("[^A-Za-z0-9_]+"));

        assertThat(honored)
            .as("an inline credential name in the surface reads as part of the contract")
            .doesNotContain("password", "private_key", "access_key", "secret_key");

        assertThat(honored)
            .as("the control: the surface really is being read, and the *_env names it redirects "
                + "TO are present — without this a broken split would pass the assertion above")
            .contains("password_env", "key_env", "access_key_env", "secret_key_env");
    }
}
