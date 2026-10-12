package net.meshsat.android.interop

import org.junit.Assume
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The pinned upstream Python environment the interop and oracle tests run RNS
 * and LXMF from (the Bridge's internal/interop/rnsenv, in Kotlin). [MESHSAT-1553]
 *
 * Resolution: $MESHSAT_RNS_VENV/bin/python3, else
 * $XDG_CACHE_HOME (or ~/.cache)/meshsat/rns-venv-<RNS version>/bin/python3.
 * Missing venv: the test is skipped unless MESHSAT_INTEROP=1, which creates it
 * with python3 -m venv and pip. A venv whose versions differ from the pin fails
 * loudly. MESHSAT_RNS_VERSION / MESHSAT_LXMF_VERSION override the pin for a run
 * against another release (the suite is also run with RNS 1.5.7).
 */
object PyEnv {
    /** What CrossTalk 2.6.0 ships. */
    const val RNS_PIN = "1.5.5"
    const val LXMF_PIN = "1.2.0"

    val rnsVersion: String = System.getenv("MESHSAT_RNS_VERSION") ?: RNS_PIN
    val lxmfVersion: String = System.getenv("MESHSAT_LXMF_VERSION") ?: LXMF_PIN

    val interop: Boolean = System.getenv("MESHSAT_INTEROP") == "1"

    @Volatile private var resolved: String? = null
    @Volatile private var failure: String? = null

    /** The venv interpreter, or an assumption failure (skip) when it is not there and must not be made. */
    fun python(): String {
        resolved?.let { return it }
        failure?.let { throw AssertionError(it) }
        synchronized(this) {
            resolved?.let { return it }
            val dir = venvDir()
            val py = File(dir, "bin/python3")
            if (!py.canExecute()) {
                Assume.assumeTrue("Python RNS $rnsVersion venv not present at $dir (set MESHSAT_INTEROP=1 to create it)", interop)
                create(dir)
            }
            checkVersions(py.path)?.let { failure = it; throw AssertionError(it) }
            resolved = py.path
            return py.path
        }
    }

    /** A console script from the venv (rnsd, lxmd, ...). */
    fun bin(name: String): String {
        val f = File(File(python()).parentFile, name)
        if (!f.canExecute()) throw AssertionError("$name not in venv ${f.parent}")
        return f.path
    }

    private fun venvDir(): File {
        System.getenv("MESHSAT_RNS_VENV")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".cache").path
        return File(cache, "meshsat/rns-venv-$rnsVersion")
    }

    private fun create(dir: File) {
        dir.parentFile.mkdirs()
        run(listOf("python3", "-m", "venv", dir.path), 120)
        run(listOf(File(dir, "bin/pip").path, "install", "--disable-pip-version-check", "-q", "rns==$rnsVersion", "lxmf==$lxmfVersion"), 600)
    }

    private fun checkVersions(py: String): String? {
        val out = run(listOf(py, "-c", "import RNS, LXMF; print(RNS.__version__ + ' ' + LXMF.__version__)"), 60).trim()
        val parts = out.split(" ")
        if (parts.size != 2) return "venv python cannot import RNS/LXMF: $out"
        if (parts[0] != rnsVersion || parts[1] != lxmfVersion) {
            return "venv has RNS ${parts[0]} / LXMF ${parts[1]}, tests are pinned to RNS $rnsVersion / LXMF $lxmfVersion (recreate ${venvDir()})"
        }
        return null
    }

    /** Runs a command to completion and returns stdout; a non-zero exit fails with stderr. */
    fun run(cmd: List<String>, timeoutSec: Long): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); throw AssertionError("timeout: $cmd") }
        if (p.exitValue() != 0) throw AssertionError("${cmd.take(2)} failed (${p.exitValue()}):\n$err\n$out")
        return out
    }

    /** Runs an inline Python script with args in the venv and returns its stdout. */
    fun py(script: String, vararg args: String): String = run(listOf(python(), "-c", script) + args, 120)

    /** Runs a script that prints key=value lines and returns them as a map. */
    fun pyKv(script: String, vararg args: String): Map<String, String> =
        py(script, *args).lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
}
