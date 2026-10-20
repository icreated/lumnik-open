package io.lumnik.identity;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource whose URL names a tenant ({@code /api/tenants/{tenantId}/…}): every door of it
 * reconciles that tenant with the caller's, before any lookup, through {@link TenantInPathFilter}.
 *
 * <p>A name binding on the class rather than a call in each method (#1292): a door added later is
 * covered without remembering to, which a call in a shared helper never guaranteed — the inventory
 * could only see that the call appeared somewhere in the file.
 *
 * <p>Class only, like the hub's {@code @TenantWide}: {@code UrlTenantReconciliationInventoryTest}
 * reads the marker anywhere in a file as that whole file's decision, so on one method it would make
 * every other door of the resource look reconciled.
 */
@NameBinding
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantInPath {}
