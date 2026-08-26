package io.lumnik.identity.org;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public final class OrgCommands {

    private OrgCommands() {}

    @Schema(name = "OrgCreate", description = "Request body to create an organization")
    public record Create(
        @Schema(description = "Org code, unique within the tenant", example = "HQ")
        @NotBlank @Size(max = 40) String code,
        @Schema(description = "Display name", example = "Headquarters")
        @NotBlank @Size(max = 60) String name,
        @Schema(description = "Optional description")
        @Size(max = 255) String description,
        @Schema(description = "Parent org ID for hierarchy. Null for root orgs.")
        Long parentOrgId,
        @Schema(description = "True if this is a summary (non-posting) org")
        boolean summary,
        @Schema(description = "Street address line 1") @Size(max = 255) String addressLine1,
        @Schema(description = "Street address line 2") @Size(max = 255) String addressLine2,
        @Schema(description = "Postal code") @Size(max = 20) String postalCode,
        @Schema(description = "City") @Size(max = 120) String city,
        @Schema(description = "ISO 3166-1 alpha-2 country code") @Size(max = 2) String country
    ) {}

    @Schema(name = "OrgUpdate", description = "Request body to update an organization")
    public record Update(
        @Schema(description = "Display name", example = "Headquarters")
        @NotBlank @Size(max = 60) String name,
        @Schema(description = "Optional description")
        @Size(max = 255) String description,
        @Schema(description = "Parent org ID for hierarchy. Null for root orgs.")
        Long parentOrgId,
        @Schema(description = "True if this is a summary (non-posting) org")
        boolean summary,
        @Schema(description = "Street address line 1") @Size(max = 255) String addressLine1,
        @Schema(description = "Street address line 2") @Size(max = 255) String addressLine2,
        @Schema(description = "Postal code") @Size(max = 20) String postalCode,
        @Schema(description = "City") @Size(max = 120) String city,
        @Schema(description = "ISO 3166-1 alpha-2 country code") @Size(max = 2) String country
    ) {}
}
