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
        envelopeErrs.addAll(ScopesValidator.errors(manifest));
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
        // Deliberately the RAW spec, and #552 is the measurement that says why: stripping its nulls
        // here — the one-place fix this door was the obvious home for — broke `rename: {id: null}`
        // and `map-columns` with it. A manifest carries nulls of two kinds. A DECLARATION written
        // empty means absent; a null inside a user-supplied MAP is a value, and "rename id to
        // nothing" is a typo those rules exist to refuse. No reading of the document tells the two
        // apart, so the rules that want "absent" ask for it by name (JsonNodes.absent / nonBlank).
        all.addAll(v.validateCrossField(spec));
        all.addAll(ScopesValidator.warnings(manifest));

        List<ValidationError> errors = new ArrayList<>();
        List<ValidationError> warnings = new ArrayList<>();
        // One mistake, one line: a rest-generic default is checked in every endpoint that inherits it, and by the
        // defaults' own declaration (#1481).
        for (ValidationError e : new LinkedHashSet<>(all)) {
            (e.severity() == Severity.ERROR ? errors : warnings).add(e);
        }
        // A cross-field rule that refuses an undeclared key already speaks of it: the closed sweep's
        // "unknown key" for the same path would report it a second time.
        Set<String> refused = new HashSet<>();
        errors.forEach(e -> refused.add(e.path()));
        warnings.removeIf(w -> ConfigDefRunner.UNKNOWN_KEY.equals(w.message()) && refused.contains(w.path()));
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
