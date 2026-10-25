package io.lumnik.hub.hook.kts

import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import javax.script.Bindings
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.onSuccess
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.api.valueOr
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

/**
 * Runs `.kts` hook scripts: compile once, load the script class once, then instantiate that
 * class once per evaluation. The script sees a single provided property, `bindings`
 * (a [javax.script.Bindings]), so the `bindings["cells"]` / `bindings["event"]` contract of
 * every existing script is unchanged.
 *
 * Why not JSR-223: Kotlin's JSR-223 engine is a REPL. Every `eval` of a CompiledScript is a new
 * REPL line — it generates and loads a fresh class and retains per-line state, ~60 KB per call.
 * A cell hook evaluates once per ROW, so a 40 000-row export retained ~2.4 GB and the hub was
 * OOM-killed (2026-09-06). Here the per-row cost is one constructor call.
 */
class KtsScriptRunner {

    private val host = BasicJvmScriptingHost()

    private val compilation = ScriptCompilationConfiguration {
        jvm { dependenciesFromCurrentContext(wholeClasspath = true) }
        providedProperties("bindings" to KotlinType(Bindings::class))
    }

    /** A compiled script: one loaded class, instantiated per [evaluate] / [instantiate]. */
    class Compiled internal constructor(private val ctor: Constructor<*>) {
        /** Runs the script body against [bindings]; a script failure surfaces as its own exception. */
        fun evaluate(bindings: Bindings) {
            newInstance(bindings)
        }

        /**
         * Runs the script body and returns the running [Script] — for scripts that DEFINE top-level
         * functions (members of the instance) rather than act on [bindings].
         */
        fun instantiate(bindings: Bindings): Script = Script(newInstance(bindings))

        /** One run of the body; [evaluate] builds no [Script], whose member lookup a cell hook never uses. */
        private fun newInstance(bindings: Bindings): Any {
            try {
                return ctor.newInstance(bindings)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("Kotlin script ${ctor.declaringClass.name} could not be instantiated", e)
            }
        }
    }

    /** A running script: its top-level functions are callable members, resolved once by name. */
    class Script internal constructor(private val instance: Any) {
        private val members = instance.javaClass.methods.groupBy { it.name }

        /** The function [name] taking [arity] arguments, or null when the script does not define it. */
        fun function(name: String, arity: Int): ScriptFunction? {
            val candidates = members[name].orEmpty().filter { it.parameterCount == arity }
            return when (candidates.size) {
                0 -> null
                1 -> ScriptFunction(instance, candidates[0])
                else -> throw IllegalStateException(
                    "$name/$arity is overloaded — a hook function must have one definition")
            }
        }
    }

    /** One top-level function of a running [Script]. */
    class ScriptFunction internal constructor(private val instance: Any, private val method: Method) {
        /**
         * Calls the function. The script's own exception surfaces as itself; a call the declared
         * signature cannot take surfaces as an [IllegalStateException] naming both sides.
         */
        fun call(vararg args: Any?): Any? = try {
            method.invoke(instance, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "$method cannot take (${args.joinToString { it?.javaClass?.name ?: "null" }})", e)
        }
    }

    /** Compiles [source] (named [name] in diagnostics) and loads its class. Compile errors throw. */
    fun compile(source: String, name: String): Compiled {
        val klass = host.runInCoroutineContext {
            host.compiler(source.toScriptSource(name), compilation)
                .onSuccess { it.getClass(ScriptEvaluationConfiguration.Default) }
        }.valueOr { throw IllegalStateException("Kotlin script compilation failed for $name:\n${it.render()}") }
        // The compiler declares the provided property's exact type on the generated constructor.
        return Compiled(klass.java.getConstructor(Bindings::class.java))
    }

    private fun ResultWithDiagnostics.Failure.render(): String =
        reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .joinToString("\n") { it.render(withStackTrace = false) }
}
