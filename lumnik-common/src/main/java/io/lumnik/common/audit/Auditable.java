package io.lumnik.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an entity for opt-in async audit logging.
 * Only the listed fields are captured in the audit snapshot.
 * No fields listed = whole-entity change is noted (CREATE/UPDATE/ARCHIVE) without field detail.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String[] fields() default {};
}
