@file:Suppress("PrivateApi")

package dev.zwander.installwithoptions.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dev.zwander.installwithoptions.data.DataModel
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.IOException
import java.util.UUID

fun Context.handleIncomingUris(uris: List<Uri>) {
    synchronized(DataModel) {
        DataModel.isImporting.value = true
        try {
            val selection = DataModel.selectedFiles.value.toMutableMap()
            uris.distinct().forEach { uri ->
                try {
                    val file = DocumentFile.fromSingleUri(this, uri) ?: return@forEach
                    when {
                        file.isApk -> addApkFile(file, selection)
                        file.isSplitBundle -> copyZipToCacheAndExtract(file).forEach {
                            addApkFile(it, selection)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("InstallWithOptions", "Failed to import $uri", e)
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
    currentSelection: MutableMap<String, List<DocumentFile>>,
) {
    var temporary: File? = null
    try {
        val apkOnDisk = if (file.uri.scheme == "file") {
            File(file.uri.path ?: throw IOException("Missing APK path"))
        } else {
            File.createTempFile("iwo_apk_", ".apk", cacheDir).also { dest ->
                temporary = dest
                (contentResolver.openInputStream(file.uri)
                    ?: throw IOException("APK content could not be opened")).use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                    }
                }
            }
        }
        val apkLite = parseApkLiteMethod.invoke(null, apkOnDisk, 0) ?: return
        val packageName = apkLitePackageNameField.get(apkLite) as String
        val packageList = currentSelection[packageName].orEmpty()
        currentSelection[packageName] = (packageList + file).distinctBy { it.uri }
    } catch (e: Exception) {
        Log.e("InstallWithOptions", "Failed to read APK $file", e)
    } finally {
        temporary?.delete()
    }
}

private fun Context.copyZipToCacheAndExtract(zip: DocumentFile): List<DocumentFile> {
    val archive = File.createTempFile("iwo_bundle_", ".zip", cacheDir)
    val directory = File(cacheDir, "iwo_extract_${UUID.randomUUID()}").apply { mkdirs() }
    try {
        (contentResolver.openInputStream(zip.uri)
            ?: throw IOException("Archive could not be opened")).use { input ->
            archive.outputStream().use { output ->
                input.copyTo(output, bufferSize = 256 * 1024)
            }
        }
        val zipFile = ZipFile(archive)
        val headers = zipFile.fileHeaders
        val root = directory.canonicalPath + File.separator
        if (headers.size > 4096 || headers.any { it.isEncrypted } ||
            headers.any { it.uncompressedSize < 0L } ||
            headers.sumOf { it.uncompressedSize } > 8L * 1024 * 1024 * 1024 ||
            headers.any { header ->
                !File(directory, header.fileName).canonicalPath.startsWith(root)
            }
        ) {
            throw IOException("Bundle contains unsafe, encrypted, or excessive ZIP entries")
        }

        zipFile.extractAll(directory.absolutePath)
        return directory.walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .map { DocumentFile.fromFile(it) }
            .toList()
    } catch (e: Exception) {
        Log.e("InstallWithOptions", "Failed to extract bundle $zip", e)
        directory.deleteRecursively()
        return emptyList()
    } finally {
        archive.delete()
    }
}

private val DocumentFile.isApk: Boolean
    get() = type == "application/vnd.android.package-archive" || name?.endsWith(".apk", ignoreCase = true) == true

private val DocumentFile.isSplitBundle: Boolean
    get() = type == "application/zip" || name?.endsWith(".xapk", ignoreCase = true) == true || name?.endsWith(".apkm", ignoreCase = true) == true || name?.endsWith(".apks", ignoreCase = true) == true
