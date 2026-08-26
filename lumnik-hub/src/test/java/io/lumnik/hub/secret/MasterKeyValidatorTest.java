package io.lumnik.hub.secret;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MasterKeyValidatorTest {

    @Test
    void rejects_missing_or_default_placeholder() {
        var validator = new MasterKeyValidator();
        validator.masterKey = "CHANGE_ME_AT_DEPLOY_TIME_OR_STARTUP_WILL_FAIL";
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void rejects_empty_or_blank() {
        var validator = new MasterKeyValidator();
        validator.masterKey = "";
        assertThrows(IllegalStateException.class, validator::validate);
        validator.masterKey = "   ";
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void rejects_too_short() {
        var validator = new MasterKeyValidator();
        validator.masterKey = "short";
        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void accepts_valid_key() {
        var validator = new MasterKeyValidator();
        validator.masterKey = "this-is-a-valid-master-key-of-sufficient-length";
        assertDoesNotThrow(validator::validate);
    }
}
