package io.lumnik.hub.connector.mapping.hooks;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a CDI bean as implementing a named hook (e.g. @HookId("parse-date")).
 * Discovered by HookCatalog at startup via reflection — NOT a CDI qualifier,
 * so beans annotated with @HookId are still injectable into unqualified Instance<> points.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface HookId {
  String value();
}
