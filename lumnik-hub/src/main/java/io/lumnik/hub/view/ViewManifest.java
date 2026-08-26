package io.lumnik.hub.view;

import java.util.List;

/** A hand-written kind:View (list) manifest. `table` null = the scope's single table; `title` null = derive it. */
public record ViewManifest(String name, String scope, String table, String title, List<String> columns) {}
