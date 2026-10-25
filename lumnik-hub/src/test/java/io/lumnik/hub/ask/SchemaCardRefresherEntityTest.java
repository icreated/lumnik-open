package io.lumnik.hub.ask;

import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An entity apply waits for the caller's cards only. Another tenant's card can need its own source to
 * be read again, and that source being slow or down must not hold the caller's `lm entity apply`:
 * those cards are rebuilt after the request, by an async observer.
 */
class SchemaCardRefresherEntityTest {

    @SuppressWarnings("unchecked")
    private final Event<SchemaCardRefresher.EntityCardsStale> later = mock(Event.class);
    private final DiscoveryService discovery = mock(DiscoveryService.class);
    private final SchemaCardStore store = mock(SchemaCardStore.class);
    private final SchemaCardRefresher refresher = new SchemaCardRefresher();

    SchemaCardRefresherEntityTest() {
        refresher.discovery = discovery;
        refresher.store = store;
        refresher.entityCardsStale = later;
    }

    @Test
    void the_callers_cards_are_rebuilt_now_and_the_other_tenants_cards_after_the_request() {
        when(store.cardsListingOrIn("connector.v_customer", List.of("ventes"))).thenReturn(List.of(
            new SchemaCardStore.Held(7L, "clients"),     // the caller's card of the scope it left
            new SchemaCardStore.Held(8L, "clients")));   // another tenant's

        refresher.afterEntityChanged("Customer", 7L, List.of("ventes"));

        verify(discovery).rediscover(7L, "ventes");
        verify(discovery).rediscover(7L, "clients");
        verify(discovery, never()).rediscover(eq(8L), anyString());
        ArgumentCaptor<SchemaCardRefresher.EntityCardsStale> sent =
            ArgumentCaptor.forClass(SchemaCardRefresher.EntityCardsStale.class);
        verify(later).fireAsync(sent.capture());
        assertThat(sent.getValue().cards()).containsExactly(new SchemaCardStore.Held(8L, "clients"));
    }

    @Test
    void the_async_observer_rebuilds_the_cards_it_was_handed() {
        refresher.onEntityCardsStale(new SchemaCardRefresher.EntityCardsStale("Customer",
            Set.of(new SchemaCardStore.Held(8L, "clients"))));

        verify(discovery).rediscover(8L, "clients");
    }

    @Test
    void nothing_is_sent_when_no_other_tenant_holds_a_card() {
        when(store.cardsListingOrIn("connector.v_customer", List.of("clients")))
            .thenReturn(List.of(new SchemaCardStore.Held(7L, "clients")));

        refresher.afterEntityChanged("Customer", 7L, List.of("clients"));

        verify(discovery).rediscover(7L, "clients");
        verify(later, never()).fireAsync(any());
        verify(discovery, never()).rediscover(eq(8L), anyString());
        verify(discovery, never()).rediscover(anyLong(), eq("ventes"));
    }
}
