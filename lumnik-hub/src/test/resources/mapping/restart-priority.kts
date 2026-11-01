// A pod that restarted more than `threshold` times is "high", any other one "none" (#1617: a script over promoted columns).
@Suppress("UNCHECKED_CAST")
val cells = bindings["cells"] as MutableMap<String, Any?>
val context = bindings["context"] as Map<*, *>
val restarts = (cells["restarts"] as Number).toInt()
cells["priority"] = if (restarts > (context["threshold"] as Number).toInt()) "high" else "none"
