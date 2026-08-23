package info.plateaukao.sharik

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Listens for Sharik beacons, replies with the device name, downloads on request. */
class ReceiveSession(private val context: Context, private val deviceName: String) {
    class Result(val title: String, val detail: String, val text: String? = null)

    interface Listener {
        fun onStatus(text: String)
        fun onOffer(offer: Offer)
        fun onResult(result: Result)
        fun onBusy(busy: Boolean)
    }

    @Volatile private var running = true
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val seen = HashSet<String>()

    fun start(listener: Listener) {
        // Wi-Fi drivers filter multicast unless someone holds a MulticastLock.
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("sharik").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { /* best effort */ }
        pool.execute {
            val sock = try {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                    soTimeout = 500
                }
            } catch (e: Exception) {
                main.post { listener.onStatus("Cannot listen on UDP ${Protocol.DISCOVERY_PORT}: ${e.message}") }; return@execute
            }
            socket = sock
            val group = InetAddress.getByName(Protocol.MULTICAST_GROUP)
            var joined = 0
            for (itf in ipv4Interfaces()) {
                try { sock.joinGroup(InetSocketAddress(group, Protocol.DISCOVERY_PORT), itf); joined++ } catch (e: Exception) {}
            }
            if (joined == 0) try { @Suppress("DEPRECATION") sock.joinGroup(group) } catch (e: Exception) {}
            main.post { listener.onStatus("Waiting for a Sharik sender… (this device is \"$deviceName\")") }
            val buf = ByteArray(65536)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                val offer = Offer.parse(String(p.data, 0, p.length), p.address.hostAddress ?: continue, p.port) ?: continue
                if (!seen.add(offer.id)) continue
                // tell the sender who got it, once (shown in Sharik as "<name> (ip) got it.")
                try {
                    val reply = deviceName.toByteArray()
                    sock.send(DatagramPacket(reply, reply.size, p.address, offer.replyPort))
                } catch (e: Exception) {}
                main.post { listener.onOffer(offer) }
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) {}
        try { lock?.release() } catch (e: Exception) {}
        pool.shutdownNow()
    }

    // ---- downloading ----------------------------------------------------------

    fun receive(offer: Offer, listener: Listener) {
        if (offer.isBareUrl) { listener.onResult(Result("Link from ${offer.sender}", offer.name, offer.name)); return }
        listener.onBusy(true)
        listener.onStatus("Receiving ${offer.name} from ${offer.sender}…")
        Thread {
            val r = try { fetch(offer.url, offer.name, "") } catch (e: Exception) { Result("Failed: ${offer.name}", e.message ?: e.toString()) }
            main.post { listener.onResult(r); listener.onStatus("Done."); listener.onBusy(false) }
        }.start()
    }

    /** GET url; text -> Result.text, listing -> recurse, otherwise save into Downloads/[subdir]. */
    private fun fetch(url: String, fallbackName: String, subdir: String, depth: Int = 0): Result {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw Exception("HTTP $code")
            val ctype = (conn.contentType ?: "").lowercase()
            if (ctype.contains("text/plain")) {
                val text = conn.inputStream.bufferedReader().readText()
                return Result("Text received", text.take(200), text)
            }
            if (ctype.contains("text/html")) {
                val html = conn.inputStream.bufferedReader().readText()
                return fetchListing(html, url, subdir, depth)
            }
            val name = filenameFromDisposition(conn.getHeaderField("Content-Disposition")) ?: fallbackName
            val saved = conn.inputStream.use { input -> Downloads.save(context, subdir, safe(name), conn.contentLengthLong.takeIf { it >= 0 }, input) }
            return Result("Saved $saved", saved, null)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchListing(html: String, base: String, subdir: String, depth: Int): Result {
        if (depth >= 8) return Result("Folder too deep", base)
        val re = Regex("<a\\s+href=\"/\\?q=([^\"]*)\"\\s+class=\"(\\w+)\"")
        val root = URL(base).let { u -> "${u.protocol}://${u.host}:${u.port}" }
        val saved = ArrayList<String>(); val failed = ArrayList<String>()
        for (m in re.findAll(html)) {
            val path = m.groupValues[1]; val isDir = m.groupValues[2] == "folder"
            val name = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
            val sub = "$root/?q=" + URLEncoder.encode(path, "UTF-8").replace("+", "%20")
            try {
                val target = if (isDir) (if (subdir.isEmpty()) safe(name) else "$subdir/${safe(name)}") else subdir
                val r = fetch(sub, name, target, depth + 1)
                if (r.text == null) saved.add(r.detail)
            } catch (e: Exception) { failed.add("$name: ${e.message}") }
        }
        var detail = saved.joinToString("\n")
        if (failed.isNotEmpty()) detail += "\nFailed:\n" + failed.joinToString("\n")
        return Result("Saved ${saved.size} file(s)", detail)
    }

    private fun filenameFromDisposition(d: String?): String? {
        if (d == null) return null
        val m = Regex("filename\\*?=\"([^\"]+)\"").find(d) ?: Regex("filename\\*?=([^;]+)").find(d) ?: return null
        var name = m.groupValues[1].trim().removePrefix("UTF-8''")
        name = try { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { name }
        return name.ifBlank { null }
    }

    private fun safe(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_").trim().let { if (it.isEmpty() || it == "." || it == "..") "sharik-file" else it }

    companion object {
        fun ipv4Interfaces(): List<NetworkInterface> = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { itf ->
                itf.isUp && !itf.isLoopback && itf.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLinkLocalAddress }
            }
        } catch (e: Exception) { emptyList() }

        fun localIPv4(): List<String> = ipv4Interfaces().flatMap { itf ->
            itf.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().filter { !it.isLinkLocalAddress }.map { it.hostAddress ?: "" }
        }.filter { it.isNotEmpty() }
    }
}

/** Writes into the public Downloads folder: MediaStore on 10+, plain files on 9. Returns the display path. */
object Downloads {
    fun save(context: Context, subdir: String, name: String, size: Long?, input: java.io.InputStream): String {
        val relDir = "Download" + (if (subdir.isEmpty()) "" else "/$subdir")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val unique = uniqueNameQ(context, relDir, name)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, unique)
                put(MediaStore.Downloads.RELATIVE_PATH, "$relDir/")
                put(MediaStore.Downloads.IS_PENDING, 1)
                if (size != null) put(MediaStore.Downloads.SIZE, size)
            }
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { out: OutputStream -> input.copyTo(out, 256 * 1024) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); resolver.update(uri, values, null, null)
            } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
            return "$relDir/$unique"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subdir)
            dir.mkdirs()
            val file = uniqueFile(dir, name)
            file.outputStream().use { input.copyTo(it, 256 * 1024) }
            return file.absolutePath.substringAfter("/storage/emulated/0/")
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name); if (!f.exists()) return f
        val stem = name.substringBeforeLast('.', name); val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        for (i in 1..999) { f = File(dir, "$stem ($i)$ext"); if (!f.exists()) return f }
        return f
    }

    private fun uniqueNameQ(context: Context, relDir: String, name: String): String {
        val existing = HashSet<String>()
        try {
            context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf("$relDir/"), null)?.use { c -> while (c.moveToNext()) existing.add(c.getString(0)) }
        } catch (e: Exception) {}
        if (name !in existing) return name
        val stem = name.substringBeforeLast('.', name); val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        for (i in 1..999) { val n = "$stem ($i)$ext"; if (n !in existing) return n }
        return name
    }
}
