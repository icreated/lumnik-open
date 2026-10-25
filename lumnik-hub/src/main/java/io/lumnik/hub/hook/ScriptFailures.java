package io.lumnik.hub.hook;

/** What an operator's Kotlin script failure may say about itself without quoting the value it could not read. */
public final class ScriptFailures {

    private ScriptFailures() {}

    /**
     * "{@code <Kind> at line N}" of the script's own frame — what a script failure may say without quoting a value
     * (#1432, #1435). The line is left out when the failure is not in the script's text.
     */
    public static String kindAndLine(Throwable e) {
        for (StackTraceElement frame : e.getStackTrace()) {
            String file = frame.getFileName();
            // The stdlib is Kotlin too (_Collections.kt): its frames are skipped, or first() would name its line.
            if (file != null && (file.endsWith(".kts") || file.endsWith(".kt")) && frame.getLineNumber() > 0
                    && !frame.getClassName().startsWith("kotlin.")) {
                return e.getClass().getSimpleName() + " at line " + frame.getLineNumber();
            }
        }
        return e.getClass().getSimpleName();
    }
}
