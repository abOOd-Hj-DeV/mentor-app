package dev.k230.mentor_app.protection.security

import android.content.Context
import android.util.AtomicFile
import com.google.crypto.tink.Aead
import java.io.File

interface BlobStore {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
    fun names(): List<String>
}

class PrivateAtomicStore(context: Context, namespace: String) : BlobStore {
    private val directory: File
    init {
        StrictJson.ensure(namespace.matches(Regex("[a-z_]{1,32}")), "bounds")
        directory = File(context.noBackupFilesDir, "mentor_v2/$namespace")
        StrictJson.ensure(directory.isDirectory || directory.mkdirs(), "storage_failed")
    }
    private fun file(name: String): AtomicFile {
        StrictJson.ensure(name.matches(Regex("[a-z0-9_-]{1,80}")), "bounds")
        return AtomicFile(File(directory, name))
    }
    override fun read(name: String): ByteArray? {
        val atomic = file(name)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
            StrictJson.ensure(!File(atomic.baseFile.path + ".new").exists(), "storage_failed")
            return null
        }
        StrictJson.ensure(atomic.baseFile.length() <= 65536, "bounds")
        return atomic.openRead().use { stream ->
            val bytes = ByteArray(65537)
            var count = 0
            while (count < bytes.size) {
                val n = stream.read(bytes, count, bytes.size - count)
                if (n < 0) break
                count += n
            }
            StrictJson.ensure(count <= 65536, "bounds")
            bytes.copyOf(count)
        }
    }
    override fun write(name: String, bytes: ByteArray) {
        StrictJson.ensure(bytes.size <= 65536, "bounds")
        val atomic = file(name)
        val output = atomic.startWrite()
        try { output.write(bytes); output.fd.sync(); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    override fun delete(name: String) = file(name).delete()
    override fun names(): List<String> = (directory.list() ?: throw SecurityFailure("storage_failed")).map {
        val name = it.removeSuffix(".bak").removeSuffix(".new")
        StrictJson.ensure(name.matches(Regex("[a-z0-9_-]{1,80}")), "storage_failed")
        name
    }.distinct().sorted()
}

class SealedStore(private val disk: BlobStore, private val aead: Aead, val pairId: String,
                  private val purpose: String) {
    init {
        StrictJson.uuid(pairId)
        StrictJson.ensure(purpose.matches(Regex("[a-z_]{1,32}")), "bounds")
    }
    private fun aad(name: String) = "mentor.storage.v2\n$purpose\n$pairId\n$name\n".toByteArray()
    @Synchronized fun put(name: String, bytes: ByteArray) {
        StrictJson.ensure(bytes.size <= 32768, "bounds")
        val encrypted = aead.encrypt(bytes, aad(name))
        val names = disk.names()
        StrictJson.ensure(name in names || names.size < 10000, "storage_failed")
        val total = names.filter { it != name }.sumOf { (disk.read(it) ?: throw SecurityFailure("storage_failed")).size.toLong() }
        StrictJson.ensure(total + 1 + encrypted.size <= 32L * 1024 * 1024, "storage_failed")
        disk.write(name, byteArrayOf(2) + encrypted)
    }
    @Synchronized fun get(name: String): ByteArray? {
        val bytes = disk.read(name) ?: return null
        StrictJson.ensure(bytes.size in 2..65536 && bytes[0].toInt() == 2, "storage_failed")
        return aead.decrypt(bytes.copyOfRange(1, bytes.size), aad(name))
    }
    @Synchronized fun delete(name: String) = disk.delete(name)
    @Synchronized fun names(): List<String> = disk.names()
}
