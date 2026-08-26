package io.lumnik.hub.connector.api.helpers;

/** Resolves {@code *_env} secret references: OS environment first, then JVM system property (dev/test injection). */
public final class EnvSecrets {

    private EnvSecrets() {}

    /** Raw value for an env-var name, or {@code null} if neither the OS env nor a system property is set. */
    public static String lookup(String name) {
        String env = System.getenv(name);
        return env != null ? env : System.getProperty(name);
    }
}
