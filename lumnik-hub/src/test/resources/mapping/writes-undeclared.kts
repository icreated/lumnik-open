// Writes a column no promote entry declares: the run must refuse it, naming the column (#1617).
@Suppress("UNCHECKED_CAST")
val cells = bindings["cells"] as MutableMap<String, Any?>
cells["surprise"] = "x"
