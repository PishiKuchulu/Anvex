package com.anvex.core.android.artifacts

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextDirectionHeuristics
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.anvex.core.engine.ArtifactFormat
import com.anvex.core.engine.ArtifactHygienePolicy
import com.anvex.core.engine.ArtifactFileSpec
import com.anvex.core.engine.ArtifactPackager
import com.anvex.core.engine.PackagedArtifact
import com.anvex.core.engine.ProjectFileSystem
import com.anvex.core.engine.PathSanitizer
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** First-class generated-file pipeline. All output stays inside app-private storage. */
class ArtifactManager(private val context: Context) {
    companion object {
        private const val MAX_SOURCE_FILES = 20_000
        private const val MAX_DOCUMENT_BYTES = 20L * 1024 * 1024
        private const val MAX_ARTIFACTS = 100
        private const val MAX_ARTIFACT_STORAGE = 500L * 1024 * 1024
    }

    data class GeneratedArtifact(
        val id: String,
        val projectId: String,
        val file: File,
        val mimeType: String,
        val format: ArtifactFormat,
        val createdAt: Long,
        /** Human-readable notes for the user, e.g. which files had secrets redacted. */
        val notes: List<String> = emptyList(),
    )

    private val root = File(context.filesDir, "build-artifacts").apply { mkdirs() }

    private fun projectDir(projectId: String): File {
        val safe = projectId.trim()
        require(safe.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "Invalid project id." }
        return File(root, safe).apply { mkdirs() }
    }

    private fun artifactFileName(id: String, safeName: String): String = "$id-$safeName"

    suspend fun packageProject(
        projectId: String,
        fs: ProjectFileSystem,
        requestedPaths: List<String> = emptyList(),
        outputName: String = "${projectId}-source.zip",
    ): GeneratedArtifact = withContext(Dispatchers.IO) {
        val paths = if (requestedPaths.isEmpty()) fs.walk() else requestedPaths
        require(paths.size <= MAX_SOURCE_FILES) { "Project contains too many files for packaging." }
        val unique = paths.map { PathSanitizer.sanitize(it) }.distinct().sorted()
        require(unique.any(ArtifactHygienePolicy::include)) { "Nothing safe to package after artifact hygiene filtering." }
        val id = UUID.randomUUID().toString()
        val dir = projectDir(projectId)
        val safeName = outputName.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "source.zip" }
            .let { if (it.endsWith(".zip", true)) it else "$it.zip" }
        val file = File(dir, artifactFileName(id, safeName))
        require(file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath()))
        val redactionNotes = java.util.Collections.synchronizedList(ArrayList<String>())
        atomicWrite(file) { out ->
            ArtifactPackager.zipTo(outputName, unique.asSequence().filter(ArtifactHygienePolicy::include).mapNotNull { path ->
                val bytes = fs.readFileBytes(path) ?: return@mapNotNull null
                val r = ArtifactHygienePolicy.redactSecrets(path, bytes)
                r.findings.forEach { redactionNotes += "$path:${it.line} (${it.key})" }
                ArtifactFileSpec(path, r.bytes)
            }, out)
        }
        enforceQuota(projectId, protectedFile = file)
        require(file.isFile) { "Artifact was removed during quota enforcement." }
        GeneratedArtifact(id, projectId, file, "application/zip", ArtifactFormat.ZIP, System.currentTimeMillis(), redactionNotes.toList())
    }

    suspend fun createDocument(
        projectId: String,
        content: String,
        format: ArtifactFormat,
        outputName: String,
    ): GeneratedArtifact = withContext(Dispatchers.IO) {
        require(format == ArtifactFormat.TXT || format == ArtifactFormat.MD || format == ArtifactFormat.JSON)
        ArtifactHygienePolicy.requireSafeGeneratedText(outputName, content)
        if (format == ArtifactFormat.JSON) {
            require(runCatching { kotlinx.serialization.json.Json.parseToJsonElement(content) }.isSuccess) { "Invalid JSON artifact content." }
        }
        val packaged = ArtifactPackager.text(outputName, content, format)
        persist(projectId, packaged, format)
    }

    suspend fun createPdf(
        projectId: String,
        title: String,
        content: String,
        outputName: String,
    ): GeneratedArtifact = withContext(Dispatchers.IO) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_DOCUMENT_BYTES) { "PDF source text exceeds the 20 MB safety limit." }
        ArtifactHygienePolicy.requireSafeGeneratedText(outputName, content)
        ArtifactHygienePolicy.requireSafeGeneratedText("$outputName:title", title)
        val pdf = PdfDocument()
        try {
            val pageWidth = 595
            val pageHeight = 842
            val margin = 42f
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL) }
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val contentWidth = (pageWidth - margin * 2).toInt()
            val bodyLayout = StaticLayout.Builder.obtain(content, 0, content.length, textPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .build()
            val titleLayout = if (title.isNotBlank()) StaticLayout.Builder.obtain(title.take(160), 0, title.take(160).length, titlePaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .build() else null
            val titleHeight = titleLayout?.height ?: 0
            val pageBodyHeight = (pageHeight - margin * 2 - titleHeight - 18f).toInt().coerceAtLeast(1)
            val lineHeight = textPaint.fontMetrics.run { bottom - top }.coerceAtLeast(1f)
            val linesPerPage = (pageBodyHeight / lineHeight).toInt().coerceAtLeast(1)
            val totalLines = bodyLayout.lineCount
            val pages = ((totalLines + linesPerPage - 1) / linesPerPage).coerceAtLeast(1)

            for (pageIndex in 0 until pages) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
                val canvas = page.canvas
                var top = margin
                if (pageIndex == 0 && titleLayout != null) {
                    canvas.save()
                    canvas.translate(margin, top)
                    titleLayout.draw(canvas)
                    canvas.restore()
                    top += titleHeight + 18f
                }
                val firstLine = pageIndex * linesPerPage
                val lastLine = minOf(totalLines, firstLine + linesPerPage)
                if (firstLine < lastLine) {
                    val topOffset = bodyLayout.getLineTop(firstLine)
                    val bottomOffset = bodyLayout.getLineBottom(lastLine - 1)
                    canvas.save()
                    canvas.clipRect(margin, top, pageWidth - margin, pageHeight - margin)
                    canvas.translate(margin, top - topOffset)
                    bodyLayout.draw(canvas)
                    canvas.restore()
                }
                pdf.finishPage(page)
            }
            val id = UUID.randomUUID().toString()
            val dir = projectDir(projectId)
            val safeName = outputName.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "document.pdf" }
                .let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
            val file = File(dir, artifactFileName(id, safeName))
            require(file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath()))
            atomicWrite(file) { pdf.writeTo(it) }
            enforceQuota(projectId, protectedFile = file)
            require(file.isFile) { "Artifact was removed during quota enforcement." }
            GeneratedArtifact(id, projectId, file, "application/pdf", ArtifactFormat.PDF, System.currentTimeMillis())
        } finally {
            pdf.close()
        }
    }

    fun list(projectId: String): List<GeneratedArtifact> =
        projectDir(projectId).listFiles { it.isFile }
            ?.mapNotNull { file ->
                val mime = when (file.extension.lowercase()) {
                    "zip" -> "application/zip"
                    "pdf" -> "application/pdf"
                    "json" -> "application/json"
                    "md", "txt" -> "text/plain"
                    else -> return@mapNotNull null
                }
                val format = when (file.extension.lowercase()) {
                    "zip" -> ArtifactFormat.ZIP
                    "md" -> ArtifactFormat.MD
                    "pdf" -> ArtifactFormat.PDF
                    "json" -> ArtifactFormat.JSON
                    else -> ArtifactFormat.TXT
                }
                val id = file.name.takeIf { it.length >= 36 }?.substring(0, 36) ?: return@mapNotNull null
                GeneratedArtifact(id, projectId, file, mime, format, file.lastModified())
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    private fun persist(projectId: String, packaged: PackagedArtifact, format: ArtifactFormat): GeneratedArtifact {
        val id = UUID.randomUUID().toString()
        val dir = projectDir(projectId)
        val safeName = packaged.fileName.replace(Regex("[^A-Za-z0-9._-]"), "-").ifBlank { "artifact" }
        val file = File(dir, artifactFileName(id, safeName))
        require(file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath()))
        atomicWrite(file) { it.write(packaged.bytes) }
        enforceQuota(projectId, protectedFile = file)
        require(file.isFile) { "Artifact was removed during quota enforcement." }
        return GeneratedArtifact(id, projectId, file, packaged.mimeType, format, System.currentTimeMillis())
    }



    /** Write through a sibling temp file and atomically publish when possible. */
    private fun atomicWrite(target: File, writer: (FileOutputStream) -> Unit) {
        val temp = File(target.parentFile, ".${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { out ->
                writer(out)
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Same-directory replacement is the safe fallback when the filesystem
                // does not expose an atomic-move primitive. The temporary file was fully
                // written and fsynced before this point.
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (target.isFile) {
                FileOutputStream(target, true).use { it.fd.sync() }
            }
        } catch (t: Throwable) {
            temp.delete()
            // The target is newly allocated for this artifact; never leave a partial file behind.
            target.delete()
            throw t
        }
    }

    /** Returns a short-lived FileProvider URI for an artifact; sharing remains a user-driven action. */
    fun shareUri(artifact: GeneratedArtifact): android.net.Uri {
        require(artifact.file.isFile) { "Artifact no longer exists." }
        val dir = projectDir(artifact.projectId)
        require(artifact.file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath()))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", artifact.file)
    }

    /** Finds a generated file of a project by the exact stored file name (as shown in the chat card). */
    fun find(projectId: String, storedName: String): GeneratedArtifact? =
        list(projectId).firstOrNull { it.file.name == storedName }

    /** The user-facing name: the stored name minus the "<uuid>-" prefix. */
    fun displayName(artifact: GeneratedArtifact): String =
        artifact.file.name.let { if (it.length > 37 && it[36] == '-') it.substring(37) else it }

    /**
     * Copies a generated file into the public Downloads collection so the user gets a real file
     * without any browser detour. API 29+: MediaStore (no permission). API 26-28: app-scoped
     * external Downloads dir, reachable through the share sheet. Returns a human-readable location.
     */
    fun saveToDownloads(artifact: GeneratedArtifact): String {
        require(artifact.file.isFile) { "File no longer exists." }
        val name = displayName(artifact)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, artifact.mimeType)
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Anvex")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create the file in Downloads.")
            try {
                resolver.openOutputStream(uri)?.use { out -> artifact.file.inputStream().use { it.copyTo(out) } }
                    ?: error("Could not open the Downloads file.")
                resolver.update(uri, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            return "Downloads/Anvex/$name"
        }
        val dir = File(context.getExternalFilesDir(null), "Downloads").apply { mkdirs() }
        val target = File(dir, name)
        artifact.file.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    fun delete(artifact: GeneratedArtifact): Boolean {
        val dir = projectDir(artifact.projectId)
        require(artifact.file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath()))
        return artifact.file.delete()
    }

    /** Enforces bounded artifact retention; oldest artifacts are removed first. */
    private fun enforceQuota(projectId: String, protectedFile: File? = null) {
        val dir = projectDir(projectId)
        val protected = protectedFile?.canonicalFile
        val files = dir.listFiles { f -> f.isFile }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        while (files.size > MAX_ARTIFACTS || total > MAX_ARTIFACT_STORAGE) {
            val victim = files.firstOrNull { it.canonicalFile != protected } ?: break
            files.remove(victim)
            val size = victim.length()
            if (victim.delete()) total -= size
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        text.replace("\r\n", "\n").split('\n').forEach { paragraph ->
            if (paragraph.isEmpty()) { out += ""; return@forEach }
            var remaining = paragraph
            while (remaining.isNotEmpty()) {
                var end = remaining.length
                while (end > 1 && paint.measureText(remaining, 0, end) > maxWidth) end--
                if (end < remaining.length) {
                    val space = remaining.lastIndexOf(' ', end - 1)
                    if (space > 0) end = space
                }
                out += remaining.substring(0, end).trimEnd()
                remaining = remaining.substring(end).trimStart()
            }
        }
        return out
    }
}
