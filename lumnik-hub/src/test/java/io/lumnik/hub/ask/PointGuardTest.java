package io.lumnik.hub.ask;

import io.lumnik.extraction.spi.ask.SchemaCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #1974: every call to connector.distance_m in generated SQL measures from a declared point, in lat, lon order, or from
 * a centre the question typed as a pair. Anything else abstains — fail-closed.
 */
class PointGuardTest {

    private static SchemaCard.Column num(String name) {
        return new SchemaCard.Column(name, "numeric", name);
    }

    private static final SchemaCard CARD = new SchemaCard("geo", List.of(
            new SchemaCard.Table("connector.v_customer", "Customer",
                    List.of(new SchemaCard.Column("name", "text", "Name"), num("latitude"), num("longitude"), num("revenue"), num("alt")),
                    null, List.of(new SchemaCard.Point("site", "latitude", "longitude"))),
            new SchemaCard.Table("connector.v_depot", "Depot",
                    List.of(new SchemaCard.Column("name", "text", "Name"), num("yard_lat"), num("yard_lon")),
                    null, List.of(new SchemaCard.Point("yard", "yard_lat", "yard_lon"))),
            new SchemaCard.Table("connector.v_store", "Store",
                    List.of(new SchemaCard.Column("name", "text", "Name"), num("latitude"), num("longitude")),
                    null, List.of(new SchemaCard.Point("site", "latitude", "longitude"))),
            // its point's column was retyped to text by a run: the card no longer offers it
            new SchemaCard.Table("connector.v_old", "Old",
                    List.of(new SchemaCard.Column("latitude", "text", "Latitude"), num("longitude")))));

    private static final String TYPED = "Which customers are within 20 km of 45.764, 4.8357?";
    private static final String CUSTOMERS = "SELECT name FROM connector.v_customer WHERE ";

    static Stream<Arguments> passes() {
        return Stream.of(
                Arguments.of("two declared points", "Customers within 20 km of the Lyon depot",
                        "SELECT c.name FROM connector.v_customer c JOIN connector.v_depot d ON d.name = 'Lyon' "
                                + "WHERE connector.distance_m(c.latitude, c.longitude, d.yard_lat, d.yard_lon) < 20000"),
                Arguments.of("a declared point and a typed centre", TYPED,
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 45.764, 4.8357) < 20000"),
                Arguments.of("the centre first", TYPED,
                        CUSTOMERS + "connector.distance_m(45.764, 4.8357, latitude, longitude) < 20000"),
                Arguments.of("a negative latitude", "Customers near -33.8688, 151.2093",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, -33.8688, 151.2093) < 5000"),
                Arguments.of("a negative longitude, a trailing zero", "Customers near 40.7128, -74.0060",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 40.7128, -74.006) < 5000"),
                Arguments.of("both negative, a semicolon", "Customers near -33.45; -70.66",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, -33.45, -70.66) < 5000"),
                Arguments.of("an explicit plus", "Customers near +45.764 +4.8357",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, +45.764, +4.8357) < 5000"),
                Arguments.of("qualified by the table's name", TYPED,
                        CUSTOMERS + "connector.distance_m(v_customer.latitude, v_customer.longitude, 45.764, 4.8357) < 20000"),
                Arguments.of("a quoted alias and quoted columns", TYPED,
                        "SELECT \"c\".name FROM \"connector\".\"v_customer\" AS \"c\" "
                                + "WHERE \"connector\".\"distance_m\"(\"c\".\"latitude\", \"c\".\"longitude\", 45.764, 4.8357) < 20000"),
                Arguments.of("a sampled table: TABLESAMPLE is no alias", TYPED,
                        "SELECT name FROM connector.v_customer TABLESAMPLE BERNOULLI (50) "
                                + "WHERE connector.distance_m(v_customer.latitude, v_customer.longitude, 45.764, 4.8357) < 20000"),
                Arguments.of("the call without its schema", TYPED,
                        CUSTOMERS + "distance_m(latitude, longitude, 45.764, 4.8357) < 20000"),
                Arguments.of("two calls in one query", TYPED,
                        "SELECT name, connector.distance_m(latitude, longitude, 45.764, 4.8357) AS d FROM connector.v_customer "
                                + "WHERE connector.distance_m(latitude, longitude, 45.764, 4.8357) < 20000 ORDER BY d"),
                Arguments.of("no call at all", "How many customers?", "SELECT count(*) FROM connector.v_customer"),
                // #1985: the pairs of one table, in the shape the 7B wrote for this question (lumnik-scenario,
                // one-customer-typed-twice): the distance shown and filtered, each half its own alias
                Arguments.of("one view joined to itself, each half its own alias's point", PAIRS,
                        "SELECT a1.name, a2.name, connector.distance_m(a1.latitude, a1.longitude, a2.latitude, a2.longitude) AS distance "
                                + "FROM \"connector\".\"v_customer\" a1 JOIN \"connector\".\"v_customer\" a2 ON a1.name <> a2.name "
                                + "WHERE connector.distance_m(a1.\"latitude\", a1.\"longitude\", a2.\"latitude\", a2.\"longitude\") <= 50"),
                Arguments.of("a centre from a row of the same table, as two sub-queries", "Customers within 20 km of customer C-01",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, "
                                + "(SELECT latitude FROM connector.v_customer WHERE customer_no = 'C-01'), "
                                + "(SELECT longitude FROM connector.v_customer WHERE customer_no = 'C-01')) <= 20000"),
                Arguments.of("a centre from another table, quoted, as two sub-queries", "Customers within 20 km of the Lyon depot",
                        "SELECT \"name\" FROM \"connector\".\"v_customer\" WHERE connector.distance_m(\"latitude\", \"longitude\", "
                                + "(SELECT \"yard_lat\" FROM \"connector\".\"v_depot\" WHERE \"name\" = 'Lyon'), "
                                + "(SELECT \"yard_lon\" FROM \"connector\".\"v_depot\" WHERE \"name\" = 'Lyon')) <= 20000"),
                // #1977: a point's column next to a number the question typed, or to the data
                Arguments.of("north of a typed latitude", NORTH, CUSTOMERS + "latitude > 45"),
                Arguments.of("a box the question typed", "Customers with a latitude between 45 and 46",
                        CUSTOMERS + "latitude BETWEEN 45 AND 46"),
                Arguments.of("a typed negative latitude", "Customers south of -33.5", CUSTOMERS + "\"latitude\" < -33.5"),
                Arguments.of("a cast column, a typed number", NORTH, CUSTOMERS + "latitude::float8 >= 45.0"),
                Arguments.of("a point's column against another table's", "Customers north of the Lyon depot",
                        "SELECT c.name FROM connector.v_customer c JOIN connector.v_depot d ON d.name = 'Lyon' "
                                + "WHERE c.latitude > d.yard_lat"),
                Arguments.of("a point's column against a sub-query reading the data", "Customers north of the Lyon depot",
                        CUSTOMERS + "latitude > (SELECT yard_lat FROM connector.v_depot WHERE name = 'Lyon')"),
                Arguments.of("a point read, not compared", "Where is customer A?",
                        "SELECT name, latitude, longitude FROM connector.v_customer WHERE name = 'A'"),
                Arguments.of("a point that is there", "Customers with a location", CUSTOMERS + "latitude IS NOT NULL"),
                Arguments.of("rounded for display", "Where are the customers?",
                        "SELECT name, round(latitude, 3), round(longitude, 3) FROM connector.v_customer"),
                Arguments.of("a column no point declares", "Customers with a big revenue", CUSTOMERS + "revenue > 100000"),
                Arguments.of("an ordinal typed", "Customers north of the 46th parallel", CUSTOMERS + "latitude > 46"),
                Arguments.of("the northernmost, ordered", "The northernmost customer",
                        "SELECT name FROM connector.v_customer ORDER BY latitude DESC LIMIT 1"),
                Arguments.of("the northernmost, an aggregate", "How far north do customers go?",
                        "SELECT max(latitude) AS north FROM connector.v_customer"),
                Arguments.of("renamed for display at the top", "Where are the customers?",
                        "SELECT name, latitude AS lat, longitude lon FROM connector.v_customer"),
                Arguments.of("a comparison in brackets", NORTH, CUSTOMERS + "name <> 'A' AND (latitude > 45)"),
                Arguments.of("counted", "How many customers have a location?", "SELECT count(latitude) FROM connector.v_customer"),
                Arguments.of("joined for display", "Where are the customers?",
                        "SELECT name, latitude || ',' || longitude FROM connector.v_customer"),
                Arguments.of("the radius in kilometres, the closest first", TYPED, CUSTOMERS
                        + "connector.distance_m(latitude, longitude, 45.764, 4.8357) / 1000 <= 20 "
                        + "ORDER BY connector.distance_m(latitude, longitude, 45.764, 4.8357) LIMIT 5"),
                Arguments.of("a radius between two bounds", TYPED,
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 45.764, 4.8357) BETWEEN 1000 AND 20000"),
                Arguments.of("a cast with its precision", NORTH, CUSTOMERS + "latitude::numeric(9, 6) > 45"),
                Arguments.of("a string that is no number: the database refuses it, no coordinate", "Customers with a latitude",
                        CUSTOMERS + "latitude <> ''"),
                Arguments.of("another number beside a point: not a coordinate", NORTH,
                        CUSTOMERS + "latitude > 45 AND revenue > 100000"),
                Arguments.of("a typed number before a full stop", "Which customers are north of latitude 45.75.",
                        CUSTOMERS + "latitude > 45.75"),
                Arguments.of("a typed pair before a full stop", "Customers within 20 km of 45.764, 4.8357.",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 45.764, 4.8357) < 20000"),
                Arguments.of("no coordinates: the 0 a legacy system stores", "Which customers have no coordinates?",
                        CUSTOMERS + "latitude = 0 OR latitude IS NULL"),
                Arguments.of("the bounds of the coordinates, no place", "Customers with a valid position",
                        CUSTOMERS + "latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180"),
                Arguments.of("the northern hemisphere: the equator is no place", "Customers in the northern hemisphere",
                        CUSTOMERS + "latitude > 0"),
                Arguments.of("an IN over a sub-query with a list of its own", "Customers as far north as a depot of regions A or B",
                        CUSTOMERS + "latitude IN (SELECT yard_lat FROM connector.v_depot WHERE region IN (10, 20))"),
                Arguments.of("radians alone: a unit, no distance", "Customer positions in radians",
                        "SELECT name, radians(latitude), radians(longitude) FROM connector.v_customer"));
    }

    private static final String NORTH = "Which customers are north of latitude 45?";

    @ParameterizedTest(name = "passes: {0}")
    @MethodSource("passes")
    void passes(String why, String question, String sql) {
        assertThat(PointGuard.check(sql, CARD, question)).isEmpty();
    }

    static Stream<Arguments> abstains() {
        return Stream.of(
                Arguments.of("(lon, lat)", TYPED, CUSTOMERS + "connector.distance_m(longitude, latitude, 45.764, 4.8357) < 1"),
                Arguments.of("two numeric columns that are no point", TYPED,
                        CUSTOMERS + "connector.distance_m(revenue, alt, 45.764, 4.8357) < 1"),
                Arguments.of("the lat of one point with the lon of another", TYPED,
                        "SELECT c.name FROM connector.v_customer c JOIN connector.v_depot d ON true "
                                + "WHERE connector.distance_m(c.latitude, d.yard_lon, 45.764, 4.8357) < 1"),
                Arguments.of("a point whose column is no longer numeric", TYPED,
                        "SELECT 1 FROM connector.v_old WHERE connector.distance_m(latitude, longitude, 45.764, 4.8357) < 1"),
                Arguments.of("a column with a literal", TYPED,
                        CUSTOMERS + "connector.distance_m(latitude, 4.8357, 45.764, 4.8357) < 1"),
                Arguments.of("a place name: no coordinates in the question", "Customers within 20 km of Lyon",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 45.764, 4.8357) < 20000"),
                Arguments.of("the literals in the other order", TYPED,
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 4.8357, 45.764) < 1"),
                Arguments.of("the radius taken for a latitude", "Customers within 45 km of 4.8357, 45.764",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 45, 4.8357) < 45000"),
                Arguments.of("the two numbers typed apart, not as a pair", "Latitude 45.764 and longitude 4.8357, 20 km",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 45.764, 4.8357) < 20000"),
                Arguments.of("a sign the question does not have", "Customers near 33.8688, 151.2093",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, -33.8688, 151.2093) < 1"),
                Arguments.of("a doubled sign", "Customers near -33.8688, 151.2093",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, - -33.8688, 151.2093) < 1"),
                Arguments.of("a subtraction", "Customers near -33.8688, 151.2093",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 0 - 33.8688, 151.2093) < 1"),
                Arguments.of("a latitude out of range", "Customers near 95.1, 4.8",
                        CUSTOMERS + "connector.distance_m(latitude, longitude, 95.1, 4.8) < 1"),
                Arguments.of("bare columns two tables could supply", TYPED,
                        "SELECT 1 FROM connector.v_customer c JOIN connector.v_store s ON s.name = c.name "
                                + "WHERE connector.distance_m(latitude, longitude, 45.764, 4.8357) < 1"),
                Arguments.of("bare columns no table supplies", TYPED,
                        "SELECT 1 FROM connector.v_depot WHERE connector.distance_m(latitude, longitude, 45.764, 4.8357) < 1"),
                Arguments.of("bare columns over a sub-query", TYPED,
                        "SELECT 1 FROM (SELECT * FROM connector.v_customer) x "
                                + "WHERE connector.distance_m(latitude, longitude, 45.764, 4.8357) < 1"),
                Arguments.of("a cast", TYPED, CUSTOMERS + "connector.distance_m(latitude::float8, longitude, 45.764, 4.8357) < 1"),
                Arguments.of("an expression", TYPED, CUSTOMERS + "connector.distance_m(latitude + 0, longitude, 45.764, 4.8357) < 1"),
                Arguments.of("an alias the guard cannot resolve", TYPED,
                        CUSTOMERS + "connector.distance_m(x.latitude, x.longitude, 45.764, 4.8357) < 1"),
                Arguments.of("a quoted call is still checked: (lon, lat)", TYPED,
                        CUSTOMERS + "\"connector\".\"distance_m\"(longitude, latitude, 45.764, 4.8357) < 1"),
                Arguments.of("another schema's distance_m", TYPED,
                        CUSTOMERS + "public.distance_m(latitude, longitude, 45.764, 4.8357) < 1"),
                Arguments.of("three arguments", TYPED, CUSTOMERS + "connector.distance_m(latitude, longitude, 45.764) < 1"),
                Arguments.of("sub-queries selecting (lon, lat)", TYPED, CUSTOMERS + "connector.distance_m(latitude, longitude, "
                        + "(SELECT longitude FROM connector.v_customer WHERE name = 'A'), "
                        + "(SELECT latitude FROM connector.v_customer WHERE name = 'A')) < 1"),
                Arguments.of("sub-queries reading two different rows", TYPED, CUSTOMERS + "connector.distance_m(latitude, longitude, "
                        + "(SELECT latitude FROM connector.v_customer WHERE name = 'A'), "
                        + "(SELECT longitude FROM connector.v_customer WHERE name = 'B')) < 1"),
                Arguments.of("sub-queries on two numbers that are no point", TYPED, CUSTOMERS + "connector.distance_m(latitude, longitude, "
                        + "(SELECT revenue FROM connector.v_customer WHERE name = 'A'), "
                        + "(SELECT alt FROM connector.v_customer WHERE name = 'A')) < 1"),
                Arguments.of("sub-queries on a table whose point is gone", TYPED, CUSTOMERS + "connector.distance_m(latitude, longitude, "
                        + "(SELECT latitude FROM connector.v_old WHERE longitude = 1), "
                        + "(SELECT longitude FROM connector.v_old WHERE longitude = 1)) < 1"),
                Arguments.of("a sub-query with a literal", TYPED, CUSTOMERS + "connector.distance_m(latitude, longitude, "
                        + "(SELECT latitude FROM connector.v_customer WHERE name = 'A'), 4.8357) < 1"),
                Arguments.of("sub-queries that pick one row of several: two different rows may answer", TYPED,
                        CUSTOMERS + "connector.distance_m(latitude, longitude, "
                                + "(SELECT latitude FROM connector.v_customer WHERE name = 'A' LIMIT 1), "
                                + "(SELECT longitude FROM connector.v_customer WHERE name = 'A' LIMIT 1)) < 1"),
                Arguments.of("sub-queries reading 'Lyon' and 'lyon': two rows", TYPED,
                CUSTOMERS + "connector.distance_m(latitude, longitude, "
                    + "(SELECT latitude FROM connector.v_customer WHERE name = 'Lyon'), "
                    + "(SELECT longitude FROM connector.v_customer WHERE name = 'lyon')) < 1"),
            Arguments.of("sub-queries reading \"name\" and name: two columns", TYPED,
                CUSTOMERS + "connector.distance_m(latitude, longitude, "
                    + "(SELECT latitude FROM connector.v_customer WHERE \"Name\" = 'A'), "
                    + "(SELECT longitude FROM connector.v_customer WHERE Name = 'A')) < 1"),
            Arguments.of("sub-queries that order their rows", TYPED,
                        CUSTOMERS + "connector.distance_m(latitude, longitude, "
                                + "(SELECT latitude FROM connector.v_customer ORDER BY revenue DESC FETCH FIRST 1 ROW ONLY), "
                                + "(SELECT longitude FROM connector.v_customer ORDER BY revenue DESC FETCH FIRST 1 ROW ONLY)) < 1"),
                Arguments.of("sub-queries whose set operation adds an invented row", TYPED,
                        CUSTOMERS + "connector.distance_m(latitude, longitude, "
                                + "(SELECT latitude FROM connector.v_customer WHERE false UNION SELECT 12.5), "
                                + "(SELECT longitude FROM connector.v_customer WHERE false UNION SELECT 12.5)) < 1"),
                Arguments.of("sub-queries that read another relation after the table", TYPED,
                        CUSTOMERS + "connector.distance_m(latitude, longitude, "
                                + "(SELECT latitude FROM connector.v_customer, connector.v_store WHERE false), "
                                + "(SELECT longitude FROM connector.v_customer, connector.v_store WHERE false)) < 1"),
                Arguments.of("\"S\" and s are two names: s is the derived table's", TYPED,
                        "SELECT name FROM connector.v_customer AS \"S\" CROSS JOIN (SELECT 45.0 AS latitude, 4.0 AS longitude) AS s "
                                + "WHERE connector.distance_m(s.latitude, s.longitude, \"S\".latitude, \"S\".longitude) < 1000"),
                Arguments.of("a column-alias list renames the columns", TYPED,
                        "SELECT 1 FROM connector.v_customer s(latitude, longitude) "
                                + "WHERE connector.distance_m(s.latitude, s.longitude, 45.764, 4.8357) < 1"),
                Arguments.of("a call inside a sub-select", TYPED, "SELECT name FROM connector.v_customer WHERE name IN "
                        + "(SELECT name FROM connector.v_store WHERE connector.distance_m(latitude, longitude, 45.764, 4.8357) < 1)"),
                // #1977: coordinates the question never typed, without distance_m
                Arguments.of("one bound the question did not type", NORTH, CUSTOMERS + "latitude > 45 AND latitude < 46"),
                Arguments.of("the number first", NEAR_LYON, CUSTOMERS + "45.70 < latitude"),
                Arguments.of("a sign the question did not type", NORTH, CUSTOMERS + "latitude > -45"),
                Arguments.of("a quoted number", NEAR_LYON, CUSTOMERS + "latitude > '45.7'"),
                Arguments.of("a number in brackets", NEAR_LYON, CUSTOMERS + "latitude > (45.7)"),
                Arguments.of("an IN list", NEAR_LYON, CUSTOMERS + "latitude IN (45.7, 45.8)"),
                Arguments.of("NOT BETWEEN", NEAR_LYON, CUSTOMERS + "latitude NOT BETWEEN 45.7 AND 45.8"),
                Arguments.of("arithmetic with an invented number", NEAR_LYON, CUSTOMERS + "abs(latitude - 45.76) < 0.1"),
                Arguments.of("a qualified column", NEAR_LYON,
                        "SELECT c.name FROM connector.v_customer c WHERE c.\"latitude\" BETWEEN 45.70 AND 45.82"),
                Arguments.of("a cast column", NEAR_LYON, CUSTOMERS + "latitude::float8 > 45.7"),
                Arguments.of("another table's point", NEAR_LYON, "SELECT name FROM connector.v_depot WHERE yard_lat > 45.7"),
                Arguments.of("inside a sub-select", NEAR_LYON, "SELECT name FROM connector.v_customer WHERE name IN "
                        + "(SELECT name FROM connector.v_store WHERE latitude BETWEEN 45.70 AND 45.82)"),
                Arguments.of("a box in the ORDER BY", NEAR_LYON,
                        "SELECT name FROM connector.v_customer ORDER BY latitude BETWEEN 45.70 AND 45.82 DESC LIMIT 10"),
                Arguments.of("a sign after WHERE the question did not type", NORTH, CUSTOMERS + "-45 < latitude"),
                Arguments.of("trigonometry around a bracket", NEAR_LYON, CUSTOMERS + "cos((latitude - longitude)) > 0.5"),
                Arguments.of("the name quoted in capitals", NEAR_LYON, CUSTOMERS + "\"LATITUDE\" > 45.7"),
                Arguments.of("the great-circle formula by hand, even from a typed centre", TYPED,
                        CUSTOMERS + "6371 * acos(cos(radians(45.764)) * cos(radians(latitude)) * cos(radians(longitude) "
                                + "- radians(4.8357)) + sin(radians(45.764)) * sin(radians(latitude))) < 20"));
    }

    private static final String NEAR_LYON = "Which customers are near Lyon?";

    /** #1977: a box around a place, a bound like the equator — the sentence says which number to type. */
    @Test
    void aNumberTheQuestionDidNotTypeIsSaid() {
        String box = CUSTOMERS + "latitude BETWEEN 45.70 AND 45.82 AND longitude BETWEEN 4.77 AND 4.90";
        assertThat(PointGuard.check(box, CARD, NEAR_LYON)).contains(PointGuard.Refusal.NUMBER_NOT_TYPED);
        assertThat(PointGuard.check(CUSTOMERS + "latitude > 45.7", CARD, "Customers north of Lyon"))
                .contains(PointGuard.Refusal.NUMBER_NOT_TYPED);
        assertThat(PointGuard.check(box, CARD, "Customers between 45.70 and 45.82 north, 4.77 and 4.90 east")).isEmpty();
    }

    /** #1977: a card point missing a column name is skipped, not a failure of every question in the scope. */
    @Test
    void aPointWithoutItsColumnNameIsSkipped() {
        SchemaCard broken = new SchemaCard("geo", List.of(new SchemaCard.Table("connector.v_customer", "Customer",
                List.of(num("latitude")), null, List.of(new SchemaCard.Point("site", "latitude", null)))));
        assertThat(PointGuard.check("SELECT name FROM connector.v_customer WHERE latitude > 45.7", broken, NORTH))
                .contains(PointGuard.Refusal.NUMBER_NOT_TYPED);
    }

    /** #1977: a distance written by hand, even from a typed centre — the sentence points to distance_m. */
    @Test
    void aDistanceByHandIsSaid() {
        String haversine = CUSTOMERS + "acos(sin(radians(45.764)) * sin(radians(latitude))) < 20";
        assertThat(PointGuard.check(haversine, CARD, TYPED)).contains(PointGuard.Refusal.HAND_DISTANCE);
    }

    /** #1977, fail-open: the guard reads the forms a model writes unprompted; these it does not, pinned so a change shows. */
    static Stream<Arguments> notRead() {
        return Stream.of(
                Arguments.of("arithmetic widening a typed bound", "Which customers are north of latitude 45.75?",
                        CUSTOMERS + "latitude < 45.75 + 0.1"),
                Arguments.of("a number behind a function", NEAR_LYON, CUSTOMERS + "latitude > round(45.76, 1)"),
                Arguments.of("a sub-query of a constant", NEAR_LYON, CUSTOMERS + "latitude > (SELECT 45.7)"),
                Arguments.of("the column in brackets", NEAR_LYON, CUSTOMERS + "(latitude) > 45.7"),
                Arguments.of("the column behind a function", NEAR_LYON, CUSTOMERS + "coalesce(latitude, 0) > 45.7"),
                Arguments.of("the column behind CAST", NEAR_LYON, CUSTOMERS + "CAST(latitude AS float8) > 45.7"),
                Arguments.of("IS DISTINCT FROM", NEAR_LYON, CUSTOMERS + "latitude IS DISTINCT FROM 45.7"),
                Arguments.of("a box around a typed centre: measured with distance_m, not in degrees", TYPED,
                        CUSTOMERS + "latitude BETWEEN 45.764 - 0.2 AND 45.764 + 0.2"),
                Arguments.of("renamed by a column list", NEAR_LYON,
                        "SELECT n FROM (SELECT name, latitude FROM connector.v_customer) s(n, y) WHERE y BETWEEN 45.70 AND 45.82"),
                Arguments.of("renamed by round in a sub-select", NEAR_LYON, "SELECT name FROM (SELECT name, round(latitude, 6) AS y "
                        + "FROM connector.v_customer) s WHERE y BETWEEN 45.70 AND 45.82"),
                Arguments.of("typed literals", NEAR_LYON, CUSTOMERS + "latitude BETWEEN float8 '45.70' AND float8 '45.82'"),
                Arguments.of("a function of a constant in a sub-query", NEAR_LYON,
                        CUSTOMERS + "latitude BETWEEN (SELECT abs(45.70)) AND (SELECT abs(45.82))"),
                Arguments.of("arithmetic on a typed number", "Customers within 20 km of Lyon",
                        CUSTOMERS + "latitude - 20 BETWEEN 25.70 AND 25.82"),
                Arguments.of("geometric types, no trigonometry", NEAR_LYON,
                        CUSTOMERS + "point(longitude, latitude) <@ box(point(4.77, 45.70), point(4.90, 45.82))"),
                Arguments.of("renamed in a sub-select, compared outside", NEAR_LYON,
                        "SELECT 1 FROM (SELECT latitude AS y FROM connector.v_customer) s WHERE y > 45.7"),
                Arguments.of("rounded, then compared", NEAR_LYON, CUSTOMERS + "round(latitude, 1) = 45.7"));
    }

    @ParameterizedTest(name = "not read: {0}")
    @MethodSource("notRead")
    void notRead(String why, String question, String sql) {
        assertThat(PointGuard.check(sql, CARD, question)).isEmpty();
    }

    @ParameterizedTest(name = "abstains: {0}")
    @MethodSource("abstains")
    void abstains(String why, String question, String sql) {
        assertThat(PointGuard.check(sql, CARD, question)).isPresent();
    }

    private static final String PAIRS = "Pour chaque client, quels autres clients sont à moins de 50 m de lui ?";
    private static final String SELF_JOIN =
            "SELECT 1 FROM connector.v_customer a1 JOIN connector.v_customer a2 ON a1.name <> a2.name WHERE ";

    /** #1985: one view under two aliases is two table references — a half never borrows the other alias's column. */
    static Stream<Arguments> selfJoinIsNoPoint() {
        return Stream.of(
                Arguments.of("a half mixing the two aliases", PAIRS,
                        SELF_JOIN + "connector.distance_m(a1.latitude, a2.longitude, a2.latitude, a2.longitude) <= 50"),
                Arguments.of("bare columns both aliases could supply", TYPED,
                        SELF_JOIN + "connector.distance_m(latitude, longitude, 45.764, 4.8357) < 1"));
    }

    @ParameterizedTest(name = "self-join, not a point: {0}")
    @MethodSource("selfJoinIsNoPoint")
    void selfJoinIsNoPoint(String why, String question, String sql) {
        assertThat(PointGuard.check(sql, CARD, question)).contains(PointGuard.Refusal.NOT_A_POINT);
    }
}
