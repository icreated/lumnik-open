package io.lumnik.hub.connector.mapping.writer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A column a writer's own table always carries: its name and what follows the name in the {@code CREATE TABLE}. A table
 * declares these once, in table order; its DDL is written from them and its reserved-name set is their names, so the
 * two cannot go out of step (#1814, after #1781).
 */
record TableColumn(String name, String definition) {

    /** {@code name definition}, as the {@code CREATE TABLE} lists it. */
    String sql() { return name + ' ' + definition; }

    @SafeVarargs
    static Set<String> namesOf(List<TableColumn>... declarations) {
        return Arrays.stream(declarations).flatMap(List::stream).map(TableColumn::name)
            .collect(Collectors.toUnmodifiableSet());
    }
}
