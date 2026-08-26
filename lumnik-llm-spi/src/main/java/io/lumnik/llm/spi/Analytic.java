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
 * Qualifies the {@link ChatPort} dedicated to the analytic text-to-SQL path (ask), which needs a
 * code/SQL-capable model, separately from the conversational RAG chat port (a general model).
 * Injection points without this qualifier resolve to the default conversational port.
 */
@Qualifier
@Documented
@Retention(RUNTIME)
@Target({ TYPE, FIELD, METHOD, PARAMETER })
public @interface Analytic {
}
