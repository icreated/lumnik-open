package io.lumnik.hub.list;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ListValueRepository implements PanacheRepository<ListValue> {

    public List<ListValue> listByList(Long listId) {
        return find("listId = ?1", Sort.by("sortOrder").and("value"), listId).list();
    }

    public Optional<ListValue> byCode(Long listId, String value) {
        return find("listId = ?1 AND value = ?2", listId, value).firstResultOptional();
    }
}
