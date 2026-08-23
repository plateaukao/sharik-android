package info.plateaukao.sharik

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

/** Something that was (or can be) shared. Mirrors Sharik's SharingObject. */
class ShareItem(
    val kind: String,            // "file" | "text"
    val data: String,            // file: content/file URIs joined by |sharik|; text: the text
    val names: List<String>,     // display names of the URIs (content URIs carry no name)
    val date: Long = System.currentTimeMillis(),
) {
    val uris: List<Uri> get() = if (kind == "file") data.split(Protocol.MULTI_DELIMITER).map(Uri::parse) else emptyList()

    /** Same naming rule as Sharik.getSharingName. */
    val name: String
        get() = when (kind) {
            "file" -> (if (names.size > 1) "${names.size}: " else "") + names.joinToString(" ")
            else -> data.trim().replace('\n', ' ').let { if (it.length > 100) it.substring(0, 100) else it }
        }

    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("data", data)
        .put("names", JSONArray(names)).put("date", date)

    companion object {
        fun fromJson(j: JSONObject): ShareItem {
            val names = j.optJSONArray("names")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            return ShareItem(j.getString("kind"), j.getString("data"), names, j.optLong("date"))
        }
    }
}

/** Device name + history in SharedPreferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("sharik", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString("deviceName", null)?.takeIf { it.isNotBlank() } ?: defaultName()
        set(value) { prefs.edit().putString("deviceName", value.trim()).apply() }

    var history: List<ShareItem>
        get() = try {
            val a = JSONArray(prefs.getString("history", "[]"))
            List(a.length()) { ShareItem.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
        private set(value) {
            prefs.edit().putString("history", JSONArray(value.map { it.toJson() }).toString()).apply()
        }

    fun remember(item: ShareItem) {
        history = (listOf(item) + history.filterNot { it.kind == item.kind && it.data == item.data }).take(50)
    }

    fun forget(item: ShareItem) { history = history.filterNot { it.kind == item.kind && it.data == item.data } }

    fun clearHistory() { history = emptyList() }

    private fun defaultName(): String {
        val model = Build.MODEL.takeIf { it.isNotBlank() } ?: "Android"
        val maker = Build.MANUFACTURER.takeIf { it.isNotBlank() && !it.equals("unknown", true) } ?: ""
        return (if (maker.isEmpty() || model.startsWith(maker, true)) model else "$maker $model").replaceFirstChar { it.uppercase() }
    }
}

/** ContentResolver helpers for the URIs other apps hand us. */
object Files {
    fun displayName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "file"
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { val n = c.getString(0); if (!n.isNullOrBlank()) return n }
            }
        } catch (e: Exception) { /* fall through */ }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun size(context: Context, uri: Uri): Long? {
        if (uri.scheme == "file") return uri.path?.let { java.io.File(it).length() }
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (e: Exception) { /* fall through */ }
        return try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l >= 0 } } } catch (e: Exception) { null }
    }

    fun openable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) { false }
}
