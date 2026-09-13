package io.lumnik.hub.connector.transport.sftp;

import io.lumnik.hub.connector.secret.SecretRefs;
import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;

@ApplicationScoped
public class SftpFileSourceFactory implements FileSourceFactory {

    private final SecretRefs secrets;

    @Inject
    public SftpFileSourceFactory(SecretRefs secrets) {
        this.secrets = secrets;
    }

    @Override
    public String kind() {
        return "sftp";
    }

    @Override
    public FileSource build(Map<String, Object> cfg) {
        String host     = (String) cfg.get("host");
        int port        = ((Number) cfg.getOrDefault("port", 22)).intValue();
        String user     = (String) cfg.get("user");
        String pathGlob = (String) cfg.get("path");
        boolean strictHostKey = (boolean) cfg.getOrDefault("strict_host_key", true);
        String hostKeyFingerprint = (String) cfg.get("host_key_fingerprint");

        SftpAuthMethod auth;
        String keyEnv = (String) cfg.get("key_env");
        String pwdEnv = (String) cfg.get("password_env");
        if (keyEnv != null) {
            String pem = secrets.lookup(keyEnv);
            auth = new SftpAuthMethod.Key(pem);
        } else if (pwdEnv != null) {
            String pwd = secrets.lookup(pwdEnv);
            auth = new SftpAuthMethod.Password(pwd);
        } else {
            auth = new SftpAuthMethod.Password(null);
        }

        return new SftpFileSource(host, port, user, auth, pathGlob, strictHostKey, hostKeyFingerprint);
    }
}
