package io.lumnik.hub.list;

public final class ListEvents {
    public static final String VALUE_ADDED   = "list.value.added";
    public static final String VALUE_UPDATED = "list.value.updated";
    public static final String VALUE_REMOVED = "list.value.removed";

    private ListEvents() {}

    public record ValueAdded(String listCode, String value, String name) {}
    public record ValueUpdated(String listCode, String value, String oldName, String newName) {}
    public record ValueRemoved(String listCode, String value) {}
}
