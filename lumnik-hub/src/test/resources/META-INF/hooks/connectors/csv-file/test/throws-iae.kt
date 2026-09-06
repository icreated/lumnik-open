// Fixture: a hook whose own failure is an IllegalArgumentException — the refusal's type.
fun shouldProcess(file: Any?, source: Any?): Boolean =
    throw IllegalArgumentException("script-level refusal, not a script_ref problem")
