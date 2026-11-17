package io.lumnik.hub.connector.transport;

import io.lumnik.hub.connector.exceptions.ConfigValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #2001: a local transport reads inside one directory, {@code lumnik.csv.local-root}, and nowhere else. Unset, it reads
 * nothing. A refusal names the setting, never the path: it reaches an integrator, and the path is the server's layout.
 */
class LocalRootTest {

    @TempDir Path tmp;

    @Test
    void unsetMeansTheLocalTransportIsOff() {
        assertThatThrownBy(() -> LocalRoot.of(Optional.empty()).confine(tmp))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("lumnik.csv.local-root")
            .hasMessageContaining("LUMNIK_CSV_LOCAL_ROOT")
            .message().doesNotContain(tmp.toString());
    }

    @Test
    void aPathInsideTheRootIsAcceptedAsWritten() throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("drop"));
        Path file = drop.resolve("orders.csv");
        LocalRoot root = LocalRoot.of(Optional.of(drop.toString()));

        assertThat(root.confine(file)).isEqualTo(file);
        assertThat(root.confine(drop)).isEqualTo(drop);
        assertThat(root.confine(drop.resolve("not-yet/there.csv"))).isEqualTo(drop.resolve("not-yet/there.csv"));
    }

    @Test
    void anAbsolutePathElsewhereIsRefused() throws Exception {
        LocalRoot root = LocalRoot.of(Optional.of(Files.createDirectories(tmp.resolve("drop")).toString()));

        assertThatThrownBy(() -> root.confine(Path.of("/etc/hostname")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("lumnik.csv.local-root")
            .message().doesNotContain("/etc");
        assertThatThrownBy(() -> root.confine(tmp.resolve("drop-sibling/x.csv"))).isInstanceOf(ConfigValidationException.class);
    }

    @Test
    void dotDotLeavingTheRootIsRefused() throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("drop"));
        Files.writeString(tmp.resolve("secret.txt"), "s");
        LocalRoot root = LocalRoot.of(Optional.of(drop.toString()));

        assertThatThrownBy(() -> root.confine(drop.resolve("../secret.txt"))).isInstanceOf(ConfigValidationException.class);
        assertThatThrownBy(() -> root.confine(drop.resolve("../../../../../../etc"))).isInstanceOf(ConfigValidationException.class);
    }

    @Test
    void aSymlinkLeavingTheRootIsRefused_aDirectoryOrAFile() throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("drop"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("secret.csv"), "id\n1\n");
        Files.createSymbolicLink(drop.resolve("link-dir"), outside);
        Files.createSymbolicLink(drop.resolve("link.csv"), secret);
        LocalRoot root = LocalRoot.of(Optional.of(drop.toString()));

        assertThatThrownBy(() -> root.confine(drop.resolve("link-dir"))).isInstanceOf(ConfigValidationException.class);
        // `..` climbs from where the link points (tmp/outside → tmp), not from drop: read lexically it would stay inside
        assertThatThrownBy(() -> root.confine(drop.resolve("link-dir/../outside/secret.csv")))
            .isInstanceOf(ConfigValidationException.class);
        assertThatThrownBy(() -> root.confine(drop.resolve("nope/../../outside/secret.csv")))
            .isInstanceOf(ConfigValidationException.class);
        assertThatThrownBy(() -> root.confine(drop.resolve("link-dir/secret.csv"))).isInstanceOf(ConfigValidationException.class);
        assertThat(root.holds(drop.resolve("link.csv"))).as("a file symlinked out is not the root's").isFalse();
        assertThat(root.holds(Files.writeString(drop.resolve("own.csv"), "id\n1\n"))).isTrue();
    }

    @Test
    void aRelativePathResolvesAgainstTheWorkingDirectory_asTheRootDoes() {
        LocalRoot root = LocalRoot.of(Optional.of("."));

        assertThat(root.confine(Path.of("target/x.csv"))).isEqualTo(Path.of("target/x.csv"));
        assertThatThrownBy(() -> root.confine(Path.of("../outside.csv"))).isInstanceOf(ConfigValidationException.class);
    }

    /** A tenant reads its own folder of the root, `<root>/<tenant id>`, never another tenant's — nor the root itself. */
    @Test
    void aTenantReadsItsOwnFolderOnly() throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("drop"));
        Path seven = Files.createDirectories(drop.resolve("7"));
        Files.createDirectories(drop.resolve("8"));
        LocalRoot tenant7 = LocalRoot.of(Optional.of(drop.toString())).forTenant(7);

        assertThat(tenant7.confine(seven.resolve("orders.csv"))).isEqualTo(seven.resolve("orders.csv"));
        assertThatThrownBy(() -> tenant7.confine(drop.resolve("8/orders.csv")))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("lumnik.csv.local-root/<tenant id>");
        assertThatThrownBy(() -> tenant7.confine(drop.resolve("orders.csv"))).isInstanceOf(ConfigValidationException.class);
        assertThatThrownBy(() -> tenant7.confine(seven.resolve("../8/orders.csv"))).isInstanceOf(ConfigValidationException.class);
        assertThatThrownBy(() -> LocalRoot.of(Optional.empty()).forTenant(7).confine(seven))
            .hasMessageContaining("the local transport is off");
    }

    /** A manifest's path is relative to its tenant's folder: the same manifest, another tenant, its own files. */
    @Test
    void aPathIsResolvedInTheTenantsFolder_andAnAbsoluteOneIsRefused() throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("drop"));
        LocalRoot root = LocalRoot.of(Optional.of(drop.toString()));

        assertThat(root.forTenant(7).resolve("orders/*.csv")).isEqualTo(drop.toRealPath().resolve("7/orders/*.csv").toString());
        assertThat(root.forTenant(8).resolve("orders/*.csv")).isEqualTo(drop.toRealPath().resolve("8/orders/*.csv").toString());
        assertThatThrownBy(() -> root.forTenant(7).resolve("/etc/hostname"))
            .isInstanceOf(ConfigValidationException.class)
            .hasMessageContaining("relative to its tenant's folder")
            .message().doesNotContain("/etc");
        assertThatThrownBy(() -> root.forTenant(7).resolve("../8/orders.csv")).isInstanceOf(ConfigValidationException.class);
        assertThatThrownBy(() -> LocalRoot.of(Optional.empty()).forTenant(7).resolve("orders.csv"))
            .hasMessageContaining("the local transport is off");
    }

    @Test
    void aPathsFormIsJudgedWithoutTheDisk() {
        assertThat(LocalRoot.formRefusal("/etc/hostname")).contains(LocalRoot.FormRefusal.ABSOLUTE_PATH);
        assertThat(LocalRoot.formRefusal("orders\u0000/*.csv")).contains(LocalRoot.FormRefusal.NOT_A_PATH);
        assertThat(LocalRoot.formRefusal("orders/*.csv")).isEmpty();
    }

    /**
     * A root whose real location could not be read holds nothing — refused as such, never compared as written — and is
     * read again for each tenant, so it recovers once it can be read.
     */
    @Test
    void anUnreadableRootRefusesEveryPath_andIsReadAgainForATenant() throws Exception {
        Path drop = Files.createDirectories(tmp.resolve("drop"));
        LocalRoot unreadable = LocalRoot.unreadable(drop);

        assertThat(unreadable.holds(Files.writeString(drop.resolve("a.csv"), "id\n1\n"))).isFalse();
        assertThatThrownBy(() -> unreadable.confine(drop.resolve("a.csv")))
            .hasMessageContaining("cannot read the real location")
            .message().doesNotContain(drop.toString());
        // read again for a tenant: the folder exists and is readable now, so it recovers
        Files.createDirectories(drop.resolve("1"));
        assertThat(unreadable.forTenant(1).resolve("a.csv")).endsWith("/1/a.csv");
    }
}
