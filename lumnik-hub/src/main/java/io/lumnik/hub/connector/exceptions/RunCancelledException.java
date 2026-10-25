package io.lumnik.hub.connector.exceptions;

/**
 * A reader stopped inside its chunk because the run was cancelled ({@code RunScope#cancelRequested}). Not a failure:
 * the chunk answers {@code ChunkResult.Cancelled}, writes nothing of what was read, and the run ends {@code Cancelled}
 * (#1444).
 */
public class RunCancelledException extends ConnectorException {

    public RunCancelledException() {
        super("the run was cancelled while reading");
    }
}
