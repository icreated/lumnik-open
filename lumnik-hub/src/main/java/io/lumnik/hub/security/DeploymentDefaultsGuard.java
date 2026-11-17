package io.lumnik.hub.security;

import io.lumnik.common.text.Text;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A hub refuses to start on a default this repository publishes (#1995), as {@code MasterKeyValidator} and
 * {@code FingerprintHmacKey} refuse their placeholders: the token checks switched off without an acknowledgement, the
 * development crypto key, a datasource's development password. The installers set every one of them; a hand-rolled
 * {@code docker run} of the image is what came up working and silent.
 *
 * <p>Every normal launch, whatever its profile ({@code %prod}, {@code %k8sdev}, a name of the operator's own): only
 * dev and test mode keep their defaults. A launch on the OTK harness's profile alone ({@code %otkauth}, booted in CI
 * against the service container's roles) keeps only the datasource passwords; it sets its own crypto key and checks
 * tokens. A token check switched off is also said at WARN on every normal launch.
 */
@Startup(1)   // before PlatformFlywayRunner (StartupEvent, priority 2): refused before the migrations run
@Singleton
public class DeploymentDefaultsGuard {

    private static final Logger LOG = Logger.getLogger(DeploymentDefaultsGuard.class);

    /** The second property dev-bypass needs on a normal launch, worded as the Helm chart's {@code auth.iUnderstandNoAuth}. */
    static final String ACKNOWLEDGEMENT = "lumnik.security.i-understand-no-auth";

    /** The development defaults application.properties ships (DeploymentDefaultsGuardTest holds the two together). */
    static final String DEV_CRYPTO_KEY = "dev-only-key-do-not-use-in-prod-please-rotate";
    static final List<DevPassword> DEV_PASSWORDS = List.of(
        new DevPassword("quarkus.datasource.password", "LUMNIK_APP_DB_PASSWORD", "lumnik_app_dev_password"),
        new DevPassword("quarkus.datasource.\"admin\".password", "LUMNIK_ADMIN_DB_PASSWORD", "lumnik_admin_dev_password"),
        new DevPassword("quarkus.datasource.\"readonly\".password", "LUMNIK_READONLY_DB_PASSWORD",
            "lumnik_readonly_dev_password"));

    /** A datasource password's property, the variable that sets it, and the development value it falls back to. */
    record DevPassword(String property, String env, String dev) {}
    private static final int MIN_KEY_LENGTH = 16;

    /** The profiles of a test harness, whose database roles are the development ones: never a deployment's. */
    static final Set<String> HARNESS_PROFILES = Set.of("otkauth");

    /** What a launch is: local (dev, test), a harness (its profiles all harnesses'), or a deployment (anything else). */
    enum Launch { LOCAL, HARNESS, DEPLOYMENT }

    @ConfigProperty(name = "lumnik.security.dev-bypass")
    boolean devBypass;

    @ConfigProperty(name = ACKNOWLEDGEMENT)
    boolean acknowledged;

    @ConfigProperty(name = "lumnik.crypto.secret-key")
    Optional<String> cryptoKey;

    @PostConstruct
    void validate() {
        Launch launch = launch(LaunchMode.current(), ConfigUtils.getProfiles());
        if (devBypass && LaunchMode.current() == LaunchMode.NORMAL) {
            LOG.warn("lumnik.security.dev-bypass is ON: this hub checks no token. Every caller is superadmin and picks "
                + "any tenant with X-Tenant-ID. For local development only.");
        }
        Config config = ConfigProvider.getConfig();
        List<String> refused = refusals(launch, devBypass, acknowledged, cryptoKey.orElse(null),
            p -> config.getOptionalValue(p, String.class));
        if (!refused.isEmpty()) {
            throw new IllegalStateException("lumnik refuses to start on a published default:\n  - "
                + String.join("\n  - ", refused));
        }
    }

    /** A harness only when every active profile is one: {@code otkauth,prod} is a deployment. */
    static Launch launch(LaunchMode mode, List<String> profiles) {
        if (mode != LaunchMode.NORMAL) return Launch.LOCAL;
        return !profiles.isEmpty() && HARNESS_PROFILES.containsAll(profiles) ? Launch.HARNESS : Launch.DEPLOYMENT;
    }

    /** What a hub in this state must not start with: each names the setting, never its value. */
    static List<String> refusals(Launch launch, boolean devBypass, boolean acknowledged, String cryptoKey,
                                 Function<String, Optional<String>> setting) {
        List<String> refused = new ArrayList<>();
        if (launch == Launch.LOCAL) return refused;
        if (devBypass && !acknowledged) {
            refused.add("lumnik.security.dev-bypass is on, so no token is checked. For local development only, also "
                + "set " + ACKNOWLEDGEMENT + "=true (env LUMNIK_SECURITY_I_UNDERSTAND_NO_AUTH); otherwise turn it off.");
        }
        if (Text.blank(cryptoKey) || DEV_CRYPTO_KEY.equals(cryptoKey) || cryptoKey.length() < MIN_KEY_LENGTH) {
            refused.add("lumnik.crypto.secret-key is unset, the development default or shorter than " + MIN_KEY_LENGTH
                + " characters: set LUMNIK_CRYPTO_SECRET_KEY (openssl rand -base64 32).");
        }
        if (launch == Launch.HARNESS) return refused;
        if (setting.apply("lumnik.tenancy").filter("single"::equals).isPresent()) {
            refused.add("lumnik.tenancy=single is for dev and test only: set LUMNIK_TENANCY to auto (the hub counts its "
                + "tenants) or multi.");
        }
        for (DevPassword p : DEV_PASSWORDS) {
            if (setting.apply(p.property()).filter(p.dev()::equals).isPresent()) {
                refused.add(p.property() + " is the development default: set " + p.env()
                    + " (and the role's password in PostgreSQL).");
            }
        }
        return refused;
    }
}
