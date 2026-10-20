package io.lumnik.identity;

import io.lumnik.common.tenant.CurrentTenantHolder;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** #1292: the tenant a door's URL names is the caller's, or the door is never reached. */
class TenantInPathFilterTest {

    @AfterEach
    void clearTenant() {
        CurrentTenantHolder.clear();
    }

    @Test
    void a_foreign_tenant_is_refused_as_require_refuses_it() {
        CurrentTenantHolder.set(1L);
        assertThatThrownBy(() -> new TenantInPathFilter().filter(request("2")))
            .isInstanceOf(ForbiddenException.class).hasMessage("tenant not granted");
    }

    @Test
    void the_callers_own_tenant_passes() {
        CurrentTenantHolder.set(1L);
        assertThatCode(() -> new TenantInPathFilter().filter(request("1"))).doesNotThrowAnyException();
    }

    @Test
    void no_caller_tenant_passes_as_it_does_in_require() {
        assertThatCode(() -> new TenantInPathFilter().filter(request("2"))).doesNotThrowAnyException();
    }

    @Test
    void a_segment_that_is_not_a_number_is_left_to_the_door() {
        CurrentTenantHolder.set(1L);
        assertThatCode(() -> new TenantInPathFilter().filter(request("not-a-number"))).doesNotThrowAnyException();
    }

    /** The marker on a door whose path names no {tenantId} checks nothing: a resource defect, not a pass. */
    @Test
    void a_marked_door_without_a_tenant_in_its_path_is_a_defect_not_a_pass() {
        CurrentTenantHolder.set(1L);
        assertThatThrownBy(() -> new TenantInPathFilter().filter(request(null)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("{tenantId}");
    }

    private static ContainerRequestContext request(String tenantId) {
        var params = new MultivaluedHashMap<String, String>();
        if (tenantId != null) params.add("tenantId", tenantId);
        var uri = mock(UriInfo.class);
        when(uri.getPathParameters()).thenReturn(params);
        var ctx = mock(ContainerRequestContext.class);
        when(ctx.getUriInfo()).thenReturn(uri);
        return ctx;
    }
}
