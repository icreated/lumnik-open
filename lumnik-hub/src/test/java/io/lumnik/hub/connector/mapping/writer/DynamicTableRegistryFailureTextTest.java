package io.lumnik.hub.connector.mapping.writer;

import io.agroal.api.AgroalDataSource;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #1649: the registry's reads and its upsert pasted the driver's words into their message, and class 22/23
 * words quote the row. They now say the label and the SQLSTATE, the driver's exception kept as the cause.
 */
class DynamicTableRegistryFailureTextTest {

    private static final String VALUE = "4111-1111";

    static Stream<Arguments> doors() {
        return Stream.of(
            door("emitsRowEvents", r -> () -> r.emitsRowEvents(1L, "connector.t_x")),
            door("findSchema", r -> () -> r.findSchema(1L, "m", null)),
            door("dependentViews", r -> () -> r.dependentViews(UUID.randomUUID())),
            door("register", r -> () -> r.register(1L, "m", "connector", "t_x", Map.of("a", "TEXT"))));
    }

    private static Arguments door(String name, Function<DynamicTableRegistry, Executable> call) {
        return Arguments.of(name, call);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("doors")
    void a_database_failure_says_its_label_and_sqlstate_not_the_value(String name,
            Function<DynamicTableRegistry, Executable> call) throws Exception {
        DynamicTableRegistry registry = new DynamicTableRegistry();
        registry.adminDs = mock(AgroalDataSource.class);
        SQLException driver = new SQLException("ERROR: invalid input syntax for type bigint: \"" + VALUE + "\"", "22P02");
        when(registry.adminDs.getConnection()).thenThrow(driver);

        Executable door = call.apply(registry);
        RuntimeException failure = catchThrowableOfType(RuntimeException.class, door::execute);

        assertThat(failure).hasMessage("DynamicTableRegistry." + name + " failed (SQLSTATE 22P02)");
        assertThat(failure).hasCause(driver);
    }
}
