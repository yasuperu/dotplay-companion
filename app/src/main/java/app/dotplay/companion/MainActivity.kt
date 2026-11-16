package app.dotplay.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * First run: a short setup (link your account by code → usage access → Cocoon folder → extras → done).
 * After that: a home screen with status, sync and settings.
 * On dual-screen handhelds (AYN Thor) the second screen shows a "this week" dashboard.
 */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val PICK_FILE = 42
    private val PICK_COCOON_DIR = 43

    private enum class Screen { WELCOME, LINK, USAGE, COCOON, EXTRAS, DONE, HOME }
    private val SETUP_STEPS = 5

    private lateinit var prefs: Prefs
    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private lateinit var scroll: ScrollView
    private var screen = Screen.WELCOME
    private var message: TextView? = null
    private var pendingCocoon: List<PlayItem> = emptyList()
    private var dashboard: DashboardPresentation? = null

    // pairing state
    private var pair: Api.PairStart? = null
    private var pairStartedAt = 0L
    private val poll = object : Runnable {
        override fun run() { checkPairing() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        window.statusBarColor = Ui.BG_TOP
        window.navigationBarColor = Ui.BG_BOTTOM
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(22), ui.dp(30), ui.dp(22), ui.dp(48))
        }
        scroll = ScrollView(this).apply { background = ui.wallpaper(); addView(root) }
        setContentView(scroll)
        show(if (prefs.setupDone && prefs.configured) Screen.HOME else if (prefs.configured) Screen.USAGE else Screen.WELCOME)
        SyncWorker.schedule(this)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        show(screen)   // re-check ✓ marks after returning from Android settings or the folder picker
        showDashboard()
        if (prefs.configured && prefs.setupDone && System.currentTimeMillis() - prefs.lastSyncAt > 15 * 60_000L) SyncWorker.runNow(this)
    }

    override fun onPause() {
        super.onPause()
        dashboard?.dismiss()
        dashboard = null
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        super.onDestroy()
    }

    // ---------- screens ----------

    private fun show(s: Screen) {
        if (s != Screen.LINK) main.removeCallbacks(poll)
        screen = s
        root.removeAllViews()
        root.addView(ui.logo(30))
        ui.space(root, 18)
        when (s) {
            Screen.WELCOME -> welcome()
            Screen.LINK -> link()
            Screen.USAGE -> usage()
            Screen.COCOON -> cocoon()
            Screen.EXTRAS -> extras()
            Screen.DONE -> done()
            Screen.HOME -> home()
        }
        message = ui.text("", 14f, Ui.DIM)
        root.addView(message)
    }

    private fun welcome() {
        root.addView(ui.title("Companion"))
        root.addView(ui.text("Dot down what you play on this device. Dotplay Companion sends your playtime from Cocoon, " +
            "emulators and Android games to your Dotplay profile, by itself.", 16f, Ui.DIM))
        ui.space(root, 10)
        val c = ui.card(root)
        c.addView(ui.text("Setting up takes about a minute:", 15f, bold = true))
        listOf("Link your Dotplay account", "Allow usage access", "Choose the Cocoon folder", "Pick extra games (optional)", "First sync")
            .forEachIndexed { i, s -> c.addView(ui.text("${i + 1}.  $s", 15f)) }
        root.addView(ui.button("Link my account", accent = true) { show(Screen.LINK) })
        root.addView(ui.link("I don't have an account yet") { open(prefs.serverUrl.removeSuffix("/api") + "/login?mode=register") })
        root.addView(ui.link("Use a different server") { askServer() })
    }

    private fun link() {
        root.addView(ui.steps(1, SETUP_STEPS))
        root.addView(ui.title("Link your account"))
        val c = ui.card(root)
        val p = pair
        if (p == null || System.currentTimeMillis() - pairStartedAt > p.expiresIn * 1000L) {
            c.addView(ui.text("Getting a code…", 15f, Ui.DIM))
            startPairing()
            return
        }
        c.addView(ui.label("Your code"))
        c.addView(ui.code(p.code))
        c.addView(ui.text("1. Tap the button below and sign in on the Dotplay website.\n2. Check that the code matches and press Link.\n" +
            "This screen continues by itself.", 15f))
        c.addView(ui.button("Open the website", accent = true) { open(p.linkUrl) })
        c.addView(ui.text("Using another device? Go to ${p.linkUrl.substringBefore("?").removePrefix("https://")} and enter the code.", 13f, Ui.DIM))
        c.addView(ui.text("● Waiting for you to press Link…", 14f, Ui.ACCENT))
        root.addView(ui.link("Get a new code") { pair = null; show(Screen.LINK) })
        root.addView(ui.link("Back") { show(Screen.WELCOME) })
        main.removeCallbacks(poll)
        main.postDelayed(poll, 2000)
    }

    private fun usage() {
        val ok = UsageTracker.hasPermission(this)
        root.addView(ui.steps(2, SETUP_STEPS))
        prefs.linkedUser?.let { root.addView(ui.check(true, "Linked to @$it")) }
        root.addView(ui.title("Allow usage access"))
        val c = ui.card(root)
        c.addView(ui.text("Android only tells Dotplay how long an app was on screen when you allow \"usage access\". " +
            "That is how playtime from Cocoon, emulators and Android games is measured. Nothing else is read.", 15f))
        c.addView(ui.check(ok, if (ok) "Usage access is on" else "Usage access is off"))
        if (!ok) {
            c.addView(ui.text("In the list that opens, tap Dotplay Companion and switch it on, then come back.", 14f, Ui.DIM))
            c.addView(ui.button("Open settings", accent = true) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        }
        root.addView(ui.button(if (ok) "Next" else "Skip for now", accent = ok, ghost = !ok) { show(Screen.COCOON) })
    }

    private fun cocoon() {
        val ok = prefs.cocoonTree != null
        root.addView(ui.steps(3, SETUP_STEPS))
        root.addView(ui.title("Cocoon"))
        val c = ui.card(root)
        c.addView(ui.text("Do you launch games with the Cocoon launcher? Then choose its folder once. Dotplay reads which game " +
            "Cocoon started and counts the time the emulator was on screen.", 15f))
        c.addView(ui.check(ok, if (ok) "Cocoon folder connected" else "No folder chosen yet"))
        c.addView(ui.button(if (ok) "Choose another folder" else "Choose the Cocoon folder", accent = !ok, ghost = ok) { pickCocoonFolder() })
        c.addView(ui.text("Pick the folder named \"Cocoon\" on internal storage and tap \"Use this folder\".", 13f, Ui.DIM))
        root.addView(ui.button(if (ok) "Next" else "I don't use Cocoon", accent = ok, ghost = !ok) { show(Screen.EXTRAS) })
    }

    private fun extras() {
        root.addView(ui.steps(4, SETUP_STEPS))
        root.addView(ui.title("Extra games"))
        root.addView(ui.text("Optional. You can always change this later.", 15f, Ui.DIM))
        val a = ui.card(root)
        a.addView(ui.label("Android games"))
        a.addView(ui.text("Games you play as a normal Android app (not through an emulator).", 14f, Ui.DIM))
        a.addView(ui.check(prefs.trackedApps.isNotEmpty(), "${prefs.trackedApps.size} tracked"))
        a.addView(ui.button("Choose Android games", ghost = true) { chooseApps() })
        val b = ui.card(root)
        b.addView(ui.label("Apps that are a game"))
        b.addView(ui.text("For apps Cocoon can't identify, like a Switch 2 emulator running one game or a PC recomp " +
            "(Ship of Harkinian). Their time counts as the game you choose.", 14f, Ui.DIM))
        val n = GameMappings.all(this).size
        b.addView(ui.check(n > 0, "$n set up"))
        b.addView(ui.button("Add an app as a game", ghost = true) { pickAppForMapping() })
        root.addView(ui.button("Next", accent = true) { show(Screen.DONE) })
    }

    private fun done() {
        root.addView(ui.steps(5, SETUP_STEPS))
        root.addView(ui.title("All set"))
        val c = ui.card(root)
        c.addView(ui.check(prefs.configured, "Linked to @${prefs.linkedUser ?: "your account"}"))
        c.addView(ui.check(UsageTracker.hasPermission(this), "Usage access"))
        c.addView(ui.check(prefs.cocoonTree != null, "Cocoon folder"))
        c.addView(ui.check(prefs.trackedApps.isNotEmpty() || GameMappings.all(this).isNotEmpty(), "Extra games"))
        c.addView(ui.text("Dotplay syncs every hour and whenever you open this app.", 14f, Ui.DIM))
        root.addView(ui.button("Sync now and finish", accent = true) {
            prefs.setupDone = true
            show(Screen.HOME)
            syncNow()
        })
    }

    private fun home() {
        root.addView(ui.title("Companion"))
        val s = ui.card(root)
        s.addView(ui.check(prefs.configured, if (prefs.configured) "Linked to @${prefs.linkedUser ?: "your account"}" else "Not linked"))
        s.addView(ui.check(UsageTracker.hasPermission(this), "Usage access"))
        s.addView(ui.check(prefs.cocoonTree != null, "Cocoon folder"))
        s.addView(ui.text("Last sync: ${prefs.lastSync} · syncs every hour and when you open the app", 13f, Ui.DIM))
        s.addView(ui.button("Sync now", accent = true) { syncNow() })
        s.addView(ui.button("Open my profile", ghost = true) {
            open(prefs.serverUrl.removeSuffix("/api") + (prefs.linkedUser?.let { "/u/$it" } ?: ""))
        })

        val g = ui.card(root)
        g.addView(ui.label("Games"))
        g.addView(ui.button("Choose Android games (${prefs.trackedApps.size})", ghost = true) { chooseApps() })
        g.addView(ui.label("Apps that are a game"))
        mappingList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        g.addView(mappingList)
        refreshMappings()
        g.addView(ui.button("Add an app as a game", ghost = true) { pickAppForMapping() })

        val k = ui.card(root)
        k.addView(ui.label("Cocoon"))
        k.addView(ui.button(if (prefs.cocoonTree != null) "Change Cocoon folder" else "Choose Cocoon folder", ghost = true) { pickCocoonFolder() })
        k.addView(ui.button("Import a play-history file", ghost = true) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, PICK_FILE)
        })

        root.addView(ui.link("Run setup again") { prefs.setupDone = false; show(if (prefs.configured) Screen.USAGE else Screen.WELCOME) })
        root.addView(ui.link("Unlink this device") {
            AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("Unlink this device?")
                .setMessage("Playtime already sent stays on your profile. You can link again anytime.")
                .setPositiveButton("Unlink") { _, _ -> prefs.unlink(); pair = null; show(Screen.WELCOME) }
                .setNegativeButton("Cancel", null)
                .show()
        })
    }

    // ---------- pairing ----------

    private fun startPairing() {
        io.execute {
            try {
                val p = Api.pairStart(prefs.serverUrl, deviceName())
                runOnUiThread { pair = p; pairStartedAt = System.currentTimeMillis(); if (screen == Screen.LINK) show(Screen.LINK) }
            } catch (e: Exception) {
                runOnUiThread { say("Can't reach Dotplay (${e.message}). Check the internet connection and try again.") }
            }
        }
    }

    private fun checkPairing() {
        val p = pair ?: return
        io.execute {
            val r = runCatching { Api.pairStatus(prefs.serverUrl, p.pairId) }.getOrNull()
            runOnUiThread {
                if (screen != Screen.LINK) return@runOnUiThread
                when (r?.status) {
                    "approved" -> {
                        prefs.deviceKey = r.key ?: ""
                        r.serverUrl?.let { prefs.serverUrl = it }
                        prefs.linkedUser = r.username
                        pair = null
                        show(Screen.USAGE)
                    }
                    "expired" -> { pair = null; show(Screen.LINK) }
                    else -> main.postDelayed(poll, 2000)   // pending, or a network hiccup: keep waiting
                }
            }
        }
    }

    private fun askServer() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        val url = ui.input(prefs.serverUrl, DEFAULT_SERVER)
        box.addView(url)
        AlertDialog.Builder(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            .setTitle("Server")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> prefs.serverUrl = url.text.toString(); pair = null }
            .setNeutralButton("Default") { _, _ -> prefs.serverUrl = "" ; pair = null }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    // ---------- second screen ----------

    private fun showDashboard() {
        if (!prefs.setupDone) return
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

    // ---------- app -> game mappings ----------

    private var mappingList: LinearLayout? = null

    private fun refreshMappings() {
        val list = mappingList ?: return
        list.removeAllViews()
        val all = GameMappings.all(this)
        if (all.isEmpty()) { list.addView(ui.text("None yet.", 14f, Ui.DIM)); return }
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
            list.addView(row)
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
                show(screen)
                say("$appLabel now counts as $t")
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dlg.setNeutralButton("Delete") { _, _ -> GameMappings.remove(this, pkg); show(screen) }
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
                    .setTitle("Android games to track")
                    .setMultiChoiceItems(labels, checked) { _, i, on ->
                        if (on) selected += apps[i].pkg else selected -= apps[i].pkg
                    }
                    .setPositiveButton("Save") { _, _ -> prefs.trackedApps = selected; show(screen) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // ---------- sync & Cocoon ----------

    private fun syncNow() {
        say("Syncing…")
        io.execute {
            try {
                val items = SyncWorker.collectAll(this, prefs)
                val msg = if (items.isEmpty()) "Nothing new to send" else Api.sync(prefs, deviceName(), items)
                prefs.lastSync = DateFormat.getDateTimeInstance().format(Date())
                runOnUiThread {
                    if (screen == Screen.HOME) show(Screen.HOME)
                    say(msg + items.joinToString("") { "\n· ${it.title} [${it.platform}]: ${it.days.values.sum()} min" })
                    refreshDashboard()
                }
            } catch (e: Exception) {
                runOnUiThread { say("Sync failed: ${e.message}") }
            }
        }
    }

    private fun pickCocoonFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ACocoon"))
        }, PICK_COCOON_DIR)
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
                show(screen)
                io.execute {
                    val text = runCatching { CocoonLog.readLog(this, uri) }.getOrNull()
                    val n = text?.let { CocoonLog.parse(it).size } ?: -1
                    runOnUiThread {
                        say(if (n < 0) "No Cocoon log in that folder. Pick the folder named Cocoon." else "Cocoon connected · $n game launches found")
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
        say("Reading file…")
        io.execute {
            val r = try { CocoonImport.read(this, uri) } catch (e: Exception) { CocoonImport.Result(emptyList(), "Error: ${e.message}") }
            runOnUiThread {
                pendingCocoon = r.items
                if (r.items.isEmpty()) {
                    say("No playtime recognised. ${r.note}")
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
                runOnUiThread { say("Import done. $msg") }
            } catch (e: Exception) {
                runOnUiThread { say("Upload failed: ${e.message}") }
            }
        }
    }

    private fun say(s: String) {
        message?.text = s
        if (s.isNotBlank()) scroll.post { scroll.smoothScrollTo(0, root.bottom) }
    }
}
