package info.plateaukao.sharik

import org.json.JSONObject

/** Wire-level constants, identical to Sharik (lib/conf.dart, lib/const.dart). */
object Protocol {
    const val MULTICAST_GROUP = "239.10.10.100"
    const val BROADCAST = "255.255.255.255"
    const val DISCOVERY_PORT = 54545
    val HTTP_PORTS = intArrayOf(50500, 50050)
    const val MULTI_DELIMITER = "|sharik|"
    const val VERSION = "3.5.0"
    const val BEACON_INTERVAL_MS = 1000L

    fun beaconJson(type: String, name: String, port: Int, deviceName: String): String =
        JSONObject().put("sharik", VERSION).put("type", type).put("name", name)
            .put("os", "android").put("port", port).put("deviceName", deviceName).toString()
}

/** What a Sharik sender is offering to this device. */
data class Offer(
    val ip: String,
    val port: Int,        // sender's HTTP port
    val replyPort: Int,   // UDP source port to answer to
    val type: String,
    val name: String,
    val os: String,
    val deviceName: String?,
    val isBareUrl: Boolean,
) {
    val id: String get() = "$ip:$port:$type:$name"
    val url: String get() = "http://$ip:$port/"
    val sender: String
        get() {
            val who = deviceName ?: "?"
            return if (os.isEmpty() || os == who) who else "$who · $os"
        }

    companion object {
        /** Parses one datagram: JSON beacon, or a bare http… string (EinkBro). */
        fun parse(raw: String, ip: String, port: Int): Offer? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            if (s.startsWith("http://") || s.startsWith("https://")) {
                return Offer(ip, port, port, "text", s, "EinkBro", "EinkBro", true)
            }
            if (!s.startsWith("{")) return null
            return try {
                val j = JSONObject(s)
                Offer(ip, j.getInt("port"), port, j.optString("type", "file"), j.optString("name"),
                    j.optString("os", "?"), j.optString("deviceName").takeIf { it.isNotEmpty() }, false)
            } catch (e: Exception) {
                null
            }
        }
    }
}
