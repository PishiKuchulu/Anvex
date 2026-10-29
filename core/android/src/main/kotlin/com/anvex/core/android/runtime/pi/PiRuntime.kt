package com.anvex.core.android.runtime.pi

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Installs the bundled Pi engine runtime (asset `pi-runtime.zip`) into
 * filesDir, mirroring the npm runtime pattern in NodeMobileRuntime:
 * extract once per runtime version, guarded by a marker file.
 *
 * Provenance: pi-coding-agent 1.4.0 from the npm registry (tarball
 * SHA-256 d155c16e…c7428, registry sha512 integrity verified). The zip
 * contains Pi's self-contained dist/bundle (9.0 MB), the jiti extension
 * loader (Pi's one deliberate external dependency), the Anvex driver and
 * the approval-gateway extension. No node_modules tree is shipped.
 *
 * The `-thinking1` suffix marks the driver change that forwards the
 * Anvex Thinking setting as pi's `--thinking` flag; the versioned
 * install dir changes with it, so existing installs re-extract
 * instead of keeping a stale driver.
 * The `-auth1` suffix marks the H-01 control-socket token handshake
 * (driver sends the per-run token first; the host verifies it before
 * sending the config with the API key). Same re-extract rule applies.
 */
object PiRuntime {

    const val RUNTIME_VERSION = "pi-1.4.0-thinking1-auth1"
    private const val ASSET_NAME = "pi-runtime.zip"
    private const val MARKER = ".anvex-pi-runtime.ok"

    fun runtimeDir(context: Context): File = File(context.filesDir, "pi-runtime/$RUNTIME_VERSION")

    fun driverFile(context: Context): File = File(runtimeDir(context), "driver/driver.mjs")

    fun gatewayExtension(context: Context): File = File(runtimeDir(context), "ext/gateway.ts")

    fun isInstalled(context: Context): Boolean =
        File(runtimeDir(context), MARKER).isFile && driverFile(context).isFile

    /** Extracts the runtime asset if needed. Returns the runtime dir, or null on failure. */
    fun ensureInstalled(context: Context): File? {
        val dir = runtimeDir(context)
        if (isInstalled(context)) return dir
        return runCatching {
            dir.deleteRecursively()
            dir.mkdirs()
            context.assets.open(ASSET_NAME).use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(dir, entry.name)
                        require(out.canonicalPath.startsWith(dir.canonicalPath)) { "Bad zip entry: ${entry.name}" }
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            File(dir, MARKER).writeText(RUNTIME_VERSION)
            dir
        }.getOrNull()
    }
}
