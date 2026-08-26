package io.lumnik.hub.tenant;

/** A known external identity exists but is deactivated/deleted (SCIM lifecycle). Login is denied
 *  (mapped to 403); JIT must NOT reactivate or recreate. */
public class DeactivatedUserException extends RuntimeException {
    public DeactivatedUserException(String issuer, String subject) {
        super("external identity is deactivated: " + issuer + "/" + subject);
    }
}
