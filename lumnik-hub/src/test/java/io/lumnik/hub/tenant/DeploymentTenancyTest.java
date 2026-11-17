package io.lumnik.hub.tenant;

import io.lumnik.hub.tenant.DeploymentTenancy.Mode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #1991: lumnik.tenancy reads exactly; DeploymentDefaultsGuardTest holds that a deployment refuses single. */
class DeploymentTenancyTest {

  @Test
  void the_three_values_read() {
    assertThat(DeploymentTenancy.parse("auto")).isEqualTo(Mode.AUTO);
    assertThat(DeploymentTenancy.parse("multi")).isEqualTo(Mode.MULTI);
    assertThat(DeploymentTenancy.parse("single")).isEqualTo(Mode.SINGLE);
  }

  @Test
  void a_fresh_install_is_single_and_its_first_customer_tenant_turns_it_multi() {
    assertThat(DeploymentTenancy.multiTenant(List.of(1L, 11L))).as("default + hubadmin").isFalse();
    assertThat(DeploymentTenancy.multiTenant(List.of(1L))).isFalse();
    assertThat(DeploymentTenancy.multiTenant(List.of(1L, 11L, 1000L))).isTrue();
    assertThat(DeploymentTenancy.multiTenant(List.of(1L, 1000L))).as("without hubadmin").isTrue();
  }

  @Test
  void anything_else_stops_the_hub() {
    for (String v : new String[] {"Multi", "", "true", "multi "}) {
      assertThatThrownBy(() -> DeploymentTenancy.parse(v)).hasMessageContaining("lumnik.tenancy");
    }
  }
}
