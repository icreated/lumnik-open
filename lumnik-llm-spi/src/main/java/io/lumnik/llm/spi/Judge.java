package io.lumnik.llm.spi;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Qualifies the {@link ChatPort} used to JUDGE faithfulness — an independent model (different blind spots
 * than the {@link Analytic} generator) that scores whether generated SQL faithfully answers the question.
 * Offline/eval only, never in the request path.
 */
@Qualifier
@Documented
@Retention(RUNTIME)
@Target({ TYPE, FIELD, METHOD, PARAMETER })
public @interface Judge {
}
