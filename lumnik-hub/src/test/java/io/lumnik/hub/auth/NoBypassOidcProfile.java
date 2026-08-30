package io.lumnik.hub.auth;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * Shared real-JWT harness for gate ITs that need {@code @RequiresPermission} enforced with
 * {@code lumnik.security.dev-bypass} off. Extracted from {@code TenantAdminAuthIT} (Task 1 of the
 * 2026-08-11 security audit) once a second IT ({@code IdentityAdminAuthIT}) needed the identical
 * machinery: {@code @TestSecurity}/{@code @JwtSecurity} cannot drive this gate on this OIDC-only
 * app — there is no {@code quarkus-smallrye-jwt} extension, so those annotations never populate
 * the injected {@code JsonWebToken}, and {@code PermissionInterceptor} reads roles exclusively
 * from {@code TenantContextFilter.currentRoles()}, which {@code TenantContextFilter.fromJwt()}
 * populates from that same {@code JsonWebToken}'s {@code groups} claim. So this mints and signs a
 * REAL bearer JWT with a fixed, test-only RSA keypair trusted via {@code quarkus.oidc.public-key}
 * ("OIDC server connection is not created when this property is set") — mirroring the OTK
 * live-auth lane's {@code ControlledKey} (`lm/test/otk/auth/controlledkey.go`), the existing
 * precedent in this codebase for real, keyless JWT validation.
 *
 * <p>The keypair is a fixed, embedded constant rather than generated with {@code
 * KeyPairGenerator} at class-init: {@link QuarkusTestProfile#getConfigOverrides()} runs before
 * Quarkus swaps in the application classloader, so a nested profile class's static field gets
 * re-initialized once per classloader — a randomly generated key would then differ between the
 * value baked into {@code quarkus.oidc.public-key} and the value used to sign the token, and
 * every token would fail signature verification (observed: {@code Invalid JWS Signature} at
 * DEBUG on {@code io.quarkus.oidc}). A constant PEM string parses identically in either
 * classloader, so it sidesteps the duplication entirely.
 */
public final class NoBypassOidcProfile implements QuarkusTestProfile {

    static final String ISSUER = "https://test-issuer.lumnik.local";
    static final String AUDIENCE = "lumnik-backend";

    // Test-only 2048-bit RSA keypair, generated once with `openssl genrsa` / `openssl rsa
    // -pubout`. Not used anywhere outside these tests — trusted only by the OIDC config this
    // profile overrides for the lifetime of the test JVM.
    private static final String TEST_PRIVATE_KEY_PKCS8 = """
        MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCz6BDhtOitMtkB
        ZbdMSs4DY6ih86VGq10FO2ONYzWd7SeVFQFJYwV7aiTCgOiJXdqlEdJBTvox4ZWs
        6uhH7dV/YPmkyjL9t8SnFuThLjF3vweHLBbYEjetMv82V7BIK35Ny1qlRo8g0r3t
        5/jnFlcSMLXDlcpppvVcFdx8rQTHue974+RnWBJeuPiaS6sZjnrs8144fODzESZp
        m8ubBFl2F0ztBB+lFwOKyWDEVrh7Wk5M6PnBLC0T0Q/anAgvVKPJ1F8mUVbWSDaz
        c1IAxxAZgZGjI4R84zSji+OZWOvOdy6TJjY9AkiEXcbVdqGoaZCSDY3ciyXua9qq
        vAjtJ2IXAgMBAAECggEANroBaKd/oDA3kxU+DRc0VBOc2KclP7ohMdY/EmN7i+74
        e4FIQuq8YC6JwCE2m5LRUCV7sCI2ObZFsbRDtJ3EtFrrlhVsLYIZcDqmpVlH8wVJ
        ZPUkfdYM2cMJn55NIacZZNDXYi90mqeA3z4RmTpSglYO9NA8IqeHO9vqloKLUbXa
        4NEjzTMtZoLFQosMBaymUtqOqNQoolTOLzLlViRdKF0nNEL1POu4JNENiU9GU83Q
        Lwk2XysXqxsCf1qYICwc1UiMXHZ3ow/QIPzuSCdMv4HLuJUExYlFWXUCLodWVENn
        OsZVsSrDHmLXr2MwQY5MR+Kq/oHfGEOwaVPYl4jMAQKBgQDrooPmxxGp0NRaUvBP
        TDJRfco2sVR7QswQw03nKMSNtXtQpL4YzejX3UnqdIstEa3/7vQqjB4shg9hGocR
        2uXWpvjdLwmaAoR+qcu4UqfHA8tiH6plYJO9t/0nZAUbxN2I8y8PjICiv5PviX6l
        n2I/PdPkbwIniokc5hyCQBIyhwKBgQDDdIvmOamCHK8MVfC+njLqkhPEdDgy23Ll
        HDkVOqawDsGW+T9UgUluccwRTIx5SwG5qAF6c3615KF04jmkn/ya/JLBLreoxoUD
        bilA23Tl94cydlB5lPBnRTWfZhqiEk9USX/GO8z+0dx1rQKsQcDfobI6dztckKPC
        iCn60aLn8QKBgQC9/ZlwuE3rqG+wLHOK3Jstzcj/9Fdz01YKpg7oNZzVzeCSE2Mb
        SNtXQhvZKQMW4cx0Mc05iSTDLYy09Ni3ctIjExpR7R1GyoTeRDX4dtzEwLB6e6G+
        f/Jf5DwASMs9qqwdQkdNuDH4B5RWbXxqOmrTJxz0cPVBwJfxbzDJbLO/fQKBgQCg
        nkXZC8mggrHXEUilXJu66BAWgOst3+irHmZKa8hPJdEwn+LLLvOJLzrjfGRzyI/C
        Ya3VUtPt5dRImR+FiXOsaaKv3hRPPdRQ3VZJVg7abG9NqKy5PQvR0a4fL2jzhthq
        C3xVCw+K2CqzCVoI9H/rRpWQNisyBiqM0hitZVjaAQKBgHrA32avWHBLRcUOqF7k
        aCYWNpmH22YOELZidB3QfyEZjbGoAZi49zCBtUH0OLoe6epWDokxvoN6H9W4CRdd
        VMr95Q8k18Jftf4yI0fpCjwFDUluzDaS8RAx64GWsMdZ+V1NBWTRgmcEpp7zGlbm
        2wbI5vbZU3anyPaQ6cpmnpbF
        """;

    private static final String TEST_PUBLIC_KEY_X509 = """
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAs+gQ4bTorTLZAWW3TErO
        A2OoofOlRqtdBTtjjWM1ne0nlRUBSWMFe2okwoDoiV3apRHSQU76MeGVrOroR+3V
        f2D5pMoy/bfEpxbk4S4xd78HhywW2BI3rTL/NlewSCt+TctapUaPINK97ef45xZX
        EjC1w5XKaab1XBXcfK0Ex7nve+PkZ1gSXrj4mkurGY567PNeOHzg8xEmaZvLmwRZ
        dhdM7QQfpRcDislgxFa4e1pOTOj5wSwtE9EP2pwIL1SjydRfJlFW1kg2s3NSAMcQ
        GYGRoyOEfOM0o4vjmVjrzncukyY2PQJIhF3G1XahqGmQkg2N3Isl7mvaqrwI7Sdi
        FwIDAQAB
        """;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "lumnik.security.dev-bypass", "false",
            "quarkus.oidc.tenant-enabled", "true",
            "quarkus.oidc.application-type", "service",
            "quarkus.oidc.public-key", TEST_PUBLIC_KEY_X509.replace("\n", ""),
            "quarkus.oidc.token.issuer", ISSUER,
            "quarkus.oidc.token.audience", AUDIENCE,
            "quarkus.keycloak.devservices.enabled", "false");
    }

    private static PrivateKey privateKey() {
        try {
            byte[] der = Base64.getMimeDecoder().decode(TEST_PRIVATE_KEY_PKCS8);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A real, signed bearer JWT for tenant 1 carrying a single {@code groups} role. */
    static String token(String role) {
        return token(Set.of(role), null);
    }

    /**
     * A signed bearer JWT for a tenant OTHER than 1 — the column the auth matrix could not write.
     * Every other mint here hardcodes {@code tenant_id: 1}, so "the caller's tenant differs from
     * the one named in the URL" was a case the harness had no way to express, and therefore a case
     * no test could pin.
     */
    static String tokenForTenant(long tenantId, String role) {
        return token(Set.of(role), null, tenantId);
    }

    /** A real, signed bearer JWT for tenant 1 carrying the given {@code groups} roles and,
     *  when non-null, an {@code acting_as_tenant} claim (impersonation). */
    static String token(Set<String> roles, Long actingAsTenant) {
        return token(roles, actingAsTenant, 1L);
    }

    private static String token(Set<String> roles, Long actingAsTenant, long tenantId) {
        JwtClaimsBuilder claims = Jwt.claims()
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .subject("tester")
            .claim("tenant_id", tenantId)
            .claim("user_id", 1)
            .groups(roles)
            .expiresIn(Duration.ofMinutes(5));
        if (actingAsTenant != null) claims = claims.claim("acting_as_tenant", actingAsTenant);
        return claims.sign(privateKey());
    }
}
