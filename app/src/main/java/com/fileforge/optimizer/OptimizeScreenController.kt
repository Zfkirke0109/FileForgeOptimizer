package com.fileforge.optimizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

data class SelectedTreeCapabilities(
    val exists: Boolean,
    val isDirectory: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean
) {
    companion object {
        val NONE = SelectedTreeCapabilities(false, false, false, false)
        val READ_ONLY_DIRECTORY = SelectedTreeCapabilities(true, true, true, false)
        val READ_WRITE_DIRECTORY = SelectedTreeCapabilities(true, true, true, true)
    }
}

data class OptimizeUiProjection(
    val startEnabled: Boolean,
    val cancelEnabled: Boolean,
    val phase: String,
    val currentPath: String?,
    val filesDiscovered: Int,
    val filesProcessed: Int,
    val candidates: Int,
    val optimized: Int,
    val errors: Int,
    val savedBytes: Long,
    val potentialSavingsBytes: Long,
    val progressIndeterminate: Boolean,
    val progressMax: Int?,
    val progressCurrent: Int?,
    val terminalStatus: RunStatus?,
    val terminalError: String?,
    val terminalFailures: List<String>
)

object OptimizeUiStateProjector {
    fun project(
        state: RunState,
        tree: SelectedTreeCapabilities,
        dryRun: Boolean
    ): OptimizeUiProjection {
        val readableDirectory = tree.exists && tree.isDirectory && tree.canRead
        val accessAllowsStart = readableDirectory && (dryRun || tree.canWrite)
        return when (state) {
            RunState.Idle -> emptyProjection(accessAllowsStart, "ready")
            is RunState.Running -> {
                val snapshot = state.snapshot
                val total = snapshot.totalWork?.takeIf { it > 0 }
                OptimizeUiProjection(
                    startEnabled = false,
                    cancelEnabled = true,
                    phase = snapshot.phase,
                    currentPath = snapshot.currentRelativePath,
                    filesDiscovered = snapshot.filesDiscovered,
                    filesProcessed = snapshot.filesProcessed,
                    candidates = snapshot.candidates,
                    optimized = snapshot.optimized,
                    errors = snapshot.errors,
                    savedBytes = snapshot.savedBytes,
                    potentialSavingsBytes = snapshot.potentialSavingsBytes,
                    progressIndeterminate = total == null,
                    progressMax = total,
                    progressCurrent = total?.let { snapshot.filesProcessed.coerceIn(0, it) },
                    terminalStatus = null,
                    terminalError = null,
                    terminalFailures = emptyList()
                )
            }
            is RunState.Terminal -> {
                val report = state.report
                OptimizeUiProjection(
                    startEnabled = accessAllowsStart,
                    cancelEnabled = false,
                    phase = report.status.name.lowercase(Locale.US),
                    currentPath = null,
                    filesDiscovered = report.scanned,
                    filesProcessed = report.scanned,
                    candidates = report.candidates,
                    optimized = report.optimized,
                    errors = report.errors,
                    savedBytes = report.savedBytes,
                    potentialSavingsBytes = report.potentialSavingsBytes,
                    progressIndeterminate = false,
                    progressMax = null,
                    progressCurrent = null,
                    terminalStatus = report.status,
                    terminalError = report.terminalError,
                    terminalFailures = report.terminalFailures.toList()
                )
            }
        }
    }

    private fun emptyProjection(startEnabled: Boolean, phase: String) = OptimizeUiProjection(
        startEnabled = startEnabled,
        cancelEnabled = false,
        phase = phase,
        currentPath = null,
        filesDiscovered = 0,
        filesProcessed = 0,
        candidates = 0,
        optimized = 0,
        errors = 0,
        savedBytes = 0,
        potentialSavingsBytes = 0,
        progressIndeterminate = false,
        progressMax = null,
        progressCurrent = null,
        terminalStatus = null,
        terminalError = null,
        terminalFailures = emptyList()
    )
}

enum class NotificationPermissionStep {
    REQUEST_PERMISSION,
    RUN,
    RUN_WITH_REDUCED_VISIBILITY_EXPLANATION
}

object NotificationPermissionFlow {
    fun beforeRun(
        sdkInt: Int,
        permissionGranted: Boolean,
        permissionPreviouslyRequested: Boolean
    ): NotificationPermissionStep = when {
        sdkInt < Build.VERSION_CODES.TIRAMISU -> NotificationPermissionStep.RUN
        permissionGranted -> NotificationPermissionStep.RUN
        !permissionPreviouslyRequested -> NotificationPermissionStep.REQUEST_PERMISSION
        else -> NotificationPermissionStep.RUN_WITH_REDUCED_VISIBILITY_EXPLANATION
    }

    fun afterPermissionResult(granted: Boolean): NotificationPermissionStep =
        if (granted) NotificationPermissionStep.RUN
        else NotificationPermissionStep.RUN_WITH_REDUCED_VISIBILITY_EXPLANATION
}

class OptimizeScreenController(
    private val activity: AppCompatActivity,
    private val startRun: (ServiceRunRequest.Optimize) -> Unit,
    private val cancelRun: () -> Unit
) {
    val view: View

    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private lateinit var selectedFolderText: TextView
    private lateinit var safeButton: MaterialButton
    private lateinit var aggressiveButton: MaterialButton
    private lateinit var dryRunSwitch: SwitchMaterial
    private lateinit var apkLabSwitch: SwitchMaterial
    private lateinit var textMinifySwitch: SwitchMaterial
    private lateinit var startButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var phaseText: TextView
    private lateinit var currentPathText: TextView
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var countersText: TextView
    private lateinit var savingsText: TextView
    private lateinit var terminalText: TextView

    private var selectedTreeUri: Uri? = restoreSelectedTreeUri()
    private var latestRunState: RunState = RunState.Idle
    private var pendingRequest: ServiceRunRequest.Optimize? = null
    private val capabilityCache = SelectedTreeCapabilitiesCache(::readSelectedTreeCapabilities)
    private val startDispatchGate = OptimizeStartDispatchGate()
    private val progressPort: OptimizeProgressIndicator by lazy {
        object : OptimizeProgressIndicator {
            override val indeterminate: Boolean
                get() = progressIndicator.isIndeterminate

            override fun hide() {
                progressIndicator.visibility = View.INVISIBLE
            }

            override fun show() {
                progressIndicator.visibility = View.VISIBLE
            }

            override fun setIndeterminateMode(value: Boolean) {
                progressIndicator.isIndeterminate = value
            }

            override fun setMaximum(value: Int) {
                progressIndicator.max = value
            }

            override fun setProgress(value: Int, animated: Boolean) {
                progressIndicator.setProgressCompat(value, animated)
            }
        }
    }

    private val treePicker = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) acceptSelectedTree(uri)
    }

    private val notificationPermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingRequest ?: buildRequestIfValid()
        pendingRequest = null
        when (NotificationPermissionFlow.afterPermissionResult(granted)) {
            NotificationPermissionStep.RUN -> request?.let(::dispatchStart)
            NotificationPermissionStep.RUN_WITH_REDUCED_VISIBILITY_EXPLANATION -> {
                explainReducedNotificationVisibility()
                request?.let(::dispatchStart)
            }
            NotificationPermissionStep.REQUEST_PERMISSION -> Unit
        }
    }

    init {
        view = buildView()
        restoreControls()
        installListeners()
        capabilityCache.refresh()
        render(RunState.Idle)
    }

    fun render(state: RunState) {
        latestRunState = state
        startDispatchGate.onObservedState(state)
        renderCurrentState()
    }

    fun refreshTreeCapabilities() {
        capabilityCache.refresh()
        renderCurrentState()
    }

    private fun renderCurrentState() {
        val projection = OptimizeUiStateProjector.project(
            latestRunState,
            capabilityCache.current,
            dryRunSwitch.isChecked
        )
        selectedFolderText.text = selectedTreeUri?.let { uri ->
            activity.getString(R.string.selected_folder_value, uri)
        } ?: activity.getString(R.string.selected_folder_none)
        startButton.isEnabled = startDispatchGate.allowsStart(projection.startEnabled)
        cancelButton.isEnabled = projection.cancelEnabled
        phaseText.text = activity.getString(R.string.run_phase_value, projection.phase)
        currentPathText.text = projection.currentPath ?: activity.getString(R.string.no_active_file)
        updateProgress(projection)
        countersText.text = activity.getString(
            R.string.live_counters_value,
            projection.filesProcessed,
            projection.filesDiscovered,
            projection.candidates,
            projection.optimized,
            projection.errors
        )
        val state = latestRunState
        savingsText.text = if (state is RunState.Running && state.dryRun ||
            state is RunState.Terminal && state.dryRun
        ) {
            activity.getString(
                R.string.potential_savings_value,
                formatBytes(projection.potentialSavingsBytes)
            )
        } else {
            activity.getString(R.string.saved_value, formatBytes(projection.savedBytes))
        }
        terminalText.text = terminalMessage(projection)
        terminalText.visibility = if (terminalText.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun buildView(): View {
        val scroll = NestedScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            contentDescription = activity.getString(R.string.optimize_screen_description)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(folderCard())
        content.addView(modeCard(), spacedLayout())
        content.addView(optionsCard(), spacedLayout())
        content.addView(actionRow(), spacedLayout())
        content.addView(progressCard(), spacedLayout())
        return scroll
    }

    private fun folderCard(): View = cardWithContent(
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(R.string.folder_access_title))
            selectedFolderText = TextView(activity).apply {
                id = R.id.selected_folder
                setPadding(0, dp(8), 0, dp(12))
                setTextIsSelectable(true)
            }
            addView(selectedFolderText)
            addView(MaterialButton(activity).apply {
                id = R.id.pick_folder
                setText(R.string.pick_folder)
                contentDescription = activity.getString(R.string.pick_folder_description)
                minHeight = dp(48)
                setOnClickListener { treePicker.launch(selectedTreeUri) }
            })
        }
    )

    private fun modeCard(): View = cardWithContent(
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(R.string.mode_title))
            addView(TextView(activity).apply {
                setText(R.string.mode_explanation)
                setPadding(0, dp(6), 0, dp(12))
            })
            val toggle = MaterialButtonToggleGroup(activity).apply {
                id = R.id.mode_toggle
                isSingleSelection = true
                isSelectionRequired = true
                orientation = LinearLayout.HORIZONTAL
            }
            safeButton = modeButton(R.id.mode_safe, R.string.mode_safe)
            aggressiveButton = modeButton(R.id.mode_aggressive, R.string.mode_aggressive)
            toggle.addView(safeButton, weightedLayout())
            toggle.addView(aggressiveButton, weightedLayout())
            addView(toggle)
        }
    )

    private fun optionsCard(): View = cardWithContent(
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(R.string.options_title))
            dryRunSwitch = optionSwitch(R.id.dry_run, R.string.dry_run, R.string.dry_run_description)
            apkLabSwitch = optionSwitch(R.id.apk_lab, R.string.apk_lab, R.string.apk_lab_description)
            textMinifySwitch = optionSwitch(
                R.id.text_minify,
                R.string.text_minify,
                R.string.text_minify_description
            )
            addView(dryRunSwitch)
            addView(apkLabSwitch)
            addView(textMinifySwitch)
        }
    )

    private fun actionRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        startButton = MaterialButton(activity).apply {
            id = R.id.start_optimization
            setText(R.string.start_optimization)
            contentDescription = activity.getString(R.string.start_optimization_description)
            minHeight = dp(48)
            setOnClickListener { onStartClicked() }
        }
        cancelButton = MaterialButton(
            activity,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            id = R.id.cancel_optimization
            setText(R.string.cancel_optimization)
            contentDescription = activity.getString(R.string.cancel_optimization_description)
            minHeight = dp(48)
            setOnClickListener { cancelRun() }
        }
        addView(startButton, weightedLayout(endMargin = dp(6)))
        addView(cancelButton, weightedLayout(startMargin = dp(6)))
    }

    private fun progressCard(): View = cardWithContent(
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(R.string.progress_title))
            phaseText = TextView(activity).apply {
                id = R.id.run_phase
                setPadding(0, dp(8), 0, dp(4))
            }
            addView(phaseText)
            currentPathText = TextView(activity).apply {
                id = R.id.current_path
                contentDescription = activity.getString(R.string.current_path_description)
                setTextIsSelectable(true)
                setPadding(0, 0, 0, dp(12))
            }
            addView(currentPathText)
            progressIndicator = LinearProgressIndicator(activity).apply {
                id = R.id.run_progress
                contentDescription = activity.getString(R.string.run_progress_description)
                isIndeterminate = false
                max = 1
                progress = 0
            }
            addView(
                progressIndicator,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8))
            )
            countersText = TextView(activity).apply {
                id = R.id.live_counters
                setPadding(0, dp(12), 0, dp(4))
            }
            addView(countersText)
            savingsText = TextView(activity).apply { id = R.id.savings }
            addView(savingsText)
            terminalText = TextView(activity).apply {
                id = R.id.terminal_message
                setTextIsSelectable(true)
                setPadding(0, dp(12), 0, 0)
            }
            addView(terminalText)
        }
    )

    private fun restoreControls() {
        val mode = try {
            OptimizeMode.valueOf(preferences.getString(KEY_MODE, OptimizeMode.SAFE.name).orEmpty())
        } catch (_: IllegalArgumentException) {
            OptimizeMode.SAFE
        }
        if (mode == OptimizeMode.SAFE) safeButton.isChecked = true
        else aggressiveButton.isChecked = true
        dryRunSwitch.isChecked = preferences.getBoolean(KEY_DRY_RUN, false)
        apkLabSwitch.isChecked = preferences.getBoolean(KEY_APK_LAB, false)
        textMinifySwitch.isChecked = preferences.getBoolean(KEY_TEXT_MINIFY, false)
    }

    private fun installListeners() {
        val toggle = safeButton.parent as MaterialButtonToggleGroup
        toggle.addOnButtonCheckedListener { _, checkedId, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.mode_aggressive) {
                OptimizeMode.AGGRESSIVE
            } else {
                OptimizeMode.SAFE
            }
            preferences.edit().putString(KEY_MODE, mode.name).apply()
        }
        dryRunSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_DRY_RUN, checked).apply()
            renderCurrentState()
        }
        apkLabSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_APK_LAB, checked).apply()
        }
        textMinifySwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(KEY_TEXT_MINIFY, checked).apply()
        }
    }

    @Suppress("WrongConstant")
    private fun acceptSelectedTree(uri: Uri) {
        val accessFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            activity.contentResolver.takePersistableUriPermission(uri, accessFlags)
        } catch (_: Exception) {
            try {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                Snackbar.make(view, R.string.folder_grant_not_persisted, Snackbar.LENGTH_LONG).show()
            }
        }
        selectedTreeUri = uri
        preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        capabilityCache.refresh()
        renderCurrentState()
    }

    private fun onStartClicked() {
        capabilityCache.refresh()
        renderCurrentState()
        val request = buildRequestIfValid()
        if (request == null) {
            Snackbar.make(view, R.string.folder_access_invalid, Snackbar.LENGTH_LONG).show()
            renderCurrentState()
            return
        }
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        when (
            NotificationPermissionFlow.beforeRun(
                sdkInt = Build.VERSION.SDK_INT,
                permissionGranted = granted,
                permissionPreviouslyRequested = preferences.getBoolean(
                    KEY_NOTIFICATION_PERMISSION_REQUESTED,
                    false
                )
            )
        ) {
            NotificationPermissionStep.REQUEST_PERMISSION -> {
                pendingRequest = request
                preferences.edit()
                    .putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true)
                    .apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            NotificationPermissionStep.RUN -> dispatchStart(request)
            NotificationPermissionStep.RUN_WITH_REDUCED_VISIBILITY_EXPLANATION -> {
                explainReducedNotificationVisibility()
                dispatchStart(request)
            }
        }
    }

    private fun buildRequestIfValid(): ServiceRunRequest.Optimize? {
        val uri = selectedTreeUri ?: return null
        val intent = RunIntent(
            mode = if (aggressiveButton.isChecked) OptimizeMode.AGGRESSIVE else OptimizeMode.SAFE,
            dryRun = dryRunSwitch.isChecked,
            apkLabMode = apkLabSwitch.isChecked,
            textMinify = textMinifySwitch.isChecked
        )
        val projection = OptimizeUiStateProjector.project(
            latestRunState,
            capabilityCache.current,
            intent.dryRun
        )
        if (!startDispatchGate.allowsStart(projection.startEnabled)) return null
        return ServiceRunRequest.Optimize(uri.toString(), intent)
    }

    private fun dispatchStart(request: ServiceRunRequest.Optimize) {
        startDispatchGate.beginDispatch()
        renderCurrentState()
        try {
            startRun(request)
        } catch (failure: RuntimeException) {
            startDispatchGate.onDispatchFailed()
            renderCurrentState()
            Snackbar.make(
                view,
                activity.getString(
                    R.string.start_failed,
                    failure.message ?: failure.javaClass.simpleName
                ),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun readSelectedTreeCapabilities(): SelectedTreeCapabilities {
        val uri = selectedTreeUri ?: return SelectedTreeCapabilities.NONE
        return try {
            val root = DocumentFile.fromTreeUri(activity, uri)
                ?: return SelectedTreeCapabilities.NONE
            SelectedTreeCapabilities(
                exists = root.exists(),
                isDirectory = root.isDirectory,
                canRead = root.canRead(),
                canWrite = root.canWrite()
            )
        } catch (_: RuntimeException) {
            SelectedTreeCapabilities.NONE
        }
    }

    private fun restoreSelectedTreeUri(): Uri? {
        val current = preferences.getString(KEY_TREE_URI, null)
        val persisted = current ?: activity.getPreferences(Context.MODE_PRIVATE)
            .getString(LEGACY_KEY_TREE_URI, null)
        if (current == null && persisted != null) {
            preferences.edit().putString(KEY_TREE_URI, persisted).apply()
        }
        return persisted?.let(Uri::parse)
    }

    private fun updateProgress(projection: OptimizeUiProjection) {
        OptimizeProgressIndicatorRenderer.render(
            progressPort,
            projection.progressIndeterminate,
            projection.progressMax,
            projection.progressCurrent
        )
    }

    private fun terminalMessage(projection: OptimizeUiProjection): String {
        val status = projection.terminalStatus ?: return ""
        val details = buildList {
            projection.terminalError?.takeIf { it.isNotBlank() }?.let(::add)
            addAll(projection.terminalFailures.filter { it.isNotBlank() })
        }
        return buildString {
            append(activity.getString(R.string.terminal_status_value, status.name))
            if (details.isNotEmpty()) {
                append('\n')
                append(details.joinToString(separator = "\n"))
            }
        }
    }

    private fun explainReducedNotificationVisibility() {
        Snackbar.make(view, R.string.notification_permission_denied, Snackbar.LENGTH_LONG).show()
    }

    private fun sectionTitle(text: Int) = TextView(activity).apply {
        setText(text)
        textSize = 18f
    }

    private fun modeButton(idValue: Int, textValue: Int) = MaterialButton(
        activity,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        id = idValue
        setText(textValue)
        isCheckable = true
        minHeight = dp(48)
    }

    private fun optionSwitch(idValue: Int, textValue: Int, description: Int) =
        SwitchMaterial(activity).apply {
            id = idValue
            setText(textValue)
            contentDescription = activity.getString(description)
            minHeight = dp(48)
        }

    private fun cardWithContent(content: View): MaterialCardView = MaterialCardView(activity).apply {
        radius = dp(20).toFloat()
        addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) }
        )
    }

    private fun spacedLayout() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(12) }

    private fun weightedLayout(startMargin: Int = 0, endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = startMargin
            marginEnd = endMargin
        }

    private fun formatBytes(bytes: Long): String = activity.getString(R.string.bytes_value, bytes)

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val PREFERENCES_NAME = "fileforge_optimize"
        const val KEY_TREE_URI = "tree_uri"
        const val LEGACY_KEY_TREE_URI = "treeUri"
        const val KEY_MODE = "mode"
        const val KEY_DRY_RUN = "dry_run"
        const val KEY_APK_LAB = "apk_lab"
        const val KEY_TEXT_MINIFY = "text_minify"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
    }
}
