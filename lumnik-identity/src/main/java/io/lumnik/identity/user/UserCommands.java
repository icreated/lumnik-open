package io.lumnik.identity.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

public final class UserCommands {

    private UserCommands() {}

    @Schema(name = "UserCreate", description = "Request body to create a user")
    public record Create(
        @Schema(description = "Login name, unique within the tenant", example = "john.doe")
        @NotBlank @Size(max = 255) String login,
        @Schema(description = "Display name", example = "John Doe")
        @NotBlank @Size(max = 60)  String name,
        @Schema(description = "Email address", example = "john.doe@example.com")
        @Email   @Size(max = 120)  String email,
        @Schema(description = "Optional description")
        @Size(max = 255)           String description
    ) {}

    @Schema(name = "UserUpdate", description = "Request body to update a user profile")
    public record Update(
        @Schema(description = "Display name", example = "John Doe")
        @NotBlank @Size(max = 60)  String name,
        @Schema(description = "Email address", example = "john.doe@example.com")
        @Email   @Size(max = 120)  String email,
        @Schema(description = "Optional description")
        @Size(max = 255)           String description
    ) {}
}
