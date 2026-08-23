package info.plateaukao.sharik

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var store: Store
    private lateinit var historyView: ListView
    private lateinit var emptyView: TextView
    private lateinit var clearView: TextView
    private lateinit var deviceView: TextView
    private var history: List<ShareItem> = emptyList()
    private var pendingReceive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        setContentView(R.layout.activity_main)
        historyView = findViewById(R.id.history)
        emptyView = findViewById(R.id.empty)
        clearView = findViewById(R.id.clear_history)
        deviceView = findViewById(R.id.device_name)

        findViewById<View>(R.id.share_file).setOnClickListener { pickFiles() }
        findViewById<View>(R.id.share_text).setOnClickListener { askText() }
        findViewById<View>(R.id.receive).setOnClickListener { startReceiving() }
        deviceView.setOnClickListener { editDeviceName() }
        clearView.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Clear history?")
                .setMessage("Shared files themselves are not touched.")
                .setPositiveButton("Clear") { _, _ -> store.clearHistory(); refresh() }
                .setNegativeButton("Cancel", null).show().styled()
        }
        historyView.adapter = HistoryAdapter()
        historyView.setOnItemClickListener { _, _, pos, _ -> share(history[pos]) }
        historyView.setOnItemLongClickListener { _, _, pos, _ -> itemMenu(history[pos]); true }
        refresh()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun refresh() {
        history = store.history
        (historyView.adapter as BaseAdapter).notifyDataSetChanged()
        historyView.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        emptyView.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        clearView.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        deviceView.text = "Device: ${store.deviceName}"
    }

    // ---- incoming shares from other apps --------------------------------------

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) shareUris(listOf(uri))
                else intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { share(ShareItem("text", it, emptyList())) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) shareUris(uris)
            }
        }
        intent.action = null // don't re-share on rotation
    }

    private fun shareUris(uris: List<Uri>) {
        for (u in uris) try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { /* temporary grant — history entry may expire */ }
        val names = uris.map { Files.displayName(this, it) }
        share(ShareItem("file", uris.joinToString(Protocol.MULTI_DELIMITER) { it.toString() }, names))
    }

    // ---- sharing ----------------------------------------------------------------

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        data.data?.let { if (uris.isEmpty()) uris.add(it) }
        if (uris.isNotEmpty()) shareUris(uris)
    }

    private fun askText() {
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; gravity = android.view.Gravity.TOP
            hint = "Text or link to share"
        }
        AlertDialog.Builder(this).setTitle("Share text").setView(pad(edit))
            .setPositiveButton("Share") { _, _ ->
                val t = edit.text.toString()
                if (t.isNotBlank()) share(ShareItem("text", t, emptyList()))
            }
            .setNeutralButton("Paste") { _, _ -> }
            .setNegativeButton("Cancel", null)
            .show().styled().also { d ->
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.let { edit.setText(it) }
                }
            }
    }

    private fun share(item: ShareItem) {
        if (item.kind == "file" && !item.uris.all { Files.openable(this, it) }) {
            AlertDialog.Builder(this).setTitle("File no longer available")
                .setMessage("The app that shared it only granted temporary access. Share it again from that app, or pick it with Share file….")
                .setPositiveButton("Remove from history") { _, _ -> store.forget(item); refresh() }
                .setNegativeButton("Close", null).show().styled()
            return
        }
        store.remember(item); refresh()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_share, null)
        val urls = view.findViewById<TextView>(R.id.urls)
        val receivers = view.findViewById<TextView>(R.id.receivers)
        urls.text = "Starting…"
        val session = ShareSession(this, item, store.deviceName)
        val dialog = AlertDialog.Builder(this).setTitle(item.name).setView(view)
            .setNegativeButton("Stop sharing", null).create()
        dialog.setOnDismissListener {
            session.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.show(); dialog.styled()
        session.start(object : ShareSession.Listener {
            override fun onStarted(port: Int) {
                val ips = ReceiveSession.localIPv4()
                urls.text = if (ips.isEmpty()) "No Wi-Fi address — is Wi-Fi on?" else ips.joinToString("\n") { "http://$it:$port/" }
            }
            override fun onReceiver(line: String) { receivers.append(if (receivers.text.isEmpty()) line else "\n$line") }
            override fun onError(message: String) { urls.text = message }
        })
    }

    // ---- receiving --------------------------------------------------------------

    private fun startReceiving() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingReceive = true
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_receive, null)
        val status = view.findViewById<TextView>(R.id.status)
        val results = view.findViewById<TextView>(R.id.results)
        val list = view.findViewById<ListView>(R.id.offers)
        val offers = ArrayList<Offer>()
        val labels = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        list.adapter = labels
        val session = ReceiveSession(this, store.deviceName)
        var busy = false
        val listener = object : ReceiveSession.Listener {
            override fun onStatus(text: String) { status.text = text }
            override fun onOffer(offer: Offer) {
                offers.add(offer); labels.add("${offer.name}\n${offer.sender} • ${offer.ip}:${offer.port}")
            }
            override fun onBusy(b: Boolean) { busy = b }
            override fun onResult(result: ReceiveSession.Result) {
                if (result.text != null) showText(result.title, result.text)
                else results.append((if (results.text.isEmpty()) "" else "\n\n") + "${result.title}\n${result.detail}")
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> if (!busy) session.receive(offers[pos], listener) }
        val dialog = AlertDialog.Builder(this).setTitle("Receive").setView(view)
            .setNegativeButton("Close", null).create()
        dialog.setOnDismissListener {
            session.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.show(); dialog.styled()
        status.text = "Starting…"
        session.start(listener)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && pendingReceive) {
            pendingReceive = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startReceiving()
            else Toast.makeText(this, "Storage permission is needed to save into Downloads.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showText(title: String, text: String) {
        val tv = TextView(this).apply { setText(text); setTextIsSelectable(true); textSize = 15f }
        AlertDialog.Builder(this).setTitle(title).setView(pad(tv))
            .setPositiveButton("Copy") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Sharik", text))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null).show().styled()
    }

    // ---- small things -------------------------------------------------------------

    private fun editDeviceName() {
        val edit = EditText(this).apply { setText(store.deviceName); setSelectAllOnFocus(true); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Device name shown to senders").setView(pad(edit))
            .setPositiveButton("Save") { _, _ -> edit.text.toString().trim().takeIf { it.isNotEmpty() }?.let { store.deviceName = it }; refresh() }
            .setNegativeButton("Cancel", null).show().styled()
    }

    private fun itemMenu(item: ShareItem) {
        AlertDialog.Builder(this).setTitle(item.name)
            .setItems(arrayOf("Share", "Remove from history")) { _, which ->
                if (which == 0) share(item) else { store.forget(item); refresh() }
            }.show().styled()
    }

    /** OEM themes (Onyx) ignore dialog button styles and can render the text invisible; force it. */
    private fun AlertDialog.styled(): AlertDialog {
        val brand = getColor(R.color.brand)
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            getButton(which)?.apply {
                setTextColor(brand)
                setBackgroundResource(android.R.color.transparent)
                isAllCaps = false
                stateListAnimator = null
            }
        }
        return this
    }

    private fun pad(v: View): View {
        val p = (20 * resources.displayMetrics.density).toInt()
        return android.widget.FrameLayout(this).apply { setPadding(p, p / 2, p, 0); addView(v) }
    }

    private inner class HistoryAdapter : BaseAdapter() {
        override fun getCount() = history.size
        override fun getItem(position: Int) = history[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_history, parent, false)
            val item = history[position]
            v.findViewById<TextView>(R.id.title).text = item.name
            v.findViewById<TextView>(R.id.subtitle).text =
                if (item.kind == "text") DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.date))
                else item.names.joinToString(", ")
            return v
        }
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_STORAGE = 2
    }
}
