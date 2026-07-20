package aenu.ax360e.ui.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ZarDiscInfo(
    val number: Int,
    val fileName: String,
    val label: String,
    val uri: Uri
)

data class ZarBuildResult(
    val success: Boolean,
    val outputPath: String? = null,
    val error: String? = null,
    val discCount: Int = 0
)

object ZarBuilder {

    private const val ZAR_MANIFEST_NAME = "zar.json"
    private const val BUFFER_SIZE = 8 * 1024 * 1024

    suspend fun buildZar(
        context: Context,
        discUris: List<Uri>,
        titleId: String,
        titleName: String,
        outputDir: Uri
    ): ZarBuildResult = withContext(Dispatchers.IO) {
        try {
            if (discUris.size < 2) {
                return@withContext ZarBuildResult(
                    success = false,
                    error = "At least 2 disc images are required"
                )
            }

            val outputDirDoc = DocumentFile.fromTreeUri(context, outputDir)
                ?: return@withContext ZarBuildResult(
                    success = false,
                    error = "Cannot access output directory"
                )

            val safeName = titleName.replace(Regex("[^a-zA-Z0-9 _-]"), "_").trim()
            val zarFileName = "$safeName.zar"

            val existingFile = outputDirDoc.findFile(zarFileName)
            existingFile?.delete()

            val zarFile = outputDirDoc.createFile("application/octet-stream", zarFileName)
                ?: return@withContext ZarBuildResult(
                    success = false,
                    error = "Cannot create output file: $zarFileName"
                )

            val discs = discUris.mapIndexed { index, uri ->
                val docFile = DocumentFile.fromSingleUri(context, uri)
                val name = docFile?.name ?: "disc_${index + 1}.iso"
                ZarDiscInfo(
                    number = index + 1,
                    fileName = "disc_${"%03d".format(index + 1)}.iso",
                    label = "Disc ${index + 1}",
                    uri = uri
                )
            }

            val manifest = buildManifest(titleId, titleName, discs)

            val outputStream = context.contentResolver.openOutputStream(zarFile.uri)
                ?: return@withContext ZarBuildResult(
                    success = false,
                    error = "Cannot open output stream"
                )

            BufferedOutputStream(outputStream, BUFFER_SIZE).use { bos ->
                ZipOutputStream(bos).use { zos ->
                    zos.setMethod(ZipOutputStream.STORED)

                    val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
                    val manifestEntry = ZipEntry(ZAR_MANIFEST_NAME)
                    manifestEntry.method = ZipEntry.STORED
                    manifestEntry.size = manifestBytes.size.toLong()
                    manifestEntry.compressedSize = manifestBytes.size.toLong()
                    manifestEntry.crc = computeCrc32(manifestBytes)
                    zos.putNextEntry(manifestEntry)
                    zos.write(manifestBytes)
                    zos.closeEntry()

                    for (disc in discs) {
                        val inputStream = context.contentResolver.openInputStream(disc.uri)
                            ?: continue

                        val discDocFile = DocumentFile.fromSingleUri(context, disc.uri)
                        val discSize = discDocFile?.length() ?: 0L

                        val discEntry = ZipEntry(disc.fileName)
                        if (discSize > 0) {
                            discEntry.method = ZipEntry.STORED
                            discEntry.size = discSize
                            discEntry.compressedSize = discSize
                        } else {
                            discEntry.method = ZipEntry.DEFLATED
                        }

                        zos.putNextEntry(discEntry)
                        BufferedInputStream(inputStream, BUFFER_SIZE).use { bis ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (bis.read(buffer).also { bytesRead = it } != -1) {
                                zos.write(buffer, 0, bytesRead)
                            }
                        }
                        zos.closeEntry()
                        inputStream.close()
                    }
                }
            }

            ZarBuildResult(
                success = true,
                outputPath = zarFileName,
                discCount = discs.size
            )
        } catch (e: Exception) {
            ZarBuildResult(
                success = false,
                error = e.message ?: "Unknown error during ZAR creation"
            )
        }
    }

    private fun buildManifest(
        titleId: String,
        titleName: String,
        discs: List<ZarDiscInfo>
    ): String {
        val json = JSONObject()
        json.put("title_id", titleId)
        json.put("title_name", titleName)
        json.put("disc_count", discs.size)

        val discsArray = JSONArray()
        for (disc in discs) {
            val discJson = JSONObject()
            discJson.put("number", disc.number)
            discJson.put("file", disc.fileName)
            discJson.put("label", disc.label)
            discsArray.put(discJson)
        }
        json.put("discs", discsArray)

        return json.toString(2)
    }

    private fun computeCrc32(data: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value
    }

    suspend fun detectMultiDiscGames(
        context: Context,
        gameDir: Uri
    ): Map<String, List<Uri>> = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, gameDir) ?: return@withContext emptyMap()
        val isoFiles = tree.listFiles().filter { file ->
            val name = file.name ?: return@filter false
            name.lowercase().endsWith(".iso")
        }

        val groups = mutableMapOf<String, MutableList<Uri>>()
        for (file in isoFiles) {
            val name = file.name ?: continue
            val baseName = name
                .removeSuffix(".iso")
                .removeSuffix(".ISO")
                .replace(Regex("[\\s._-]*[Dd]isc[\\s._-]*\\d+.*$"), "")
                .replace(Regex("[\\s._-]*\\(Disc[\\s._-]*\\d+\\).*$"), "")
                .replace(Regex("[\\s._-]*\\[Disc[\\s._-]*\\d+\\].*$"), "")
                .trim()

            groups.getOrPut(baseName.lowercase()) { mutableListOf() }.add(file.uri)
        }

        groups.filter { it.value.size >= 2 }
    }
}
