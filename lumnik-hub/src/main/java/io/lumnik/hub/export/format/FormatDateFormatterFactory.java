package io.lumnik.hub.export.format;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Set;

/**
 * {@code format-date}: a canonical date or timestamp, written in the partner's {@code pattern}.
 * Reads {@code yyyy-MM-dd}, and a timestamp with a space or a {@code T}, optional fraction and
 * optional offset ({@code +00}, {@code +05:30}, {@code +00:09:21}, {@code Z}), surrounding
 * whitespace ignored — the shapes PostgreSQL's text and ISO
 * give. A timestamp with an offset is moved to {@code zone} (default UTC) first; one without is
 * written as it reads. {@link Locale#ROOT}, never the hub's default locale.
 */
@ApplicationScoped
public class FormatDateFormatterFactory implements ColumnFormatterFactory {

    private static final Set<String> PARAMS = Set.of("pattern", "zone");

    private static final DateTimeFormatter READ = new DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .optionalStart()
        .appendLiteral('T')
        .append(DateTimeFormatter.ISO_LOCAL_TIME)
        .optionalStart()
        .appendOffset("+HH:mm:ss", "Z")
        .optionalEnd()
        .optionalEnd()
        .toFormatter(Locale.ROOT)
        // STRICT: the default SMART resolver turns 2019-02-30 into 2019-02-28 and 24:00 into the
        // next day — a wrong date delivered, where the contract is a failed run.
        .withResolverStyle(ResolverStyle.STRICT);

    @Override
    public String kind() {
        return "format-date";
    }

    @Override
    public ColumnFormatter compile(JsonNode params) {
        FormatterParams.refuseUnknown(params, PARAMS);
        String pattern = FormatterParams.text(params, "pattern");
        if (pattern == null || pattern.isBlank()) throw new IllegalArgumentException("pattern is required");
        DateTimeFormatter write;
        try {
            write = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("pattern: " + e.getMessage(), e);
        }
        String zoneText = FormatterParams.text(params, "zone");
        ZoneId zone;
        try {
            zone = zoneText == null ? ZoneId.of("UTC") : ZoneId.of(zoneText);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("zone: '" + zoneText + "' is not a time zone", e);
        }
        return value -> write(read(value), write, zone);
    }

    private static TemporalAccessor read(String raw) {
        String value = raw.strip();
        String iso = value.length() > 10 && value.charAt(10) == ' '
            ? value.substring(0, 10) + 'T' + value.substring(11) : value;
        TemporalAccessor parsed;
        try {
            parsed = READ.parse(iso);
        } catch (DateTimeParseException e) {
            throw new ColumnFormatException(
                "not a date or a timestamp (expected yyyy-MM-dd, optionally followed by a time and an offset)");
        }
        // Parse once and ask what was read — not parseBest(), which finds out by catching a
        // DateTimeException per query: two to four per plain date, on every cell of the column.
        if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) return OffsetDateTime.from(parsed);
        if (parsed.isSupported(ChronoField.HOUR_OF_DAY)) return LocalDateTime.from(parsed);
        return LocalDate.from(parsed);
    }

    private static String write(TemporalAccessor read, DateTimeFormatter write, ZoneId zone) {
        TemporalAccessor out = read instanceof OffsetDateTime odt ? odt.atZoneSameInstant(zone) : read;
        try {
            return write.format(out);
        } catch (DateTimeException e) {
            // Not e.getMessage(): java.time quotes the value in it ("… from temporal 2019-12-31").
            throw new ColumnFormatException("the pattern needs a field the value does not carry "
                + "(a time of day, an offset or a zone)");
        }
    }
}
