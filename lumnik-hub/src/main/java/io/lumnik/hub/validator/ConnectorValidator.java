package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface ConnectorValidator {

    /** Connector kind this validator handles, e.g. "rest-generic". */
    String kind();

    /** Declarative field-level schema, walked by ConfigDefRunner. */
    ConfigDef configDef();

    /** Cross-field rules that can't be expressed declaratively. Default = no extra rules. */
    default List<ValidationError> validateCrossField(JsonNode spec) {
        return List.of();
    }
}
