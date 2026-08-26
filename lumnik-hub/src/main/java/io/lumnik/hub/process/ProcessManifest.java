package io.lumnik.hub.process;

import java.util.List;

/** A hand-written kind:Process (notify) manifest: trigger → when-conditions → the event to emit. */
public record ProcessManifest(String name, String scope, String onEvent, String onTable,
                              List<String> when, String emitType, String emitMessage) {}
