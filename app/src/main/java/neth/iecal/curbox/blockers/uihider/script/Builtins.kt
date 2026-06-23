package neth.iecal.curbox.blockers.uihider.script

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Host-agnostic standard library: math and utility functions with no Android dependency.
 * The runtime's [RuntimeApi] delegates here for any name it doesn't handle itself; [UNKNOWN]
 * is returned for names this object doesn't recognise so the host can raise a clean error.
 */
object Builtins {

    val UNKNOWN = Any()

    private const val MAX_RANGE = 100_000

    fun tryCall(name: String, args: List<Any?>): Any? = when (name) {
        "abs" -> abs(num(name, args, 0))
        "floor" -> floor(num(name, args, 0))
        "ceil" -> ceil(num(name, args, 0))
        "round" -> num(name, args, 0).roundToLong().toDouble()
        "sqrt" -> sqrt(num(name, args, 0))
        "pow" -> num(name, args, 0).pow(num(name, args, 1))
        "min" -> reduceNums(name, args) { a, b -> if (a < b) a else b }
        "max" -> reduceNums(name, args) { a, b -> if (a > b) a else b }
        "clamp" -> {
            val v = num(name, args, 0); val lo = num(name, args, 1); val hi = num(name, args, 2)
            if (v < lo) lo else if (v > hi) hi else v
        }
        "len" -> when (val x = arg(name, args, 0)) {
            is String -> x.length.toDouble()
            is List<*> -> x.size.toDouble()
            else -> throw ScriptError("len() expects a string or list, got ${Values.typeName(x)}")
        }
        "str" -> Values.stringify(arg(name, args, 0))
        "int" -> when (val x = arg(name, args, 0)) {
            is Double -> x.toLong().toDouble()
            is Boolean -> if (x) 1.0 else 0.0
            is String -> x.trim().toDoubleOrNull()?.toLong()?.toDouble()
                ?: throw ScriptError("int() cannot parse \"$x\"")
            else -> throw ScriptError("int() expects a number or string, got ${Values.typeName(x)}")
        }
        "range" -> range(args)
        else -> UNKNOWN
    }

    private fun range(args: List<Any?>): List<Any?> {
        val start: Int
        val end: Int
        when (args.size) {
            1 -> { start = 0; end = Values.asInt(args[0], 0) }
            2 -> { start = Values.asInt(args[0], 0); end = Values.asInt(args[1], 0) }
            else -> throw ScriptError("range() expects 1 or 2 arguments")
        }
        if (end - start > MAX_RANGE) throw ScriptError("range too large (max $MAX_RANGE)")
        val out = ArrayList<Any?>(maxOf(0, end - start))
        var i = start
        while (i < end) { out.add(i.toDouble()); i++ }
        return out
    }

    private fun reduceNums(name: String, args: List<Any?>, op: (Double, Double) -> Double): Double {
        val nums: List<Double> = if (args.size == 1 && args[0] is List<*>) {
            (args[0] as List<*>).map { Values.asNumber(it, 0) }
        } else {
            args.map { Values.asNumber(it, 0) }
        }
        if (nums.isEmpty()) throw ScriptError("$name() needs at least one number")
        return nums.reduce(op)
    }

    private fun arg(name: String, args: List<Any?>, i: Int): Any? {
        if (i >= args.size) throw ScriptError("$name() missing argument ${i + 1}")
        return args[i]
    }

    private fun num(name: String, args: List<Any?>, i: Int): Double =
        Values.asNumber(arg(name, args, i), 0)
}
