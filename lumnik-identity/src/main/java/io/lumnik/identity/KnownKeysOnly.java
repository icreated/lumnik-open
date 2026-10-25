package io.lumnik.identity;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.lang.reflect.Type;
import java.util.function.BiFunction;

/**
 * A JSON body read with {@code FAIL_ON_UNKNOWN_PROPERTIES} on, for the write doors that name it:
 * {@code @CustomDeserialization(KnownKeysOnly.class)} on the resource method.
 *
 * <p>Quarkus turns that feature off for the whole application, so a key the body record does not
 * declare is dropped in silence: {@code entity_filter} for {@code entityFilter} stored an email rule
 * with no filter, which then mailed every table's events behind a clean 201. A class-level
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} does not bring the failure back (measured:
 * it only declines to ignore, the mapper-wide feature still decides). The refusal is dressed by
 * {@link UnknownKeyExceptionMapper}.
 *
 * <p>Door by door, not global: a door that answers {@code 400} where it used to answer {@code 200}
 * breaks every caller that sends a key it never read, so each door joins after its callers are
 * checked.
 */
public class KnownKeysOnly implements BiFunction<ObjectMapper, Type, ObjectReader> {

    @Override
    public ObjectReader apply(ObjectMapper mapper, Type type) {
        return mapper.readerFor(mapper.constructType(type))
                     .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
