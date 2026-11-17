package io.lumnik.hub.connector.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #2001: the flat connector and endpoint doors store a config the manifest's validator never sees; a local path whose
 * form the run refuses is refused there too, in the run's words — wherever the run reads a transport from.
 */
class LocalTransportRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static LocalTransportRules.Violation refused(String json) throws Exception {
        return LocalTransportRules.ofConnector(JSON.readTree(json)).orElse(null);
    }

    @Test
    void anAbsoluteOrUnparsableLocalPathIsRefused() throws Exception {
        assertThat(refused("{\"transport\": {\"kind\": \"local\", \"path\": \"/etc/hostname\"}}"))
            .isEqualTo(new LocalTransportRules.Violation("transport.path", LocalRoot.ABSOLUTE, LocalRoot.FormRefusal.ABSOLUTE_PATH.hint()));
        assertThat(refused("{\"transport\": {\"path\": \"orders\\u0000/*.csv\"}}"))
            .as("an absent kind is local, as the run reads it")
            .isEqualTo(new LocalTransportRules.Violation("transport.path", LocalRoot.UNPARSABLE, LocalRoot.FormRefusal.NOT_A_PATH.hint()));
        assertThat(refused("{\"connection\": {\"transport\": {\"kind\": \"local\", \"path\": \"/etc/hostname\"}}}"))
            .as("a connection block's transport: where the run reads it then")
            .isEqualTo(new LocalTransportRules.Violation("connection.transport.path", LocalRoot.ABSOLUTE, LocalRoot.FormRefusal.ABSOLUTE_PATH.hint()));
        assertThat(refused("{\"connection\": {\"transport\": {\"kind\": \"local\", \"path\": \"orders/*.csv\"}},"
            + " \"transport\": {\"kind\": \"local\", \"path\": \"/old/*.csv\"}}"))
            .as("in a connector, beside a connection block, a top-level transport is a key the run ignores").isNull();
    }

    /** An endpoint's config is laid over the connector's: its own top-level transport is read, a connection key is inert. */
    @Test
    void anEndpointsTopLevelTransportIsJudged_whateverElseItCarries() throws Exception {
        assertThat(LocalTransportRules.ofEndpoint(JSON.readTree(
            "{\"connection\": {}, \"transport\": {\"kind\": \"local\", \"path\": \"/etc/hostname\"}}")))
            .map(LocalTransportRules.Violation::path).contains("transport.path");
        assertThat(LocalTransportRules.ofEndpoint(JSON.readTree(
            "{\"connection\": {\"transport\": {\"path\": \"/x\"}}, \"transport\": {\"path\": \"orders/*.csv\"}}")))
            .as("the connection key is inert in an endpoint: not judged").isEmpty();
    }

    @Test
    void aRelativeLocalPath_aRemotePath_andAKindThatIsNotLocal_pass() throws Exception {
        assertThat(refused("{\"transport\": {\"kind\": \"local\", \"path\": \"orders/*.csv\"}}")).isNull();
        assertThat(refused("{\"transport\": {\"kind\": \"sftp\", \"path\": \"/erp-export/*.csv\"}}"))
            .as("a remote path is the remote server's").isNull();
        assertThat(refused("{\"transport\": {\"kind\": null, \"path\": \"/export/*.csv\"}}"))
            .as("an explicit null names no kind: the run refuses it as such, not as a local path").isNull();
        assertThat(refused("{\"path\": \"/v1/items\"}")).as("a REST endpoint's path is not a transport").isNull();
    }
}
