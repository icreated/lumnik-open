package io.lumnik.hub.tenant;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.tenant.CurrentTenantHolder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.lumnik.testkit.auth.TestIdentity.TENANT_HEADER;
import static io.lumnik.testkit.auth.TestIdentity.USER_HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Dev-bypass header path (#1615): anything but a positive id → 400 by name, before any role; absence passes. */
class TenantContextFilterHeaderTest {

  private static final String IMPERSONATE_HEADER = "X-Impersonate-Tenant";   // TestIdentity has no constant for it

  private static TenantContextFilter devBypass() {
    TenantContextFilter f = new TenantContextFilter();
    f.devBypass = true;
    return f;
  }

  private static ContainerRequestContext request(Map<String, String> headers) {
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    headers.forEach((name, value) -> when(ctx.getHeaderString(name)).thenReturn(value));
    return ctx;
  }

  @AfterEach
  void clear() {
    TenantContextFilter.CURRENT_TENANT.remove();
    TenantContextFilter.CURRENT_USER.remove();
    CurrentRolesHolder.clear();
    CurrentTenantHolder.clear();
    MDC.clear();
  }

  @Test
  void a_valid_identity_binds_tenant_user_and_the_dev_roles() {
    ContainerRequestContext ctx = request(Map.of(TENANT_HEADER, " 7 ", USER_HEADER, "42"));

    devBypass().filter(ctx);

    verify(ctx, never()).abortWith(any());
    assertThat(TenantContextFilter.current()).isEqualTo(7L);
    assertThat(CurrentTenantHolder.get()).isEqualTo(7L);
    assertThat(TenantContextFilter.currentUser()).isEqualTo(42L);
    assertThat(CurrentRolesHolder.get()).contains("lm_user", "lm_admin", "lm_superadmin");
  }

  /** A valid impersonation replaces the tenant and tags the log context. */
  @Test
  void a_valid_impersonation_replaces_the_tenant_and_is_tagged() {
    ContainerRequestContext ctx = request(Map.of(TENANT_HEADER, "7", USER_HEADER, "42", IMPERSONATE_HEADER, "9"));

    devBypass().filter(ctx);

    verify(ctx, never()).abortWith(any());
    assertThat(CurrentTenantHolder.get()).isEqualTo(9L);
    assertThat(MDC.get("impersonatedTenant")).isEqualTo(9L);
  }

  /** Absent headers keep today's behaviour: dev-bypass callers may reach tenant-less endpoints (#1615, kept open). */
  @Test
  void absent_headers_are_not_refused_at_the_door() {
    ContainerRequestContext ctx = request(Map.of());

    devBypass().filter(ctx);

    verify(ctx, never()).abortWith(any());
    assertThat(TenantContextFilter.current()).isNull();
  }

  static Stream<Arguments> malformed() {
    return Stream.concat(
        Stream.of(TENANT_HEADER, USER_HEADER, IMPERSONATE_HEADER)
            .flatMap(h -> Stream.of("acme", "-3", "1.5", " ", "9223372036854775808").map(v -> Arguments.of(h, v))),
        // 0 is the system sentinel: never an identity a request may claim — absence is how a caller has no tenant
        Stream.of(TENANT_HEADER, USER_HEADER, IMPERSONATE_HEADER).map(h -> Arguments.of(h, "0")));
  }

  @ParameterizedTest(name = "{0}: \"{1}\"")
  @MethodSource("malformed")
  void a_malformed_header_is_refused_by_its_name_before_any_role(String header, String value) {
    Map<String, String> headers = new HashMap<>(Map.of(TENANT_HEADER, "7", USER_HEADER, "42"));
    headers.put(header, value);
    ContainerRequestContext ctx = request(headers);

    devBypass().filter(ctx);

    ArgumentCaptor<Response> refused = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(refused.capture());
    assertThat(refused.getValue().getStatus()).isEqualTo(400);
    assertThat(refused.getValue().getEntity()).isEqualTo(Map.of("error", header + " must be a positive integer"));
    assertThat(CurrentRolesHolder.get()).as("no role on a refused request").isEmpty();
    assertThat(CurrentTenantHolder.get()).as("no tenant on a refused request").isNull();
  }
}
