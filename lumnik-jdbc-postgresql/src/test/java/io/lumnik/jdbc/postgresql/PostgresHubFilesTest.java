package io.lumnik.jdbc.postgresql;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.Driver;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #2027: the hub's own {@code ~/.postgresql} — a client certificate and key a tenant's server could ask for. A fake
 * home holds a real pair, a fake server asks for a client certificate, and the test reads what was presented. Without
 * {@link PostgresDialect#isolationProperties()} pgjdbc presents the hub's pair (the control); with them, nothing.
 */
class PostgresHubFilesTest {

  @TempDir static Path dir;
  private static String homeBefore;
  private static SSLContext serverTls;

  @BeforeAll
  static void a_home_with_a_client_pair_and_a_server_that_asks_for_it() throws Exception {
    Path home = dir.resolve("home");
    Path pg = Files.createDirectories(home.resolve(".postgresql"));
    KeyStore client = keyPair("client");
    KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) client.getEntry("k", new KeyStore.PasswordProtection(PW));
    Files.write(pg.resolve("postgresql.pk8"), entry.getPrivateKey().getEncoded());
    // As a real key is kept: pgjdbc ignores one others may read.
    Files.setPosixFilePermissions(pg.resolve("postgresql.pk8"), PosixFilePermissions.fromString("rw-------"));
    Files.write(pg.resolve("postgresql.crt"), entry.getCertificate().getEncoded());
    Files.write(pg.resolve("root.crt"), entry.getCertificate().getEncoded());

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyPair("server"), PW);
    serverTls = SSLContext.getInstance("TLS");
    serverTls.init(kmf.getKeyManagers(), new TrustManager[] {ANY_CLIENT}, null);

    homeBefore = System.getProperty("user.home");
    System.setProperty("user.home", home.toString());
  }

  @AfterAll
  static void restore_home() {
    System.setProperty("user.home", homeBefore);
  }

  @Test
  void without_isolation_the_driver_presents_the_hubs_own_certificate() throws Exception {
    assertTrue(presentedClientCertificate(new Properties()), "the control: pgjdbc reads ~/.postgresql by default");
  }

  @Test
  void with_isolation_the_driver_presents_nothing_of_the_hubs() throws Exception {
    Properties p = new Properties();
    p.putAll(new PostgresDialect().isolationProperties());
    assertFalse(presentedClientCertificate(p));
  }

  /** One connection under {@code sslmode=require} to a server that wants a client certificate; whether one came. */
  private static boolean presentedClientCertificate(Properties props) throws Exception {
    try (ServerSocket listener = new ServerSocket(0)) {
      CompletableFuture<Boolean> presented = CompletableFuture.supplyAsync(() -> {
        try (Socket plain = listener.accept()) {
          new DataInputStream(plain.getInputStream()).readFully(new byte[8]);   // SSLRequest
          plain.getOutputStream().write('S');
          plain.getOutputStream().flush();
          SSLSocket tls = (SSLSocket) serverTls.getSocketFactory().createSocket(plain, null, plain.getPort(), false);
          tls.setUseClientMode(false);
          tls.setWantClientAuth(true);
          tls.startHandshake();
          try {
            return tls.getSession().getPeerCertificates().length > 0;
          } catch (SSLPeerUnverifiedException none) {
            return false;
          }
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      });
      props.setProperty("user", "erp");
      props.setProperty("password", "");
      props.setProperty("connectTimeout", "5");
      props.setProperty("loginTimeout", "5");
      String url = "jdbc:postgresql://127.0.0.1:" + listener.getLocalPort() + "/erp?sslmode=require";
      try {
        new Driver().connect(url, props).close();
      } catch (SQLException expected) {   // the fake server hangs up after the handshake
      }
      return presented.get(10, TimeUnit.SECONDS);
    }
  }

  private static final char[] PW = "changeit".toCharArray();

  /** A fresh self-signed pair, by the JDK's own keytool: nothing kept in the repository. */
  private static KeyStore keyPair(String name) throws Exception {
    Path p12 = dir.resolve(name + ".p12");
    Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
        "-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + name,
        "-validity", "1", "-storetype", "PKCS12", "-keystore", p12.toString(),
        "-storepass", new String(PW), "-keypass", new String(PW))
        .redirectErrorStream(true).start();
    assertTrue(keytool.waitFor(60, TimeUnit.SECONDS) && keytool.exitValue() == 0, new String(keytool.getInputStream().readAllBytes()));
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (FileInputStream in = new FileInputStream(p12.toFile())) {
      ks.load(in, PW);
    }
    return ks;
  }

  private static final X509TrustManager ANY_CLIENT = new X509TrustManager() {
    public void checkClientTrusted(X509Certificate[] chain, String authType) { }
    public void checkServerTrusted(X509Certificate[] chain, String authType) { }
    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
  };
}
