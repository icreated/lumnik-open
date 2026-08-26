package io.lumnik.hub.connector.persistence.converters;

import io.lumnik.hub.connector.api.types.SyncMode;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class SyncModeConverter implements AttributeConverter<SyncMode, String> {

  @Override
  public String convertToDatabaseColumn(SyncMode attr) {
    if (attr == null) return null;
    return switch (attr) {
      case SyncMode.Incremental i -> "Incremental";
      case SyncMode.FullRefresh f -> "FullRefresh";
      case SyncMode.Snapshot s    -> "Snapshot";
    };
  }

  @Override
  public SyncMode convertToEntityAttribute(String value) {
    if (value == null) return null;
    return switch (value) {
      case "Incremental" -> new SyncMode.Incremental();
      case "FullRefresh" -> new SyncMode.FullRefresh();
      case "Snapshot"    -> new SyncMode.Snapshot();
      default -> throw new IllegalArgumentException("Unknown SyncMode: " + value);
    };
  }
}
