package io.lumnik.testkit.cdi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListInstanceTest {

    @Test
    void wrapsZeroElementsAsUnsatisfied() {
        ListInstance<String> instance = new ListInstance<>(List.of());
        assertThat(instance.isUnsatisfied()).isTrue();
        assertThat(instance.isAmbiguous()).isFalse();
        assertThat(instance.isResolvable()).isFalse();
        assertThatThrownBy(instance::get).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrapsOneElementAsResolvable() {
        ListInstance<String> instance = new ListInstance<>(List.of("only"));
        assertThat(instance.isResolvable()).isTrue();
        assertThat(instance.get()).isEqualTo("only");
        assertThat(instance).containsExactly("only");
    }

    @Test
    void wrapsTwoElementsAsAmbiguous() {
        ListInstance<String> instance = new ListInstance<>(List.of("a", "b"));
        assertThat(instance.isAmbiguous()).isTrue();
        assertThat(instance.isResolvable()).isFalse();
        assertThatThrownBy(instance::get).isInstanceOf(IllegalStateException.class);
        assertThat(instance).containsExactly("a", "b");
    }
}
