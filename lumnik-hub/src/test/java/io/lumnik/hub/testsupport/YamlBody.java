package io.lumnik.hub.testsupport;

import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;

/**
 * How a test posts a manifest: rest-assured sending {@code application/yaml} as characters.
 *
 * <p>Without it rest-assured has no serialiser for that content type and tries to invent one for
 * the String body; with it the bytes go out exactly as the test wrote them. Every apply door in
 * this module takes YAML, so twenty-six test classes had declared the same three lines and three
 * imports under the same name, {@code YAML_CFG} — plumbing nobody's test is about, copied once per
 * door (#519).
 *
 * <p>Here rather than in {@code lumnik-testkit}: that module is main-scope and every test module
 * consumes it, so holding a rest-assured helper would put a test HTTP client on the compile path
 * of all of them — the constraint {@code TestIdentity}'s javadoc states for the same reason.
 *
 * <p>The MANIFESTS were counted too and deliberately left alone. The near-copies are two, not the
 * three the ticket counted — the third posts an sftp transport with a schedule, which is its
 * subject — and what those two differ in, the name and the scopes list, is precisely what each one
 * asserts. A template would hide the variable and share the constant.
 *
 * <p>{@code ConnectorValidatorResourceTest} keeps its own inline configs: it sends
 * {@code application/x-yaml} and {@code application/yaml} from neighbouring tests to prove the
 * validator accepts both spellings, so there the content type is the subject, not the plumbing.
 */
public final class YamlBody {

    /** Send an {@code application/yaml} body verbatim. */
    public static final RestAssuredConfig CONFIG = RestAssuredConfig.config()
        .encoderConfig(EncoderConfig.encoderConfig()
            .encodeContentTypeAs("application/yaml", ContentType.TEXT));

    private YamlBody() {}
}
