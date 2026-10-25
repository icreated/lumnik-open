package io.lumnik.hub.view;

import io.agroal.api.AgroalDataSource;
import io.lumnik.extraction.spi.ask.SchemaCard;
import io.lumnik.hub.ask.SchemaCardStore;
import io.lumnik.hub.testsupport.YamlBody;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;

/** #1050: the apply door answers about a link's target, the render door hands the links to the PWA. */
@QuarkusTest
class ViewLinksIT {

    private static final long TENANT = 31L;
    private static final String SCOPE = "linkcat";
    private static final String OTHER_SCOPE = "linkother";

    @Inject SchemaCardStore cards;
    @Inject @DataSource("admin") AgroalDataSource adminDs;

    private void exec(String sql) {
        try (Connection c = adminDs.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @BeforeEach
    void seed() {
        exec("CREATE TABLE IF NOT EXISTS connector.t_vl_cat (category_id text, name text)");
        exec("TRUNCATE connector.t_vl_cat");
        exec("INSERT INTO connector.t_vl_cat VALUES ('1000021','Pompes')");
        cards.save(TENANT, "registry", new SchemaCard(SCOPE, List.of(
            new SchemaCard.Table("connector.t_vl_cat", "Categories", List.of(
                new SchemaCard.Column("category_id", "text", "Category"),
                new SchemaCard.Column("name", "text", "Name"))),
            new SchemaCard.Table("connector.t_vl_art", "Articles", List.of(
                new SchemaCard.Column("article_id", "text", "Article"),
                new SchemaCard.Column("category_id", "text", "Category"))))));
        cards.save(TENANT, "registry", new SchemaCard(OTHER_SCOPE, List.of(
            new SchemaCard.Table("connector.t_vl_cat", "Categories", List.of(
                new SchemaCard.Column("category_id", "text", "Category"),
                new SchemaCard.Column("name", "text", "Name"))))));
        for (String v : List.of("vl-articles", "vl-categories", "vl-foreign"))
            given().headers(TestIdentity.tenantAndUser(TENANT)).delete("/api/views/" + v);
    }

    private static String view(String name, String scope, String table, String columns, String links) {
        return """
            apiVersion: apps.lumnik.io/v1
            kind: View
            metadata: { name: %s }
            spec:
              scope: %s
              table: %s
              columns: %s
            """.formatted(name, scope, table, columns) + (links == null ? "" : "  links:\n" + links.indent(4));
    }

    private static final String TO_ARTICLES = """
        - column: name
          targetView: vl-articles
          filters:
            category_id: "{category_id}"
        """;

    private ValidatableResponse apply(String yaml) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(TENANT))
            .contentType("application/yaml").body(yaml).post("/api/views/apply").then();
    }

    private ValidatableResponse render(String yaml) {
        return given().config(YamlBody.CONFIG).headers(TestIdentity.tenantAndUser(TENANT))
            .contentType("application/yaml").body(yaml).post("/api/views/render").then();
    }

    private String categories(String links) {
        return view("vl-categories", SCOPE, "connector.t_vl_cat", "[category_id, name]", links);
    }

    @Test
    void aLinkToAViewThatDoesNotExistYetIsAcceptedWithAWarning() {
        apply(categories(TO_ARTICLES)).statusCode(200)
            .body("warnings", hasSize(1))
            .body("warnings[0].path", equalTo("spec.links[0].targetView"))
            .body("warnings[0].severity", equalTo("WARNING"))
            .body("warnings[0].message", containsString("vl-articles"));
    }

    @Test
    void aLinkToAnExistingViewThatShowsTheFilteredColumnSaysNothing() {
        apply(view("vl-articles", SCOPE, "connector.t_vl_art", "[article_id, category_id]", null)).statusCode(200);
        apply(categories(TO_ARTICLES)).statusCode(200).body("$", not(hasKey("warnings")));
    }

    @Test
    void aTargetThatDoesNotShowTheFilteredColumnIsWarnedAbout() {
        apply(view("vl-articles", SCOPE, "connector.t_vl_art", "[article_id]", null)).statusCode(200);
        apply(categories(TO_ARTICLES)).statusCode(200)
            .body("warnings", hasSize(1))
            .body("warnings[0].path", equalTo("spec.links[0].filters.category_id"))
            .body("warnings[0].message", containsString("category_id"));
    }

    @Test
    void aTargetInAnotherScopeIsReportedExactlyLikeAMissingOne() {
        String missing = apply(categories(TO_ARTICLES)).statusCode(200).extract().path("warnings[0].message");
        apply(view("vl-articles", OTHER_SCOPE, "connector.t_vl_cat", "[category_id, name]", null)).statusCode(200);
        apply(categories(TO_ARTICLES)).statusCode(200)
            .body("warnings[0].message", equalTo(missing));
    }

    @Test
    void aMalformedLinkIsRefusedAtTheDoor() {
        apply(categories("- column: name\n  targetView: vl-articles\n")).statusCode(400)
            .body("error", containsString("spec.links[0].filters"));
        apply(categories("- column: name\n  url: \"http://erp.example.com/{category_id}\"\n")).statusCode(400)
            .body("error", containsString("spec.links[0].url"));
    }

    @Test
    void aLinkWhoseSourceValueDoesNotSurviveAsTextIsWarnedAbout() {
        cards.save(TENANT, "registry", new SchemaCard(SCOPE, List.of(
            new SchemaCard.Table("connector.t_vl_cat", "Categories", List.of(
                new SchemaCard.Column("category_id", "numeric(18,4)", "Category"),
                new SchemaCard.Column("name", "text", "Name"))))));
        apply(categories(TO_ARTICLES)).statusCode(200)
            .body("warnings.path", hasItem("spec.links[0].filters.category_id"))
            .body("warnings.find { it.path == 'spec.links[0].filters.category_id' }.message",
                containsString("numeric"));
    }

    /** #1338: a bigint is an integer too — a CSV read with parse-number integer: true, or an entity's whole number. */
    @Test
    void aLinkOnABigintSourceColumnSaysNothingAboutTypes() {
        cards.save(TENANT, "registry", new SchemaCard(SCOPE, List.of(
            new SchemaCard.Table("connector.t_vl_cat", "Categories", List.of(
                new SchemaCard.Column("category_id", "bigint", "Category"),
                new SchemaCard.Column("name", "text", "Name"))))));
        apply(view("vl-articles", SCOPE, "connector.t_vl_cat", "[category_id, name]", null)).statusCode(200);
        apply(categories(TO_ARTICLES)).statusCode(200).body("$", not(hasKey("warnings")));
    }

    @Test
    void aLinkOnATextOrIntegerSourceColumnSaysNothingAboutTypes() {
        cards.save(TENANT, "registry", new SchemaCard(SCOPE, List.of(
            new SchemaCard.Table("connector.t_vl_cat", "Categories", List.of(
                new SchemaCard.Column("category_id", "integer", "Category"),
                new SchemaCard.Column("name", "character varying", "Name"))))));
        apply(view("vl-articles", SCOPE, "connector.t_vl_cat", "[category_id, name]", null)).statusCode(200);
        apply(categories(TO_ARTICLES)).statusCode(200).body("$", not(hasKey("warnings")));
    }

    @Test
    void aViewMayLinkToItself() {
        apply(view("vl-categories", SCOPE, "connector.t_vl_cat", "[category_id, name]",
            TO_ARTICLES.replace("vl-articles", "vl-categories"))).statusCode(200).body("$", not(hasKey("warnings")));
    }

    @Test
    void aTypoInsideALinkIsRefusedWithItsPath() {
        apply(categories(TO_ARTICLES + "  target: oops\n")).statusCode(400)
            .body("error", containsString("spec.links[0].target"));
    }

    @Test
    void renderHandsTheLinksToTheClient() {
        render(categories(TO_ARTICLES
            + "- column: category_id\n  url: \"https://erp.example.com/c/{category_id}\"\n")).statusCode(200)
            .body("links", hasSize(2))
            .body("links.column", contains("name", "category_id"))
            .body("links[0].targetView", equalTo("vl-articles"))
            .body("links[0].filters.category_id", equalTo("{category_id}"))
            .body("links[1].url", equalTo("https://erp.example.com/c/{category_id}"));
    }

    @Test
    void aViewWithoutLinksRendersExactlyAsBefore() {
        render(categories(null)).statusCode(200).body("$", not(hasKey("links")));
    }
}
