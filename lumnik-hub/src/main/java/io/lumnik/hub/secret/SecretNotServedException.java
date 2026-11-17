package io.lumnik.hub.secret;

import io.lumnik.hub.connector.exceptions.AuthoredRunFailure;
import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import io.lumnik.hub.export.AuthoredFailure;

import java.util.List;

/**
 * A secret exists but its reader does not carry every scope of it (#2015). A configuration problem, so a door answers
 * {@code 400}; and a sentence the hub wrote — a secret's name, its reader's scopes, the gesture — so a
 * connector run and an export run both store it as written.
 *
 * <p>Only the reader's own scopes are named, never the secret's: a scope-bound caller probing its own connector has no
 * business learning which scopes a secret carries, its own among them.
 */
public class SecretNotServedException extends ConfigValidationException implements AuthoredRunFailure, AuthoredFailure {

    SecretNotServedException(String name, List<String> audience) {
        super("secret " + name + " does not serve scope " + String.join(", ", audience)
            + ": lm secret scope " + name + " SCOPES replaces the scopes it serves");
    }
}
