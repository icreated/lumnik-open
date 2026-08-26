package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JsonNodesTest {
    private final ObjectMapper m = new ObjectMapper();

    @Test void nonBlank_trueOnlyForNonBlankText() {
        ObjectNode n = m.createObjectNode();
        n.put("s", "x"); n.put("blank", "   "); n.putNull("nul"); n.put("num", 5);
        assertThat(JsonNodes.nonBlank(n.get("s"))).isTrue();
        assertThat(JsonNodes.nonBlank(n.get("blank"))).isFalse();
        assertThat(JsonNodes.nonBlank(n.get("nul"))).isFalse();
        assertThat(JsonNodes.nonBlank(n.get("num"))).isFalse();
        assertThat(JsonNodes.nonBlank(n.get("missing"))).isFalse();
        assertThat(JsonNodes.nonBlank(n.path("missing"))).isFalse();
    }

    @Test void blankOrNull_trueForMissingNullOrBlankText() {
        ObjectNode n = m.createObjectNode();
        n.put("s", "x"); n.put("blank", "   "); n.putNull("nul");
        assertThat(JsonNodes.blankOrNull(n.path("s"))).isFalse();
        assertThat(JsonNodes.blankOrNull(n.path("blank"))).isTrue();
        assertThat(JsonNodes.blankOrNull(n.path("nul"))).isTrue();
        assertThat(JsonNodes.blankOrNull(n.path("missing"))).isTrue();
    }

    @Test void blankOrNull_handlesNullReference() {
        // node.get("missing") returns a literal null (not MissingNode); the helper must not NPE.
        assertThat(JsonNodes.blankOrNull(null)).isTrue();
        assertThat(JsonNodes.blankOrNull(m.createObjectNode().get("missing"))).isTrue();
    }

    @Test void textOrNull_textOnlyWhenRealAndNonBlank() {
        ObjectNode n = m.createObjectNode();
        n.put("s", "x"); n.put("blank", "   "); n.putNull("nul"); n.put("num", 5);
        assertThat(JsonNodes.textOrNull(n.path("s"))).isEqualTo("x");
        assertThat(JsonNodes.textOrNull(n.path("blank"))).isNull();
        assertThat(JsonNodes.textOrNull(n.path("nul"))).isNull();
        assertThat(JsonNodes.textOrNull(n.path("num"))).isNull();
        assertThat(JsonNodes.textOrNull(n.path("missing"))).isNull();
    }

    @Test void textOrNull_handlesNullReference() {
        // Same contract as its siblings: a literal null (node.get() on an absent field) is an
        // absent optional field, not an NPE.
        assertThat(JsonNodes.textOrNull(null)).isNull();
        assertThat(JsonNodes.textOrNull(m.createObjectNode().get("missing"))).isNull();
    }
}
