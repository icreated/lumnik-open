package io.lumnik.hub.list;

public record ListDefinitionDto(
    Long id,
    String code,
    String name,
    String description,
    boolean systemOwned
) {
    public static ListDefinitionDto from(ListDefinition d) {
        return new ListDefinitionDto(d.getId(), d.getCode(), d.getName(),
                                     d.getDescription(), d.isSystemOwned());
    }
}
