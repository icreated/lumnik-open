package io.lumnik.hub.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lumnik.hub.json.JsonNodes;
import io.lumnik.hub.validator.ConfigDef.Importance;
import io.lumnik.hub.validator.ConfigDef.Type;
import io.lumnik.hub.validator.ValidationError.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A list field declares the shape of its elements: each one is checked at its own path. */
class ConfigDefListsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<ValidationError> run(ConfigDef def, String json) throws Exception {
        return ConfigDefRunner.run(def, mapper.readTree(json), "spec");
    }

    private static ConfigDef indexes() {
        ConfigDef entry = new ConfigDef()
            .require("columns", Type.LIST, Importance.HIGH, "Columns, in index order")
                .nonEmpty()
                .eachItemIs("a non-blank string", JsonNodes::nonBlank)
            .optional("unique", Type.BOOLEAN, Importance.LOW, "Unique index", false);
        return new ConfigDef()
            .optionalListOf("indexes", Importance.LOW, "Indexes on the hub table", entry)
                .hint("indexes is a list of { columns: [...], unique?: bool } objects");
    }

    // -- the elements -----------------------------------------------------------------------------

    @Test void everyWellFormedElementPasses() throws Exception {
        assertThat(run(indexes(), "{\"indexes\":[{\"columns\":[\"a\"]},{\"columns\":[\"a\",\"b\"],\"unique\":true}]}")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{}", "{\"indexes\":null}", "{\"indexes\":[]}"})
    void noIndexesIsFine(String block) throws Exception {
        assertThat(run(indexes(), block)).isEmpty();
    }

    @Test void aBadElementIsReportedAtItsOwnIndex_andTheGoodOnesAreLeftAlone() throws Exception {
        var errs = run(indexes(), "{\"indexes\":[{\"columns\":[\"a\"]},{\"columns\":[\"a\"],\"unique\":\"yes\"}]}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec.indexes[1].unique");
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
        a number                 | 1                         | spec.indexes[0]
        a string                 | "x"                       | spec.indexes[0]
        no columns               | {}                        | spec.indexes[0].columns
        columns written empty    | {"columns":null}          | spec.indexes[0].columns
        an empty columns list    | {"columns":[]}            | spec.indexes[0].columns
        a column that is no name | {"columns":[123]}         | spec.indexes[0].columns
        a blank column           | {"columns":["a","  "]}    | spec.indexes[0].columns
        a unique that is no flag | {"columns":["a"],"unique":"yes"} | spec.indexes[0].unique
        """)
    void eachKindOfMalformedElementIsRefusedOnceAtItsPath(String what, String element, String path) throws Exception {
        var errs = run(indexes(), "{\"indexes\":[" + element + "]}");

        assertThat(errs).as(what).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo(path);
            assertThat(e.severity()).isEqualTo(Severity.ERROR);
        });
    }

    @Test void anElementThatIsNoObject_teachesTheShapeOfTheList() throws Exception {
        var errs = run(indexes(), "{\"indexes\":[1]}");

        assertThat(errs).singleElement().satisfies(e ->
            assertThat(e.recommendations()).containsExactly("indexes is a list of { columns: [...], unique?: bool } objects"));
    }

    @Test void whatIsNotAListAtAllIsRefusedOnce_notOncePerElement() throws Exception {
        var errs = run(indexes(), "{\"indexes\":\"nope\"}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec.indexes");
            assertThat(e.recommendations()).containsExactly("indexes is a list of { columns: [...], unique?: bool } objects");
        });
    }

    @Test void theElementsOfAClosedElementDefAreSweptToo() throws Exception {
        var def = new ConfigDef().optionalListOf("tags", Importance.LOW, "Tags",
            new ConfigDef().require("name", Type.STRING, Importance.LOW, "Tag").closed());

        var errs = run(def, "{\"tags\":[{\"name\":\"a\",\"colour\":\"red\"}]}");

        assertThat(errs).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec.tags[0].colour");
            assertThat(e.severity()).isEqualTo(Severity.WARNING);
        });
    }

    // -- nonEmpty / eachItemIs --------------------------------------------------------------------

    @Test void nonEmptyRefusesAnEmptyListAndNothingElse() throws Exception {
        var def = new ConfigDef().optional("pk_columns", Type.LIST, Importance.HIGH, "Primary key", null).nonEmpty();

        assertThat(run(def, "{\"pk_columns\":[]}")).singleElement()
            .satisfies(e -> assertThat(e.path()).isEqualTo("spec.pk_columns"));
        assertThat(run(def, "{\"pk_columns\":[\"id\"]}")).isEmpty();
        assertThat(run(def, "{}")).isEmpty();
    }

    @Test void eachItemIsNamesTheFirstItemThatIsNot() throws Exception {
        var def = new ConfigDef().optional("columns", Type.LIST, Importance.LOW, "Columns", null)
            .eachItemIs("a non-blank string", JsonNodes::nonBlank);

        var errs = run(def, "{\"columns\":[\"a\",\"\",7]}");

        assertThat(errs).singleElement().satisfies(e -> assertThat(e.message()).isEqualTo("item 1 must be a non-blank string"));
    }

    /** An item that fails both checks is reported by the one declared first — and an empty list reaches `nonEmpty` before any item. */
    @Test void theChecksOfOneFieldAreAskedInTheOrderTheyWereDeclared() throws Exception {
        var def = new ConfigDef().optional("columns", Type.LIST, Importance.LOW, "Columns", null)
            .nonEmpty()
            .eachItemIs("a string", JsonNode::isTextual)
            .eachItemIs("non-blank", JsonNodes::nonBlank);

        assertThat(run(def, "{\"columns\":[]}")).singleElement()
            .satisfies(e -> assertThat(e.message()).isEqualTo("must not be empty"));
        assertThat(run(def, "{\"columns\":[7]}")).singleElement()
            .satisfies(e -> assertThat(e.message()).isEqualTo("item 0 must be a string"));
        assertThat(run(def, "{\"columns\":[\" \"]}")).singleElement()
            .satisfies(e -> assertThat(e.message()).isEqualTo("item 0 must be non-blank"));
    }

    // -- a shape that is described and not enforced -----------------------------------------------

    /** What an element is, for a reader — a rule of its own elsewhere decides what is refused (jdbc: on the merged node). */
    private static ConfigDef tableShape() {
        return new ConfigDef()
            .require("name", Type.STRING, Importance.HIGH, "Table name")
            .require("pk_columns", Type.LIST, Importance.HIGH, "Primary key");
    }

    @Test void aListWhoseElementIsOnlyDescribed_neverChecksItsElements() throws Exception {
        var def = new ConfigDef().optionalListOf("tables", Importance.HIGH, "Tables", tableShape().describesOnly());

        assertThat(run(def, "{\"tables\":[{},{\"name\":7}]}")).isEmpty();
        assertThat(ConfigDefRunner.run(tableShape(), mapper.readTree("{}"), "spec.tables[0]"))
            .as("the same shape, called directly, still checks").isNotEmpty();
    }

    @Test void aBlockThatIsOnlyDescribed_isNeverDescendedInto() throws Exception {
        var def = new ConfigDef().optionalNested("defaults", Importance.LOW, "Defaults", tableShape().describesOnly());

        assertThat(run(def, "{\"defaults\":{}}")).isEmpty();
        assertThat(run(new ConfigDef().optionalNested("defaults", Importance.LOW, "Defaults", tableShape()), "{\"defaults\":{}}"))
            .as("undescribed, the same block is checked").isNotEmpty();
    }

    @Test void whatIsOnlyDescribedIsPublishedAllTheSame() {
        JsonNode json = ConfigDefSerializer.toJson(new ConfigDef()
            .optionalListOf("tables", Importance.HIGH, "Tables", tableShape().describesOnly())
            .optionalNested("defaults", Importance.LOW, "Defaults", tableShape().describesOnly()));

        assertThat(json.get("fields").get(0).get("items").get("fields").findValuesAsText("name")).containsExactly("name", "pk_columns");
        assertThat(json.get("fields").get(1).get("nested").get("fields").findValuesAsText("name")).containsExactly("name", "pk_columns");
    }

    @Test void aRequiredList_isRequiredLikeAnyOtherAndStillShapedByItsElement() throws Exception {
        var def = new ConfigDef().requireListOf("tables", Importance.HIGH, "Tables", tableShape());

        assertThat(run(def, "{}")).singleElement().satisfies(e -> {
            assertThat(e.path()).isEqualTo("spec.tables");
            assertThat(e.message()).startsWith("required field missing");
        });
        assertThat(run(def, "{\"tables\":[{}]}")).extracting(ValidationError::path)
            .containsExactlyInAnyOrder("spec.tables[0].name", "spec.tables[0].pk_columns");
    }

    // -- what the hub publishes -------------------------------------------------------------------

    @Test void theElementShapeIsPublishedAsItems_notAsANestedBlock() {
        JsonNode json = ConfigDefSerializer.toJson(indexes());

        JsonNode field = json.get("fields").get(0);
        assertThat(field.get("type").asText()).isEqualTo("LIST");
        assertThat(field.has("nested")).as("lm renders `nested` as a block, which a list of them is not").isFalse();
        assertThat(field.get("items").get("fields").findValuesAsText("name")).containsExactly("columns", "unique");
    }
}
