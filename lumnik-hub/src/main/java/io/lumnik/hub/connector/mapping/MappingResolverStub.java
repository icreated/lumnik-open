package io.lumnik.hub.connector.mapping;

import io.lumnik.hub.connector.api.helpers.MappingResolver;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class MappingResolverStub implements MappingResolver {

    @Override
    public Mapping resolve(UUID mappingId) {
        return (source, ctx) -> source;
    }
}
