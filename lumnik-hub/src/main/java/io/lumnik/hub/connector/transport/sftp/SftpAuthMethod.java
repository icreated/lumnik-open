package io.lumnik.hub.connector.transport.sftp;

public sealed interface SftpAuthMethod permits SftpAuthMethod.Password, SftpAuthMethod.Key {
    // toString() masks the secret so a resolved auth method can't leak through a log or exception.
    record Password(String value) implements SftpAuthMethod {
        @Override public String toString() { return "Password[value=***]"; }
    }
    record Key(String pemContent) implements SftpAuthMethod {
        @Override public String toString() { return "Key[pemContent=***]"; }
    }
}
