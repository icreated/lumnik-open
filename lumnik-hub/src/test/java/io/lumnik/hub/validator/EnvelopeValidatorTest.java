package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<ValidationError> validate(String json) throws Exception {
        return EnvelopeValidator.validate(mapper.readTree(json));
    }

    @Test
    void validEnvelope_noErrors() throws Exception {
        assertTrue(validate("""
            {"apiVersion":"connectors.lumnik.io/v1",
             "kind":"Connector",
             "metadata":{"name":"x","connector_type":"rest-generic"}}
            """).isEmpty());
    }

    @Test
    void missingApiVersion_isError() throws Exception {
        var errs = validate("{\"kind\":\"Connector\",\"metadata\":{\"name\":\"x\",\"connector_type\":\"r\"}}");
        assertTrue(errs.stream().anyMatch(e -> e.path().equals("apiVersion")));
    }

    @Test
    void wrongKind_isError() throws Exception {
        var errs = validate("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Foo",
             "metadata":{"name":"x","connector_type":"r"}}""");
        assertTrue(errs.stream().anyMatch(e -> e.path().equals("kind")));
    }

    @Test
    void missingMetadataName_isError() throws Exception {
        var errs = validate("""
            {"apiVersion":"connectors.lumnik.io/v1","kind":"Connector",
             "metadata":{"connector_type":"r"}}""");
        assertTrue(errs.stream().anyMatch(e -> e.path().equals("metadata.name")));
    }

    @Test
    void unsupportedApiVersion_isError() throws Exception {
        var errs = validate("""
            {"apiVersion":"connectors.lumnik.io/v999","kind":"Connector",
             "metadata":{"name":"x","connector_type":"r"}}""");
        assertTrue(errs.stream().anyMatch(e ->
            e.path().equals("apiVersion") && e.message().contains("unsupported")));
    }
}
