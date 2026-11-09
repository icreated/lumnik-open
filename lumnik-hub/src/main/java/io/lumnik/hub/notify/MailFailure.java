package io.lumnik.hub.notify;

import io.lumnik.common.error.Throwables;
import io.vertx.ext.mail.SMTPException;

import javax.net.ssl.SSLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Maps a failed send to a fixed phrase naming the gesture that fixes it (the password, the network, the
 * addresses). The server's text and the address never reach the row or the log: an SMTP reply code is a
 * number. The mailer wraps the real failure in a {@link CompletionException}, so it reads the whole cause chain.
 */
final class MailFailure {

    private MailFailure() {}

    static String describe(Throwable failure) {
        List<Throwable> chain = Throwables.causeChain(failure);
        for (Throwable t : chain)
            if (t instanceof SMTPException smtp) return refusal(smtp.getReplyCode());
        if (has(chain, SSLException.class)) return "TLS refused";
        if (has(chain, SocketException.class, SocketTimeoutException.class, UnknownHostException.class,
                UnresolvedAddressException.class))
            return "relay unreachable";
        return unwrap(failure).getClass().getSimpleName();
    }

    private static boolean has(List<Throwable> chain, Class<?>... kinds) {
        return chain.stream().anyMatch(t -> Arrays.stream(kinds).anyMatch(k -> k.isInstance(t)));
    }

    /** The first link that is not just the async wrapper. */
    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null)
            t = t.getCause();
        return t;
    }

    private static String refusal(int code) {
        return switch (code) {
            case 530, 534, 535, 454 -> "authentication refused (SMTP " + code + ")";
            // The code does not say which command was refused (MAIL FROM, RCPT TO, DATA): stay generic.
            case 550, 551, 553 -> "address or message refused (SMTP " + code + ")";
            default -> "refused by the relay (SMTP " + code + ")";
        };
    }
}
