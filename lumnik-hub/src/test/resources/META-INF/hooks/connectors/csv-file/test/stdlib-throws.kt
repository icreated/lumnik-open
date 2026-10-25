// Fixture (#1435): the failure is thrown inside the Kotlin stdlib (_Collections.kt), not in this file.
fun shouldProcess(file: Any?, source: Any?): Boolean =
    listOf<Int>().first() > 0
