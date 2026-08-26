package io.lumnik.hub.connector.csv;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A {@link Reader} over an {@link InputStream} + {@link CharsetDecoder} that can name a
 * <em>resumable position</em> for any char offset it has delivered (lot-5b design (A),
 * checkpoint-anchor).
 *
 * <p><strong>The problem.</strong> A char count alone cannot be turned back into a byte offset
 * under multi-byte charsets, so "resume at char P" normally means re-decoding the whole prefix —
 * O(file) per resume. The trick is to checkpoint at <em>decode-batch granularity</em>: bytes are
 * decoded in batches, and a ledger records, per batch, the pair
 * {@code (batchStartByte, charsDeliveredBefore)}. A checkpoint for char position P is then
 * {@code (anchor.batchStartByte, P − anchor.charsDeliveredBefore)} — found by binary search — and
 * resuming costs one byte-seek (done by the caller) plus skipping at most one batch worth of chars.
 *
 * <p><strong>The invariant that makes resume sound.</strong> A batch's {@code batchStartByte} must
 * be a position where a FRESH decoder can start cleanly. When a decode pass leaves undecoded
 * leftover bytes (a multi-byte sequence split by the raw read), those bytes are <em>carried into
 * the next batch</em>: its anchor is where the leftover began, not where the next raw read began.
 * The leftover bytes are exactly the start of the next character, so every anchor sits on a
 * character boundary in the byte stream — which is precisely what a fresh decoder needs. (The
 * decoder itself holds no split-sequence state between passes: {@code decode(…, endOfInput=false)}
 * leaves incomplete trailing bytes in the input buffer rather than buffering them internally.)
 *
 * <p><strong>The allowlist.</strong> A character boundary is a clean fresh-decoder start only for
 * charsets whose decoding is stateless — where the meaning of a byte never depends on bytes before
 * the current character. {@link #checkpoint(long)} therefore only works for a verified allowlist
 * (allowlist, not blocklist — anything unverified falls back):
 * <ul>
 *   <li>UTF-8, US-ASCII, ISO-8859-*, windows-125x — self-synchronizing or single-byte;</li>
 *   <li>UTF-16BE / UTF-16LE — fixed code-unit size, and their decoders are BOM-agnostic: a
 *       leading U+FEFF is delivered and counted like any other char, so a mid-stream anchor never
 *       re-strips or mis-handles a BOM.</li>
 * </ul>
 * Excluded, with reasons:
 * <ul>
 *   <li><em>plain UTF-16</em> — its decoder is BOM-stateful: it resolves endianness from an
 *       initial BOM and strips it, state a fresh decoder at a mid-stream anchor cannot reproduce
 *       (it would default to big-endian and would not know a BOM was consumed);</li>
 *   <li><em>ISO-2022-*</em> — stateful escape sequences select the active sub-charset, so no
 *       mid-stream byte position is decodable without the escapes that preceded it;</li>
 *   <li>everything else (Shift_JIS, GB18030, EBCDIC variants, …) — possibly sound, never
 *       verified, therefore off the list.</li>
 * </ul>
 * Off-allowlist charsets still READ normally — only {@code checkpoint()}/{@code resume()} throw
 * {@link UncheckpointableCharsetException}, which the caller maps to the skip-parse fallback path.
 *
 * <p><strong>Coordinates and resume.</strong> Byte anchors are offsets into the stream this reader
 * was given; char positions count chars delivered by this reader. {@link #resume} returns a
 * CheckpointingReader whose coordinates are relative to ITS stream — i.e. to the anchor — with the
 * skipped {@code charDelta} chars already counted as delivered, so a resumed reader can itself be
 * checkpointed (the caller adds the previous anchor to get absolute offsets).
 *
 * <p>Malformed input is {@linkplain CodingErrorAction#REPORT reported} (thrown), matching the
 * {@code Files.newBufferedReader} semantics of the existing CSV path — and making resume trivially
 * equivalent on malformed files: both paths throw at the same bytes. Not thread-safe, like any
 * single-consumer Reader.
 */
public class CheckpointingReader extends Reader {

  /** Thrown by {@link #checkpoint} / {@link #resume} when the charset is off the allowlist. */
  public static final class UncheckpointableCharsetException extends IllegalStateException {
    UncheckpointableCharsetException(Charset cs) {
      super("charset " + cs.name() + " is not on the checkpoint allowlist"
          + " (stateless decoding not verified) — use the skip-parse fallback");
    }
  }

  /** A resumable position: byte-seek to {@code anchorByte}, then skip {@code charDelta} chars. */
  public record Checkpoint(long anchorByte, long charDelta) {}

  /** One ledger entry: where a decode batch starts, and how many chars all prior batches made. */
  private record Batch(long startByte, long charsBefore) {}

  private static final int DEFAULT_BATCH_SIZE = 8192;
  /** Leftover carried between batches is at most a few bytes (longest sequence − 1). */
  private static final int MIN_BATCH_SIZE = 16;

  private final InputStream in;
  private final Charset charset;
  private final CharsetDecoder decoder;
  private final int batchSize;
  private final ByteBuffer bytes;   // drain mode between refills; remaining = undecoded leftover
  private final CharBuffer chars;   // drain mode; remaining = decoded, not yet delivered
  private final List<Batch> ledger = new ArrayList<>();

  private long streamBytes;         // total bytes consumed from `in`
  private long charsProduced;       // total chars decoded (ledger's running count)
  private long charsDelivered;      // total chars handed out via read()
  private boolean eofSeen;          // `in` returned -1
  private boolean finished;         // decoder flushed; nothing more will be produced

  public CheckpointingReader(InputStream in, Charset charset) {
    this(in, charset, DEFAULT_BATCH_SIZE);
  }

  /**
   * @param batchSize raw bytes per decode batch (and ledger granularity). Small values make batch
   *     boundaries — and therefore boundary-straddling multi-byte sequences — deterministic in
   *     tests; production uses the default. Must leave room for carried leftover bytes.
   */
  public CheckpointingReader(InputStream in, Charset charset, int batchSize) {
    if (batchSize < MIN_BATCH_SIZE) {
      throw new IllegalArgumentException("batchSize must be >= " + MIN_BATCH_SIZE
          + " (leftover bytes carried between batches need room): " + batchSize);
    }
    this.in = Objects.requireNonNull(in, "in");
    this.charset = Objects.requireNonNull(charset, "charset");
    this.decoder = newDecoder(charset);
    this.batchSize = batchSize;
    this.bytes = ByteBuffer.allocate(batchSize);
    this.bytes.limit(0);
    this.chars = CharBuffer.allocate(
        (int) Math.ceil(batchSize * decoder.maxCharsPerByte()) + 2);
    this.chars.limit(0);
  }

  /** True when {@link #checkpoint} will work for this charset — the verified-stateless allowlist. */
  public static boolean canCheckpoint(Charset cs) {
    String n = cs.name();  // canonical name; aliases resolve before we get here
    return n.equals("UTF-8") || n.equals("US-ASCII")
        || n.equals("UTF-16BE") || n.equals("UTF-16LE")
        || n.startsWith("ISO-8859-") || n.startsWith("windows-125");
  }

  /**
   * Names a resumable position for char offset {@code charPosition} (the next char a resumed
   * reader will deliver). The position must already have been delivered by this reader — the
   * ledger only covers decoded prefix, and a checkpoint beyond it would be a caller bug.
   *
   * @throws UncheckpointableCharsetException if the charset is off the allowlist (fallback path)
   * @throws IllegalArgumentException if {@code charPosition} has not been delivered yet
   */
  public Checkpoint checkpoint(long charPosition) {
    if (!canCheckpoint(charset)) {
      throw new UncheckpointableCharsetException(charset);
    }
    if (charPosition < 0 || charPosition > charsDelivered) {
      throw new IllegalArgumentException("char position " + charPosition
          + " has not been delivered yet (delivered: " + charsDelivered + ")");
    }
    // Rightmost batch with charsBefore <= charPosition: later batches with the same char count
    // (zero-char batches are possible near EOF) give the same delta but a shorter resume skip.
    int lo = 0;
    int hi = ledger.size() - 1;
    int best = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (ledger.get(mid).charsBefore() <= charPosition) {
        best = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (best < 0) {
      return new Checkpoint(0, charPosition);  // nothing decoded yet: position 0 of an empty read
    }
    Batch anchor = ledger.get(best);
    return new Checkpoint(anchor.startByte(), charPosition - anchor.charsBefore());
  }

  /**
   * Resumes from a checkpoint: a fresh decoder over a stream the caller has already byte-seeked
   * to the checkpoint's anchor, with the checkpoint's {@code charDelta} chars skipped. This class
   * owns only the char arithmetic — the byte-seek ({@code openAt}) is the transport's job.
   *
   * <p>The returned reader is itself checkpointable, in anchor-relative coordinates (see class
   * javadoc); the skipped delta counts as delivered.
   *
   * @throws UncheckpointableCharsetException if the charset is off the allowlist
   * @throws EOFException if the stream ends before {@code charDelta} chars — the anchor does not
   *     match the stream (wrong file, mutated file): fail loud, never resume on garbage
   */
  public static CheckpointingReader resume(InputStream alreadyPositionedAtAnchor, Charset cs,
                                           long charDelta) throws IOException {
    return resume(alreadyPositionedAtAnchor, cs, charDelta, DEFAULT_BATCH_SIZE);
  }

  /** {@link #resume(InputStream, Charset, long)} with an explicit batch size (tests). */
  public static CheckpointingReader resume(InputStream alreadyPositionedAtAnchor, Charset cs,
                                           long charDelta, int batchSize) throws IOException {
    if (!canCheckpoint(cs)) {
      throw new UncheckpointableCharsetException(cs);
    }
    if (charDelta < 0) {
      throw new IllegalArgumentException("negative charDelta: " + charDelta);
    }
    CheckpointingReader r = new CheckpointingReader(alreadyPositionedAtAnchor, cs, batchSize);
    char[] skip = new char[(int) Math.min(charDelta, 4096)];
    long remaining = charDelta;
    while (remaining > 0) {
      int n = r.read(skip, 0, (int) Math.min(remaining, skip.length));
      if (n < 0) {
        throw new EOFException("stream ended after " + (charDelta - remaining) + " of "
            + charDelta + " checkpoint chars — anchor does not match this stream");
      }
      remaining -= n;
    }
    return r;
  }

  /** Chars delivered so far — the highest position {@link #checkpoint} will accept. */
  public long charsDelivered() {
    return charsDelivered;
  }

  @Override
  public int read(char[] cbuf, int off, int len) throws IOException {
    Objects.checkFromIndexSize(off, len, cbuf.length);
    if (len == 0) {
      return 0;
    }
    if (!chars.hasRemaining() && !refill()) {
      return -1;
    }
    int n = Math.min(len, chars.remaining());
    chars.get(cbuf, off, n);
    charsDelivered += n;
    return n;
  }

  @Override
  public void close() throws IOException {
    in.close();
  }

  /**
   * Decodes batches until at least one char is available or the stream is exhausted. Each pass
   * records its ledger entry BEFORE decoding: the batch starts where the previous pass's
   * undecoded leftover began — that leftover is the first bytes of the next character, so the
   * anchor lands on a character boundary, where a fresh decoder starts clean.
   */
  private boolean refill() throws IOException {
    while (!finished) {
      // THE invariant: the batch starts where the previous pass's undecoded leftover began —
      // `streamBytes` alone (where the next raw read begins) would skip those carried bytes and
      // hand resume an anchor inside a multi-byte sequence. `bytes` is in drain mode here, so
      // its remaining() is exactly that leftover.
      long batchStart = streamBytes - bytes.remaining();
      fillFromStream();
      if (!bytes.hasRemaining()) {
        // Terminal: EOF with no undecoded bytes. Give the decoder its end-of-input signal and
        // flush; a stateless decoder produces nothing here, but the protocol requires it.
        chars.clear();
        CoderResult cr = decoder.decode(bytes, chars, true);
        if (cr.isError()) {
          cr.throwException();
        }
        cr = decoder.flush(chars);
        if (cr.isError()) {
          cr.throwException();
        }
        chars.flip();
        charsProduced += chars.remaining();
        finished = true;
        return chars.hasRemaining();
      }
      ledger.add(new Batch(batchStart, charsProduced));
      chars.clear();
      CoderResult cr = decoder.decode(bytes, chars, eofSeen);
      if (cr.isError()) {
        cr.throwException();  // REPORT semantics: malformed/unmappable bytes fail the read
      }
      if (eofSeen && !bytes.hasRemaining()) {
        // Final decode consumed everything (with endOfInput=true an incomplete tail would have
        // been reported above): flush terminal state and finish.
        cr = decoder.flush(chars);
        if (cr.isError()) {
          cr.throwException();
        }
        finished = true;
      }
      chars.flip();
      charsProduced += chars.remaining();
      if (chars.hasRemaining()) {
        return true;
      }
      // Zero chars produced (all bytes were an incomplete tail near EOF): loop and read more.
    }
    return false;
  }

  /**
   * Compacts the undecoded leftover to the front of the byte buffer and tops it up from the
   * stream, so each decode pass sees at most {@code batchSize} bytes: leftover first, fresh bytes
   * after. Leaves the buffer in drain mode.
   */
  private void fillFromStream() throws IOException {
    bytes.compact();
    if (!eofSeen) {
      int room = batchSize - bytes.position();
      if (room > 0) {
        int n = in.read(bytes.array(), bytes.position(), room);
        if (n < 0) {
          eofSeen = true;
        } else if (n == 0) {
          throw new IOException("InputStream.read returned 0 for a non-empty request");
        } else {
          bytes.position(bytes.position() + n);
          streamBytes += n;
        }
      }
    }
    bytes.flip();
  }

  /** Both construction and resume MUST build the decoder identically, or resume drifts. */
  private static CharsetDecoder newDecoder(Charset cs) {
    return cs.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
  }
}
