package io.lumnik.identity.tenant;

/**
 * Fired synchronously by {@link TenantResource#deactivate} <b>before</b> {@code Tenant.archive()}
 * runs (design doc §2 "Purge on tenant delete", #701 trigger row 7). {@code lumnik-hub} depends on
 * {@code lumnik-identity} at compile scope (never the reverse), so this is the one direction a CDI
 * event can cross the module boundary in: identity fires it, a hub {@code @Observes} listener reacts.
 *
 * @param tenantId the tenant being archived
 */
public record TenantArchived(long tenantId) {
}
