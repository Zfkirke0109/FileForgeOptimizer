package com.fileforge.optimizer

import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Restore presentation only. It observes service state and dispatches a serialized restore request;
 * document mutation remains exclusively inside [RestoreCoordinator] in [OptimizationService].
 */
class RestoreScreenController(
    private val activity: AppCompatActivity,
    private val startRestore: (ServiceRunRequest.Restore) -> Unit,
    private val cancelRun: () -> Unit
) : AutoCloseable {
    val view: View

    private val testFixture = RestoreScreenTestHooks.snapshot()
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FileForge-Restore-Discovery").apply { isDaemon = true }
    }
    private var selectedTreeUri: String? = testFixture?.treeUri ?: readSelectedTreeUri()
    private var capabilities: SelectedTreeCapabilities = testFixture?.selectedTree ?: readCapabilities()
    @Volatile private var discoveryTreeUri: String? = null
    private var discoveryLoading = false
    private var discoveryResult = testFixture?.discovery ?: RestoreDiscoveryResult(emptyList(), emptyList())
    private var selectedUndoLogId: String? = null
    private val selectedPaths = linkedSetOf<String>()
    private var allSelected = false
    private var latestRunState: RunState = RunState.Idle
    private var closed = false
    private val discoverySession = RestoreDiscoverySession(
        schedule = { task -> executor.execute(task) },
        createDiscovery = {
            val treeUri = discoveryTreeUri
                ?: throw IllegalStateException("Selected folder is unavailable")
            val root = DocumentFile.fromTreeUri(activity, Uri.parse(treeUri))
                ?: throw IllegalStateException("Selected folder is unavailable")
            val gateway = SafDocumentGateway(activity, root)
            RestoreLogDiscovery(gateway, gateway.rootNode)
        },
        onResult = { result ->
            if (!closed) {
                discoveryLoading = false
                discoveryResult = result
                clearSelection()
                render()
            }
        },
        deliver = { task -> activity.runOnUiThread(task) }
    )

    private lateinit var discoveryStatus: TextView
    private lateinit var cards: LinearLayout
    private lateinit var restoreButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var progressSummary: TextView
    private lateinit var terminalSummary: TextView
    private lateinit var terminalDetails: LinearLayout

    init {
        view = buildView()
        render()
    }

    fun onVisible() {
        refreshSelectedTree()
        if (testFixture == null) refreshDiscovery()
        render()
    }

    fun onHidden() {
        discoverySession.onHidden()
        discoveryLoading = false
    }

    fun render(observation: SequencedRunState) {
        latestRunState = observation.state
        render()
    }

    override fun close() {
        if (closed) return
        closed = true
        discoverySession.close()
        executor.shutdownNow()
    }

    private fun refreshDiscovery() {
        if (!canReadTree()) {
            discoverySession.onHidden()
            discoveryLoading = false
            discoveryResult = RestoreDiscoveryResult(emptyList(), emptyList())
            clearSelection()
            render()
            return
        }
        discoveryTreeUri = selectedTreeUri ?: return
        discoveryLoading = true
        render()
        discoverySession.onVisible()
    }

    private fun refreshSelectedTree() {
        if (testFixture != null) return
        val latest = readSelectedTreeUri()
        val wasReadable = canReadTree()
        if (latest != selectedTreeUri) {
            selectedTreeUri = latest
            discoveryResult = RestoreDiscoveryResult(emptyList(), emptyList())
            clearSelection()
        }
        capabilities = readCapabilities()
        if (wasReadable && !canReadTree()) clearSelection()
    }

    private fun buildView(): View = NestedScrollView(activity).apply {
        isFillViewport = true
        contentDescription = activity.getString(R.string.restore_screen_description)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            addView(TextView(activity).apply { setText(R.string.restore_scope_description) })
            discoveryStatus = TextView(activity).apply {
                id = R.id.restore_empty_state
                setPadding(0, dp(12), 0, dp(8))
            }
            addView(discoveryStatus)
            cards = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            addView(cards)
            addView(actionRow(), spacedLayout())
            progressSummary = TextView(activity).apply {
                id = R.id.restore_progress
                setPadding(0, dp(16), 0, 0)
            }
            addView(progressSummary)
            terminalSummary = TextView(activity).apply {
                id = R.id.restore_terminal_summary
                setPadding(0, dp(16), 0, 0)
                setTextIsSelectable(true)
            }
            addView(terminalSummary)
            terminalDetails = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            addView(terminalDetails)
        })
    }

    private fun actionRow(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(MaterialButton(activity).apply {
            id = R.id.restore_select_all
            setText(R.string.restore_select_all)
            minHeight = dp(48)
            setOnClickListener { selectAllForSelectedRun() }
        }, weightedLayout(endMargin = dp(4)))
        restoreButton = MaterialButton(activity).apply {
            id = R.id.restore_selected
            setText(R.string.restore_selected)
            minHeight = dp(48)
            setOnClickListener { confirmSelectedRestore() }
        }
        addView(restoreButton, weightedLayout(startMargin = dp(4)))
        cancelButton = MaterialButton(activity).apply {
            id = R.id.cancel_restore
            setText(R.string.cancel_restore)
            minHeight = dp(48)
            visibility = View.GONE
            setOnClickListener { cancelRun() }
        }
        addView(cancelButton, weightedLayout(startMargin = dp(4)))
    }

    private fun render() {
        if (closed) return
        renderDiscovery()
        val running = latestRunState is RunState.Running
        restoreButton.isEnabled = !running &&
            ProcessRestoreLaunchOwnership.instance.current() == null &&
            canWriteTree() &&
            hasSelection()
        cancelButton.visibility = if (running && (latestRunState as RunState.Running).operationKind == RunOperationKind.RESTORE) {
            View.VISIBLE
        } else {
            View.GONE
        }
        progressSummary.text = restoreProgressMessage()
        progressSummary.visibility = if (progressSummary.text.isEmpty()) View.GONE else View.VISIBLE
        terminalSummary.text = restoreTerminalHeadline()
        terminalSummary.visibility = if (terminalSummary.text.isEmpty()) View.GONE else View.VISIBLE
        terminalDetails.removeAllViews()
        restoreTerminalDetails().forEach { detail ->
            terminalDetails.addView(TextView(activity).apply {
                text = detail
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun renderDiscovery() {
        discoveryStatus.text = when {
            discoveryLoading -> activity.getString(R.string.restore_loading)
            !canReadTree() -> activity.getString(R.string.restore_tree_unavailable)
            discoveryResult.runs.isEmpty() && discoveryResult.failures.isEmpty() -> activity.getString(R.string.restore_empty)
            discoveryResult.runs.isEmpty() -> activity.getString(R.string.restore_empty)
            else -> ""
        }
        discoveryStatus.visibility = if (discoveryStatus.text.isEmpty()) View.GONE else View.VISIBLE
        cards.removeAllViews()
        discoveryResult.runs.forEach { discovered -> cards.addView(runCard(discovered), spacedLayout()) }
        discoveryResult.failures.forEach { failure ->
            cards.addView(TextView(activity).apply {
                text = activity.getString(R.string.restore_parse_failure, failure.undoLogId, failure.message)
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun runCard(discovered: DiscoveredUndoLog): View {
        val card = RestoreRunCard.from(discovered.undoLogId, discovered.run)
        return MaterialCardView(activity).apply {
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(TextView(activity).apply { text = card.runId; textSize = 18f })
                addView(TextView(activity).apply { text = activity.getString(R.string.restore_run_details, card.runDate, card.status.name, card.entryCount, formatBytes(card.recoverableBytes)) })
                addView(TextView(activity).apply { text = card.verificationLabel })
                addView(MaterialButton(activity).apply {
                    text = activity.getString(R.string.restore_select_all)
                    contentDescription = "Select all from ${card.runId}"
                    setOnClickListener { selectAll(discovered) }
                })
                card.entries.forEach { entry ->
                    addView(CheckBox(activity).apply {
                        id = View.generateViewId()
                        isSaveEnabled = false
                        text = entry.relativePath
                        contentDescription = activity.getString(R.string.restore_select_entry, entry.relativePath)
                        isChecked = selectedUndoLogId == card.undoLogId &&
                            (allSelected || entry.relativePath in selectedPaths)
                        setOnCheckedChangeListener { _, checked -> updateSelection(card.undoLogId, entry.relativePath, checked) }
                    })
                    addView(TextView(activity).apply {
                        text = activity.getString(
                            R.string.restore_entry_details,
                            formatBytes(entry.originalBytes),
                            formatBytes(entry.optimizedBytes),
                            entry.backupPath
                        )
                        setPadding(dp(32), 0, 0, dp(8))
                    })
                    addView(TextView(activity).apply {
                        text = entry.verificationLabel
                        setPadding(dp(32), 0, 0, dp(8))
                    })
                }
            })
        }
    }

    private fun updateSelection(undoLogId: String, path: String, checked: Boolean) {
        if (checked && selectedUndoLogId != undoLogId) {
            selectedUndoLogId = undoLogId
            selectedPaths.clear()
            allSelected = false
        }
        if (selectedUndoLogId == undoLogId) {
            if (!checked && allSelected) {
                selectedPaths.clear()
                discoveryResult.runs
                    .firstOrNull { it.undoLogId == undoLogId }
                    ?.run
                    ?.entries
                    ?.asSequence()
                    ?.map { it.relativePath }
                    ?.filter { it != path }
                    ?.toCollection(selectedPaths)
                allSelected = false
            } else if (checked) {
                selectedPaths += path
            } else {
                selectedPaths -= path
            }
            if (!allSelected && selectedPaths.isEmpty()) selectedUndoLogId = null
        }
        render()
    }

    private fun selectAllForSelectedRun() {
        val id = selectedUndoLogId ?: return
        discoveryResult.runs.firstOrNull { it.undoLogId == id }?.let(::selectAll)
    }

    private fun selectAll(run: DiscoveredUndoLog) {
        selectedUndoLogId = run.undoLogId
        selectedPaths.clear()
        allSelected = true
        render()
    }

    private fun confirmSelectedRestore() {
        val undoLogId = selectedUndoLogId ?: return
        if (!restoreButton.isEnabled) return
        val count = selectedCount(undoLogId)
        if (count <= 0) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.resources.getQuantityString(R.plurals.restore_confirmation_title, count, count))
            .setMessage(R.string.restore_confirmation_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_confirm) { _, _ ->
                if (latestRunState is RunState.Running) return@setPositiveButton
                val treeUri = selectedTreeUri ?: return@setPositiveButton
                val request = ServiceRunRequest.Restore(
                    treeUri = treeUri,
                    undoLogId = undoLogId,
                    selection = if (allSelected) {
                        RestoreSelection.All
                    } else {
                        RestoreSelection.Entries(selectedPaths.toSet())
                    }
                )
                try {
                    OptimizationServiceRequestCodec.encode(request)
                } catch (failure: Throwable) {
                    if (failure.isVmFatal()) throw failure
                    showRestoreStartFailure(failure)
                    return@setPositiveButton
                }
                val claim = ProcessRestoreLaunchOwnership.instance.tryClaim(
                    request,
                    optimizePending = ProcessOptimizeDispatchOwnership.instance.current() != null
                ) ?: return@setPositiveButton
                render()
                try {
                    startRestore(request)
                } catch (failure: Throwable) {
                    ProcessRestoreLaunchOwnership.instance.onDispatchFailed(claim)
                    if (failure.isVmFatal()) throw failure
                    render()
                    showRestoreStartFailure(failure)
                }
            }
            .show()
    }

    private fun hasSelection(): Boolean = selectedUndoLogId != null && (allSelected || selectedPaths.isNotEmpty())

    private fun selectedCount(undoLogId: String): Int = if (allSelected) {
        discoveryResult.runs.firstOrNull { it.undoLogId == undoLogId }?.run?.entries?.size ?: 0
    } else {
        selectedPaths.size
    }

    private fun clearSelection() {
        selectedUndoLogId = null
        selectedPaths.clear()
        allSelected = false
    }

    private fun showRestoreStartFailure(failure: Throwable) {
        Snackbar.make(
            view,
            failure.message ?: "Restore could not be started",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun restoreTerminalHeadline(): String {
        val terminal = latestRunState as? RunState.Terminal ?: return ""
        if (terminal.operationKind != RunOperationKind.RESTORE) return ""
        val report = terminal.report
        return activity.getString(R.string.restore_terminal_counts, report.optimized, report.scanned)
    }

    private fun restoreTerminalDetails(): List<String> {
        val terminal = latestRunState as? RunState.Terminal ?: return emptyList()
        if (terminal.operationKind != RunOperationKind.RESTORE) return emptyList()
        val report = terminal.report
        return buildList {
            add(activity.getString(R.string.restore_failed_count, report.errors))
            if (report.status == RunStatus.CANCELLED) add(activity.getString(R.string.restore_cancelled))
            addAll(report.terminalFailures.filter { it.isNotBlank() })
            report.terminalError?.takeIf { it.isNotBlank() }?.let(::add)
            report.restoreReceiptName?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun restoreProgressMessage(): String {
        val running = latestRunState as? RunState.Running ?: return ""
        if (running.operationKind != RunOperationKind.RESTORE) return ""
        val snapshot = running.snapshot
        return activity.getString(
            R.string.restore_progress_value,
            snapshot.filesProcessed,
            snapshot.filesDiscovered,
            snapshot.currentRelativePath ?: snapshot.phase
        )
    }

    private fun canReadTree(): Boolean = capabilities.exists && capabilities.isDirectory && capabilities.canRead
    private fun canWriteTree(): Boolean = canReadTree() && capabilities.canWrite

    private fun readCapabilities(): SelectedTreeCapabilities {
        val value = selectedTreeUri ?: return SelectedTreeCapabilities.NONE
        if (!ContentTreeUriValidator.isValid(value)) return SelectedTreeCapabilities.NONE
        return try {
            val root = DocumentFile.fromTreeUri(activity, Uri.parse(value)) ?: return SelectedTreeCapabilities.NONE
            SelectedTreeCapabilities(root.exists(), root.isDirectory, root.canRead(), root.canWrite())
        } catch (_: RuntimeException) {
            SelectedTreeCapabilities.NONE
        }
    }

    private fun readSelectedTreeUri(): String? = preferences.getString(KEY_TREE_URI, null)
        ?: activity.getPreferences(Context.MODE_PRIVATE).getString(LEGACY_KEY_TREE_URI, null)

    private fun formatBytes(bytes: Long): String = "$bytes B"
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
    private fun spacedLayout() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    private fun weightedLayout(startMargin: Int = 0, endMargin: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = startMargin; marginEnd = endMargin }

    private companion object {
        const val PREFERENCES_NAME = "fileforge_optimize"
        const val KEY_TREE_URI = "tree_uri"
        const val LEGACY_KEY_TREE_URI = "treeUri"
    }
}

/** Safe in-memory presentation seam used only by instrumentation; it never opens or writes SAF documents. */
object RestoreScreenTestHooks {
    private val fixture = AtomicReference<Fixture?>(null)

    fun install(
        selectedTree: SelectedTreeCapabilities,
        discovery: RestoreDiscoveryResult,
        startRestore: ((ServiceRunRequest.Restore) -> Unit)? = null
    ) {
        fixture.set(Fixture(selectedTree, discovery, startRestore))
    }

    fun clear() {
        fixture.set(null)
    }

    internal fun snapshot(): Fixture? = fixture.get()

    internal data class Fixture(
        val selectedTree: SelectedTreeCapabilities,
        val discovery: RestoreDiscoveryResult,
        val startRestore: ((ServiceRunRequest.Restore) -> Unit)?,
        val treeUri: String = "content://test/selected-root"
    )
}
