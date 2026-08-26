package io.lumnik.hub.list;

public record ListValueDto(
    Long id,
    String value,
    String name,
    String description,
    String color,
    int sortOrder,
    boolean active
) {
    public static ListValueDto from(ListValue v) {
        return new ListValueDto(v.getId(), v.getValue(), v.getName(), v.getDescription(),
                                v.getColor(), v.getSortOrder(), v.isActive());
    }
}
