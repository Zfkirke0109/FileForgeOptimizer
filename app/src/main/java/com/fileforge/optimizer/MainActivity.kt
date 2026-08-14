package com.fileforge.optimizer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {
    private val requestTreeCode = 4107
    private lateinit var selectedFolderText: TextView
    private lateinit var logText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var apkLabBox: CheckBox
    private lateinit var textMinifyBox: CheckBox
    private var selectedTreeUri: Uri? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyActivityThemeBeforeOnCreate(this)
        super.onCreate(savedInstanceState)
        selectedTreeUri = getPreferences(MODE_PRIVATE).getString("treeUri", null)?.let(Uri::parse)
        buildUi()
        updateSelectedText()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
        }

        val title = TextView(this).apply {
            text = "FileForge Optimizer"
            textSize = 24f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Rootless same-type optimizer. Uses Storage Access Framework folder access."
            textSize = 14f
            setPadding(0, 8, 0, 18)
        }
        root.addView(subtitle)

        selectedFolderText = TextView(this).apply { textSize = 13f }
        root.addView(selectedFolderText)

        val pick = Button(this).apply {
            text = "Pick folder"
            setOnClickListener { pickFolder() }
        }
        root.addView(pick)

        apkLabBox = CheckBox(this).apply {
            text = "Enable APK Lab Mode (unsafe for install/update compatibility)"
            isChecked = false
        }
        root.addView(apkLabBox)

        textMinifyBox = CheckBox(this).apply {
            text = "Enable XML/JSON/SVG/TXT minify (may alter whitespace/metadata)"
            isChecked = false
        }
        root.addView(textMinifyBox)

        val safe = Button(this).apply {
            text = "Run Safe Mode"
            setOnClickListener { runOptimizer(OptimizeMode.SAFE) }
        }
        root.addView(safe)

        val aggressive = Button(this).apply {
            text = "Run Aggressive Mode"
            setOnClickListener { runOptimizer(OptimizeMode.AGGRESSIVE) }
        }
        root.addView(aggressive)

        val clear = Button(this).apply {
            text = "Clear log"
            setOnClickListener { logText.text = "" }
        }
        root.addView(clear)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        logText = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        scroll.addView(logText)
        root.addView(scroll)

        setContentView(root)
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, requestTreeCode)
    }

    @Deprecated("Deprecated by Activity Result APIs, but fine for this minimal no-AndroidX-Activity MVP.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestTreeCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            try {
                @Suppress("WrongConstant") // `flags` is masked to the two documented access-mode grants above.
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Some providers do not allow persisted grants; the active grant can still work for the session.
            }
            selectedTreeUri = uri
            getPreferences(MODE_PRIVATE).edit().putString("treeUri", uri.toString()).apply()
            updateSelectedText()
            appendLog("Selected folder: $uri")
        }
    }

    private fun updateSelectedText() {
        selectedFolderText.text = selectedTreeUri?.let { "Selected: $it" } ?: "Selected: none"
    }

    private fun runOptimizer(mode: OptimizeMode) {
        if (running) return
        val uri = selectedTreeUri
        if (uri == null) {
            Toast.makeText(this, "Pick a folder first.", Toast.LENGTH_SHORT).show()
            return
        }
        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.exists() || !root.canRead() || !root.canWrite()) {
            Toast.makeText(this, "Cannot read/write that folder. Pick it again.", Toast.LENGTH_LONG).show()
            return
        }

        val settings = OptimizerSettings(
            mode = mode,
            apkLabMode = apkLabBox.isChecked,
            textMinify = textMinifyBox.isChecked
        )

        running = true
        progress.visibility = View.VISIBLE
        appendLog("\n=== Run started: $settings ===")

        Thread {
            val report = try {
                OptimizerEngine(this, root, settings) { message -> appendLog(message) }.run()
            } catch (t: Throwable) {
                appendLog("Fatal error: ${t.message ?: t.javaClass.name}")
                null
            }
            runOnUiThread {
                running = false
                progress.visibility = View.GONE
                if (report != null) {
                    appendLog("=== Done: scanned=${report.scanned}, optimized=${report.optimized}, saved=${report.savedBytes} bytes, skipped=${report.skipped}, errors=${report.errors} ===")
                }
            }
        }.start()
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            logText.append(message + "\n")
        }
    }
}
