package io.lumnik.hub.connector.api;

import io.lumnik.hub.connector.api.types.ConnectorContext;
import io.lumnik.hub.connector.exceptions.MappingException;

public interface ItemProcessor<S, T> {
  T process(S source, ConnectorContext ctx) throws MappingException;

  static <T> ItemProcessor<T, T> identity() {
    return (s, ctx) -> s;
  }
}
