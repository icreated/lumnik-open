// Throws on every row: a bug in a script, not an unreadable value (#1432).
@Suppress("UNCHECKED_CAST")
val cells = bindings["cells"] as MutableMap<String, Any?>
cells["qty"] = (cells["quantity"] as String).toInt()   // quotes the value it cannot read
