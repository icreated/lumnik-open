package io.lumnik.hub.workflow;

import java.time.Duration;
import java.util.List;

/** A parsed kind:Workflow declaration — the unfrozen head-knowledge of a document lifecycle. */
public record WorkflowManifest(
    String name, String scope, String provenance,
    String table, String column,
    String key,                       // nullable — business key linking versions of one document
    DormancyDef dormancy,             // nullable — requires key (parser-enforced)
    List<StateDef> states,
    List<TransitionDef> transitions
) {
    public record StateDef(String code, String label, List<String> aliases,
                           boolean initial, boolean terminal) {}
    public record TransitionDef(List<String> from, String to) {}

    /** after matches [0-9]+[mhd] — validated by the parser before construction. */
    public record DormancyDef(String after) {
        public Duration duration() {
            long n = Long.parseLong(after.substring(0, after.length() - 1));
            return switch (after.charAt(after.length() - 1)) {
                case 'm' -> Duration.ofMinutes(n);
                case 'h' -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
        }
    }

    public String initialState() {
        return states.stream().filter(StateDef::initial).map(StateDef::code).findFirst().orElseThrow();
    }
}
