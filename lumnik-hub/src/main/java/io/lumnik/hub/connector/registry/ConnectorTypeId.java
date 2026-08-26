package io.lumnik.hub.connector.registry;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConnectorTypeId {
  String value();
}
