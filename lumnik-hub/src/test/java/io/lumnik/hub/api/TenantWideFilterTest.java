package io.lumnik.hub.api;

import io.lumnik.common.security.CurrentRolesHolder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** #1207: a tenant-wide door refuses a caller bound to a scope, and only that caller. */
class TenantWideFilterTest {

  @AfterEach
  void clearRoles() {
    CurrentRolesHolder.clear();
  }

  @Test
  void a_bound_caller_is_answered_the_uniform_refusal() {
    CurrentRolesHolder.set(Set.of("lm_admin", "scope:magasin"));
    var ctx = mock(ContainerRequestContext.class);

    new TenantWideFilter().filter(ctx);

    var response = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(response.capture());
    assertThat(response.getValue().getStatus()).isEqualTo(403);
    assertThat(response.getValue().getEntity()).isEqualTo(Map.of("error", "scope not granted"));
  }

  @Test
  void an_unbound_caller_passes_to_the_door() {
    CurrentRolesHolder.set(Set.of("lm_admin"));
    var ctx = mock(ContainerRequestContext.class);

    new TenantWideFilter().filter(ctx);

    verify(ctx, never()).abortWith(any());
  }
}
