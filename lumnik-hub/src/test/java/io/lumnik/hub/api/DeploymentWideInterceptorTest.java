package io.lumnik.hub.api;

import io.lumnik.common.security.CurrentRolesHolder;
import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.hub.tenant.DeploymentTenancy;
import io.lumnik.hub.tenant.DeploymentTenancy.Verdict;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1991: the count a deployment-wide write rests on is held while the write runs, and let go once it is over — allowed,
 * refused, or failed. The IT cannot reach the allowed case under {@code auto}: its database already counts several
 * tenants.
 */
class DeploymentWideInterceptorTest {

  private final DeploymentWideInterceptor interceptor = new DeploymentWideInterceptor();
  private final Connection held = mock(Connection.class);
  private final InvocationContext call = mock(InvocationContext.class);

  static class Door {
    @RequiresPermission("lm_admin")
    public void write() {
      // the door's role gate, as the interceptor reads it
    }
  }

  @BeforeEach
  void door() throws Exception {
    interceptor.tenancy = mock(DeploymentTenancy.class);
    when(call.getMethod()).thenReturn(Door.class.getMethod("write"));
    when(call.getTarget()).thenReturn(new Door());
    CurrentRolesHolder.set(Set.of("lm_admin"));
  }

  @AfterEach
  void clearRoles() {
    CurrentRolesHolder.clear();
    CurrentTenantHolder.clear();
  }

  /** #2050: what is written from the hub's own tenant is shared with every tenant, on any hub: lm_superadmin only. */
  @Test
  void a_write_from_the_hubs_own_tenant_needs_lm_superadmin() throws Exception {
    CurrentTenantHolder.set(DeploymentTenancy.HUBADMIN_TENANT);

    assertThatThrownBy(() -> interceptor.around(call)).isInstanceOf(WebApplicationException.class)
        .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(403));

    verify(call, never()).proceed();
    verify(interceptor.tenancy, never()).hold();
  }

  @Test
  void a_superadmin_writes_from_the_hubs_own_tenant() throws Exception {
    CurrentTenantHolder.set(DeploymentTenancy.HUBADMIN_TENANT);
    CurrentRolesHolder.set(Set.of("lm_admin", "lm_superadmin"));
    when(call.proceed()).thenReturn("written");

    assertThat(interceptor.around(call)).isEqualTo("written");
  }

  @Test
  void an_allowed_write_runs_under_its_count_then_lets_it_go() throws Exception {
    when(interceptor.tenancy.hold()).thenReturn(new Verdict(false, held));
    when(call.proceed()).thenAnswer(i -> {
      verify(held, never()).rollback();   // still held while the write runs
      return "written";
    });

    assertThat(interceptor.around(call)).isEqualTo("written");

    verify(held).rollback();
    verify(held).close();
  }

  @Test
  void a_failed_write_lets_its_count_go_too() throws Exception {
    when(interceptor.tenancy.hold()).thenReturn(new Verdict(false, held));
    when(call.proceed()).thenThrow(new IllegalStateException("the write failed"));

    assertThatThrownBy(() -> interceptor.around(call)).hasMessage("the write failed");

    verify(held).rollback();
    verify(held).close();
  }

  @Test
  void a_refused_write_never_runs_and_lets_its_count_go() throws Exception {
    when(interceptor.tenancy.hold()).thenReturn(new Verdict(true, held));

    assertThatThrownBy(() -> interceptor.around(call)).isInstanceOf(WebApplicationException.class)
        .satisfies(e -> assertThat(((WebApplicationException) e).getResponse().getStatus()).isEqualTo(403));

    verify(call, never()).proceed();
    verify(held).rollback();
    verify(held).close();
  }
}
