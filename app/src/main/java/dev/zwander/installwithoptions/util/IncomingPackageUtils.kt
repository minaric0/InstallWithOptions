@file:Suppress("PrivateApi")

package dev.zwander.installwithoptions.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.zwander.installwithoptions.data.DataModel
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.IOException
import java.util.UUID

private val importLock = Any()
private const val MAX_ARCHIVE_APKS = 256
private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024

/**
 * Imports on the caller's IO dispatcher. Serializing imports prevents concurrent
 * document-provider shares from dropping entries in the selected queue.
 */
fun Context.handleIncomingUris(uris: List<Uri>) {
    synchronized(importLock) {
        DataModel.isImporting.value = true
        try {
            val selection = DataModel.selectedFiles.value.toMutableMap()
            uris.forEach { uri ->
                try {
                    val file = DocumentFile.fromSingleUri(this, uri) ?: return@forEach
                    when {
                        file.isApk -> addApkFile(file, selection)
                        file.isSplitBundle -> copyZipToCacheAndExtract(file).forEach { apk ->
                            addApkFile(apk, selection)
                        }
                    }
                } catch (_: Exception) {
                    // Ignore an unreadable input, not the whole batch.
                }
            }
            DataModel.selectedFiles.value = selection
        } finally {
            DataModel.isImporting.value = false
        }
    }
}

private val apkLiteClass by lazy {
    Class.forName("android.content.pm.PackageParser\$ApkLite")
}
private val apkLitePackageNameField by lazy {
    apkLiteClass.getField("packageName")
}
private val parseApkLiteMethod by lazy {
    Class.forName("android.content.pm.PackageParser")
        .getMethod("parseApkLite", File::class.java, Int::class.java)
}

private fun Context.addApkFile(
    file: DocumentFile,
    selection: MutableMap<String, List<DocumentFile>>,
) {
    var temp: File? = null
    try {
        val localFile = if (file.uri.scheme == "file") {
            File(requireNotNull(file.uri.path))
        } else {
            File.createTempFile("iwo_parse_", ".apk", cacheDir).also { dest ->
                temp = dest
                val input = contentResolver.openInputStream(file.uri)
                    ?: throw IOException("Cannot read selected APK")
                input.use { source ->
                    dest.outputStream().use { target -> source.copyTo(target) }
                }
            }
        }
        val packageName = try {
            val apk = parseApkLiteMethod.invoke(null, localFile, 0)
            apkLitePackageNameField.get(apk) as? String
        } catch (_: Throwable) {
            // Some OEM versions relocate private PackageParser methods.
            packageManager.getPackageArchiveInfo(localFile.absolutePath, 0)?.packageName
        } ?: return

        selection[packageName] = ((selection[packageName] ?: emptyList()) + file)
            .distinctBy { it.uri }
    } catch (_: Exception) {
        // A malformed APK must not interrupt other selected files.
    } finally {
        temp?.delete()
    }
}

/**
 * Only stream APK entries into an isolated cache directory. Enforce actual
 * output byte limits instead of trusting archive header sizes, and never
 * materialize archive-controlled paths or non-APK/OBB entries.
 */
private fun Context.copyZipToCacheAndExtract(zip: DocumentFile): List<DocumentFile> {
    val zipCache = File.createTempFile("iwo_bundle_", ".zip", cacheDir)
    val extractionDir = File(cacheDir, "iwo_apks_${UUID.randomUUID()}").apply { mkdirs() }
    var success = false
    try {
        val source = contentResolver.openInputStream(zip.uri) ?: return emptyList()
        source.use { input ->
            zipCache.outputStream().use { output ->
                copyBounded(input, output, MAX_ARCHIVE_BYTES)
            }
        }

        val archive = ZipFile(zipCache)
        val headers = archive.fileHeaders.filter { header ->
            !header.isDirectory && header.fileName.endsWith(".apk", ignoreCase = true)
        }
        if (headers.isEmpty() || headers.size > MAX_ARCHIVE_APKS) return emptyList()
        if (headers.any { it.isEncrypted }) return emptyList()

        var totalUncompressed = 0L
        val result = headers.mapIndexed { index, header ->
            val output = File(extractionDir, "split_${index}.apk")
            archive.getInputStream(header).use { input ->
                output.outputStream().use { stream ->
                    val available = MAX_ARCHIVE_BYTES - totalUncompressed
                    totalUncompressed += copyBounded(input, stream, available)
                }
            }
            DocumentFile.fromFile(output)
        }
        success = true
        return result
    } catch (_: Exception) {
        return emptyList()
    } finally {
        zipCache.delete()
        if (!success) extractionDir.deleteRecursively()
    }
}

private fun copyBounded(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    maxBytes: Long,
): Long {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (read.toLong() > maxBytes - copied) {
            throw IOException("Archive exceeds the maximum permitted size")
        }
        output.write(buffer, 0, read)
        copied += read
    }
    return copied
}

private val DocumentFile.isApk: Boolean
    get() = type == "application/vnd.android.package-archive" ||
        name?.endsWith(".apk", ignoreCase = true) == true

private val DocumentFile.isSplitBundle: Boolean
    get() = type == "application/zip" ||
        name?.endsWith(".xapk", ignoreCase = true) == true ||
        name?.endsWith(".apkm", ignoreCase = true) == true ||
        name?.endsWith(".apks", ignoreCase = true) == true
