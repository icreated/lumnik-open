package io.lumnik.hub.notify;

import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.lumnik.testkit.auth.TestIdentity;
import io.quarkus.mailer.Mail;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * #1902: the integrator reads the e-mails the hub recorded, given-up ones first, and re-sends one given up. The
 * guarantee of the event replay holds here too: a replay works on the delivery that exists — a rule never gets a second
 * mail for an event, and a mail that is sent already is never sent again.
 */
@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class NotificationDeliveryDoorIT {

    private static final String URL = "/api/platform/notification-deliveries";

    @InjectMock MailTransport transport;
    @Inject EntityManager em;

    long sent;
    long gaveUpSend;
    long gaveUpRender;
    long retrying;
    long theirs;
    long ruleId;

    @BeforeEach
    @Transactional
    void seed() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // the retry sweep claims globally: nothing else may be retryable while a test counts sends
        em.createNativeQuery("DELETE FROM core.notification_delivery WHERE succeeded = FALSE AND attempts < 3").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_delivery WHERE recipient LIKE '%@door.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_rule WHERE recipient LIKE '%@door.local'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient)
            VALUES (1, 'door.probe.*', 'rule@door.local')
            """).executeUpdate();
        ruleId = ((Number) em.createNativeQuery(
            "SELECT id FROM core.notification_rule WHERE recipient = 'rule@door.local'").getSingleResult()).longValue();

        EventOutboxRow event = EventOutboxRow.of(1L, "door.probe.fired", Map.of("detail", "frost"));
        em.persist(event);
        long eventId = event.getId();

        sent = insert(1, 1001, "ok@door.local", "door.probe.sent", "TRUE", 1, null, "<p>hi</p>", "now() - interval '3 hours'");
        gaveUpSend = insert(1, 1002, "ko@door.local", "door.probe.send", "FALSE", 3, "MailException", "<p>hi</p>", "now() - interval '2 hours'");
        gaveUpRender = insertForEvent(1, ruleId, eventId, "render@door.local", "door.probe.fired", 3, "template: unknown", "");
        retrying = insert(1, 1004, "again@door.local", "door.probe.retry", "FALSE", 1, "MailException", "<p>hi</p>", "now() - interval '1 hour'");
        theirs = insert(2, 1005, "other@door.local", "door.probe.theirs", "FALSE", 3, "MailException", "<p>hi</p>", "now()");
    }

    private long insert(int tenant, long eventId, String to, String type, String succeeded, int attempts, String error,
                        String body, String attemptedAt) {
        return ((Number) em.createNativeQuery("""
            INSERT INTO core.notification_delivery
                (tenant_id, rule_id, event_id, event_type, recipient, subject, body, attempts, succeeded, error, attempted_at)
            VALUES (:t, :rule, :event, :type, :to, 'lumnik event: x', :body, :attempts, %s, :error, %s)
            RETURNING id
            """.formatted(succeeded, attemptedAt))
            .setParameter("t", tenant).setParameter("rule", 9000 + eventId)
            .setParameter("event", eventId).setParameter("type", type).setParameter("to", to)
            .setParameter("body", body).setParameter("attempts", attempts).setParameter("error", error)
            .getSingleResult()).longValue();
    }

    /** A delivery on the real rule and a real outbox event: what a replay of a render failure needs to redo. */
    private long insertForEvent(int tenant, long rule, long eventId, String to, String type, int attempts, String error,
                                String body) {
        return ((Number) em.createNativeQuery("""
            INSERT INTO core.notification_delivery
                (tenant_id, rule_id, event_id, event_type, recipient, subject, body, attempts, succeeded, error)
            VALUES (:t, :rule, :event, :type, :to, 'lumnik event: x', :body, :attempts, FALSE, :error)
            RETURNING id
            """)
            .setParameter("t", tenant).setParameter("rule", rule).setParameter("event", eventId)
            .setParameter("type", type).setParameter("to", to).setParameter("body", body)
            .setParameter("attempts", attempts).setParameter("error", error)
            .getSingleResult()).longValue();
    }

    @Transactional
    Object[] row(long id) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return (Object[]) em.createNativeQuery(
            "SELECT succeeded, attempts, error, body FROM core.notification_delivery WHERE id = :id")
            .setParameter("id", id).getSingleResult();
    }

    @Transactional
    long deliveriesOf(long rule, long event) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        return ((Number) em.createNativeQuery(
            "SELECT count(*) FROM core.notification_delivery WHERE rule_id = :r AND event_id = :e")
            .setParameter("r", rule).setParameter("e", event).getSingleResult()).longValue();
    }

    private io.restassured.specification.RequestSpecification asTenant(int tenant) {
        return given().headers(TestIdentity.tenantAndUser(tenant));
    }

    @Test
    void the_list_shows_given_up_first_then_retrying_then_sent_and_only_the_tenants_own() {
        asTenant(1).when().get(URL)
            .then().statusCode(200)
            .body("findAll { it.recipient.endsWith('@door.local') }", hasSize(4))
            .body("findAll { it.recipient.endsWith('@door.local') }.status",
                equalTo(List.of("given up: render", "given up: send", "retrying", "sent")))
            .body("find { it.recipient == 'ko@door.local' }.status", equalTo("given up: send"))
            .body("find { it.recipient == 'ko@door.local' }.attempts", equalTo(3))
            .body("find { it.recipient == 'ko@door.local' }.error", equalTo("MailException"))
            .body("find { it.recipient == 'render@door.local' }.status", equalTo("given up: render"))
            .body("find { it.recipient == 'again@door.local' }.status", equalTo("retrying"))
            .body("find { it.recipient == 'ok@door.local' }.status", equalTo("sent"))
            .body("find { it.recipient == 'ok@door.local' }.error", nullValue())
            .body("find { it.recipient == 'other@door.local' }", nullValue());
    }

    @Test
    void the_list_never_carries_the_mail_itself() {
        String json = asTenant(1).when().get(URL).then().statusCode(200).extract().asString();
        assertThat(json).doesNotContain("<p>hi</p>").doesNotContain("\"body\"").doesNotContain("\"subject\"");
    }

    @Test
    void the_list_honours_its_limit() {
        asTenant(1).when().get(URL + "?limit=1").then().statusCode(200).body("size()", equalTo(1));
    }

    @Test
    void replaying_a_mail_given_up_on_send_sends_that_same_delivery_and_adds_none() {
        asTenant(1).when().post(URL + "/" + gaveUpSend + "/replay")
            .then().statusCode(200)
            .body("id", equalTo((int) gaveUpSend))
            .body("status", equalTo("sent"));

        verify(transport, times(1)).send(any(Mail.class));
        Object[] r = row(gaveUpSend);
        assertThat((Boolean) r[0]).isTrue();
        assertThat(((Number) r[1]).intValue()).as("the new attempt is counted on the delivery").isEqualTo(4);
        assertThat(deliveriesOf(9000 + 1002, 1002)).as("no second delivery for the rule and event").isEqualTo(1);
    }

    @Test
    void a_replay_that_fails_again_leaves_the_delivery_given_up_with_the_kind_of_failure() {
        doThrow(new IllegalStateException("550 ko@door.local rejected")).when(transport).send(any(Mail.class));

        String answer = asTenant(1).when().post(URL + "/" + gaveUpSend + "/replay")
            .then().statusCode(200)
            .body("status", equalTo("given up: send"))
            .extract().asString();
        assertThat(answer).doesNotContain("rejected").doesNotContain("ko@door.local");

        Object[] r = row(gaveUpSend);
        assertThat((Boolean) r[0]).isFalse();
        assertThat(r[2]).as("the class, never the message: it quotes the address").isEqualTo("IllegalStateException");
    }

    @Test
    void a_mail_already_sent_is_never_sent_again() {
        asTenant(1).when().post(URL + "/" + sent + "/replay").then().statusCode(409);
        verify(transport, never()).send(any(Mail.class));
    }

    @Test
    void a_mail_still_being_retried_has_nothing_to_replay() {
        asTenant(1).when().post(URL + "/" + retrying + "/replay").then().statusCode(409);
        verify(transport, never()).send(any(Mail.class));
    }

    @Test
    void another_tenants_delivery_is_a_404_and_is_not_sent() {
        asTenant(1).when().post(URL + "/" + theirs + "/replay").then().statusCode(404);
        verify(transport, never()).send(any(Mail.class));
        assertThat(((Number) row(theirs)[1]).intValue()).isEqualTo(3);
    }

    @Test
    void a_mail_given_up_on_render_is_rendered_again_from_its_event_then_sent() {
        asTenant(1).when().post(URL + "/" + gaveUpRender + "/replay")
            .then().statusCode(200)
            .body("status", equalTo("sent"));

        verify(transport, times(1)).send(any(Mail.class));
        Object[] r = row(gaveUpRender);
        assertThat((Boolean) r[0]).isTrue();
        assertThat((String) r[3]).as("the body the render now produced").contains("door.probe.fired");
    }

    @Test
    void a_render_replay_whose_event_was_purged_says_so_and_sends_nothing() {
        purgeEvents();
        asTenant(1).when().post(URL + "/" + gaveUpRender + "/replay")
            .then().statusCode(409)
            .body("error", containsString("event"));
        verify(transport, never()).send(any(Mail.class));
        assertThat((Boolean) row(gaveUpRender)[0]).isFalse();
    }

    @Transactional
    void purgeEvents() {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_outbox WHERE event_type = 'door.probe.fired'").executeUpdate();
    }
}
