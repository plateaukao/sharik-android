package info.plateaukao.sharik

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/** One sharing session: HTTP server + one-second beacon, like Sharik's SharingService. */
class ShareSession(private val context: Context, val item: ShareItem, private val deviceName: String) {
    interface Listener {
        fun onStarted(port: Int)
        fun onReceiver(line: String)   // "<name> (ip) got it."
        fun onError(message: String)
    }

    @Volatile private var running = true
    private var server: ServerSocket? = null
    private var beacon: MulticastSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    var port = 0; private set

    fun start(listener: Listener) {
        pool.execute {
            val ss = try { openServer() } catch (e: Exception) {
                main.post { listener.onError("Cannot start server: ${e.message}") }; return@execute
            }
            server = ss
            port = ss.localPort
            main.post { listener.onStarted(port) }
            pool.execute { runBeacon(listener) }
            while (running) {
                val client = try { ss.accept() } catch (e: Exception) { break }
                pool.execute { handle(client) }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) {}
        try { beacon?.close() } catch (e: Exception) {}
        pool.shutdownNow()
    }

    private fun openServer(): ServerSocket {
        for (p in Protocol.HTTP_PORTS) {
            try { return ServerSocket(p).apply { reuseAddress = true } } catch (e: Exception) { /* next */ }
        }
        return ServerSocket(0)
    }

    // ---- beacon -------------------------------------------------------------

    private fun runBeacon(listener: Listener) {
        val sock = MulticastSocket().apply { timeToLive = 1; broadcast = true; soTimeout = 300 }
        beacon = sock
        val json = Protocol.beaconJson(item.kind, item.name, port, deviceName).toByteArray()
        val group = InetAddress.getByName(Protocol.MULTICAST_GROUP)
        val bcast = InetAddress.getByName(Protocol.BROADCAST)
        val buf = ByteArray(4096)
        var next = 0L
        while (running) {
            val now = System.currentTimeMillis()
            if (now >= next) {
                next = now + Protocol.BEACON_INTERVAL_MS
                try {
                    // multicast like Sharik, plus limited broadcast for Wi-Fi drivers that drop multicast
                    sock.send(DatagramPacket(json, json.size, group, Protocol.DISCOVERY_PORT))
                    sock.send(DatagramPacket(json, json.size, bcast, Protocol.DISCOVERY_PORT))
                } catch (e: Exception) { if (!running) break }
            }
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val name = String(p.data, 0, p.length).trim()
                val line = "$name (${p.address.hostAddress}) got it."
                main.post { listener.onReceiver(line) }
            } catch (e: SocketTimeoutException) {
            } catch (e: Exception) { if (!running) break }
        }
    }

    // ---- HTTP ---------------------------------------------------------------

    private fun handle(client: Socket) {
        client.use { s ->
            s.soTimeout = 15000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break } // headers
            val target = requestLine.split(' ').getOrNull(1) ?: "/"
            val out = s.getOutputStream()
            try { respond(target, out) } catch (e: Exception) { /* client went away */ }
            out.flush()
        }
    }

    private fun respond(target: String, out: OutputStream) {
        if (target.endsWith("/favicon.ico")) { send(out, "404 Not Found", "text/plain", ByteArray(0)); return }
        if (item.kind == "text") {
            send(out, "200 OK", "text/plain; charset=utf-8", item.data.toByteArray()); return
        }
        val uris = item.uris
        if (uris.size == 1) { sendFile(out, uris[0], item.names.getOrNull(0)); return }
        val q = query(target, "q")
        if (q.isNullOrEmpty()) { send(out, "200 OK", "text/html; charset=utf-8", listingHtml().toByteArray()); return }
        // Same access rule as Sharik: only what was shared. Entries are addressed by index.
        val idx = q.toIntOrNull()
        if (idx == null || idx !in uris.indices) { send(out, "403 Forbidden", "text/plain", "NO ACCESS".toByteArray()); return }
        sendFile(out, uris[idx], item.names.getOrNull(idx))
    }

    private fun query(target: String, key: String): String? {
        val qs = target.substringAfter('?', "")
        for (pair in qs.split('&')) {
            val k = pair.substringBefore('='); if (k != key) continue
            return URLDecoder.decode(pair.substringAfter('=', "").replace("+", "%2B"), "UTF-8")
        }
        return null
    }

    private fun header(status: String, contentType: String, length: Long?, extra: List<String> = emptyList()): ByteArray {
        val sb = StringBuilder("HTTP/1.1 $status\r\nContent-Type: $contentType\r\nConnection: close\r\n")
        if (length != null) sb.append("Content-Length: $length\r\n")
        for (e in extra) sb.append(e).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun send(out: OutputStream, status: String, contentType: String, body: ByteArray) {
        out.write(header(status, contentType, body.size.toLong()))
        out.write(body)
    }

    /** Same headers as Sharik's _pipeFile; streamed from the ContentResolver. */
    private fun sendFile(out: OutputStream, uri: Uri, knownName: String?) {
        val name = knownName ?: Files.displayName(context, uri)
        val input = try { context.contentResolver.openInputStream(uri) } catch (e: Exception) { null }
        if (input == null) { send(out, "404 Not Found", "text/plain", "missing".toByteArray()); return }
        val size = Files.size(context, uri)
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        out.write(header("200 OK", "application/octet-stream; charset=utf-8", size, listOf(
            "Content-Transfer-Encoding: Binary",
            "Content-disposition: attachment; filename=\"$encoded\"")))
        input.use { it.copyTo(out, 256 * 1024) }
    }

    /** Byte-compatible with Sharik's _buildHTML so every Sharik receiver parses it. */
    private fun listingHtml(): String {
        val items = item.names.mapIndexed { i, n ->
            "<li><a href=\"/?q=$i\" class=\"file\"><b>${esc(n)}</b> <small>(${esc(n)})</small></li></a>"
        }.joinToString("\n")
        return """<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <title>Sharik</title>
  </head>
  <body>
    <button onClick="downloadAll()">Download all</button>
    <ul style="line-height:200%">
      $items
    </ul>
    <script>
    function downloadAll(){
      var arr = [].slice.call(document.getElementsByClassName('file'));
      arr.forEach(function(item,index){
        setTimeout(function(){
          var node = document.createElement("iframe");
          node.setAttribute("style", "display: none;");
          node.setAttribute("src", item.href);
          document.body.appendChild(node);
          setTimeout(function(){ node.remove(); }, 1000);
        }, index * 100);
      });
    }
    </script>
  </body>
</html>
"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
