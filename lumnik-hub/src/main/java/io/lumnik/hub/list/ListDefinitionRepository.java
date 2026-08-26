package io.lumnik.hub.list;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ListDefinitionRepository implements PanacheRepository<ListDefinition> {

    public List<ListDefinition> listAll(Long tenantId) {
        return find("tenantId = ?1 AND deletedAt IS NULL", Sort.by("name"), tenantId).list();
    }

    public Optional<ListDefinition> byCode(Long tenantId, String code) {
        return find("tenantId = ?1 AND code = ?2 AND deletedAt IS NULL", tenantId, code)
            .firstResultOptional();
    }
}
