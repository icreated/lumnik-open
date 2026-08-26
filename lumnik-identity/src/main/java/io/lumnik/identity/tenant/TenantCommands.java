package io.lumnik.identity.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public final class TenantCommands {

    private TenantCommands() {}

    @Schema(name = "TenantCreate", description = "Request body to create a tenant")
    public record Create(
        @Schema(description = "Unique business code", example = "ACME")
        @NotBlank @Size(max = 40) String code,
        @Schema(description = "Display name", example = "Acme Corp")
        @NotBlank @Size(max = 60) String name,
        @Schema(description = "Optional description")
        @Size(max = 255) String description
    ) {}

    @Schema(name = "TenantUpdate", description = "Request body to update a tenant")
    public record Update(
        @Schema(description = "Display name", example = "Acme Corp")
        @NotBlank @Size(max = 60) String name,
        @Schema(description = "Optional description")
        @Size(max = 255) String description
    ) {}
}
