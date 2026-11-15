package com.kitsumed.shizucallrecorder.data.archive

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Documents
import androidx.documentfile.provider.DocumentFile
import com.kitsumed.shizucallrecorder.data.AppPreferences
import com.kitsumed.shizucallrecorder.data.call.CallDirection
import com.kitsumed.shizucallrecorder.data.call.EnrichedCallData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

object RecordingArchive {
    private val _revision = MutableStateFlow(0)
    val revision = _revision.asStateFlow()
    private val _capture = MutableStateFlow(Capture.Idle)
    private val _activeUri = MutableStateFlow<Uri?>(null)
    val activeUri = _activeUri.asStateFlow()
    val capture = _capture.asStateFlow()
    private val audioExtensions = setOf("ogg", "opus", "webm", "m4a", "aac", "amr", "wav", "mp3", "flac", "mp4")
    private const val FAILURE_LIMIT = 30

    enum class Capture { Idle, Starting, Recording, Paused }

    data class Recording(
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val date: Long,
        val direction: CallDirection?,
        val contact: String,
        val number: String,
        val starred: Boolean,
    ) {
        val hasContact get() = contact.isNotBlank()
        val title get() = contact.ifBlank { number.ifBlank { fileName } }
    }

    data class Failure(val date: Long, val reason: String)

    private fun prefs(context: Context) = context.getSharedPreferences("recording_archive", Context.MODE_PRIVATE)
    private fun metadataKey(uri: Uri) = "metadata_$uri"
    private fun favoriteKey(uri: Uri) = "favorite_$uri"

    fun setCapture(capture: Capture, uri: Uri? = null) {
        _activeUri.value = uri
        _capture.value = capture
    }

    @Synchronized
    fun saved(context: Context, file: DocumentFile, metadata: EnrichedCallData) {
        val item = JSONObject().apply {
            put("saved", System.currentTimeMillis())
            put("direction", metadata.direction.name)
            put("contact", metadata.callerName.orEmpty())
            put("number", metadata.getBestNumber())
            put("modified", file.lastModified())
            put("size", file.length())
        }
        prefs(context).edit().putString(metadataKey(file.uri), item.toString()).apply()
        _revision.update { it + 1 }
    }

    @Synchronized
    fun failed(context: Context, reason: String) {
        val items = JSONArray()
        items.put(JSONObject().put("date", System.currentTimeMillis()).put("reason", reason))
        for (failure in failures(context).take(FAILURE_LIMIT - 1)) {
            items.put(JSONObject().put("date", failure.date).put("reason", failure.reason))
        }
        prefs(context).edit().putString("failures", items.toString()).apply()
        _revision.update { it + 1 }
    }

    fun failures(context: Context): List<Failure> {
        val items = runCatching { JSONArray(prefs(context).getString("failures", "[]")) }.getOrNull()
            ?: return emptyList()
        return List(items.length()) { items.optJSONObject(it) }.mapNotNull {
            it?.let { entry -> Failure(entry.optLong("date"), entry.optString("reason")) }
        }
    }

    fun load(context: Context): List<Recording> {
        val treeUri = AppPreferences(context).getRecordingFolderUri() ?: return emptyList()
        val folderId = Documents.getTreeDocumentId(treeUri)
        val store = prefs(context)
        val visited = mutableSetOf<String>()
        val folders = ArrayDeque<String>().apply { add(folderId) }
        val recordings = mutableListOf<Recording>()
        val projection = arrayOf(
            Documents.Document.COLUMN_DOCUMENT_ID,
            Documents.Document.COLUMN_DISPLAY_NAME,
            Documents.Document.COLUMN_MIME_TYPE,
            Documents.Document.COLUMN_LAST_MODIFIED,
            Documents.Document.COLUMN_SIZE,
        )
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            if (!visited.add(folder)) continue
            val childrenUri = Documents.buildChildDocumentsUriUsingTree(treeUri, folder)
            val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
                ?: error("Recording directory unavailable")
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mimeType = it.getString(2).orEmpty()
                    if (mimeType == Documents.Document.MIME_TYPE_DIR) {
                        folders.add(id)
                        continue
                    }
                    if (mimeType.startsWith("video/") || (!mimeType.startsWith("audio/") &&
                            name.substringAfterLast('.', "").lowercase() !in audioExtensions) ||
                        (!it.isNull(4) && it.getLong(4) <= 0)
                    ) continue

                    val uri = Documents.buildDocumentUriUsingTree(treeUri, id)
                    val metadata = parse(store.getString(metadataKey(uri), null))
                    val direction = metadata?.optString("direction")?.let { value ->
                        CallDirection.entries.firstOrNull { it.name == value }
                    } ?: CallDirection.entries.firstOrNull { name.contains("_${it.name.lowercase()}", true) }
                    recordings += Recording(
                        uri, name, mimeType, metadata?.optLong("saved") ?: it.getLong(3),
                        direction, metadata?.optString("contact").orEmpty(),
                        metadata?.optString("number").orEmpty(), store.getBoolean(favoriteKey(uri), false)
                    )
                }
            }
        }
        return recordings.sortedByDescending { it.date }
    }

    @Synchronized
    fun setFavorite(context: Context, uri: Uri, starred: Boolean) {
        prefs(context).edit().putBoolean(favoriteKey(uri), starred).apply()
        _revision.update { it + 1 }
    }

    @Synchronized
    fun delete(context: Context, uri: Uri): Boolean {
        if (DocumentFile.fromSingleUri(context, uri)?.delete() != true) return false
        prefs(context).edit().remove(metadataKey(uri)).remove(favoriteKey(uri)).apply()
        _revision.update { it + 1 }
        return true
    }

    fun retentionDays(context: Context) = prefs(context).getInt("retention_days", 0)

    fun setRetention(context: Context, days: Int) {
        require(days in setOf(0, 30, 90))
        prefs(context).edit().putInt("retention_days", days).apply()
        ArchiveMaintenance.schedule(context)
    }

    fun remindOnBoot(context: Context) = prefs(context).getBoolean("remind_on_boot", true)

    fun setRemindOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("remind_on_boot", enabled).apply()
        if (!enabled) ArchiveMaintenance.dismissReminder(context)
    }

    fun prune(context: Context, shouldContinue: () -> Boolean) {
        val store = prefs(context)
        val days = retentionDays(context)
        if (days == 0) return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        val tree = AppPreferences(context).getRecordingFolderUri() ?: return
        for ((key, value) in store.all) {
            if (!shouldContinue() || retentionDays(context) != days) return
            if (!key.startsWith("metadata_") || value !is String) continue
            val uri = Uri.parse(key.removePrefix("metadata_"))
            if (uri.authority != tree.authority ||
                runCatching { Documents.getTreeDocumentId(uri) }.getOrNull() != Documents.getTreeDocumentId(tree)
            ) continue
            val item = parse(value) ?: continue
            if (item.optLong("saved") !in 1 until cutoff || item.optLong("modified") <= 0) continue
            runCatching {
                synchronized(this) {
                    if (retentionDays(context) != days || store.getBoolean(favoriteKey(uri), false)) {
                        return@synchronized
                    }
                    val document = DocumentFile.fromSingleUri(context, uri) ?: return@synchronized
                    if (document.lastModified() == item.optLong("modified") &&
                        document.length() == item.optLong("size") && item.optLong("size") > 0
                    ) delete(context, uri)
                }
            }
        }
    }

    private fun parse(value: String?) = value?.let { runCatching { JSONObject(it) }.getOrNull() }
}
