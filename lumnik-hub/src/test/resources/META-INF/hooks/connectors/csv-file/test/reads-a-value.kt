// Fixture (#1435): a hook that cannot read a value — toInt()'s message quotes it.
fun shouldProcess(file: Any?, source: Any?): Boolean =
    "env. 4 kg".toInt() > 0
