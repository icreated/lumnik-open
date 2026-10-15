package io.lumnik.hub.entity.resolution;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes an ordered list of strings unambiguously, for both the rule's policy hash and its
 * fingerprint HMAC (design doc §6). Plain {@code "||"}-joined concatenation is not unambiguous:
 * {@code ("ab","c")} and {@code ("a","bc")} join identically. Each field is instead prefixed with
 * its own UTF-8 byte length, so no decoding is needed to prove two distinct field lists can never
 * produce the same bytes.
 *
 * <p>{@code null} is encoded as a distinct length ({@code -1}, no bytes) from an empty string
 * ({@code 0}, no bytes) — a missing field and a present-but-empty one are different facts and must
 * not fingerprint alike.
 */
public final class LengthPrefixedEncoding {

  private LengthPrefixedEncoding() {}

  public static byte[] encode(List<String> fields) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      for (String field : fields) {
        if (field == null) {
          out.writeInt(-1);
          continue;
        }
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e); // ByteArrayOutputStream never actually throws
    }
    return buffer.toByteArray();
  }
}
