package net.meshsat.android.interop

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An upstream Python RNS + LXMF peer (interop/rnsnode.py from the test
 * resources, the Bridge's script unchanged) driven over stdin/stdout JSON lines.
 * [MESHSAT-1553]
 */
class PyPeer private constructor(val name: String, private val proc: Process, val cfgDir: File) {
    private val stdin = OutputStreamWriter(proc.outputStream)
    private val events = LinkedBlockingQueue<Map<String, Any?>>()
    private val log = ArrayList<Map<String, Any?>>()
    private val lock = Any()

    lateinit var identity: String; private set
    lateinit var delivery: String; private set
    lateinit var raw: String; private set
    lateinit var transportId: String; private set

    class Opts(
        val transport: Boolean = false,
        val connect: String? = null,
        val listen: Int = 0,
        val name: String = "rnsnode",
        val stampCost: Int = 0,
        val enforce: Boolean = false,
        val loglevel: Int = 0,
    )

    companion object {
        private val started = ArrayList<PyPeer>()

        private fun script(): File {
            val res = PyPeer::class.java.getResource("/interop/rnsnode.py") ?: throw AssertionError("interop/rnsnode.py missing from test resources")
            val f = File.createTempFile("rnsnode", ".py")
            f.deleteOnExit()
            res.openStream().use { it.copyTo(f.outputStream()) }
            return f
        }

        fun start(o: Opts): PyPeer {
            val py = PyEnv.python()
            val cfgDir = File.createTempFile("rnspeer-${o.name}-", "").also { it.delete(); it.mkdirs() }
            val args = arrayListOf(py, script().path, "--config-dir", cfgDir.path, "--name", o.name)
            if (o.transport) args += listOf("--transport", "yes")
            o.connect?.let { args += listOf("--connect", it) }
            if (o.listen != 0) args += listOf("--listen", o.listen.toString())
            if (o.stampCost > 0) args += listOf("--stamp-cost", o.stampCost.toString())
            if (o.enforce) args += "--enforce-stamps"
            if (o.loglevel > 0) args += listOf("--loglevel", o.loglevel.toString())
            val proc = ProcessBuilder(args).redirectErrorStream(false).start()
            val p = PyPeer(o.name, proc, cfgDir)
            synchronized(started) { started.add(p) }
            Thread({
                val r = BufferedReader(InputStreamReader(proc.inputStream))
                while (true) {
                    val line = r.readLine() ?: break
                    val ev = Json.parse(line) as? Map<String, Any?> ?: continue
                    synchronized(p.lock) { p.log.add(ev) }
                    p.events.put(ev)
                }
                p.events.put(mapOf("event" to "__exit__"))
            }, "py-${o.name}-out").apply { isDaemon = true }.start()
            Thread({
                val r = BufferedReader(InputStreamReader(proc.errorStream))
                while (true) { val line = r.readLine() ?: break; System.err.println("[py ${o.name}] $line") }
            }, "py-${o.name}-err").apply { isDaemon = true }.start()
            val ready = p.wait("ready", null, 30_000)
            p.identity = ready["identity"] as String
            p.delivery = ready["delivery"] as String
            p.raw = ready["raw"] as String
            p.transportId = ready["transport_id"] as String
            if (ready["rns"] != PyEnv.rnsVersion) throw AssertionError("peer runs RNS ${ready["rns"]}, want ${PyEnv.rnsVersion}")
            return p
        }

        /** Stops every peer started so far; call from @After. */
        fun stopAll(dumpLogs: Boolean) {
            val ps = synchronized(started) { started.toList().also { started.clear() } }
            for (p in ps) p.stop(dumpLogs)
        }
    }

    fun send(cmd: Map<String, Any?>) {
        synchronized(stdin) { stdin.write(Json.stringify(cmd) + "\n"); stdin.flush() }
    }

    fun send(vararg kv: Pair<String, Any?>) = send(mapOf(*kv))

    /** The first event named [name] matching [pred]; events already received are searched first. */
    fun wait(name: String, pred: ((Map<String, Any?>) -> Boolean)? = null, timeoutMs: Long = 10_000): Map<String, Any?> {
        synchronized(lock) {
            val i = log.indexOfFirst { it["event"] == name && (pred == null || pred(it)) }
            if (i >= 0) return log.removeAt(i)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw AssertionError("timeout waiting for $name from peer $name")
            val ev = events.poll(remaining, TimeUnit.MILLISECONDS) ?: throw AssertionError("timeout waiting for $name from peer ${this.name}")
            if (ev["event"] == "__exit__") throw AssertionError("peer ${this.name} exited while waiting for $name")
            if (ev["event"] == name && (pred == null || pred(ev))) {
                synchronized(lock) { log.remove(ev) }
                return ev
            }
            if (ev["event"] == "error") System.err.println("[py ${this.name}] error event: ${ev["error"]}")
        }
    }

    /** True when an event named [name] matching [pred] arrives within [timeoutMs], consuming it. */
    fun tryWait(name: String, pred: ((Map<String, Any?>) -> Boolean)? = null, timeoutMs: Long): Map<String, Any?>? =
        try { wait(name, pred, timeoutMs) } catch (_: AssertionError) { null }

    fun stop(dumpLog: Boolean) {
        try { send("cmd" to "quit") } catch (_: Exception) {}
        if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        if (dumpLog) {
            val f = File(cfgDir, "rns.log")
            if (f.exists()) {
                val lines = f.readLines()
                System.err.println("[py $name rns.log tail]\n" + lines.takeLast(60).joinToString("\n"))
            }
        }
        cfgDir.deleteRecursively()
    }
}

/** A JSON codec small enough to live here (org.json is a stub in JVM unit tests). */
object Json {
    fun stringify(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is String -> quote(v)
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { quote(it.key.toString()) + ":" + stringify(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { stringify(it) }
        else -> quote(v.toString())
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun parse(s: String): Any? = Parser(s).value().also { }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("empty json")
            return when (s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++
            while (true) {
                ws(); if (s[i] == '}') { i++; return m }
                val k = str(); ws(); i++ /* : */
                m[k] = value(); ws()
                if (s[i] == ',') i++
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++
            while (true) {
                ws(); if (s[i] == ']') { i++; return l }
                l.add(value()); ws()
                if (s[i] == ',') i++
            }
        }
        fun str(): String {
            val sb = StringBuilder(); i++
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> sb.append(s[i])
                    }
                } else sb.append(s[i])
                i++
            }
            i++
            return sb.toString()
        }
        fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return if ('.' in t || 'e' in t || 'E' in t) t.toDouble() else t.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
        }
    }
}
