package io.lumnik.hub.validator.jdbc;

import io.lumnik.hub.connector.mapping.Zones;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ConfigDefValidators;

public final class ConnectionBlockValidator {
    private ConnectionBlockValidator() {}

    public static ConfigDef configDef() {
        return new ConfigDef()
            .requireCustom("url", Type.STRING, Importance.HIGH,
                "JDBC URL — jdbc:<dialect>://... (the dialect's provider must be installed)",
                ConfigDefValidators.matchesRegex("^jdbc:[a-z0-9]+:.*$", "jdbc:<dialect>:..."))
            .require("user", Type.STRING, Importance.HIGH, "DB user")
            .require("password_env", Type.STRING, Importance.HIGH,
                "Env var name for the DB password")
            .optional("dialect", Type.STRING, Importance.LOW,
                "JDBC dialect (inferred from url scheme if absent)", null)
            .optionalCustom("zone", Type.STRING, Importance.LOW,
                "Time zone the source writes its dates-and-times without a zone in, for example"
                    + " Europe/Paris (read as UTC if absent)",
                v -> Zones.whyNot(v.asText()))
            .optional("password", Type.STRING, Importance.LOW,
                "Plain-text DB password (dev/test only — wins over password_env; prefer password_env)",
                null)
            .closed();
    }
}
