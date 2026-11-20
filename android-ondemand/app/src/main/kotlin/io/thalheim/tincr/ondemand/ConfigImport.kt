package io.thalheim.tincr.ondemand

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class ImportError(message: String) : IOException(message)

// Replaces dir with a zipped tinc config tree (tinc.conf, hosts/,
// private key, vpn.conf). The tree may sit in a subdirectory of the zip.
object ConfigImport {
    fun fromZip(input: InputStream, dir: File) {
        val tmp = File(dir.parentFile, dir.name + ".import").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val root = unzip(input, tmp)
            if (!File(root, "vpn.conf").isFile) throw ImportError("vpn.conf missing next to tinc.conf")
            if (NetworkConfig.load(root).addresses.isEmpty()) throw ImportError("vpn.conf has no address")
            // Shell scripts written for a Linux host only fail here.
            root.listFiles()?.filter { it.name.endsWith("-up") || it.name.endsWith("-down") }?.forEach { it.delete() }
            dir.deleteRecursively()
            dir.parentFile!!.mkdirs()
            if (!root.renameTo(dir)) throw ImportError("cannot move config into place")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // Returns the directory holding the shallowest tinc.conf.
    private fun unzip(input: InputStream, to: File): File {
        val base = to.canonicalPath + File.separator
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val f = File(to, e.name)
                if (!f.canonicalPath.startsWith(base)) throw ImportError("bad path in zip: ${e.name}")
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile!!.mkdirs()
                    f.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        return to.walkTopDown().filter { it.name == "tinc.conf" && it.isFile }
            .minByOrNull { it.path.length }?.parentFile
            ?: throw ImportError("no tinc.conf in zip")
    }
}
