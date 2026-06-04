package ai.inquery.server.web.start.controller.oauth.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Self change-password request. Used by an authenticated user to change their
 * own password (current password verification + new password).
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    /**
     * The user's current password (plain text). Verified server-side against the
     * stored bcrypt hash before the new password is accepted.
     */
    @NotBlank(message = "currentPassword can not be blank")
    private String currentPassword;

    /**
     * The new password (plain text). Hashed with bcrypt before persistence.
     */
    @NotBlank(message = "newPassword can not be blank")
    @Size(min = 6, max = 64, message = "newPassword must be between 6 and 64 characters")
    private String newPassword;
}
