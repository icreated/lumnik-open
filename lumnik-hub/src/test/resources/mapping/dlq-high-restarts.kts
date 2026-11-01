// A pod restarted more than 5 times cannot be read (#1617: its item is dead-lettered whole under on-error: dlq).
@Suppress("UNCHECKED_CAST")
val cells = bindings["cells"] as MutableMap<String, Any?>
if ((cells["restarts"] as Number).toInt() > 5) (bindings["unreadable"] as Runnable).run()
