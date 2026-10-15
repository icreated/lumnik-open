package io.lumnik.hub.export.format;

/**
 * One compiled formatting step of an exported column (#1090). Pure: the same input always gives
 * the same output — no clock, no randomness, no I/O, no default locale — because the export's
 * receipt promises the same data gives the same bytes and the same SHA-256. (A named time zone
 * follows the JDK's tzdata: a hub upgrade that changes a zone's rules can move a converted hour.)
 */
@FunctionalInterface
public interface ColumnFormatter {

    ColumnFormatter IDENTITY = value -> value;

    /**
     * Never called with null: SQL NULL stays an empty, unquoted field. A value this step cannot
     * write throws {@link ColumnFormatException}, whose message names what was expected and never
     * the value itself.
     */
    String format(String value);
}
