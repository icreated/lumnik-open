package io.lumnik.hub.workflow;

import io.lumnik.hub.connector.mapping.Zones;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/** A parsed kind:Workflow declaration — the unfrozen head-knowledge of a document lifecycle. */
public record WorkflowManifest(
    String name, String scope, String provenance,
    String table, String column,
    String key,                       // nullable — business key linking versions of one document
    DormancyDef dormancy,             // nullable — requires key (parser-enforced)
    DueDef due,                       // nullable — requires key (parser-enforced, #1448)
    List<StateDef> states,
    List<TransitionDef> transitions
) {
    public record StateDef(String code, String label, List<String> aliases,
                           boolean initial, boolean terminal) {}
    public record TransitionDef(List<String> from, String to) {}

    /**
     * after matches [0-9]+[mhd] — validated by the parser before construction. message is the
     * workflow's own dormancy sentence, null for the default (#1363; {@link DormancyMessage}).
     */
    public record DormancyDef(String after, String message) {

        public Duration duration() {
            long n = Long.parseLong(after.substring(0, after.length() - 1));
            return switch (after.charAt(after.length() - 1)) {
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
        }
    }

    /**
     * A watched due date (#1448): a document in one of {@code states} whose {@code column} date has
     * passed is overdue. {@code states} are declared, non-terminal codes, as declared. {@code zone}
     * is the declared one, null when absent; a date-only column is due at the end of its day there.
     * {@code message} is the workflow's own sentence, null for the default.
     */
    public record DueDef(String column, List<String> states, String zone, String message) {

        public ZoneId zoneId() {
            return Zones.orUtc(zone);
        }
    }

    public String initialState() {
        return states.stream().filter(StateDef::initial).map(StateDef::code).findFirst().orElseThrow();
    }
}
