package desu.inugram.helpers.analytics

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.LaunchActivity
import java.io.File
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AnalyticsHelper {
    private const val ENDPOINT = "https://entaytion.is-a.dev/api/entinygram/analytics"
    private const val DAY_MS = 86_400_000L
    private const val RETRY_MS = 900_000L
    private const val MAX_BYTES = 65_536
    private val stateLock = Any()
    private val crashLock = Any()
    private val queued = AtomicBoolean(false)
    private val worker by lazy {
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "entiny-analytics").apply { isDaemon = true } }
    }
    private lateinit var directory: File
    private var appBuild = 0
    @Volatile private var state: State? = null
    private var dialog: AlertDialog? = null
    private var promptTask: Runnable? = null

    private data class State(
        val id: String,
        val showBanner: Boolean = true,
        val consent: String = "pending",
        val decisionSent: Boolean = false,
        val lastSnapshotAt: Long = 0,
        val lastSnapshotBuild: Int = 0,
    ) {
        fun json() = JSONObject()
            .put("installation_id", id)
            .put("show_analytics_banner", showBanner)
            .put("consent", consent)
            .put("decision_sent", decisionSent)
            .put("last_snapshot_at", lastSnapshotAt)
            .put("last_snapshot_build", lastSnapshotBuild)
    }

    fun init(context: Context) {
        if (BuildConfig.INU_BUILD_TYPE != "beta" || state != null) return
        try {
            directory = File(context.noBackupFilesDir, "entiny-analytics")
            appBuild = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            val file = AtomicFile(File(directory, "state.json"))
            state = if (!file.baseFile.exists() && !File(directory, "state.json.bak").exists()) {
                State(randomId())
            } else {
                require(file.baseFile.length() <= 4096 && File(directory, "state.json.bak").length() <= 4096)
                val bytes = file.readFully()
                require(bytes.size <= 4096)
                val saved = JSONObject(bytes.toString(Charsets.UTF_8))
                val id = saved.getString("installation_id")
                val consent = saved.getString("consent")
                require(id.matches(Regex("[0-9a-f]{32}")))
                require(consent == "accepted" || consent == "declined")
                require(!saved.getBoolean("show_analytics_banner"))
                State(id, false, consent, saved.optBoolean("decision_sent"),
                    saved.optLong("last_snapshot_at"), saved.optInt("last_snapshot_build"))
            }
        } catch (_: Exception) {
            state = State(randomId(), false, "disabled", true)
        }
    }

    fun onResume(activity: LaunchActivity) {
        if (BuildConfig.INU_BUILD_TYPE != "beta") return
        val current = state ?: return
        if (!current.showBanner) {
            enqueue()
            return
        }
        promptTask?.let { AndroidUtilities.cancelRunOnUIThread(it) }
        val owner = WeakReference(activity)
        val task = object : Runnable {
            override fun run() {
                val target = owner.get() ?: return
                if (target.isFinishing || target.isDestroyed || !LaunchActivity.isResumed) return
                if (state?.showBanner != true || dialog?.isShowing == true) return
                if (SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter ||
                    !target.hasWindowFocus() || target.visibleDialog != null ||
                    target.actionBarLayout?.lastFragment?.visibleDialog != null) {
                    AndroidUtilities.runOnUIThread(this, 1000)
                    return
                }
                showConsent(target)
            }
        }
        promptTask = task
        AndroidUtilities.runOnUIThread(task, 1000)
    }

    private fun showConsent(activity: LaunchActivity) {
        try {
            dialog = AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.InuAnalyticsTitle))
                .setMessage(LocaleController.getString(R.string.InuAnalyticsInfo))
                .setPositiveButton(LocaleController.getString(R.string.InuAnalyticsAgree)) { _, _ -> choose(activity, true) }
                .setNegativeButton(LocaleController.getString(R.string.InuAnalyticsDecline)) { _, _ -> choose(activity, false) }
                .setOnDismissListener { dialog = null }
                .create()
                .apply {
                    setDismissDialogByButtons(false)
                    setCancelable(false)
                    setCanceledOnTouchOutside(false)
                    show()
                }
        } catch (_: Exception) {
            dialog = null
        }
    }

    private fun choose(context: Context, accepted: Boolean) {
        if (state?.showBanner != true) return
        val saved = updateState { it.copy(showBanner = false, consent = if (accepted) "accepted" else "declined") }
        if (saved) {
            dialog?.dismiss()
            enqueue()
        } else {
            Toast.makeText(context, LocaleController.getString(R.string.InuAnalyticsSaveFailed), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateState(change: (State) -> State): Boolean = synchronized(stateLock) {
        val current = state ?: return@synchronized false
        val next = change(current)
        try {
            if (!directory.exists() && !directory.mkdirs()) return@synchronized false
            writeAtomic(File(directory, "state.json"), next.json().toString().toByteArray(Charsets.UTF_8))
            state = next
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun enqueue(delay: Long = 0) {
        val current = state ?: return
        if (current.showBanner || current.consent !in setOf("accepted", "declined")) return
        if (current.consent == "declined" && current.decisionSent) return
        if (!queued.compareAndSet(false, true)) return
        worker.schedule({
            val retry = try { !flush() } catch (_: Exception) { true }
            queued.set(false)
            if (retry) enqueue(RETRY_MS)
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun flush(): Boolean {
        val current = state ?: return true
        if (current.showBanner) return true
        if (current.consent == "declined") {
            if (!current.decisionSent && !post(base("decision", current))) return false
            return current.decisionSent || updateState { it.copy(decisionSent = true) }
        }
        if (current.consent != "accepted") return true
        val now = System.currentTimeMillis()
        if (!current.decisionSent || current.lastSnapshotBuild != appBuild ||
            now < current.lastSnapshotAt || now - current.lastSnapshotAt >= DAY_MS) {
            val snapshot = base("settings", current)
                .put("catalog_version", AnalyticsSettings.VERSION)
                .put("config", AnalyticsSettings.snapshot())
            if (!post(snapshot)) return false
            if (!updateState { it.copy(decisionSent = true, lastSnapshotAt = now, lastSnapshotBuild = appBuild) }) return false
        }
        val files = synchronized(crashLock) {
            File(directory, "crashes").listFiles { file -> file.extension == "json" }?.sortedBy { it.name }.orEmpty()
        }
        for (file in files) {
            if (file.length() > MAX_BYTES || now - file.lastModified() > 30L * DAY_MS) {
                synchronized(crashLock) { file.delete() }
                continue
            }
            val report = try { JSONObject(file.readText(Charsets.UTF_8)) } catch (_: Exception) {
                synchronized(crashLock) { file.delete() }
                continue
            }
            if (!post(report)) return false
            synchronized(crashLock) { file.delete() }
        }
        return true
    }

    fun recordCrash(error: Throwable) {
        if (BuildConfig.INU_BUILD_TYPE != "beta") return
        val current = state ?: return
        if (current.showBanner || current.consent != "accepted") return
        try {
            val exceptions = JSONArray()
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = error
            var remainingFrames = 80
            while (cause != null && exceptions.length() < 8 && seen.add(cause)) {
                val frames = JSONArray()
                for (frame in cause.stackTrace.take(remainingFrames)) {
                    frames.put(JSONObject()
                        .put("class", safe(frame.className, "[^A-Za-z0-9_.$]", 192))
                        .put("method", safe(frame.methodName, "[^A-Za-z0-9_$<>-]", 128))
                        .put("line", frame.lineNumber.coerceAtLeast(-2)))
                    remainingFrames--
                }
                exceptions.put(JSONObject()
                    .put("type", safe(cause.javaClass.name, "[^A-Za-z0-9_.$]", 192))
                    .put("frames", frames))
                cause = cause.cause
            }
            val eventId = randomId()
            val bytes = base("crash", current).put("event_id", eventId)
                .put("crash", JSONObject().put("exceptions", exceptions))
                .toString().toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) return
            synchronized(crashLock) {
                val queue = File(directory, "crashes")
                if (!queue.exists() && !queue.mkdirs()) return
                val files = queue.listFiles { file -> file.extension == "json" }?.sortedBy { it.lastModified() }.orEmpty()
                files.take((files.size - 9).coerceAtLeast(0)).forEach { it.delete() }
                writeAtomic(File(queue, "$eventId.json"), bytes)
            }
        } catch (_: Throwable) {
        }
    }

    private fun base(kind: String, current: State) = JSONObject()
        .put("schema_version", 1)
        .put("kind", kind)
        .put("installation_id", current.id)
        .put("app_build", appBuild)
        .put("consent", current.consent)
        .put("device", JSONObject()
            .put("manufacturer", safe(Build.MANUFACTURER, "[^A-Za-z0-9 ._()+-]", 100))
            .put("model", safe(Build.MODEL, "[^A-Za-z0-9 ._()+-]", 100)))
        .put("os", JSONObject().put("name", "android")
            .put("version", safe(Build.VERSION.RELEASE, "[^A-Za-z0-9._ -]", 32))
            .put("sdk", Build.VERSION.SDK_INT).put("skin", JSONObject.NULL))

    private fun post(report: JSONObject): Boolean {
        val bytes = report.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return false
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT
        } finally {
            connection.disconnect()
        }
    }

    private fun safe(value: String, invalid: String, limit: Int) = value.replace(Regex(invalid), "_").take(limit).ifBlank { "unknown" }

    private fun randomId() = UUID.randomUUID().toString().replace("-", "")

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}
