package io.lumnik.hub.validator.csv;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.validator.ConfigDefRunner;
import io.lumnik.hub.validator.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TransportBlockValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test void unknownKey_warnsIgnored() throws Exception {
        // The block is CLOSED (C3): the declared 17 keys are exactly what the runtime reads
        // (confirmed by the wave-finale inventory) — anything else is a typo, warn on it.
        List<ValidationError> errs = ConfigDefRunner.run(
            TransportBlockValidator.configDef(),
            mapper.readTree("{\"kind\":\"local\",\"path\":\"/in/*.csv\",\"bogus_key\":true}"),
            "spec.transport");
        assertTrue(errs.stream().anyMatch(e ->
                e.severity() == ValidationError.Severity.WARNING
                && e.path().equals("spec.transport.bogus_key")),
            () -> "expected the unknown-key warning for bogus_key, got: " + errs);
    }

    @Test void archivePath_isHonoredByRuntime_mustStayDeclared() throws Exception {
        // markCfg starts from the transport block and only putIfAbsent-tops-up from spec level —
        // a transport-level archive_path is honored and even WINS (FileSourceCursorReader ctor;
        // LocalFileSource/SftpFileSource read it via cfg.getOrDefault). Closing the block without
        // declaring it would emit a lying "ignored by the runtime" warning (Copilot, PR #169 r3).
        List<ValidationError> errs = ConfigDefRunner.run(
            TransportBlockValidator.configDef(),
            mapper.readTree("{\"kind\":\"sftp\",\"host\":\"h\",\"user\":\"u\","
                + "\"path\":\"/in/*.csv\",\"archive_path\":\"/done/\"}"),
            "spec.transport");
        assertTrue(errs.isEmpty(), () -> "archive_path is honored — no warning allowed, got: " + errs);
    }

    @Test void declaredKeys_localAndSftpAndS3_clean() throws Exception {
        List<String> nodes = List.of(
            "{\"kind\":\"local\",\"path\":\"/in/*.csv\"}",
            "{\"kind\":\"sftp\",\"host\":\"h\",\"user\":\"u\",\"path\":\"/in/*.csv\","
                + "\"port\":2222,\"strict_host_key\":false,\"key_env\":\"K\",\"password_env\":\"P\"}",
            "{\"kind\":\"s3\",\"bucket\":\"b\",\"prefix\":\"in/\",\"suffix\":\".csv\","
                + "\"region\":\"eu-west-3\",\"endpoint\":\"http://minio:9000\","
                + "\"archive_bucket\":\"arch\",\"archive_prefix\":\"done/\","
                + "\"access_key_env\":\"AK\",\"secret_key_env\":\"SK\"}");
        for (String node : nodes) {
            List<ValidationError> errs = ConfigDefRunner.run(
                TransportBlockValidator.configDef(), mapper.readTree(node), "spec.transport");
            assertTrue(errs.isEmpty(),
                () -> "declared keys must stay clean for " + node + ", got: " + errs);
        }
    }
}
