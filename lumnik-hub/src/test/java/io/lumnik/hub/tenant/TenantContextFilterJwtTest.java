package io.lumnik.hub.tenant;

import jakarta.enterprise.inject.Instance;
import jakarta.json.Json;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;

/** Unit test of the filter's JWT routing: short-circuit on user_id, external resolution otherwise, 401 on gaps. */
class TenantContextFilterJwtTest {

  private TenantContextFilter newFilter(JsonWebToken token, ExternalIdentityResolver resolver) {
    TenantContextFilter f = new TenantContextFilter();
    f.devBypass = false;
    f.subjectClaim = "sub";
    @SuppressWarnings("unchecked")
    Instance<JsonWebToken> inst = mock(Instance.class);
    when(inst.isUnsatisfied()).thenReturn(false);
    when(inst.get()).thenReturn(token);
    f.jwtInstance = inst;
    f.externalIdentities = instanceOf(resolver);
    // Default: the user row is unknown to the hub — the filter must pass it through (back-compat).
    // (Mockito's boolean default is false = not deactivated.)
    f.loginGuard = mock(UserLoginGuard.class);
    return f;
  }

  /** Wrap a resolver as the CDI Instance the filter injects; null = an edition that ships none. */
  private static Instance<ExternalIdentityResolver> instanceOf(ExternalIdentityResolver resolver) {
    @SuppressWarnings("unchecked")
    Instance<ExternalIdentityResolver> inst = mock(Instance.class);
    when(inst.isUnsatisfied()).thenReturn(resolver == null);
    if (resolver != null) when(inst.get()).thenReturn(resolver);
    return inst;
  }

  @AfterEach
  void clear() {
    // The unit tests invoke only the REQUEST filter (which sets CurrentTenantHolder + MDC),
    // not the response filter that clears them — so clear everything here to stop state
    // leaking across tests and causing order-dependent failures.
    TenantContextFilter.CURRENT_TENANT.remove();
    TenantContextFilter.CURRENT_USER.remove();
    CurrentRolesHolder.clear();
    CurrentTenantHolder.clear();
    MDC.clear();
  }

  @Test
  void user_id_present_short_circuits_no_resolver() {
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(99L);
    when(t.getGroups()).thenReturn(Set.of("lm_integrator"));
    ExternalIdentityResolver resolver = mock(ExternalIdentityResolver.class);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, resolver).filter(ctx);

    verifyNoInteractions(resolver);
    assertThat(TenantContextFilter.currentUser()).isEqualTo(99L);
    verify(ctx, never()).abortWith(any());
  }

  @Test
  void user_id_of_deactivated_user_is_denied_403() {
    // The SCIM promise: archiving a user denies the very next request — even a still-valid
    // token carrying user_id (the claim the shipped realm emits) must not bypass the check.
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(99L);
    when(t.getGroups()).thenReturn(Set.of("lm_user"));
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    TenantContextFilter f = newFilter(t, mock(ExternalIdentityResolver.class));
    when(f.loginGuard.isDeactivated(99L)).thenReturn(true);

    f.filter(ctx);

    ArgumentCaptor<Response> cap = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(cap.capture());
    assertThat(cap.getValue().getStatus()).isEqualTo(403);
    assertThat(cap.getValue().getEntity())
        .as("same refusal shape as the JIT path")
        .isEqualTo(Map.of("error", "user is deactivated"));
    assertThat(TenantContextFilter.currentUser())
        .as("no user context may survive a denied login").isNull();
  }

  @Test
  void user_id_of_active_user_passes() {
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(99L);
    when(t.getGroups()).thenReturn(Set.of("lm_user"));
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    TenantContextFilter f = newFilter(t, mock(ExternalIdentityResolver.class));
    when(f.loginGuard.isDeactivated(99L)).thenReturn(false);

    f.filter(ctx);

    verify(ctx, never()).abortWith(any());
    assertThat(TenantContextFilter.currentUser()).isEqualTo(99L);
  }

  @Test
  void roles_flow_into_the_cross_module_holder_and_clear_on_response() {
    // lumnik-rag cannot see this filter at compile time — the roles must land in the
    // common CurrentRolesHolder (mirror of CurrentTenantHolder) and be cleared with it.
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(99L);
    when(t.getGroups()).thenReturn(Set.of("lm_user", "scope:achat"));
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    TenantContextFilter f = newFilter(t, mock(ExternalIdentityResolver.class));
    f.filter(ctx);

    assertThat(CurrentRolesHolder.get())
        .containsExactlyInAnyOrder("lm_user", "scope:achat");

    f.filter(ctx, mock(ContainerResponseContext.class));
    assertThat(CurrentRolesHolder.get())
        .as("response filter clears the holder — no leak onto the pooled thread").isEmpty();
  }

  @Test
  void no_user_id_triggers_resolver_and_uses_the_returned_pk() {
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(null);
    when(t.getClaim("sub")).thenReturn("sub-XYZ");
    when(t.getClaim("name")).thenReturn("Xavier");
    when(t.getClaim("email")).thenReturn("x@y.test");
    when(t.getIssuer()).thenReturn("https://idp.test");
    when(t.getGroups()).thenReturn(Set.of("lm_integrator"));
    ExternalIdentityResolver resolver = mock(ExternalIdentityResolver.class);
    when(resolver.findOrCreate("https://idp.test", "sub-XYZ", "Xavier", "x@y.test")).thenReturn(555L);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, resolver).filter(ctx);

    assertThat(TenantContextFilter.currentUser()).isEqualTo(555L);
    verify(ctx, never()).abortWith(any());
  }

  @Test
  void missing_tenant_id_aborts_401() {
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(null);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, mock(ExternalIdentityResolver.class)).filter(ctx);

    ArgumentCaptor<Response> cap = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(cap.capture());
    assertThat(cap.getValue().getStatus()).isEqualTo(401);
  }

  @Test
  void missing_subject_aborts_401() {
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(null);
    when(t.getClaim("sub")).thenReturn(null);
    when(t.getIssuer()).thenReturn("https://idp.test");
    when(t.getGroups()).thenReturn(Set.of());
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, mock(ExternalIdentityResolver.class)).filter(ctx);

    ArgumentCaptor<Response> cap = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(cap.capture());
    assertThat(cap.getValue().getStatus()).isEqualTo(401);
  }

  @Test
  void configurable_subject_claim_is_honoured() {
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(null);
    when(t.getClaim("cohab_id")).thenReturn("COHAB-123");
    when(t.getClaim("name")).thenReturn("Cee");
    when(t.getClaim("email")).thenReturn(null);
    when(t.getIssuer()).thenReturn("https://idp.test");
    when(t.getGroups()).thenReturn(Set.of("lm_integrator"));
    ExternalIdentityResolver resolver = mock(ExternalIdentityResolver.class);
    when(resolver.findOrCreate("https://idp.test", "COHAB-123", "Cee", null)).thenReturn(777L);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    TenantContextFilter f = newFilter(t, resolver);
    f.subjectClaim = "cohab_id";
    f.filter(ctx);

    assertThat(TenantContextFilter.currentUser()).isEqualTo(777L);
  }

  @Test
  void provisioner_failure_propagates_fail_closed() {
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(null);
    when(t.getClaim("sub")).thenReturn("sub-ERR");
    when(t.getIssuer()).thenReturn("https://idp.test");
    when(t.getGroups()).thenReturn(Set.of("lm_integrator"));
    ExternalIdentityResolver resolver = mock(ExternalIdentityResolver.class);
    when(resolver.findOrCreate(anyString(), anyString(), any(), any()))
        .thenThrow(new IllegalStateException("db down"));
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    // The provisioner failure must propagate (request fails closed with 500) — never
    // swallowed into a 200 with no user — AND the tenant/user context must be cleared so it
    // can't leak (RLS-critical) into the next request on this pooled thread.
    assertThatThrownBy(() -> newFilter(t, resolver).filter(ctx)).isInstanceOf(IllegalStateException.class);
    assertThat(TenantContextFilter.currentUser()).as("no user set on failure").isNull();
    assertThat(TenantContextFilter.current()).as("tenant thread-local cleared on failure").isNull();
    assertThat(CurrentTenantHolder.get())
        .as("RLS holder cleared on failure").isNull();
  }

  @Test
  void oversized_subject_aborts_401() {
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(null);
    when(t.getClaim("sub")).thenReturn("s".repeat(256));   // > the User.idp_subject/login 255 limit
    when(t.getIssuer()).thenReturn("https://idp.test");
    when(t.getGroups()).thenReturn(Set.of("lm_integrator"));
    ExternalIdentityResolver resolver = mock(ExternalIdentityResolver.class);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, resolver).filter(ctx);

    verifyNoInteractions(resolver);   // never reaches the provisioner (no oversized-key INSERT → no 500)
    ArgumentCaptor<Response> cap = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(cap.capture());
    assertThat(cap.getValue().getStatus()).isEqualTo(401);
  }

  @Test
  void non_integer_tenant_id_aborts_401_not_truncated() {
    // A fractional JSON number must be rejected, not truncated to an int (1.9 → 1 could land
    // the user in the wrong tenant).
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(Json.createValue(new BigDecimal("1.9")));
    ExternalIdentityResolver resolver = mock(ExternalIdentityResolver.class);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, resolver).filter(ctx);

    verifyNoInteractions(resolver);
    ArgumentCaptor<Response> cap = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(cap.capture());
    assertThat(cap.getValue().getStatus()).isEqualTo(401);
  }

  @Test
  void smallrye_jakarta_json_claim_types_are_parsed() {
    // Real Keycloak claims arrive as jakarta.json values (JsonNumber/JsonString), NOT
    // java Long/String. tenant_id (JsonNumber) must parse (no 401) and a custom subject
    // (JsonString) must be unquoted.
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(Json.createValue(7));
    when(t.getClaim("user_id")).thenReturn(null);
    when(t.getClaim("cohab_id")).thenReturn(Json.createValue("COHAB-9"));
    when(t.getClaim("name")).thenReturn(null);
    when(t.getClaim("email")).thenReturn(null);
    when(t.getIssuer()).thenReturn("https://idp.test");
    when(t.getGroups()).thenReturn(Set.of("lm_integrator"));
    ExternalIdentityResolver resolver = mock(ExternalIdentityResolver.class);
    when(resolver.findOrCreate("https://idp.test", "COHAB-9", "COHAB-9", null)).thenReturn(888L);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    TenantContextFilter f = newFilter(t, resolver);
    f.subjectClaim = "cohab_id";
    f.filter(ctx);

    assertThat(TenantContextFilter.currentUser()).isEqualTo(888L);
    verify(ctx, never()).abortWith(any());
  }

  @Test
  void pre_authenticated_request_preserves_tenant_and_skips_jwt() {
    // An AUTHENTICATION-priority filter (e.g. the pro SCIM one) already authenticated this
    // request, set the tenant, and said so via the request property. TenantContextFilter must
    // NOT clear the tenant (clearing → null tenant → RLS sees nothing for the operation) and
    // must not run its own JWT path.
    CurrentTenantHolder.set(99L);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    when(ctx.getProperty(TenantContextFilter.PRE_AUTHENTICATED)).thenReturn(Boolean.TRUE);

    TenantContextFilter f = new TenantContextFilter();
    f.devBypass = false;
    f.filter(ctx);

    assertThat(CurrentTenantHolder.get())
        .as("pre-authenticated tenant must survive TenantContextFilter").isEqualTo(99L);
    verify(ctx, never()).abortWith(any());
    CurrentTenantHolder.clear();
  }

  @Test
  void pre_authenticated_flag_must_be_true_not_merely_present() {
    // The skip is licensed by an authenticator saying "this request is MINE" — Boolean.TRUE,
    // not mere presence. Any other value must leave this filter owning (and clearing) the context.
    CurrentTenantHolder.set(99L);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    when(ctx.getProperty(TenantContextFilter.PRE_AUTHENTICATED)).thenReturn(Boolean.FALSE);

    TenantContextFilter f = new TenantContextFilter();
    f.devBypass = true;   // the owning path in its simplest form (no headers → no tenant)
    f.filter(ctx);

    assertThat(CurrentTenantHolder.get())
        .as("a non-TRUE flag must not skip context ownership").isNull();
  }

  @Test
  void null_from_resolver_aborts_401_fail_closed() {
    // The resolver contract returns a boxed Long; a broken pro implementation returning null
    // must not produce a user-less authenticated request — refuse at the seam.
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(null);
    when(t.getClaim("sub")).thenReturn("sub-NULL");
    when(t.getIssuer()).thenReturn("https://idp.test");
    when(t.getGroups()).thenReturn(Set.of("lm_integrator"));
    ExternalIdentityResolver resolver = mock(ExternalIdentityResolver.class);
    when(resolver.findOrCreate(anyString(), anyString(), any(), any())).thenReturn(null);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, resolver).filter(ctx);

    assertThat(TenantContextFilter.currentUser()).as("no user on a null resolution").isNull();
    ArgumentCaptor<Response> cap = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(cap.capture());
    assertThat(cap.getValue().getStatus()).isEqualTo(401);
  }

  @Test
  void no_resolver_and_no_user_id_aborts_401_fail_closed() {
    // Open edition: no ExternalIdentityResolver bean on the classpath. A JWT without a user_id
    // claim then has no way to become a user — refuse (401), never a silent user-less request.
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(null);
    when(t.getClaim("sub")).thenReturn("sub-XYZ");
    when(t.getIssuer()).thenReturn("https://idp.test");
    when(t.getGroups()).thenReturn(Set.of("lm_integrator"));
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, null).filter(ctx);

    ArgumentCaptor<Response> cap = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(cap.capture());
    assertThat(cap.getValue().getStatus()).isEqualTo(401);
  }

  @Test
  void no_resolver_with_user_id_claim_still_authenticates() {
    // Open edition happy path: our realm emits user_id — external resolution is never needed.
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(99L);
    when(t.getGroups()).thenReturn(Set.of("lm_integrator"));
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, null).filter(ctx);

    assertThat(TenantContextFilter.currentUser()).isEqualTo(99L);
    verify(ctx, never()).abortWith(any());
  }

  @Test
  void deactivated_identity_aborts_403() {
    // A known external identity that SCIM deactivated must be denied at login (403), not 500/200.
    JsonWebToken t = mock(JsonWebToken.class);
    when(t.getClaim("tenant_id")).thenReturn(7L);
    when(t.getClaim("user_id")).thenReturn(null);
    when(t.getClaim("sub")).thenReturn("sub-GONE");
    when(t.getIssuer()).thenReturn("https://idp.test");
    when(t.getGroups()).thenReturn(Set.of());
    ExternalIdentityResolver resolver = mock(ExternalIdentityResolver.class);
    when(resolver.findOrCreate(anyString(), anyString(), any(), any()))
        .thenThrow(new DeactivatedUserException("https://idp.test", "sub-GONE"));
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);

    newFilter(t, resolver).filter(ctx);

    ArgumentCaptor<Response> cap = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(cap.capture());
    assertThat(cap.getValue().getStatus()).isEqualTo(403);
  }
}
