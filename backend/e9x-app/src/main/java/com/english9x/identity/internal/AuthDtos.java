package com.english9x.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request / response bodies of /api/auth. */
final class AuthDtos {

    private AuthDtos() {}

    @Schema(name = "RegisterRequest")
    record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, message = "must be at least 8 characters") @Utf8MaxBytes(PasswordHasher.MAX_BYTES) String password,
            @NotBlank @Size(max = 100) String displayName,
            @NotNull @AssertTrue(message = "consent to personal data processing is required") Boolean consent,
            @Schema(allowableValues = {"vi", "en"}, nullable = true) @Size(max = 5) String locale) {}

    @Schema(name = "RegisterResponse")
    record RegisterResponse(@Schema(example = "pending_verification", requiredMode = Schema.RequiredMode.REQUIRED) String status) {}

    @Schema(name = "TokenRequest")
    record TokenRequest(@NotBlank @Size(max = 200) String token) {}

    @Schema(name = "EmailRequest")
    record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}

    @Schema(name = "LoginRequest")
    record LoginRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {}

    @Schema(name = "RefreshRequest")
    record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    @Schema(name = "ResetConfirmRequest")
    record ResetConfirmRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(min = 8, message = "must be at least 8 characters") @Utf8MaxBytes(PasswordHasher.MAX_BYTES) String newPassword) {}

    @Schema(name = "OAuthCodeRequest")
    record OAuthCodeRequest(@NotBlank @Size(max = 200) String code) {}

    @Schema(name = "TokenPair")
    record TokenPair(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String accessToken,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String refreshToken,
            @Schema(description = "access token lifetime in seconds", requiredMode = Schema.RequiredMode.REQUIRED) long expiresIn,
            @Schema(example = "Bearer", requiredMode = Schema.RequiredMode.REQUIRED) String tokenType) {}

    @Schema(name = "AuthConfig")
    record AuthConfig(@Schema(description = "false when Google sign-in is not configured: hide the button", requiredMode = Schema.RequiredMode.REQUIRED) boolean googleEnabled) {}

    @Schema(name = "Me")
    record Me(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String email,
            @Schema(nullable = true) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean emailVerified,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String locale) {}
}
