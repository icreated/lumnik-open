package io.lumnik.hub.export.destination;

import java.util.Map;

public interface FileDestinationFactory {
    String kind();
    FileDestination build(Map<String, Object> cfg);

    /** The registry validates only {@code kind}; each factory checks its own required fields with
     *  this before touching the network, so a missing one fails as a named
     *  {@link FileDestinationException} rather than an NPE deep in a client. {@code label} is the
     *  transport's name in messages ({@code "S3"}, {@code "SFTP"}). */
    static String required(Map<String, Object> cfg, String label, String field) {
        if (!(cfg.get(field) instanceof String value) || value.isBlank())
            throw new FileDestinationException(label + " destination: '" + field + "' is required");
        return value;
    }
}
