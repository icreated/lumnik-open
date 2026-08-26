package io.lumnik.hub.connector.transport;

public enum AfterProcessAction { LEAVE, MOVE, DELETE, CUSTOM_KOTLIN;

    /**
     * Parse a manifest {@code after_process} value, reconciling the validator/manifest vocabulary
     * ({@code archive | delete | move | none}) with this runtime enum. Case- and hyphen-insensitive.
     * <ul>
     *   <li>{@code none} / {@code leave} → {@link #LEAVE} (file kept in place)</li>
     *   <li>{@code archive} / {@code move} → {@link #MOVE} (relocate to the configured
     *       {@code archive_path} / {@code archive_bucket}+{@code archive_prefix})</li>
     *   <li>{@code delete} → {@link #DELETE}; {@code custom_kotlin} → {@link #CUSTOM_KOTLIN}</li>
     *   <li>null / blank / unknown → {@link #LEAVE} (safe no-op)</li>
     * </ul>
     * Before this mapping {@code archive} fell through to LEAVE and silently never archived.
     */
    public static AfterProcessAction parse(Object node) {
        if (node == null) return LEAVE;
        return switch (String.valueOf(node).trim().toUpperCase().replace('-', '_')) {
            case "NONE", "LEAVE", "" -> LEAVE;
            case "ARCHIVE", "MOVE"   -> MOVE;
            case "DELETE"            -> DELETE;
            case "CUSTOM_KOTLIN"     -> CUSTOM_KOTLIN;
            default                  -> LEAVE;
        };
    }
}
