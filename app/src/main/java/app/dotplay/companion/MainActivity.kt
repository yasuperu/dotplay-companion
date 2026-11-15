package app.dotplay.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Display
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Settings, tracked apps, Cocoon and sync on the main screen.
 * On dual-screen handhelds (AYN Thor) the second screen shows a "this week" dashboard.
 */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val PICK_FILE = 42
    private val PICK_COCOON_DIR = 43

    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var cocoonStatus: TextView
    private lateinit var log: TextView
    private var pendingCocoon: List<PlayItem> = emptyList()
    private var dashboard: DashboardPresentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        window.statusBarColor = Ui.BG_TOP
        window.navigationBarColor = Ui.BG_BOTTOM
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(40))
        }
        setContentView(ScrollView(this).apply { background = ui.wallpaper(); addView(root) })
        build()
        SyncWorker.schedule(this)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
        showDashboard()
        // sync on open, unless that just happened
        if (prefs.configured && System.currentTimeMillis() - prefs.lastSyncAt > 15 * 60_000L) SyncWorker.runNow(this)
    }

    override fun onPause() {
        super.onPause()
        dashboard?.dismiss()
        dashboard = null
    }

    // ---------- second screen ----------

    private fun showDashboard() {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val second: Display = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull() ?: return
        if (dashboard?.display?.displayId != second.displayId) {
            dashboard?.dismiss()
            dashboard = runCatching { DashboardPresentation(this, second).also { it.show() } }.getOrNull()
        }
        refreshDashboard()
    }

    private fun refreshDashboard() {
        if (dashboard == null) return
        io.execute {
            val items = runCatching { SyncWorker.collectAll(this, prefs) }.getOrDefault(emptyList())
            runOnUiThread { dashboard?.render(items, prefs.lastSync) }
        }
    }

    // ---------- main screen ----------

    private fun build() {
        root.addView(ui.label("● DOTPLAY", Ui.ACCENT))
        root.addView(ui.title("Companion"))
        root.addView(ui.text("Sends playtime from this device to your Dotplay profile.", 14f, Ui.DIM))
        ui.space(root, 18)

        // --- connection ---
        val card1 = ui.card(root)
        card1.addView(ui.label("Server URL"))
        val url = ui.input(prefs.serverUrl, "https://games.example.nl/api")
        card1.addView(url)
        card1.addView(ui.label("Device key  ·  Dotplay → Me → Android devices"))
        val key = ui.input(prefs.deviceKey, "paste key", password = true)
        card1.addView(key)
        card1.addView(ui.button("Save") {
            prefs.serverUrl = url.text.toString()
            prefs.deviceKey = key.text.toString()
            say("Saved")
            refreshStatus()
        })

        // --- Cocoon live tracking ---
        val card2 = ui.card(root)
        card2.addView(ui.label("Cocoon"))
        card2.addView(ui.text(
            "Choose the Cocoon folder on internal storage once. Dotplay reads which game Cocoon started " +
                "and counts the time the emulator was on screen.", 14f, Ui.DIM))
        cocoonStatus = ui.text("", 14f)
        card2.addView(cocoonStatus)
        card2.addView(ui.button("Choose Cocoon folder", ghost = true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ACocoon"))
            }, PICK_COCOON_DIR)
        })
        card2.addView(ui.button("Import Cocoon play-history backup", ghost = true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, PICK_FILE)
        })

        // --- usage access / Android games ---
        val card3 = ui.card(root)
        card3.addView(ui.label("Android"))
        status = ui.text("", 14f)
        card3.addView(status)
        card3.addView(ui.button("Grant usage access", ghost = true) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        card3.addView(ui.button("Choose Android games to track", ghost = true) { chooseApps() })
        card3.addView(ui.button("Sync now", accent = true) { syncNow() })

        // --- apps that are really a game (emulators Cocoon can't read, recomps, ports) ---
        val card4 = ui.card(root)
        card4.addView(ui.label("Apps as games"))
        card4.addView(ui.text(
            "For apps Cocoon can't identify: a Switch 2 emulator running one game, PC recomps like " +
                "Ship of Harkinian, Android ports. Their time counts as the game you choose. Switch a mapping off " +
                "to stop it without deleting it.", 14f, Ui.DIM))
        mappingList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card4.addView(mappingList)
        card4.addView(ui.button("Add app as game", ghost = true) { pickAppForMapping() })
        refreshMappings()

        ui.space(root, 12)
        log = ui.text("", 13f, Ui.DIM)
        root.addView(log)
        refreshStatus()
    }

    private fun refreshStatus() {
        val perm = if (UsageTracker.hasPermission(this)) "granted" else "not granted yet (needed for Cocoon too)"
        status.text = "Usage access: $perm\nAndroid games tracked: ${prefs.trackedApps.size}\nLast sync: ${prefs.lastSync}\nAuto sync: every hour and when you open this app" +
            if (!prefs.configured) "\n\nSet the server URL and device key first." else ""
        cocoonStatus.text = if (prefs.cocoonTree != null) "Cocoon folder: connected" else "Cocoon folder: not chosen"
    }

    // ---------- app -> game mappings ----------

    private lateinit var mappingList: LinearLayout

    private fun refreshMappings() {
        mappingList.removeAllViews()
        val all = GameMappings.all(this)
        if (all.isEmpty()) { mappingList.addView(ui.text("No mappings yet.", 14f, Ui.DIM)); return }
        all.forEach { m ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                setOnClickListener { editMapping(m.pkg, m.appLabel, m) }
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(ui.text(m.title, 15f, if (m.enabled) Ui.TEXT else Ui.DIM, bold = true))
            texts.addView(ui.text("${m.appLabel} → ${m.platform.ifBlank { m.platformType }}", 13f, Ui.DIM))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(android.widget.Switch(this).apply {
                isChecked = m.enabled
                setOnCheckedChangeListener { _, on -> GameMappings.put(this@MainActivity, m.copy(enabled = on)); refreshMappings() }
            })
            mappingList.addView(row)
        }
    }

    private fun pickAppForMapping() {
        io.execute {
            val apps = UsageTracker.launchableApps(this)
            runOnUiThread {
                AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Which app is really a game?")
                    .setItems(apps.map { it.label }.toTypedArray()) { _, i -> editMapping(apps[i].pkg, apps[i].label, null) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private val TYPES = listOf("emulated" to "Emulated", "pc" to "PC / recomp", "console" to "Console", "mobile" to "Android", "steam" to "Steam")

    private fun editMapping(pkg: String, appLabel: String, existing: GameMapping?) {
        val guess = existing?.let { Triple(it.title, it.platformType, it.platform) } ?: GameMappings.suggest(appLabel)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        box.addView(ui.label("Game (official name)"))
        val title = ui.input(guess?.first ?: "", "e.g. Pokémon Pokopia")
        box.addView(title)
        box.addView(ui.label("Platform type"))
        val type = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, TYPES.map { it.second })
            setSelection(TYPES.indexOfFirst { it.first == (guess?.second ?: "emulated") }.coerceAtLeast(0))
        }
        box.addView(type)
        box.addView(ui.label("System"))
        val platform = ui.input(guess?.third ?: "", "e.g. Switch 2, N64, PC")
        box.addView(platform)
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle(appLabel)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = title.text.toString().trim()
                if (t.isEmpty()) { say("A game name is needed"); return@setPositiveButton }
                GameMappings.put(this, GameMapping(pkg, appLabel, t, TYPES[type.selectedItemPosition].first,
                    platform.text.toString().trim(), existing?.enabled ?: true))
                refreshMappings()
                say("$appLabel now counts as $t")
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dlg.setNeutralButton("Delete") { _, _ -> GameMappings.remove(this, pkg); refreshMappings() }
        dlg.show()
    }

    private fun chooseApps() {
        io.execute {
            val apps = UsageTracker.launchableApps(this)
            runOnUiThread {
                val selected = prefs.trackedApps.toMutableSet()
                val labels = apps.map { (if (it.isGame) "🎮 " else "") + it.label }.toTypedArray()
                val checked = apps.map { it.pkg in selected }.toBooleanArray()
                AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Track as games (not emulators: Cocoon covers those)")
                    .setMultiChoiceItems(labels, checked) { _, i, on ->
                        if (on) selected += apps[i].pkg else selected -= apps[i].pkg
                    }
                    .setPositiveButton("Save") { _, _ -> prefs.trackedApps = selected; refreshStatus() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun syncNow() {
        say("Syncing…")
        io.execute {
            try {
                val items = SyncWorker.collectAll(this, prefs)
                val msg = if (items.isEmpty()) "No new playtime to send" else Api.sync(prefs, deviceName(), items)
                prefs.lastSync = DateFormat.getDateTimeInstance().format(Date())
                runOnUiThread {
                    say(msg + items.joinToString("") { "\n· ${it.title} [${it.platform}]: ${it.days.values.sum()} min" })
                    refreshStatus(); refreshDashboard()
                }
            } catch (e: Exception) {
                runOnUiThread { say("Sync failed: ${e.message}") }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            PICK_FILE -> importCocoon(uri)
            PICK_COCOON_DIR -> {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                prefs.cocoonTree = uri.toString()
                refreshStatus()
                io.execute {
                    val text = runCatching { CocoonLog.readLog(this, uri) }.getOrNull()
                    val n = text?.let { CocoonLog.parse(it).size } ?: -1
                    runOnUiThread {
                        say(if (n < 0) "launch_debug.log not found in that folder. Pick the folder named Cocoon." else "Cocoon connected · $n game launches found")
                        refreshDashboard()
                    }
                }
            }
        }
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        uri?.let { importCocoon(it) }
    }

    private fun importCocoon(uri: Uri) {
        say("Reading backup…")
        io.execute {
            val r = try { CocoonImport.read(this, uri) } catch (e: Exception) { CocoonImport.Result(emptyList(), "Error: ${e.message}") }
            runOnUiThread {
                pendingCocoon = r.items
                if (r.items.isEmpty()) {
                    say("No playtime recognised. ${r.note}\nPlease share a sample of this backup in a GitHub issue so the format can be supported.")
                    return@runOnUiThread
                }
                val preview = r.items.take(30).joinToString("\n") { "· ${it.title} [${it.platform}] ${(it.totalMinutes ?: 0) / 60}h" }
                AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle("Found ${r.items.size} games")
                    .setMessage(preview + if (r.items.size > 30) "\n…" else "")
                    .setPositiveButton("Upload") { _, _ -> uploadCocoon() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun uploadCocoon() {
        say("Uploading ${pendingCocoon.size} games…")
        io.execute {
            try {
                val msg = pendingCocoon.chunked(200).joinToString { Api.sync(prefs, deviceName(), it) }
                runOnUiThread { say("Cocoon import done. $msg") }
            } catch (e: Exception) {
                runOnUiThread { say("Upload failed: ${e.message}") }
            }
        }
    }

    private fun say(s: String) { log.text = s }
}
