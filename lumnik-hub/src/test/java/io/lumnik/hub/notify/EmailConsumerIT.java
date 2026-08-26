package io.lumnik.hub.notify;

import io.lumnik.common.tenant.TenantContext;
import io.lumnik.hub.event.EventOutboxRow;
import io.lumnik.hub.event.consumer.OutboxSweeper;
import io.lumnik.hub.event.consumer.SchedulerPausedProfile;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SchedulerPausedProfile.class)
class EmailConsumerIT {

    @Inject OutboxSweeper sweeper;
    @Inject EmailConsumer consumer;
    @Inject MockMailbox mailbox;
    @Inject EntityManager em;

    @BeforeEach
    @Transactional
    void clean() {
        mailbox.clear();
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // Whole-table wipe: prior ITs leave low-id outbox rows that would fill the
        // fresh cursor's first batch before our seeds (Task-2-approved pattern).
        em.createNativeQuery("DELETE FROM core.event_outbox").executeUpdate();
        em.createNativeQuery("DELETE FROM core.notification_rule WHERE recipient LIKE '%@mailtest.local'").executeUpdate();
        em.createNativeQuery("DELETE FROM core.event_consumer WHERE consumer_name = 'email'").executeUpdate();
    }

    @Transactional
    void seedRule(String pattern, String recipient) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient)
            VALUES (1, :p, :r)
            """).setParameter("p", pattern).setParameter("r", recipient).executeUpdate();
    }

    @Transactional
    void seedRuleWithTemplate(String pattern, String recipient, String template) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient, template)
            VALUES (1, :p, :r, :t)
            """).setParameter("p", pattern).setParameter("r", recipient).setParameter("t", template).executeUpdate();
    }

    @Transactional
    void seedRuleWithEntityFilter(String pattern, String recipient, String entityFilter) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.createNativeQuery("""
            INSERT INTO core.notification_rule (tenant_id, event_pattern, recipient, entity_filter)
            VALUES (1, :p, :r, :f)
            """).setParameter("p", pattern).setParameter("r", recipient).setParameter("f", entityFilter).executeUpdate();
    }

    @Transactional
    void seedEvent(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.persist(EventOutboxRow.of(1L, type, Map.of("detail", "frost")));
    }

    @Transactional
    void seedEventWithEntity(String type, String entity) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        em.persist(EventOutboxRow.ofFull(1L, null, type, "system", entity, null,
                Map.of("detail", "frost"), null, Instant.now()));
    }

    @Test
    void matchingRuleSendsEmail() {
        seedRule("mail.test.*", "ops@mailtest.local");
        seedEvent("mail.test.fired");

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        var sent = mailbox.getMailsSentTo("ops@mailtest.local");
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).getSubject().contains("mail.test.fired"));
    }

    @Test
    void payloadIsRenderedAsJsonNotMapToString() {
        seedRule("mail.test.*", "json@mailtest.local");
        seedEvent("mail.test.payload"); // payload = {detail: frost}

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        var sent = mailbox.getMailsSentTo("json@mailtest.local");
        assertEquals(1, sent.size());
        String html = sent.get(0).getHtml();
        // Qute HTML-escapes the <pre> body, so JSON quotes render as &quot;.
        assertTrue(html.contains("&quot;detail&quot;:&quot;frost&quot;"), "payload rendered as JSON");
        assertFalse(html.contains("detail=frost"), "not the Java Map toString form");
    }

    @Test
    void payloadIsNavigableInTemplates() {
        // The metier contract: a template reaches INTO the payload ({payload.detail}),
        // not just a JSON blob in a <pre>. test-nested.html lives in test resources.
        seedRuleWithTemplate("mail.test.*", "nested@mailtest.local", "test-nested");
        seedEvent("mail.test.nested"); // payload = {detail: frost}

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        var sent = mailbox.getMailsSentTo("nested@mailtest.local");
        assertEquals(1, sent.size(), "a navigable payload must not break the render");
        assertTrue(sent.get(0).getHtml().contains("detail:frost"),
            "the template reads the field, not the serialized blob");
    }

    @Test
    void decisionAlertTemplateRendersTheMetierMessage() {
        seedRuleWithTemplate("decision.*", "adv@mailtest.local", "decision-alert");
        seedDecisionEvent("decision.commande-bloquee");

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        var sent = mailbox.getMailsSentTo("adv@mailtest.local");
        assertEquals(1, sent.size());
        String html = sent.get(0).getHtml();
        assertTrue(html.contains("Commande 12045 bloquée depuis 32 jours"),
            "the rendered process message is the headline");
        assertTrue(html.contains("12045"), "entity key visible");
        assertTrue(html.contains("erp.commandes"), "entity visible");
    }

    @Transactional
    void seedDecisionEvent(String type) {
        em.createNativeQuery("SET LOCAL app.current_tenant = '0'").executeUpdate();
        // The exact shape ProcessConsumer.buildPayload emits.
        em.persist(EventOutboxRow.ofFull(1L, null, type, "process:commande-bloquee",
                "erp.commandes", "12045",
                Map.of("message", "Commande 12045 bloquée depuis 32 jours",
                       "process", "commande-bloquee",
                       "entity", "erp.commandes",
                       "entity_key", "12045",
                       "fields", Map.of("num", "12045", "jours", 32)),
                null, Instant.now()));
    }

    @Test
    void nonMatchingRuleSendsNothing() {
        seedRule("other.domain.*", "quiet@mailtest.local");
        seedEvent("mail.test.ignored");

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        assertTrue(mailbox.getMailsSentTo("quiet@mailtest.local").isEmpty());
    }

    @Test
    void twoMatchingRulesSendToBothRecipients() {
        seedRule("mail.test.*", "a@mailtest.local");
        seedRule("mail.test.*", "b@mailtest.local");
        seedEvent("mail.test.multi");

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        assertEquals(1, mailbox.getMailsSentTo("a@mailtest.local").size());
        assertEquals(1, mailbox.getMailsSentTo("b@mailtest.local").size());
    }

    @Test
    void entityFilteredRuleIgnoresOtherEntity() {
        seedRuleWithEntityFilter("data.row.changed", "scoped@mailtest.local", "erp.fact");
        seedEventWithEntity("data.row.changed", "erp.other");

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        assertTrue(mailbox.getMailsSentTo("scoped@mailtest.local").isEmpty(),
            "entity_filter excludes events for other tables");
    }

    @Test
    void entityFilteredRuleReceivesMatchingEntity() {
        seedRuleWithEntityFilter("data.row.changed", "scoped@mailtest.local", "erp.fact");
        seedEventWithEntity("data.row.changed", "erp.fact");

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        assertEquals(1, mailbox.getMailsSentTo("scoped@mailtest.local").size());
    }

    @Test
    void entityFilteredRuleStillReceivesEntityLessEvents() {
        seedRuleWithEntityFilter("connector.run.*", "scoped@mailtest.local", "erp.fact");
        seedEvent("connector.run.completed"); // of() carries no entity

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        assertEquals(1, mailbox.getMailsSentTo("scoped@mailtest.local").size(),
            "entity-less events reach scoped rules");
    }

    @Test
    void unknownTemplateSkipsRuleWithoutKillingTheSweep() {
        seedRuleWithTemplate("mail.test.*", "bad@mailtest.local", "does-not-exist");
        seedRule("mail.test.*", "good@mailtest.local");
        seedEvent("mail.test.template-skip");

        TenantContext.runAsSystem(() -> sweeper.sweep("email", 100, consumer::process));

        assertTrue(mailbox.getMailsSentTo("bad@mailtest.local").isEmpty());
        assertEquals(1, mailbox.getMailsSentTo("good@mailtest.local").size());
    }
}
