package io.lumnik.common.security;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JAX-RS resource whose subject is the whole tenant, or wider, never one scope: a caller bound to
 * {@code scope:*} roles is refused every door of it, enforced by the hub's {@code TenantWideFilter}.
 *
 * <p>A subscription is such a resource (#1207): a notification rule or a webhook receives every
 * matching event of the tenant — another scope's row activity, the documents its workflows find
 * dormant, its connectors' runs — and sends them where its author chose. So are the tenant's secrets, its
 * users and roles, its SCIM tokens (#1992). A name binding on the class rather than a check in each method:
 * a door added later is covered without remembering to.
 *
 * <p>Here, not in the hub, so that {@code lumnik-identity}'s doors carry it too: the hub's filter binds to
 * this one annotation, whichever module the resource lives in.
 *
 * <p>Class only, unlike {@code @AiDoor}, and deliberately: {@code ScopeBoundaryInventoryTest} and
 * {@code DoorInventoryTest} read the marker anywhere in a file as that whole file's decision, so on
 * one method it would make every other method of the resource look guarded while answering a bound caller.
 */
@NameBinding
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantWide {}
