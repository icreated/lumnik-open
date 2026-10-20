package io.lumnik.hub.api.connector;

import io.lumnik.extraction.spi.ChunkRetagger;
import io.lumnik.hub.connector.domain.Connector;
import io.lumnik.hub.connector.repository.ConnectorRepository;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Unit test of the retag seam: the chunk store is an Instance<> — absent means zero chunks
 *  exist, so zero updated is the honest answer, never a schema error. */
class ConnectorRetagResourceTest {

    @SuppressWarnings("unchecked")
    private static ConnectorRetagResource resource(ConnectorRepository repo, ChunkRetagger retagger) {
        ConnectorRetagResource r = new ConnectorRetagResource();
        r.repo = repo;
        Instance<ChunkRetagger> inst = mock(Instance.class);
        when(inst.isUnsatisfied()).thenReturn(retagger == null);
        if (retagger != null) when(inst.get()).thenReturn(retagger);
        r.retaggers = inst;
        return r;
    }

    @Test
    void without_a_chunk_store_retag_answers_zero_not_an_error() {
        UUID id = UUID.randomUUID();
        Connector c = new Connector();
        c.setTags(List.of("achat", "stock"));
        ConnectorRepository repo = mock(ConnectorRepository.class);
        when(repo.findByIdForTenantForUpdate(eq(id), any())).thenReturn(c);

        Response resp = resource(repo, null).retag(id);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(((Map<?, ?>) resp.getEntity()).get("chunksUpdated")).isEqualTo(0);
    }

    @Test
    void with_a_chunk_store_retag_delegates_connector_tags() {
        UUID id = UUID.randomUUID();
        Connector c = new Connector();
        c.setTags(List.of("achat"));
        ConnectorRepository repo = mock(ConnectorRepository.class);
        when(repo.findByIdForTenantForUpdate(eq(id), any())).thenReturn(c);
        ChunkRetagger retagger = mock(ChunkRetagger.class);
        when(retagger.retagByConnector(id, List.of("achat"))).thenReturn(7);

        Response resp = resource(repo, retagger).retag(id);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(((Map<?, ?>) resp.getEntity()).get("chunksUpdated")).isEqualTo(7);
        verify(retagger).retagByConnector(id, List.of("achat"));
    }

    @Test
    void unknown_connector_is_404_before_the_store_is_consulted() {
        ConnectorRepository repo = mock(ConnectorRepository.class);
        ChunkRetagger retagger = mock(ChunkRetagger.class);

        Response resp = resource(repo, retagger).retag(UUID.randomUUID());

        assertThat(resp.getStatus()).isEqualTo(404);
        verifyNoInteractions(retagger);
    }
}
