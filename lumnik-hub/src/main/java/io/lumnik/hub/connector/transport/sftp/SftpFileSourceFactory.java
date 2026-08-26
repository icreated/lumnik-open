package io.lumnik.hub.connector.transport.sftp;

import io.lumnik.hub.connector.transport.FileSource;
import io.lumnik.hub.connector.transport.FileSourceFactory;
import io.lumnik.hub.secret.SecretResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@ApplicationScoped
public class SftpFileSourceFactory implements FileSourceFactory {

    private final Function<String, String> envLookup;
    private final SecretResolver secrets;

    @Inject
    public SftpFileSourceFactory(SecretResolver secrets) {
        this(name -> {
            String v = System.getProperty(name);
            return v != null ? v : System.getenv(name);
        }, secrets);
    }

    /** Test ctor with stub env lookup and no-op secret resolver. */
    public SftpFileSourceFactory(Function<String, String> envLookup) {
        this(envLookup, name -> Optional.empty());
    }

    public SftpFileSourceFactory(Function<String, String> envLookup, SecretResolver secrets) {
        this.envLookup = envLookup;
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
            String pem = secrets.resolve(keyEnv).orElse(envLookup.apply(keyEnv));
            auth = new SftpAuthMethod.Key(pem);
        } else if (pwdEnv != null) {
            String pwd = secrets.resolve(pwdEnv).orElse(envLookup.apply(pwdEnv));
            auth = new SftpAuthMethod.Password(pwd);
        } else {
            auth = new SftpAuthMethod.Password(null);
        }

        return new SftpFileSource(host, port, user, auth, pathGlob, strictHostKey, hostKeyFingerprint);
    }
}
