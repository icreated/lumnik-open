# ConnectorValidator — contributor guide

Installer-facing docs: docs/connectors/index.md. This file documents the SPI for contributors.

## Adding a new connector validator

To wire validation for a new connector kind (e.g. `csv-file`, `jdbc-generic`):

1. Implement the SPI:

```java
@ApplicationScoped
@ConnectorTypeId("my-kind")
public class MyConnectorValidator implements ConnectorValidator {

    @Override public String kind() { return "my-kind"; }

    @Override
    public ConfigDef configDef() {
        return new ConfigDef()
            .requireCustom("endpoint", Type.STRING, Importance.HIGH,
                "Service endpoint URL", ConfigDefValidators.isUrl())
            .requireNested("auth", Importance.HIGH, "Auth block", authBlock());
    }

    @Override
    public List<ValidationError> validateCrossField(JsonNode spec) {
        // Optional: rules that can't be expressed declaratively.
        return List.of();
    }

    private static ConfigDef authBlock() {
        return new ConfigDef()
            .requireEnum("kind", Importance.HIGH, "Auth method", "bearer", "basic");
    }
}
```

CDI discovery picks it up via `Instance<ConnectorValidator>` — no registration step needed.

2. (Optional) Split sub-blocks (auth, endpoints, target) into `*BlockValidator` classes that expose static `ConfigDef configDef()`. The `RestConnectorValidator` uses `AuthBlockValidator` and `EndpointsBlockValidator` this way for reuse across HTTP-family connectors.

3. Test with a `@QuarkusTest` and YAML fixtures under `src/test/resources/validator/`. Inject `ConnectorValidationService` and feed it the loaded manifest.

## Built-in `ConfigDefValidators` helpers

| Helper | Purpose |
|---|---|
| `isUrl()` | Asserts absolute URL (scheme + host) |
| `matchesRegex(pattern, desc)` | Asserts the value matches a regex; `desc` appears in the error message |
| `inRange(min, max)` | Asserts integer in `[min, max]` |
| `nonEmpty()` | Asserts non-blank string |

All return `Function<JsonNode, Optional<String>>` — drop into `ConfigDef.requireCustom(...)`.

## References

- Source: `lumnik-hub/src/main/java/io/lumnik/hub/validator/`
- Tests: `lumnik-hub/src/test/java/io/lumnik/hub/validator/`
- Pilot validator: `RestConnectorValidator` (`rest-generic`)
- Spec: `docs/superpowers/specs/2026-05-25-connector-validator-design.md`
- Plan: `docs/superpowers/plans/2026-05-25-connector-validator-plan.md`
- Ship snapshot: `docs/snapshot/2026-05-25-connector-validator-v1-project-snapshot.md`

## Supported connector kinds (v1)

| Kind | Validator class | Manifest doc |
|---|---|---|
| `rest-generic` | `RestConnectorValidator` | `docs/connectors/rest.md` |
| `csv-file` | `CsvFileConnectorValidator` | `docs/connectors/csv.md` |
| `jdbc-generic` | `JdbcConnectorValidator` | `docs/connectors/jdbc.md` |
| `kafka` | `KafkaConnectorValidator` | `docs/connectors/kafka.md` |

Two *endpoint* kinds ride on the `rest-generic` engine (same envelope, per-endpoint `kind:`):
`graphql` (`docs/connectors/graphql.md`) and `soap`
(`docs/connectors/soap.md`).
