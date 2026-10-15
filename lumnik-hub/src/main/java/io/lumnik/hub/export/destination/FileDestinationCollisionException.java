package io.lumnik.hub.export.destination;

/** The final path already carried a delivered artifact — the rename was refused, and nothing
 *  about the existing file was touched. Distinguished from {@link FileDestinationException} so a
 *  caller (the future export runner, #680) can tell "someone already delivered this" apart from
 *  every other publication failure without parsing a message string. */
public class FileDestinationCollisionException extends FileDestinationException {
    public FileDestinationCollisionException(String message, Throwable cause) {
        super(message, cause);
    }
}
