package io.lumnik.hub.connector.transport;

import io.lumnik.common.tenant.CurrentTenantHolder;
import io.lumnik.common.text.Text;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class LocalFileSourceFactory implements FileSourceFactory {

    /** The one directory a local transport may read (#2001); unset, it reads nothing. */
    @Inject LocalRoots localRoots;

    @Override
    public String kind() { return "local"; }

    @Override
    public FileSource build(Map<String, Object> cfg) {
        String path = (String) cfg.get("path");
        if (Text.blank(path))
            throw new IllegalArgumentException("local transport requires `path`");
        // A local source is built for one tenant, the one in scope: it reads that tenant's folder of the root only.
        Long tenantId = CurrentTenantHolder.get();
        if (tenantId == null) throw new IllegalStateException("a local transport is built inside a tenant scope");
        return new LocalFileSource(localRoots.forTenant(tenantId), localRoots.resolve(tenantId, path));
    }
}
