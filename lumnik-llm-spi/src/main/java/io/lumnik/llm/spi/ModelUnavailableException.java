package io.lumnik.llm.spi;

/**
 * The model runtime failed or is unreachable — not running, model not pulled, out of
 * memory, stale GPU drivers… A deployment state, not a bug: owed an honest refusal at
 * the API door, never an anonymous 500. Thrown by the port implementations at their
 * boundary; lives in the SPI so every caller can translate it without knowing the
 * provider.
 */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String portName, Throwable cause) {
        super("the model runtime failed or is unreachable (" + portName
            + (cause != null && cause.getMessage() != null ? "): " + cause.getMessage() : ")"),
            cause);
    }
}
