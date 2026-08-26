package io.lumnik.hub.connector.api.types;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SkipPolicy.SkipUpTo.class,   name = "SkipUpTo"),
    @JsonSubTypes.Type(value = SkipPolicy.AlwaysSkip.class, name = "AlwaysSkip"),
    @JsonSubTypes.Type(value = SkipPolicy.NeverSkip.class,  name = "NeverSkip")
})
public sealed interface SkipPolicy {
  record SkipUpTo(int maxSkips) implements SkipPolicy {}
  record AlwaysSkip()           implements SkipPolicy {}
  record NeverSkip()            implements SkipPolicy {}
}
