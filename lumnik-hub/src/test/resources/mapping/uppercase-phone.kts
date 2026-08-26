// Receives mutable 'cells' (Map<String, Any?>) and read-only 'context' (Map<String, Any?>)
// Both are injected as top-level bindings by KotlinScriptHost.
@Suppress("UNCHECKED_CAST")
val cellsMap = bindings["cells"] as MutableMap<String, Any?>
cellsMap["phone"] = (cellsMap["phone"] as? String)?.uppercase()
