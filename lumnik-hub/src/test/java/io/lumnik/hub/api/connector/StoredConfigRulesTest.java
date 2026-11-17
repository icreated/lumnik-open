package io.lumnik.hub.api.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.connector.transport.LocalRoot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The one check the flat connector and endpoint doors call before storing a config: both of its rules, one wording. */
class StoredConfigRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aLocalPathWhoseFormTheRunRefuses_isRefusedNamingItsKey() throws Exception {
        assertThat(StoredConfigRules.connectorRefusal(JSON.readTree(
            "{\"transport\": {\"kind\": \"local\", \"path\": \"/etc/hostname\"}}")))
            .contains("config 'transport.path' " + LocalRoot.ABSOLUTE + ". " + LocalRoot.FormRefusal.ABSOLUTE_PATH.hint());
    }

    @Test
    void aMappingRefusalComesFirst_andAValidConfigPasses() throws Exception {
        assertThat(StoredConfigRules.connectorRefusal(JSON.readTree(
            "{\"mapping\": {\"row\": {\"map-columns\": {}}}, \"transport\": {\"kind\": \"local\", \"path\": \"/etc/hostname\"}}")))
            .hasValueSatisfying(r -> assertThat(r).startsWith("config 'mapping.row'"));
        assertThat(StoredConfigRules.connectorRefusal(JSON.readTree(
            "{\"transport\": {\"kind\": \"local\", \"path\": \"orders/*.csv\"}}"))).isEmpty();
    }

    /** #1989: the endpoint door stores no hybrid target the apply door would refuse; an endpoint without one passes. */
    @Test
    void anEndpointTargetOutsideTheHybridSchemas_isRefusedNamingItsKey() throws Exception {
        assertThat(StoredConfigRules.endpointRefusal(JSON.readTree(
            "{\"target\": {\"kind\": \"hybrid\", \"table\": \"identity.app_user\", \"unique_key\": \"$.id\"}}")))
            .hasValueSatisfying(r -> assertThat(r).startsWith("config 'target.table' must be ext.<name> or connector.<name>"));
        assertThat(StoredConfigRules.endpointRefusal(JSON.readTree(
            "{\"target\": {\"kind\": \"hybrid\", \"table\": \"ext.items\", \"unique_key\": \"$.id\"}}"))).isEmpty();
        assertThat(StoredConfigRules.endpointRefusal(JSON.readTree("{\"path\": \"/v1/items\"}"))).isEmpty();
        assertThat(StoredConfigRules.endpointRefusal(JSON.readTree("{\"target\": {\"table\": 42}}")))
            .as("a table that is not text is not a name either").isPresent();
    }

    /**
     * The connector's connection is laid under every endpoint's at the run — its connection block when it has one, the
     * flat config otherwise — and a synthetic endpoint copies the flat config whole: a target in either place is held
     * to the same rule. Another target is not one: jdbc's names its table as a string, a csv-file's carries indexes.
     */
    @Test
    void aConnectorLevelTargetOutsideTheHybridSchemas_isRefusedToo() throws Exception {
        assertThat(StoredConfigRules.connectorRefusal(JSON.readTree(
            "{\"base_url\": \"https://x\", \"target\": {\"table\": \"identity.app_user\"}}")))
            .hasValueSatisfying(r -> assertThat(r).startsWith("config 'target.table' must be ext.<name>"));
        assertThat(StoredConfigRules.connectorRefusal(JSON.readTree(
            "{\"connection\": {\"target\": {\"table\": null}}, \"target\": {\"table\": \"ext.items\"}}")))
            .hasValueSatisfying(r -> assertThat(r).startsWith("config 'connection.target.table' must be ext.<name>"));
        assertThat(StoredConfigRules.connectorRefusal(JSON.readTree(
            "{\"connection\": {\"base_url\": \"https://x\"}, \"target\": {\"table\": \"identity.app_user\"}}")))
            .as("a synthetic endpoint copies the flat config, target included")
            .hasValueSatisfying(r -> assertThat(r).startsWith("config 'target.table' must be ext.<name>"));
        assertThat(StoredConfigRules.connectorRefusal(JSON.readTree("{\"target\": \"orders\"}"))).isEmpty();
        assertThat(StoredConfigRules.connectorRefusal(JSON.readTree(
            "{\"target\": {\"indexes\": [{\"columns\": [\"email\"]}]}}"))).isEmpty();
    }
}
