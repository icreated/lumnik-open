package io.lumnik.hub.notify;

import io.vertx.ext.mail.SMTPException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MailFailureTest {

    private static Throwable wrapped(Throwable cause) {
        return new CompletionException(cause);
    }

    private static Throwable smtp(int code) {
        return wrapped(new SMTPException("rejected ko@secret.example", code, List.of("ko@secret.example"), true));
    }

    @Test
    void a_refused_password_is_an_authentication_refusal() {
        assertEquals("authentication refused (SMTP 535)", MailFailure.describe(smtp(535)));
        assertEquals("authentication refused (SMTP 530)", MailFailure.describe(smtp(530)));
    }

    @Test
    void a_refused_address_or_message_does_not_pretend_to_know_which_command_failed() {
        assertEquals("address or message refused (SMTP 550)", MailFailure.describe(smtp(550)));
        assertEquals("address or message refused (SMTP 553)", MailFailure.describe(smtp(553)));
    }

    /** A 550 can come from MAIL FROM or DATA as well as RCPT TO: whatever the context says, the words are the same. */
    @Test
    void a_550_from_the_sender_or_the_data_step_reads_the_same_as_one_from_the_recipient() {
        for (String context : new String[] {"sender address not accepted", "message data refused", "recipient address not accepted"})
            assertEquals("address or message refused (SMTP 550)", MailFailure.describe(
                wrapped(new SMTPException(context, 550, List.of("550 policy"), true))), context);
    }

    @Test
    void any_other_reply_is_named_by_its_code() {
        assertEquals("refused by the relay (SMTP 554)", MailFailure.describe(smtp(554)));
        assertEquals("refused by the relay (SMTP 451)", MailFailure.describe(smtp(451)));
    }

    @Test
    void a_dead_or_unknown_relay_is_unreachable() {
        assertEquals("relay unreachable", MailFailure.describe(wrapped(new ConnectException("refused"))));
        assertEquals("relay unreachable", MailFailure.describe(wrapped(new UnknownHostException("smtp.nowhere"))));
        assertEquals("relay unreachable", MailFailure.describe(wrapped(new SocketTimeoutException("slow"))));
    }

    @Test
    void a_failed_handshake_is_a_tls_refusal() {
        assertEquals("TLS refused", MailFailure.describe(wrapped(new SSLHandshakeException("bad cert"))));
    }

    @Test
    void anything_else_is_the_class_of_the_unwrapped_cause() {
        assertEquals("IllegalStateException", MailFailure.describe(wrapped(new IllegalStateException("down"))));
        assertEquals("IllegalStateException", MailFailure.describe(new IllegalStateException("down")));
        assertEquals("CompletionException", MailFailure.describe(new CompletionException(null)));
    }

    @Test
    void the_servers_words_and_the_address_never_appear() {
        assertFalse(MailFailure.describe(smtp(550)).contains("secret.example"));
        assertFalse(MailFailure.describe(new IllegalStateException("550 ko@secret.example")).contains("secret"));
    }
}
