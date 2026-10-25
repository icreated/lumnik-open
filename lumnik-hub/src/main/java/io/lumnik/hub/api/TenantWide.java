package io.lumnik.hub.api;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JAX-RS resource whose subject is the whole tenant, not one scope: a caller bound to
 * {@code scope:*} roles is refused every door of it, enforced by {@link TenantWideFilter}.
 *
 * <p>A subscription is such a resource (#1207): a notification rule or a webhook receives every
 * matching event of the tenant — another scope's row activity, the documents its workflows find
 * dormant, its connectors' runs — and sends them where its author chose. A name binding on the
 * class rather than a check in each method: a door added later is covered without remembering to.
 *
 * <p>Class only, unlike {@code @AiDoor}, and deliberately: {@code ScopeBoundaryInventoryTest} reads
 * the marker anywhere in a file as that whole file's decision, so on one method it would make every
 * other method of the resource look guarded while answering a bound caller.
 */
@NameBinding
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantWide {}
