package io.lumnik.hub.notify;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The one place a mail leaves the hub — a seam a test can make fail, which the mailer itself does not allow. */
@ApplicationScoped
public class MailTransport {

    @Inject Mailer mailer;

    public void send(Mail mail) {
        mailer.send(mail);
    }
}
