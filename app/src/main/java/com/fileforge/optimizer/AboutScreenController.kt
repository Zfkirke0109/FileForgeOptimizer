package com.fileforge.optimizer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object AboutLinks {
    const val REPOSITORY = "https://github.com/Zfkirke0109/FileForgeOptimizer"
    const val ISSUES = "https://github.com/Zfkirke0109/FileForgeOptimizer/issues"
    const val PROFILE = "https://github.com/Zfkirke0109"
}

class AboutScreenController(
    private val activity: Activity
) : Closeable {
    val view: View

    private val fixture = AboutScreenTestHooks.snapshot()
    private val ownedWorker: ExecutorService? = if (fixture == null) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "fileforge-about-update").apply { isDaemon = true }
        }
    } else {
        null
    }
    private val worker: Executor = fixture?.worker ?: ownedWorker!!
    private val openLink: (String) -> Boolean = fixture?.openLink ?: ::openExternalLink
    private val nativeInventory = fixture?.nativeInventory ?: UnavailableNativeToolInventory
    private lateinit var checkButton: MaterialButton
    private lateinit var updateStatus: TextView
    private lateinit var releaseButton: MaterialButton
    private var releaseUrl: String? = null
    private val updateSession: AboutUpdateSession

    init {
        view = buildView()
        val checker = fixture?.checker ?: GitHubLatestReleaseChecker(
            installedVersionName = BuildConfig.VERSION_NAME,
            assetKind = ReleaseAssetKind.STANDARD,
            transport = HttpUrlConnectionUpdateTransport()
        )
        updateSession = AboutUpdateSession(
            checker = checker,
            worker = worker,
            deliverOnMain = Executor { task -> activity.runOnUiThread(task) },
            deliver = ::renderUpdateResult
        )
    }

    override fun close() {
        updateSession.close()
        ownedWorker?.shutdownNow()
    }

    private fun buildView(): View = ScrollView(activity).apply {
        contentDescription = activity.getString(R.string.about_screen_description)
        isFillViewport = true
        addView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(24))
                addView(identityCard())
                addView(themeCard(), spaced())
                addView(nativeToolsCard(), spaced())
                addView(updateCard(), spaced())
            },
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    @Suppress("DEPRECATION")
    private fun identityCard(): View = card {
        addText(R.string.app_name, 22f)
        addText(R.string.about_credit, 16f, top = 8)
        val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        addText(activity.getString(R.string.about_version_name, packageInfo.versionName ?: "Unavailable"))
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        addText(activity.getString(R.string.about_version_code, versionCode))
        addText(activity.getString(R.string.about_build_variant, "${BuildConfig.BUILD_TYPE} (standard)"))
        addText(activity.getString(R.string.about_abi, Build.SUPPORTED_ABIS.firstOrNull() ?: "Unavailable"))
        addLinkButton(R.string.about_repository, AboutLinks.REPOSITORY)
        addLinkButton(R.string.about_issues, AboutLinks.ISSUES)
        addLinkButton(R.string.about_profile, AboutLinks.PROFILE)
    }

    private fun themeCard(): View = card {
        addText(R.string.about_theme_title, 20f)
        addText(R.string.about_theme_description, top = 4)
        val preferences = ThemePreferences.forActivity(activity)
        val saved = preferences.read()
        val group = RadioGroup(activity).apply {
            id = R.id.about_theme_group
            orientation = RadioGroup.VERTICAL
            contentDescription = activity.getString(R.string.about_theme_title)
        }
        val choices = linkedMapOf(
            ThemeMode.SYSTEM to R.string.theme_system,
            ThemeMode.LIGHT to R.string.theme_light,
            ThemeMode.DARK to R.string.theme_dark,
            ThemeMode.AMOLED to R.string.theme_amoled
        )
        val modesById = mutableMapOf<Int, ThemeMode>()
        choices.forEach { (mode, label) ->
            group.addView(RadioButton(activity).apply {
                id = View.generateViewId()
                setText(label)
                contentDescription = activity.getString(R.string.about_theme_choice, activity.getString(label))
                isChecked = mode == saved
                modesById[id] = mode
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            modesById[checkedId]?.let(preferences::select)
        }
        addView(group)
    }

    private fun nativeToolsCard(): View = card {
        addText(R.string.about_native_tools_title, 20f)
        val inventoryText = nativeInventory.snapshot().joinToString("\n") { tool ->
            "${tool.name}: ${tool.detail} • License: ${tool.licenseNotice}"
        }
        addText(inventoryText, top = 6)
    }

    private fun updateCard(): View = card {
        addText(R.string.about_updates_title, 20f)
        addText(R.string.about_updates_manual, top = 4)
        updateStatus = TextView(activity).apply {
            id = R.id.about_update_status
            setText(R.string.about_update_not_checked)
            setPadding(0, dp(8), 0, 0)
        }
        addView(updateStatus)
        checkButton = MaterialButton(activity).apply {
            id = R.id.about_check_updates
            setText(R.string.about_check_updates)
            contentDescription = activity.getString(R.string.about_check_updates)
            setOnClickListener {
                isEnabled = false
                releaseUrl = null
                releaseButton.visibility = View.GONE
                updateStatus.setText(R.string.about_update_checking)
                updateSession.checkNow()
            }
        }
        addView(checkButton)
        releaseButton = MaterialButton(activity).apply {
            id = R.id.about_view_release
            setText(R.string.about_view_release)
            visibility = View.GONE
            setOnClickListener { releaseUrl?.let(::launchLink) }
        }
        addView(releaseButton)
    }

    private fun renderUpdateResult(result: UpdateCheckResult) {
        checkButton.isEnabled = true
        releaseUrl = null
        releaseButton.visibility = View.GONE
        when (result) {
            is UpdateCheckResult.Current -> updateStatus.text =
                activity.getString(R.string.about_update_current, result.latestVersion)
            is UpdateCheckResult.Available -> {
                updateStatus.text = activity.getString(
                    R.string.about_update_available,
                    result.tagName
                )
                releaseUrl = result.releasePageUrl
                releaseButton.visibility = View.VISIBLE
            }
            UpdateCheckResult.NoRelease -> updateStatus.setText(R.string.about_update_no_release)
            UpdateCheckResult.Offline -> updateStatus.setText(R.string.about_update_offline)
            UpdateCheckResult.RateLimited -> updateStatus.setText(R.string.about_update_rate_limited)
            UpdateCheckResult.Invalid -> updateStatus.setText(R.string.about_update_invalid)
        }
    }

    private fun openExternalLink(url: String): Boolean = try {
        activity.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun launchLink(url: String) {
        if (!openLink(url)) {
            Snackbar.make(view, R.string.about_no_browser, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun LinearLayout.addLinkButton(label: Int, url: String) {
        addView(MaterialButton(activity).apply {
            setText(label)
            contentDescription = activity.getString(label)
            setOnClickListener { launchLink(url) }
        })
    }

    private fun card(content: LinearLayout.() -> Unit): MaterialCardView = MaterialCardView(activity).apply {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            content()
        })
    }

    private fun LinearLayout.addText(resource: Int, size: Float = 14f, top: Int = 0) {
        addView(TextView(activity).apply {
            setText(resource)
            textSize = size
            if (top > 0) setPadding(0, dp(top), 0, 0)
        })
    }

    private fun LinearLayout.addText(value: String, size: Float = 14f, top: Int = 0) {
        addView(TextView(activity).apply {
            text = value
            textSize = size
            if (top > 0) setPadding(0, dp(top), 0, 0)
        })
    }

    private fun spaced() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(12) }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}

data class AboutScreenTestFixture(
    val checker: LatestReleaseChecker,
    val worker: Executor,
    val openLink: ((String) -> Boolean)?,
    val nativeInventory: NativeToolInventory
)

object AboutScreenTestHooks {
    @Volatile private var fixture: AboutScreenTestFixture? = null

    fun install(
        checker: LatestReleaseChecker,
        worker: Executor,
        openLink: ((String) -> Boolean)? = null,
        nativeInventory: NativeToolInventory = UnavailableNativeToolInventory
    ) {
        fixture = AboutScreenTestFixture(checker, worker, openLink, nativeInventory)
    }

    fun clear() {
        fixture = null
    }

    internal fun snapshot(): AboutScreenTestFixture? = fixture
}
