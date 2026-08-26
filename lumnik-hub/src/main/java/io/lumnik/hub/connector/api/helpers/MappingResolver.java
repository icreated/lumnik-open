package io.lumnik.hub.connector.api.helpers;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import java.util.UUID;

public interface MappingResolver {
  Mapping resolve(UUID mappingId);

  interface Mapping {
    Object transform(Object source, ConnectorContext ctx);
  }
}
