-- V97 — A failed webhook delivery's error names the receiver's status alone (#2002).
-- Before, it read "HTTP <status>: <the first 200 characters of the receiver's body>", and the delivery list hands the
-- error back: pointed at an internal service, that was a read of whatever the URL reached. The body itself stays in
-- response_body, which no door returns. Only the prefix WebhookDeliveryService wrote is cut; every other error is kept.
-- Idempotent: a cut error no longer matches.

UPDATE core.webhook_delivery
   SET error = substring(error FROM '^HTTP [0-9]{3}')
 WHERE error ~ '^HTTP [0-9]{3}: ';
