package io.lumnik.hub.validator.csv;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.validator.ConfigDef;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;

import java.util.List;

public final class TransportBlockValidator {
    private TransportBlockValidator() {}

    /** Read only by the one-shot ingest service, which no `lm csv ingest` flag feeds: a scheduled connector run never does. */
    private static final List<String> ONE_SHOT_ONLY = List.of("file_filter", "pre_process", "post_process", "max_file_size");

    private static boolean sftp(JsonNode transport) {
        return "sftp".equals(transport.path("kind").asText());
    }

    public static ConfigDef configDef() {
        ConfigDef def = new ConfigDef()
            .requireEnum("kind", Importance.HIGH,
                "Transport mechanism", "local", "sftp", "s3")
            .optional("path", Type.STRING, Importance.HIGH,
                "File path (local) or glob path (sftp)", null)
                .requiredWhen("kind", "local", "sftp")
                .hint("Set path: /imports/*.csv (local) or a remote glob (sftp)")
            .requireWhen("host", Type.STRING, Importance.HIGH,
                "SFTP host", "kind", "sftp")
            .requireWhen("user", Type.STRING, Importance.HIGH,
                "SFTP user", "kind", "sftp")
            .optional("port", Type.INT, Importance.LOW, "SFTP port", 22)
            .optional("strict_host_key", Type.BOOLEAN, Importance.LOW,
                "Verify the SFTP host key against host_key_fingerprint", true)
            .optional("host_key_fingerprint", Type.STRING, Importance.MEDIUM,
                "SFTP host key fingerprint (SHA256:… as ssh-keygen -lf prints it)", "")
                // strict_host_key refuses at every run a server whose key nothing declares (#1251).
                .requiredWhen("kind is sftp and strict_host_key is not off",
                    t -> sftp(t) && t.path("strict_host_key").asBoolean(true))
                .hint("Read it with `ssh-keyscan -p <port> <host> | ssh-keygen -lf -` (the run's refusal prints the same), "
                    + "confirm it out of band, then set host_key_fingerprint: \"SHA256:…\"")
            .requireWhen("bucket", Type.STRING, Importance.HIGH,
                "S3 bucket name", "kind", "s3")
            .optional("prefix", Type.STRING, Importance.MEDIUM,
                "S3 key prefix filter", "")
            .optional("region", Type.STRING, Importance.MEDIUM,
                "AWS region for S3", "eu-west-3")
            .optional("endpoint", Type.STRING, Importance.LOW,
                "S3-compat endpoint (MinIO, R2)", null)
            .optional("suffix", Type.STRING, Importance.LOW,
                "File suffix filter for s3 (empty = no filter)", "")
            .optional("archive_path", Type.STRING, Importance.LOW,
                "Relocation target for after_process=archive|move — canonical home is spec level; "
                    + "a transport-level value wins if both are set (one-shot style)", null)
            .optional("archive_bucket", Type.STRING, Importance.LOW,
                "S3 archive bucket for after_process=archive|move (default: source bucket)", null)
            .optional("archive_prefix", Type.STRING, Importance.LOW,
                "S3 archive key prefix for after_process=archive|move", "archive/")
            .optional("key_env", Type.STRING, Importance.MEDIUM,
                "Env var for SSH private key (SFTP)", null)
            .optional("password_env", Type.STRING, Importance.MEDIUM,
                "Env var for SSH password (SFTP)", null)
            .optional("access_key_env", Type.STRING, Importance.MEDIUM,
                "Env var for AWS access key (S3)", "AWS_ACCESS_KEY_ID")
            .optional("secret_key_env", Type.STRING, Importance.MEDIUM,
                "Env var for AWS secret key (S3)", "AWS_SECRET_ACCESS_KEY")
            // These name a SECRET: a key written empty, or blank, names none.
            .atLeastOneOf("kind is sftp", TransportBlockValidator::sftp,
                "Use `lm secret set <NAME> ...` then reference it via key_env: <NAME>", "key_env", "password_env");
        for (String key : ONE_SHOT_ONLY) {
            def.refused(key, key + " is not read by connector runs", "Drop it from the connector manifest");
        }
        return def.closed();
    }
}
