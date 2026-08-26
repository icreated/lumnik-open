package io.lumnik.hub.connector.mapping.hooks;

public record HookDescriptor(String name, Tier tier, String description, String exampleYaml) {
  public enum Tier { DATASET, ROW, CELL }
}
