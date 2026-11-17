package io.lumnik.hub.security;

import io.lumnik.hub.security.DeploymentDefaultsGuard.DevPassword;
import io.lumnik.hub.security.DeploymentDefaultsGuard.Launch;
import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** #1995: a hub refuses to start on a published default, the way MasterKeyValidator refuses its placeholder. */
class DeploymentDefaultsGuardTest {

    private static final String GOOD_KEY = "a-deployment-own-key-for-this-test";
    private static final String[] DEV_VALUES = DeploymentDefaultsGuard.DEV_PASSWORDS.stream()
        .map(DevPassword::dev).toArray(String[]::new);

    private static Map<String, String> goodPasswords() {
        Map<String, String> p = new HashMap<>();
        DeploymentDefaultsGuard.DEV_PASSWORDS.forEach(d -> p.put(d.property(), "generated-" + d.env()));
        return p;
    }

    private static Map<String, String> devPasswords() {
        Map<String, String> p = new HashMap<>();
        DeploymentDefaultsGuard.DEV_PASSWORDS.forEach(d -> p.put(d.property(), d.dev()));
        return p;
    }

    private static List<String> refusals(Launch launch, boolean bypass, boolean ack, String key, Map<String, String> pw) {
        return DeploymentDefaultsGuard.refusals(launch, bypass, ack, key, name -> Optional.ofNullable(pw.get(name)));
    }

    private static List<String> deployment(boolean bypass, boolean ack, String key, Map<String, String> passwords) {
        return refusals(Launch.DEPLOYMENT, bypass, ack, key, passwords);
    }

    @Test
    void aDeploymentWithItsOwnValuesStarts() {
        assertThat(deployment(false, false, GOOD_KEY, goodPasswords())).isEmpty();
    }

    @Test
    void everyNormalLaunchIsADeploymentButAHarnessAlone() {
        assertThat(DeploymentDefaultsGuard.launch(LaunchMode.NORMAL, List.of("prod"))).isEqualTo(Launch.DEPLOYMENT);
        assertThat(DeploymentDefaultsGuard.launch(LaunchMode.NORMAL, List.of("k8sdev"))).as("a hand-run image")
            .isEqualTo(Launch.DEPLOYMENT);
        assertThat(DeploymentDefaultsGuard.launch(LaunchMode.NORMAL, List.of("staging"))).isEqualTo(Launch.DEPLOYMENT);
        assertThat(DeploymentDefaultsGuard.launch(LaunchMode.NORMAL, List.of("otkauth"))).isEqualTo(Launch.HARNESS);
        assertThat(DeploymentDefaultsGuard.launch(LaunchMode.NORMAL, List.of("otkauth", "prod")))
            .as("a harness profile does not exempt the profiles beside it").isEqualTo(Launch.DEPLOYMENT);
        assertThat(DeploymentDefaultsGuard.launch(LaunchMode.DEVELOPMENT, List.of("dev"))).isEqualTo(Launch.LOCAL);
        assertThat(DeploymentDefaultsGuard.launch(LaunchMode.TEST, List.of("test"))).isEqualTo(Launch.LOCAL);
    }

    /** #1991: no deployment can be told it serves one tenant; a local launch or a harness may. */
    @Test
    void aDeploymentRefusesSingleTenancy() {
        Map<String, String> single = Map.of("lumnik.tenancy", "single");
        assertThat(refusals(Launch.DEPLOYMENT, false, false, GOOD_KEY, single))
            .singleElement().asString().contains("lumnik.tenancy=single");
        assertThat(refusals(Launch.DEPLOYMENT, false, false, GOOD_KEY, Map.of("lumnik.tenancy", "auto"))).isEmpty();
        assertThat(refusals(Launch.HARNESS, false, false, GOOD_KEY, single)).isEmpty();
        assertThat(refusals(Launch.LOCAL, false, false, GOOD_KEY, single)).isEmpty();
    }

    @Test
    void aLocalLaunchRefusesNothing() {
        assertThat(refusals(Launch.LOCAL, true, false, DeploymentDefaultsGuard.DEV_CRYPTO_KEY, devPasswords())).isEmpty();
    }

    /** The OTK harness runs on CI's development roles, but sets its own crypto key and checks tokens. */
    @Test
    void aHarnessKeepsOnlyItsDatabasePasswords() {
        assertThat(refusals(Launch.HARNESS, false, false, GOOD_KEY, devPasswords())).isEmpty();
        assertThat(refusals(Launch.HARNESS, false, false, DeploymentDefaultsGuard.DEV_CRYPTO_KEY, devPasswords()))
            .singleElement().asString().contains("LUMNIK_CRYPTO_SECRET_KEY");
        assertThat(refusals(Launch.HARNESS, true, false, GOOD_KEY, devPasswords()))
            .singleElement().asString().contains(DeploymentDefaultsGuard.ACKNOWLEDGEMENT);
    }

    /** As MasterKeyValidator measures its own: a padded short key is the operator's, and so is its length. */
    @Test
    void theKeyIsMeasuredAsWritten() {
        assertThat(deployment(false, false, "x" + " ".repeat(15), goodPasswords())).isEmpty();
    }

    @Test
    void devBypassInProdNeedsTheAcknowledgement() {
        assertThat(deployment(true, false, GOOD_KEY, goodPasswords()))
            .singleElement().asString().contains("lumnik.security.dev-bypass", DeploymentDefaultsGuard.ACKNOWLEDGEMENT);
        assertThat(deployment(true, true, GOOD_KEY, goodPasswords())).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "short-key-15chr", DeploymentDefaultsGuard.DEV_CRYPTO_KEY})
    void theCryptoKeyMustBeTheDeploymentsOwn(String key) {
        assertThat(deployment(false, false, key, goodPasswords()))
            .singleElement().asString().contains("lumnik.crypto.secret-key", "LUMNIK_CRYPTO_SECRET_KEY");
    }

    @Test
    void eachDatasourceRefusesItsDevPassword() {
        for (DevPassword d : DeploymentDefaultsGuard.DEV_PASSWORDS) {
            Map<String, String> p = goodPasswords();
            p.put(d.property(), d.dev());
            assertThat(deployment(false, false, GOOD_KEY, p)).as(d.property())
                .singleElement().asString().contains(d.property(), d.env());
        }
    }

    @Test
    void aRefusalNamesTheSettingNeverItsValue() {
        List<String> all = deployment(true, false, DeploymentDefaultsGuard.DEV_CRYPTO_KEY, devPasswords());
        assertThat(all).hasSize(2 + DeploymentDefaultsGuard.DEV_PASSWORDS.size());
        assertThat(String.join("\n", all)).doesNotContain(DeploymentDefaultsGuard.DEV_CRYPTO_KEY).doesNotContain(DEV_VALUES);
    }

    /** The defaults the guard refuses, and their variables, are the ones application.properties ships. */
    @Test
    void theRefusedDefaultsAreTheShippedOnes() throws IOException {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        Map<String, String> shipped = new HashMap<>();
        Matcher m = Pattern.compile(
                "(?m)^(quarkus\\.datasource[^=]*\\.password|lumnik\\.crypto\\.secret-key)=\\$\\{([A-Z_]+):([^}]*)}$")
            .matcher(props);
        while (m.find()) shipped.put(m.group(1), m.group(2) + ":" + m.group(3));
        Map<String, String> refused = new HashMap<>();
        DeploymentDefaultsGuard.DEV_PASSWORDS.forEach(d -> refused.put(d.property(), d.env() + ":" + d.dev()));
        refused.put("lumnik.crypto.secret-key", "LUMNIK_CRYPTO_SECRET_KEY:" + DeploymentDefaultsGuard.DEV_CRYPTO_KEY);
        assertThat(shipped).isEqualTo(refused);
    }
}
