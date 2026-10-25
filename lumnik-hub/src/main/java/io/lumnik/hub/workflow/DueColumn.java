package io.lumnik.hub.workflow;

/**
 * What a {@code spec.due.column} can be read as (#1448), by its {@code information_schema} data type:
 * a day, an instant, or a wall-clock time. Shared by the sweep that reads it, the apply report that
 * says what the sweep will find, and the door that refuses a zone over an instant.
 */
enum DueColumn {
    DATE("date"), TIMESTAMPTZ("timestamp with time zone"), TIMESTAMP("timestamp without time zone");

    private final String dataType;

    DueColumn(String dataType) {
        this.dataType = dataType;
    }

    /** The kind of a column of this data type, null when it is not a date at all (or the column is not there). */
    static DueColumn of(String dataType) {
        for (DueColumn k : values()) if (k.dataType.equals(dataType)) return k;
        return null;
    }

    /** Why no deadline can be read from {@code column}, null when one can. */
    static String problem(String table, String column, String dataType) {
        if (dataType == null) return "spec.due.column " + column + " is not a column of " + table + " (yet)";
        if (of(dataType) == null)
            return "spec.due.column " + column + " is " + dataType + ", not a date: no deadline can be read from it";
        return null;
    }
}
