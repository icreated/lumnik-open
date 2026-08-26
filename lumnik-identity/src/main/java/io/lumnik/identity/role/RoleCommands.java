package io.lumnik.identity.role;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public final class RoleCommands {

    private RoleCommands() {}

    @Schema(name = "RoleCreate", description = "Request body to create a role")
    public record Create(
        @Schema(description = "Role name, unique within the tenant", example = "Sales Manager")
        @NotBlank @Size(max = 60) String name,
        @Schema(description = "Optional description")
        @Size(max = 255) String description,
        @Schema(description = "User level: S=System, C=Client, O=Org, CO=Client+Org. Defaults to CO.", example = "CO")
        @Pattern(regexp = "S|C|O|CO", message = "userLevel must be S, C, O or CO") String userLevel
    ) {}

    @Schema(name = "RoleUpdate", description = "Request body to update a role")
    public record Update(
        @Schema(description = "Role name, unique within the tenant", example = "Sales Manager")
        @NotBlank @Size(max = 60) String name,
        @Schema(description = "Optional description")
        @Size(max = 255) String description
    ) {}
}
