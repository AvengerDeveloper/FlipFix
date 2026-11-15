package io.github.avengerdeveloper.flipfix.covertest

import android.app.Activity
import android.app.WallpaperManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.io.FileInputStream

/**
 * Feasibility probe for FlipFix. Answers, on a real One UI 9 cover screen:
 *  1. Can a third-party activity run on the cover display (display 1)?
 *  2. Does Samsung's cover video wallpaper show through it?
 *  3. Does the wallpaper engine accept "samsung.android.wallpaper.resume" from our window?
 *  4. Can a normal app read the cover video file under /data/overlays/coverwallpaper?
 * Everything is logged under the tag FlipFixTest.
 */
class CoverTestActivity : Activity() {

    private lateinit var status: TextView
    private var autoResumeSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cover_test)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.resume).setOnClickListener { sendCommand(CMD_RESUME) }
        findViewById<Button>(R.id.pause).setOnClickListener { sendCommand(CMD_PAUSE) }
        findViewById<Button>(R.id.close).setOnClickListener { finish() }

        val d = display
        log("onCreate display=${d?.displayId} name=${d?.name} state=${d?.state}")
        probeVideoFile()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        log("focus=$hasFocus")
        if (hasFocus && !autoResumeSent) {
            autoResumeSent = true
            // Give WindowManager a moment to make us the wallpaper target before sending.
            window.decorView.postDelayed({ sendCommand(CMD_RESUME) }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        log("onResume")
    }

    override fun onPause() {
        log("onPause")
        super.onPause()
    }

    private fun sendCommand(action: String) {
        val token = window.decorView.windowToken
        if (token == null) {
            log("$action: no window token yet")
            return
        }
        try {
            WallpaperManager.getInstance(this).sendWallpaperCommand(token, action, 0, 0, 0, null)
            log("sent $action")
        } catch (t: Throwable) {
            log("$action failed: $t")
        }
    }

    private fun probeVideoFile() {
        val root = File(COVER_WALLPAPER_ROOT)
        val dirs = root.listFiles()
        log("list $COVER_WALLPAPER_ROOT -> ${dirs?.size ?: "null (no access)"}")

        // Also try a path given by adb (--es videoPath ...), which works even if listing is denied.
        val candidates = buildList {
            intent.getStringExtra("videoPath")?.let { add(File(it)) }
            dirs?.forEach { add(File(it, "video_wallpaper_0_17.mp4")) }
        }.filter { it.exists() || it.path == intent.getStringExtra("videoPath") }

        if (candidates.isEmpty()) {
            log("no candidate video file found")
            return
        }
        for (f in candidates) {
            try {
                FileInputStream(f).use { input ->
                    val head = ByteArray(12)
                    val n = input.read(head)
                    val brand = String(head, 4, 4, Charsets.US_ASCII)
                    log("READ OK ${f.path} (${f.length()} bytes, $n read, box=$brand)")
                }
            } catch (t: Throwable) {
                log("read FAILED ${f.path}: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        if (::status.isInitialized) {
            status.append(msg + "\n")
        }
    }

    private companion object {
        const val TAG = "FlipFixTest"
        const val CMD_RESUME = "samsung.android.wallpaper.resume"
        const val CMD_PAUSE = "samsung.android.wallpaper.pause"
        const val COVER_WALLPAPER_ROOT = "/data/overlays/coverwallpaper/0"
    }
}
