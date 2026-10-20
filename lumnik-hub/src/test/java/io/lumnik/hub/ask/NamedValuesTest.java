package io.lumnik.hub.ask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** #1098: a value the question names as a category, a status or a code is an equality, whatever the model wrote. */
class NamedValuesTest {

    private static final String LISTING = "Donne-moi les références de la catégorie Pompes dont la description contient Badu.";

    /** The SQL the 7B wrote for the ticket's question, 3/3. */
    private static final String ISSUE_SQL = """
        SELECT v.product_id, v.reference
        FROM connector."v_followchatproduct" v
        JOIN connector."t_follow_chat_product_categories" c ON v.category_id = c.m_product_category_id
        WHERE c.name ILIKE '%Pompes%' AND v.description ILIKE '%Badu%'
        LIMIT 500""";

    @Test
    void the_named_category_of_the_ticket_becomes_an_equality_and_the_contained_text_keeps_its_pattern() {
        assertThat(NamedValues.asEqualities(ISSUE_SQL, LISTING))
            .isEqualTo(ISSUE_SQL.replace("c.name ILIKE '%Pompes%'", "c.name = 'Pompes'"));
    }

    @Test
    void the_english_wording_too() {
        String sql = "SELECT reference FROM v WHERE category_name ILIKE '%Pompes%' AND description ILIKE '%Badu%'";
        assertThat(NamedValues.asEqualities(sql, "List the references in the category Pompes whose description contains Badu."))
            .isEqualTo("SELECT reference FROM v WHERE category_name = 'Pompes' AND description ILIKE '%Badu%'");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
        Combien de commandes au statut Validé ont un commentaire qui mentionne urgent ?  | status ILIKE '%Validé%'          | status = 'Validé'
        How many orders have the status Validé and a comment that mentions urgent?      | "status" LIKE '%Validé%'        | "status" = 'Validé'
        Les articles du code PROMO10 dont le nom contient Pro                            | code ILIKE '%PROMO10%'          | code = 'PROMO10'
        Les articles de la catégorie Pompes à chaleur dont la description contient Badu  | name ILIKE '%Pompes à chaleur%' | name = 'Pompes à chaleur'
        Les articles de la catégorie « Pompes » dont la description contient Badu        | name ILIKE '%Pompes%'           | name = 'Pompes'
        Les articles de la catégorie Pompes dont la description contient Badu            | name ILIKE 'Pompes%'            | name = 'Pompes'
        Les articles de la catégorie pompes dont la description contient Badu            | name ILIKE '%Pompes%'           | name = 'Pompes'
        Les articles hors de la catégorie Pompes dont la description contient Badu       | name NOT ILIKE '%Pompes%'       | name <> 'Pompes'
        Les articles de la catégorie L'Été dont la description contient Badu             | name ILIKE '%L''Été%'           | name = 'L''Été'
        Les articles de la catégorie Pompes_HP dont la description contient Badu         | name ILIKE '%Pompes_HP%'        | name = 'Pompes_HP'
        """)
    void a_named_value_is_an_equality(String question, String pattern, String equality) {
        assertThat(NamedValues.asEqualities("SELECT 1 FROM t WHERE " + pattern, question))
            .isEqualTo("SELECT 1 FROM t WHERE " + equality);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
        Les articles dont la catégorie contient Pompes                                  | name ILIKE '%Pompes%'
        Les articles de la catégorie Pompes dont le nom contient Pompes                  | name ILIKE '%Pompes%'
        Les articles dont la description contient Badu                                   | description ILIKE '%Badu%'
        Les articles de la catégorie Pompes                                              | name ILIKE '%Pom%pes%'
        Les articles de la catégorie Pompes                                              | name ILIKE '%Pomp_s%'
        Les articles de la catégorie Pompes                                              | name ILIKE '%Pompes\\_X%'
        Les articles de la catégorie Pompes                                              | name ILIKE '%Pompes%' ESCAPE '!'
        Les articles de la catégorie Pompes                                              | name ILIKE 'Pompes'
        Les articles de la catégorie Pompes                                              | name ILIKE '%Pompe%'
        Les articles de la catégorie Pompes                                              | name ILIKE '%%'
        """)
    void anything_else_is_left_as_the_model_wrote_it(String question, String pattern) {
        String sql = "SELECT 1 FROM t WHERE " + pattern;
        assertThat(NamedValues.asEqualities(sql, question)).isEqualTo(sql);
    }

    @Test
    void no_question_changes_nothing() {
        assertThat(NamedValues.asEqualities(ISSUE_SQL, null)).isEqualTo(ISSUE_SQL);
        assertThat(NamedValues.asEqualities(null, LISTING)).isNull();
    }
}
