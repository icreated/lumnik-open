package io.lumnik.hub.list;

import io.lumnik.common.list.ListMirror;
import io.lumnik.common.tenant.TenantContext;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

@Startup
@ApplicationScoped
public class ListMirrorBootstrapper {

    private static final Logger LOG = Logger.getLogger(ListMirrorBootstrapper.class);
    private static final DotName ANN = DotName.createSimple(ListMirror.class.getName());

    @Inject EntityManager em;
    @Inject ListDefinitionRepository defs;
    @Inject ListValueRepository values;

    @PostConstruct
    void onStart() {
        TenantContext.runAsSystem(this::bootstrapForAllTenants);
    }

    @Transactional
    void bootstrapForAllTenants() {
        @SuppressWarnings("unchecked")
        List<Object[]> tenants = em.createNativeQuery(
            "SELECT id, code FROM identity.tenant WHERE deleted_at IS NULL"
        ).getResultList();
        if (tenants.isEmpty()) {
            LOG.debug("No tenants found — skipping list mirror bootstrap");
            return;
        }

        List<MirrorSpec> mirrors = findMirrors();
        if (mirrors.isEmpty()) {
            LOG.debug("No @ListMirror enums found — skipping bootstrap");
            return;
        }

        for (Object[] t : tenants) {
            Long tid = ((Number) t[0]).longValue();
            em.createNativeQuery("SET LOCAL app.current_tenant = " + tid).executeUpdate();
            for (MirrorSpec m : mirrors) bootstrap(tid, m);
        }
        LOG.infof("ListMirrorBootstrapper: %d list(s) × %d tenant(s) upserted",
                  mirrors.size(), tenants.size());
    }

    private void bootstrap(Long tid, MirrorSpec m) {
        ListDefinition def = defs.byCode(tid, m.code()).orElse(null);
        if (def == null) {
            def = new ListDefinition();
            def.setTenantId(tid);
            def.setCode(m.code());
            def.setName(m.name());
            def.setDescription(m.description());
            def.setSystemOwned(true);
            defs.persist(def);
        }

        Long defId = def.getId();
        List<String> enumValues = m.values();
        for (int i = 0; i < enumValues.size(); i++) {
            String enumValue = enumValues.get(i);
            if (values.byCode(defId, enumValue).isEmpty()) {
                ListValue v = new ListValue();
                v.setListId(defId);
                v.setTenantId(tid);
                v.setValue(enumValue);
                v.setName(enumValue);
                v.setSortOrder(i);
                values.persist(v);
            }
        }
    }

    private List<MirrorSpec> findMirrors() {
        List<MirrorSpec> result = new ArrayList<>();
        IndexView index = buildIndex();
        if (index == null) return result;

        for (AnnotationInstance ann : index.getAnnotations(ANN)) {
            if (ann.target() == null || ann.target().kind() != AnnotationTarget.Kind.CLASS) continue;
            ClassInfo cls = ann.target().asClass();
            if (!cls.isEnum()) {
                LOG.warnf("@ListMirror on non-enum %s — skipped", cls.name());
                continue;
            }
            try {
                Class<?> enumCls = Class.forName(cls.name().toString());
                List<String> vals = new ArrayList<>();
                for (Object c : enumCls.getEnumConstants()) vals.add(((Enum<?>) c).name());
                String code = ann.value("code").asString();
                String name = ann.value("name").asString();
                String description = ann.value("description") == null ? "" :
                                     ann.value("description").asString();
                result.add(new MirrorSpec(code, name, description, vals));
            } catch (ClassNotFoundException e) {
                LOG.warnf("Cannot load @ListMirror class %s", cls.name());
            }
        }
        return result;
    }

    private IndexView buildIndex() {
        try {
            Enumeration<URL> resources = Thread.currentThread()
                .getContextClassLoader().getResources("META-INF/jandex.idx");
            List<IndexView> indices = new ArrayList<>();
            while (resources.hasMoreElements()) {
                try (var is = resources.nextElement().openStream()) {
                    indices.add(new IndexReader(is).read());
                } catch (IOException e) {
                    LOG.warnf("Failed to read jandex index: %s", e.getMessage());
                }
            }
            return indices.isEmpty() ? null : CompositeIndex.create(indices);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to scan for @ListMirror annotations");
            return null;
        }
    }

    private record MirrorSpec(String code, String name, String description, List<String> values) {}
}
