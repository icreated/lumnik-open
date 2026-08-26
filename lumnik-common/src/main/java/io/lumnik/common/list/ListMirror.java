package io.lumnik.common.list;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ListMirror {

    /** Technical key for the list. Stable. e.g. "order_status". */
    String code();

    /** Default display name. Tenants may override. */
    String name();

    /** Optional description, used as initial value. */
    String description() default "";
}
