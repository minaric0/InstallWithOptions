@file:Suppress("PrivateApi")

package dev.zwander.installwithoptions.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.zwander.installwithoptions.data.DataModel
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private const val MAX_ARCHIVE_ENTRIES = 512
private const val MAX_ARCHIVE_COMPRESSED_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_ARCHIVE_EXTRACTED_BYTES = 4L * 1024 * 1024 * 1024

fun Context.handleIncomingUris(uris: List<Uri>) {
    DataModel.isImporting.value = true
    try {
        val selected = DataModel.selectedFiles.value.toMutableMap()
        uris.forEach { uri ->
            runCatching {
                val file = DocumentFile.fromSingleUri(this, uri) ?: return@runCatching
                when {
                    file.isApk -> addApkFile(file, selected)
                    file.isSplitBundle -> copyZipToCacheAndExtract(file).forEach {
                        addApkFile(it, selected)
                    }
                }
            }
        }
        DataModel.selectedFiles.value = selected
    } finally {
        DataModel.isImporting.value = false
    }
}

private val parseApkLite by lazy {
    runCatching {
        Class.forName("android.content.pm.PackageParser")
            .getMethod("parseApkLite", File::class.java, Int::class.java)
    }.getOrNull()
}

private fun Context.addApkFile(
    document: DocumentFile,
    selected: MutableMap<String, List<DocumentFile>>,
) {
    var tempFile: File? = null
    try {
        val localFile = if (document.uri.scheme == "file") {
            document.uri.path?.let(::File) ?: return
        } else {
            File.createTempFile("iwo-import-", ".apk", cacheDir).also { dest ->
                tempFile = dest
                contentResolver.openInputStream(document.uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it, 128 * 1024) }
                } ?: error("Unable to read APK")
            }
        }

        val packageName = packageManager.getPackageArchiveInfo(
            localFile.absolutePath,
            PackageManager.GET_META_DATA,
        )?.packageName ?: runCatching {
            val lite = parseApkLite?.invoke(null, localFile, 0)
            lite?.javaClass?.getField("packageName")?.get(lite) as? String
        }.getOrNull()

        if (!packageName.isNullOrBlank()) {
            val existing = selected[packageName].orEmpty()
            selected[packageName] = (existing + document)
                .distinctBy { it.uri.toString() }
        }
    } finally {
        tempFile?.delete()
    }
}

private fun InputStream.copyBounded(output: OutputStream, maximum: Long) {
    val buffer = ByteArray(128 * 1024)
    var count = 0L
    while (true) {
        val n = read(buffer)
        if (n < 0) return
        count += n
        require(count <= maximum) { "Archive exceeds allowed size" }
        output.write(buffer, 0, n)
    }
}

private fun Context.copyZipToCacheAndExtract(bundle: DocumentFile): List<DocumentFile> {
    if (bundle.length() > MAX_ARCHIVE_COMPRESSED_BYTES) return emptyList()

    val zipFile = File.createTempFile("iwo-bundle-", ".zip", cacheDir)
    val extractDir = File(cacheDir, zipFile.nameWithoutExtension + "-extracted")
    return try {
        contentResolver.openInputStream(bundle.uri)?.use { input ->
            zipFile.outputStream().use { output ->
                input.copyBounded(output, MAX_ARCHIVE_COMPRESSED_BYTES)
            }
        } ?: return emptyList()

        val archive = ZipFile(zipFile)
        val entries = archive.fileHeaders
        require(entries.size <= MAX_ARCHIVE_ENTRIES) { "Too many archive entries" }
        require(entries.all {
            val path = it.fileName.replace('\\', '/')
            !path.startsWith("/") && path.split('/').none { part -> part == ".." }
        }) { "Unsafe archive entry path" }
        require(entries.all { it.uncompressedSize >= 0 } &&
            entries.sumOf { it.uncompressedSize.coerceAtMost(MAX_ARCHIVE_EXTRACTED_BYTES + 1) }
                <= MAX_ARCHIVE_EXTRACTED_BYTES
        ) { "Extracted archive would be too large" }

        extractDir.mkdirs()
        archive.extractAll(extractDir.absolutePath)
        extractDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
            .take(MAX_ARCHIVE_ENTRIES)
            .map(DocumentFile::fromFile)
            .toList()
    } catch (_: Exception) {
        emptyList()
    } finally {
        zipFile.delete()
    }
}

private val DocumentFile.isApk: Boolean
    get() = type == "application/vnd.android.package-archive" ||
        name?.endsWith(".apk", ignoreCase = true) == true

private val DocumentFile.isSplitBundle: Boolean
    get() = type == "application/zip" ||
        listOf(".zip", ".xapk", ".apkm", ".apks").any {
            name?.endsWith(it, ignoreCase = true) == true
        }
