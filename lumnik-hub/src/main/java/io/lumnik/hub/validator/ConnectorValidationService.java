package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import io.lumnik.hub.validator.ValidationError.Severity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.*;

@ApplicationScoped
public class ConnectorValidationService {

    @Inject Instance<ConnectorValidator> validators;

    public ValidationResult validate(JsonNode manifest) {
        List<ValidationError> envelopeErrs = EnvelopeValidator.validate(manifest);
        if (!envelopeErrs.isEmpty()) {
            return new ValidationResult(envelopeErrs, List.of());
        }

        String kind = manifest.path("metadata").path("connector_type").asText();
        ConnectorValidator v = findValidator(kind);
        if (v == null) {
            return new ValidationResult(
                List.of(new ValidationError("metadata.connector_type", Severity.ERROR,
                    "unknown connector type: " + kind,
                    List.of("Available types: " + String.join(", ", availableKinds())))),
                List.of());
        }

        JsonNode spec = manifest.path("spec");
        List<ValidationError> all = new ArrayList<>();
        all.addAll(ConfigDefRunner.run(v.configDef(), spec, "spec"));
        all.addAll(v.validateCrossField(spec));

        List<ValidationError> errors = new ArrayList<>();
        List<ValidationError> warnings = new ArrayList<>();
        for (ValidationError e : all) {
            (e.severity() == Severity.ERROR ? errors : warnings).add(e);
        }
        return new ValidationResult(errors, warnings);
    }

    public Optional<ConfigDef> configDefFor(String kind) {
        var v = findValidator(kind);
        return v == null ? Optional.empty() : Optional.of(v.configDef());
    }

    public List<String> availableKinds() {
        List<String> kinds = new ArrayList<>();
        validators.forEach(v -> kinds.add(v.kind()));
        Collections.sort(kinds);
        return kinds;
    }

    private ConnectorValidator findValidator(String kind) {
        for (ConnectorValidator v : validators) {
            if (v.kind().equals(kind)) return v;
        }
        return null;
    }
}
