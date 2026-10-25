// Reads each context value by its own type (#1440): a number, a list, a boolean, a map.
@Suppress("UNCHECKED_CAST")
val cells = bindings["cells"] as MutableMap<String, Any?>
val context = bindings["context"] as Map<*, *>
cells["lead"] = (context["lead_days"] as Number).toInt() + 1
cells["closed"] = (context["closed"] as List<*>).joinToString(",")
cells["express"] = context["express"] as Boolean
cells["zone"] = (context["warehouse"] as Map<*, *>)["zone"]
cells["country"] = context["country"] as String
