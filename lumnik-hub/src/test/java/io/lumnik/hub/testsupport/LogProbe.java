package io.lumnik.hub.testsupport;

import org.jboss.logmanager.ExtLogRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * What a class wrote to the log while an action ran.
 *
 * <p>Some behaviour reaches the operator only as a log line — a connector that read less than its
 * source held, a run that ended without being caught up. Asserting on the line is then the only way
 * to pin it, and the row counts stay identical with and without the fix.
 *
 * <p>The probe reads {@link ExtLogRecord#getFormattedMessage()} so a {@code warnf}-style message
 * arrives with its parameters already substituted; a record from a plain JUL backend has no such
 * step and yields {@link LogRecord#getMessage()} unchanged.
 *
 * <p><b>A probe that sees nothing passes every assertion about what was not logged.</b> Assert one
 * line you expect BEFORE trusting the absence of another, or the silence proves only that the probe
 * was deaf.
 */
public final class LogProbe {

    private LogProbe() {}

    /** One captured line: the level it was written at, and its message with parameters applied. */
    public record Line(Level level, String message) {}

    /** Runs {@code action} with a probe on {@code source}'s logger; returns what it wrote, in order. */
    public static List<Line> capture(Class<?> source, Runnable action) {
        List<Line> lines = new CopyOnWriteArrayList<>();
        Logger log = Logger.getLogger(source.getName());
        Handler probe = new Handler() {
            @Override public void publish(LogRecord r) {
                lines.add(new Line(r.getLevel(),
                    r instanceof ExtLogRecord ext ? ext.getFormattedMessage() : r.getMessage()));
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        log.addHandler(probe);
        try {
            action.run();
        } finally {
            log.removeHandler(probe);
        }
        return new ArrayList<>(lines);
    }

    /** {@link #capture} narrowed to WARNING and above — the levels an operator is meant to act on. */
    public static List<String> warnings(Class<?> source, Runnable action) {
        return capture(source, action).stream()
            .filter(l -> l.level().intValue() >= Level.WARNING.intValue())
            .map(Line::message)
            .toList();
    }
}
