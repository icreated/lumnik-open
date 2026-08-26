package io.lumnik.hub.notify;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Boot-time verdict on the email posture. Mock is the fail-safe default (no SMTP host, no
 * 5-second failure loop) — but a configured host with mock still on would deliver nothing
 * while looking configured: named loudly here instead of discovered in three weeks.
 */
@ApplicationScoped
public class SmtpConfigSanity {

    private static final Logger LOG = Logger.getLogger(SmtpConfigSanity.class);

    enum Verdict { LIVE, MOCKED_WITH_HOST, LIVE_WITHOUT_HOST, MOCKED }

    @Inject @ConfigProperty(name = "lumnik.smtp.host")
    Optional<String> smtpHost;

    @Inject @ConfigProperty(name = "quarkus.mailer.mock", defaultValue = "true")
    boolean mock;

    static Verdict verdict(boolean hostConfigured, boolean mock) {
        if (!hostConfigured) return mock ? Verdict.MOCKED : Verdict.LIVE_WITHOUT_HOST;
        return mock ? Verdict.MOCKED_WITH_HOST : Verdict.LIVE;
    }

    void onStart(@Observes @Priority(9) StartupEvent ev) {
        switch (verdict(smtpHost.filter(h -> !h.isBlank()).isPresent(), mock)) {
            case LIVE -> LOG.infof("email delivery live via %s", smtpHost.orElse("?"));
            case MOCKED_WITH_HOST -> LOG.warn(
                "LUMNIK_SMTP_HOST is set but delivery is still mocked — set LUMNIK_SMTP_MOCK=false to go live");
            case LIVE_WITHOUT_HOST -> LOG.warn(
                "LUMNIK_SMTP_MOCK=false but LUMNIK_SMTP_HOST is not set — the mailer targets"
                + " localhost and the email consumer will loop on failures every 5s; set"
                + " LUMNIK_SMTP_HOST or drop LUMNIK_SMTP_MOCK");
            case MOCKED -> LOG.debug("email delivery mocked (no SMTP host configured)");
        }
    }
}
