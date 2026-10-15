package io.lumnik.hub.proposal;

import io.lumnik.common.security.RequiresPermission;
import io.lumnik.common.security.ScopeGrants;
import io.lumnik.hub.api.ApiErrors;
import io.lumnik.hub.tenant.RequireTenant;
import io.lumnik.hub.workflow.WorkflowDataTruth;
import io.lumnik.hub.workflow.WorkflowGraph;
import io.lumnik.hub.workflow.WorkflowManifestStore;
import io.lumnik.hub.workflow.WorkflowRegistry;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Checks a proposal dossier against a workflow a human already applied (spec 2026-10-04). Stateless:
 * the dossier in, the checked dossier out, nothing stored, applied or audited. The designated
 * workflow's scope is decided BEFORE its YAML is loaded; an absent workflow, one the caller is bound
 * out of and one that no longer parses all answer the same dossier as no designation at all — no
 * conclusion, no leak of existence. A probe that cannot run is different on purpose: the workflow is
 * held and in the caller's scope, so {@code workflow-data-truth} abstains while
 * {@code workflow-apply-door} still confirms from the stored manifest.
 *
 * <p>The body is read as bytes and decoded as UTF-8, whatever {@code Content-Type} says: a dossier is
 * UTF-8 by contract, and a client that omits the charset (a bare {@code curl --data-binary}) would
 * otherwise have its accents decoded as Latin-1 and its "…" turned into "?". Bytes that are not valid
 * UTF-8 are refused ({@code 400 malformed-header}), never repaired with U+FFFD.
 *
 * <p>{@code @Transactional}: {@link WorkflowDataTruth#check} issues SET LOCAL and needs an ambient
 * transaction (see TenantRlsScope). This door has no RAG sink call, which is why
 * {@code WorkflowApplyResource} cannot do the same.
 */
@Path("/api/proposal")
@RequiresPermission("lm_integrator")
public class ProposalCheckResource {

    private static final Logger LOG = Logger.getLogger(ProposalCheckResource.class);

    @Inject WorkflowManifestStore store;
    @Inject WorkflowDataTruth truth;
    @Inject WorkflowRegistry registry;

    private final TrustedCatalogue trusted = TrustedCatalogue.load();

    @POST
    @Path("/check")
    @Transactional
    @Consumes(MediaType.WILDCARD)
    @Produces("text/markdown;charset=UTF-8")
    public Response check(@QueryParam("workflow") String workflow, byte[] body) {
        Dossier read;
        try {
            read = DossierReader.read(DossierReader.decode(body == null ? new byte[0] : body));
        } catch (DossierReader.Refused e) {
            // The contract's reason, never a value from the dossier.
            return Response.status(400).type(MediaType.APPLICATION_JSON)
                .entity(ApiErrors.error(e.reason().code())).build();
        }
        long tenant = RequireTenant.current();
        Dossier checked = DossierChecker.check(read, trusted, designate(tenant, workflow),
            g -> truth.check(tenant, g));
        return Response.ok(DossierWriter.write(checked)).build();
    }

    /** The scope first, the YAML after; every "no" is the same empty answer. */
    private Optional<WorkflowGraph> designate(long tenant, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        Optional<String> scope = store.findScope(tenant, name);
        if (scope.isEmpty() || !ScopeGrants.allows(scope.get())) return Optional.empty();
        try {
            return registry.findByName(tenant, name);
        } catch (IllegalArgumentException e) {
            // A stored manifest the parser no longer accepts (its refusals are IllegalArgumentException):
            // abstain, same surface as an absent one. Anything else — a database failure is a
            // RuntimeException from Jdbc — is not the manifest's fault and must not read as "no
            // conclusion" with a log that blames it (#811). The log names the workflow (its name is
            // [a-z0-9-]+, enforced at apply, and was matched against a stored row above) and the failure's
            // class — never the parser's message, which quotes the YAML's own text.
            LOG.warnf("proposal check: stored workflow %s no longer parses (%s)", name, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
