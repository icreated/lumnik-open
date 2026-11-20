package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** #2096: a question that leaves out what an honest query needs is answered with what is missing, never a guess. */
class UnderspecifiedTest {

    /** A customer Entity with a declared point, a city column, a date and an amount. */
    private static final SchemaCard CARD = new SchemaCard("sales", List.of(
        new SchemaCard.Table("connector.v_customer", "Customer", List.of(
            new SchemaCard.Column("customer_no", "text", "Customer number"),
            new SchemaCard.Column("ville", "text", "Ville du client"),
            new SchemaCard.Column("latitude", "numeric", "Latitude"),
            new SchemaCard.Column("longitude", "numeric", "Longitude"),
            new SchemaCard.Column("order_date", "date", "Date of the last order"),
            new SchemaCard.Column("revenue", "numeric", "Revenue of the year"),
            new SchemaCard.Column("segment", "text", "Segment", null, null, List.of("Grand compte", "PME"))),
            "One row per customer.", List.of(new SchemaCard.Point("site", "latitude", "longitude")))));

    /** The same customers, with nothing to suggest from: no point, no city, every column text. */
    private static final SchemaCard BARE = new SchemaCard("bare", List.of(
        new SchemaCard.Table("connector.v_customer", "Customer", List.of(
            new SchemaCard.Column("customer_no", "text", "Customer number")))));

    private static String clarified(String question, SchemaCard card, String lang) {
        return Underspecified.clarify(question, card, lang).orElseThrow(() -> new AssertionError("not clarified: " + question));
    }

    @Test
    void around_a_place_names_the_radius_and_the_missing_coordinates_and_invents_neither() {
        String answer = clarified("Combien de clients autour de Lyon ?", CARD, "fr");
        assertThat(answer)
            .contains("autour de Lyon", "rayon", "coordonnées")
            .contains("« Combien de clients avec ville égale à Lyon ? »")
            .contains("« Combien de clients à moins de [rayon] km de [latitude], [longitude] ? »")
            .doesNotContainPattern("\\d"); // no radius, no coordinates: not one number of its own
    }

    @Test
    void the_same_diagnosis_in_english() {
        String answer = clarified("How many customers are near Lyon?", CARD, "en");
        assertThat(answer)
            .contains("near Lyon", "radius", "coordinates")
            .contains("“How many customers within [radius] km of [latitude], [longitude]?”")
            .contains("“How many customers with ville equal to Lyon?”")
            .doesNotContainPattern("\\d");
    }

    @Test
    void a_suggestion_is_offered_only_when_the_card_holds_what_it_names() {
        String answer = clarified("Combien de clients autour de Lyon ?", BARE, "fr");
        assertThat(answer).contains("rayon").doesNotContain("égale à", "[latitude]", "Vous pouvez demander");
    }

    @Test
    void best_names_the_missing_metric_and_does_not_choose_one() {
        String answer = clarified("Quels sont mes meilleurs clients ?", CARD, "fr");
        assertThat(answer).contains("« meilleurs »", "indicateur")
            .contains("« Quels sont mes meilleurs clients, par [indicateur] ? »")
            .contains("revenue"); // offered as an example of the card's figures, among which the asker chooses
        assertThat(clarified("Who are my best customers?", CARD, "en")).contains("“best”", "metric", "by [metric]");
    }

    @Test
    void recent_names_the_missing_period() {
        String answer = clarified("Quelles commandes sont récentes ?", CARD, "fr");
        assertThat(answer).contains("« récentes »", "période", "order_date")
            .contains("« Quelles commandes sont récentes, depuis le [AAAA-MM-JJ] ? »");
        assertThat(clarified("Which orders are recent?", CARD, "en")).contains("“recent”", "period", "since [YYYY-MM-DD]");
    }

    @Test
    void a_qualitative_adjective_names_the_missing_threshold() {
        String answer = clarified("Quels clients ont un chiffre faible ?", CARD, "fr");
        assertThat(answer).contains("« faible »", "seuil")
            .contains("« Quels clients ont un chiffre faible, avec [colonne] en dessous de [seuil] ? »");
        assertThat(clarified("Which customers have high revenue?", CARD, "en"))
            .contains("“high”", "threshold", "above [threshold]");
    }

    /** The acceptance examples that name what they need, and questions these words appear in without asking for it. */
    @ParameterizedTest
    @ValueSource(strings = {
        "Combien de clients sont à moins de 20 km de 45.7640, 4.8357 ?",
        "Combien de clients ont Lyon comme ville ?",
        "Which customer numbers are within 20 km of the Lyon depot?",
        "Combien de clients autour de Lyon dans un rayon de 15 km ?",
        "Quels sont mes meilleurs clients par chiffre d'affaires ?",
        "Quels sont mes meilleurs clients selon revenue ?",
        "Who are my best customers by revenue?",
        "Who are my best customers in terms of revenue?",
        "Quels sont les meilleurs clients en revenue ?",
        "Quelles commandes sont récentes depuis le 2026-11-01 ?",
        "Quelles commandes récentes depuis 30 jours ?",
        "Which orders are recent, in the last 7 days?",
        "Which orders since 2026-11-01 are recent?",
        "Quels clients ont un chiffre faible, en dessous de 1000 ?",
        "Which customers have high revenue, above 50000?",
        "Quels clients sont du segment Grand compte ?",
        "Quelles sont les 5 dernières commandes ?",          // "last 5" is an order and a count, not a period
        "Where is customer C-01?",
        "Which customers are in Lyon?",
        "Quel est le client le plus proche du dépôt de Lyon ?",   // nearest is a ranking by distance, measured
        "Liste des clients de la ville de Lyon",
        "Which orders are around 500 euros?",                     // an amount, not a place
        "Y a-t-il près de 100 commandes en attente ?",
        "Quelle est la commande la plus récente ?",              // superlatives: an order, not a period or a threshold
        "What is the most recent order?",
        "Quel client a le chiffre d'affaires le plus élevé ?",
        "Quelle est la plus grosse commande ?",
        "Combien d'élèves sont inscrits ?",
        "Quelles sont les ventes en gros de juillet ?",
        "Show recent orders from last month",
        "Quels clients sont proches de la faillite ?",          // "proche de" without a place name is not geography
        "Which orders are near completion?",
        "Which sensors read a temperature close to the threshold?",
        "Commandes récentes de cette semaine ?",
    })
    void a_question_that_names_what_it_needs_takes_the_normal_path(String question) {
        assertThat(Underspecified.clarify(question, CARD, "fr")).as(question).isEmpty();
    }

    @Test
    void the_place_is_the_name_typed_not_the_rest_of_the_question() {
        assertThat(clarified("Combien de clients autour de Lyon ont commandé ?", CARD, "fr"))
            .contains("autour de Lyon.", "« Combien de clients avec ville égale à Lyon ? »");
        assertThat(clarified("Combien de clients autour d’Annecy ?", CARD, "fr")).contains("autour d’Annecy.");
    }

    @Test
    void a_suggestion_follows_the_language_the_caller_named() {
        assertThat(clarified("Clients proches de Lyon ?", CARD, "fr"))
            .contains("Il manque un rayon", "« Clients avec ville égale à Lyon ? »").doesNotContain("with ville");
        assertThat(clarified("Clients près de Lyon ?", CARD, "fr")).contains("« Clients à moins de [rayon] km");
        assertThat(clarified("Customers near Lyon?", CARD, "en")).contains("“Customers with ville equal to Lyon?”");
    }

    @Test
    void a_threshold_on_a_named_column_asks_for_the_threshold_only() {
        assertThat(clarified("Which customers have high revenue?", CARD, "en"))
            .contains("“Which customers have high revenue, with revenue above [threshold]?”").doesNotContain("[column]");
    }

    /** A word the scope itself defines is the scope's meaning, not a gap. */
    @Test
    void a_word_the_card_declares_is_not_underspecified() {
        SchemaCard defined = new SchemaCard("ops", List.of(new SchemaCard.Table("connector.v_order", "Order", List.of(
            new SchemaCard.Column("status", "text", "Status", null, null, List.of("recent", "archived")),
            new SchemaCard.Column("is_best", "boolean", "Best customer flag")))));
        assertThat(Underspecified.clarify("Which orders are recent?", defined, "en")).isEmpty();
        assertThat(Underspecified.clarify("Who are my best customers?", defined, "en")).isEmpty();
    }

    /** Every suggestion, once its placeholders are filled in, is a question this check lets through. */
    @ParameterizedTest
    @ValueSource(strings = {
        "Combien de clients autour de Lyon ?", "How many customers are near Lyon?", "Quels sont mes meilleurs clients ?",
        "Who are my best customers?", "Quelles commandes sont récentes ?", "Which orders are recent?",
        "Quels clients ont un chiffre faible ?", "Which customers have high revenue?" })
    void a_filled_in_suggestion_is_not_clarified_again(String question) {
        String answer = clarified(question, CARD, question.endsWith(" ?") ? "fr" : "en");
        List<String> suggestions = quoted(answer);
        assertThat(suggestions).as(answer).isNotEmpty();
        for (String suggestion : suggestions) {
            String filled = suggestion
                .replace("[rayon]", "20").replace("[radius]", "20")
                .replace("[latitude]", "45.76").replace("[longitude]", "4.83")
                .replace("[indicateur]", "revenue").replace("[metric]", "revenue")
                .replace("[AAAA-MM-JJ]", "2026-11-01").replace("[YYYY-MM-DD]", "2026-11-01")
                .replace("[colonne]", "revenue").replace("[column]", "revenue")
                .replace("[seuil]", "1000").replace("[threshold]", "1000");
            assertThat(Underspecified.clarify(filled, CARD, "fr")).as(filled).isEmpty();
        }
    }

    private static List<String> quoted(String answer) {
        String offered = answer.substring(Math.max(answer.indexOf("demander"), answer.indexOf("can ask")));
        return Pattern.compile("[«“]\\s?(.+?)\\s?[»”]").matcher(offered).results().map(m -> m.group(1)).toList();
    }
}
