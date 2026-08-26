package com.fileforge.optimizer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal fun foregroundServiceTypeForSdk(sdkInt: Int): Int =
    if (sdkInt >= 35) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }

class OptimizationBinder internal constructor(
    private val binding: OptimizationBinding
) : Binder() {
    val currentState: RunState
        get() = binding.currentState

    fun addListener(listener: (RunState) -> Unit) = binding.addListener(listener)
    fun removeListener(listener: (RunState) -> Unit) = binding.removeListener(listener)
}

class OptimizationService : Service(), OptimizationServiceRuntime {
    private lateinit var repository: RunStateRepository
    private lateinit var controller: OptimizationServiceController
    private lateinit var commandRouter: OptimizationServiceCommandRouter
    private lateinit var binder: OptimizationBinder
    private lateinit var notificationSubscription: AutoCloseable
    private lateinit var executor: ExecutorService
    private val notificationThrottle = NotificationUpdateThrottle(
        MonotonicClock { SystemClock.elapsedRealtime() }
    )

    @Volatile
    private var foregroundNotificationActive = false
    @Volatile
    private var detailedNotificationUpdatesAllowed = false
    @Volatile
    private var optimizeDispatchClaim: OptimizeDispatchClaim? = null

    override fun onCreate() {
        super.onCreate()
        repository = RunStateRepository.forAndroid(this)
        executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FileForge-Optimization").apply { isDaemon = false }
        }
        controller = OptimizationServiceController(
            repository = repository,
            runtime = this,
            notificationPermissionGranted = ::hasNotificationPermission
        )
        commandRouter = OptimizationServiceCommandRouter(
            controller = controller,
            restoreOwnership = ProcessRestoreLaunchOwnership.instance,
            optimizeOwnership = ProcessOptimizeDispatchOwnership.instance,
            requireRestoreClaimId = true,
            stopIdleService = { stopSelf() }
        )
        binder = OptimizationBinder(controller.binding)
        notificationSubscription = repository.observe(::updateNotification)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val policy = commandRouter.onCommand(intent?.action, intentExtras(intent))
        return when (policy) {
            ServiceRestartPolicy.NOT_STICKY -> START_NOT_STICKY
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        controller.onTimeout()
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.cancelActive()
        if (::commandRouter.isInitialized) commandRouter.close()
        else if (::controller.isInitialized) controller.close()
        if (::notificationSubscription.isInitialized) notificationSubscription.close()
        if (::executor.isInitialized) executor.shutdown()
        releaseOptimizeDispatchOwnership()
        super.onDestroy()
    }

    @SuppressLint("InlinedApi")
    override fun enterForeground(
        initialState: RunState.Running,
        detailedNotificationsAllowed: Boolean
    ) {
        optimizeDispatchClaim = if (initialState.operationKind == RunOperationKind.OPTIMIZE) {
            ProcessOptimizeDispatchOwnership.instance.current()
        } else {
            null
        }
        // Android still requires an FGS notification when POST_NOTIFICATIONS is denied.
        detailedNotificationUpdatesAllowed = detailedNotificationsAllowed
        notificationThrottle.shouldDeliver(initialState)
        ServiceCompat.startForeground(
            this,
            OptimizationNotification.NOTIFICATION_ID,
            OptimizationNotification.buildAndroidNotification(this, initialState),
            foregroundServiceTypeForSdk(Build.VERSION.SDK_INT)
        )
        foregroundNotificationActive = true
    }

    override fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    override fun run(
        request: ServiceRunRequest,
        cancellation: CancellationToken,
        onProgress: (ProgressSnapshot) -> Unit
    ): RunState.Terminal = when (request) {
        is ServiceRunRequest.Optimize -> runOptimization(request, cancellation, onProgress)
        is ServiceRunRequest.Restore -> runRestore(request, cancellation, onProgress)
    }

    override fun deliverTimeoutTerminal(
        terminal: RunState.Terminal,
        deliverObservers: () -> Unit
    ) {
        var vmFatal: Throwable? = null
        try {
            updateTerminalNotification(terminal)
        } catch (failure: Throwable) {
            if (failure.isVmFatal()) vmFatal = failure
        }
        try {
            Thread(
                {
                    // Repository delivery resets its ownership before any escaping failure. VM
                    // fatals remain uncaught on this independent thread by policy.
                    deliverObservers()
                },
                TIMEOUT_DELIVERY_THREAD_NAME
            ).apply {
                isDaemon = true
                start()
            }
        } catch (startFailure: Throwable) {
            val primary = vmFatal
            if (primary != null) {
                if (startFailure !== primary) primary.addSuppressed(startFailure)
            } else if (startFailure.isVmFatal()) {
                vmFatal = startFailure
            } else {
                // A nonfatal thread-start rejection must not leave the repository drain owned.
                deliverObservers()
            }
        }
        vmFatal?.let { throw it }
    }

    override fun stopForegroundAndSelf() {
        releaseOptimizeDispatchOwnership()
        stopForeground(STOP_FOREGROUND_DETACH)
        foregroundNotificationActive = false
        detailedNotificationUpdatesAllowed = false
        stopSelf()
    }

    private fun releaseOptimizeDispatchOwnership() {
        val owned = optimizeDispatchClaim ?: return
        optimizeDispatchClaim = null
        ProcessOptimizeDispatchOwnership.instance.onServiceFinished(owned)
    }

    private fun runOptimization(
        request: ServiceRunRequest.Optimize,
        cancellation: CancellationToken,
        onProgress: (ProgressSnapshot) -> Unit
    ): RunState.Terminal {
        val gateway = gatewayFor(request)
        val runId = newRunId()
        val engine = OptimizerEngine(
            documentGateway = gateway,
            selectedRoot = gateway.rootNode,
            candidateStore = CandidateStore(cacheDir, runId),
            runId = runId,
            runIntent = request.runIntent,
            startedAt = ::eventTimestamp,
            completedAt = ::eventTimestamp,
            appVersion = appVersion(),
            buildVariant = BuildConfig.FILEFORGE_VARIANT,
            nativeToolExecutor = NativeToolRuntime.executorOrNull(this)
        )
        return RunState.Terminal(
            engine.run(cancellation, onProgress),
            dryRun = request.runIntent.dryRun,
            operationKind = RunOperationKind.OPTIMIZE
        )
    }

    private fun runRestore(
        request: ServiceRunRequest.Restore,
        cancellation: CancellationToken,
        onProgress: (ProgressSnapshot) -> Unit
    ): RunState.Terminal {
        request.selection.requireServiceSnapshot()
        DocumentPathPolicy.requireSafeSegment(request.undoLogId)
        val gateway = gatewayFor(request)
        val undoNode = gateway.resolve(gateway.rootNode, request.undoLogId)
            ?: throw IllegalArgumentException("Undo log is missing")
        require(!undoNode.isDirectory) { "Undo log must be a file" }
        lateinit var undoIntegrity: StreamIntegrity
        val undoRun = gateway.openRead(undoNode).use { input ->
            val trackedInput = IntegrityTrackingInputStream(input)
            InputStreamReader(trackedInput, Charsets.UTF_8).use { reader ->
                UndoLogRepository().readStreamingForRestore(reader, cancellation)
                    .also { undoIntegrity = trackedInput.finish() }
            }
        }
        request.selection.requireMatchingSnapshot(undoNode, undoRun, undoIntegrity.sha256)
        require(undoRun.header.runId.isNotBlank()) { "Undo log is not recognized" }
        val receiptWriter = DocumentGatewayRestoreReceiptWriter(gateway, gateway.rootNode)
        val restore = RestoreCoordinator(
            documentGateway = gateway,
            selectedRoot = gateway.rootNode,
            receiptWriter = receiptWriter,
            onProgress = { progress ->
                onProgress(
                    ProgressSnapshot(
                        phase = "restoring",
                        currentRelativePath = progress.lastResult?.relativePath,
                        filesDiscovered = progress.totalEntries,
                        filesProcessed = progress.processedEntries,
                        optimized = progress.restoredEntries,
                        errors = progress.processedEntries - progress.restoredEntries,
                        totalWork = progress.totalEntries.takeIf { it > 0 }
                    )
                )
            },
            clock = ::safeTimestamp
        ).restore(undoRun, request.selection, cancellation)
        return RunState.Terminal(
            restore.toOptimizationReport(),
            dryRun = false,
            operationKind = RunOperationKind.RESTORE
        )
    }

    private fun gatewayFor(request: ServiceRunRequest): SafDocumentGateway {
        val document = DocumentFile.fromTreeUri(this, Uri.parse(request.treeUri))
            ?: throw IllegalArgumentException("Selected tree is unavailable")
        val capabilities = TreeAccessCapabilities(
            exists = document.exists(),
            isDirectory = document.isDirectory,
            canRead = document.canRead(),
            canWrite = document.canWrite()
        )
        require(OptimizationServiceTreeAccessPolicy.allows(request, capabilities)) {
            "Selected tree lacks the access required for this operation"
        }
        return SafDocumentGateway(this, document)
    }

    private fun intentExtras(intent: Intent?): Map<String, Any?> {
        if (intent == null) return emptyMap()
        return try {
            val bundle = intent.extras ?: return emptyMap()
            @Suppress("DEPRECATION")
            bundle.keySet().associateWith { name -> bundle.get(name) }
        } catch (failure: Throwable) {
            if (failure.isVmFatal()) throw failure
            mapOf(INVALID_EXTRAS_MARKER to true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(state: RunState) {
        if (!foregroundNotificationActive || !detailedNotificationUpdatesAllowed ||
            state === RunState.Idle || !notificationThrottle.shouldDeliver(state)
        ) return
        // The permission was checked immediately before the run; denial skips drawer updates while
        // the mandatory startForeground notification continues to represent the FGS in Task Manager.
        getSystemService(NotificationManager::class.java).notify(
            OptimizationNotification.NOTIFICATION_ID,
            OptimizationNotification.buildAndroidNotification(this, state)
        )
    }

    @SuppressLint("MissingPermission")
    private fun updateTerminalNotification(state: RunState.Terminal) {
        // The FGS notification exists even when drawer permission is denied. Updating the same ID
        // before detach preserves an actionable terminal state in every visibility surface.
        getSystemService(NotificationManager::class.java).notify(
            OptimizationNotification.NOTIFICATION_ID,
            OptimizationNotification.buildAndroidNotification(this, state)
        )
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    private fun newRunId(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) +
            "_${UUID.randomUUID().toString().take(12)}"

    private fun eventTimestamp(): String = utcFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private fun safeTimestamp(): String = utcFormat("yyyyMMdd'T'HHmmss_SSS'Z'")

    private fun utcFormat(pattern: String): String = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    companion object {
        private const val INVALID_EXTRAS_MARKER = "com.fileforge.optimizer.extra.INVALID"
        private const val TIMEOUT_DELIVERY_THREAD_NAME = "FileForge-Timeout-Terminal"
        const val ACTION_START = OptimizationServiceContract.ACTION_START
        const val ACTION_RESTORE = OptimizationServiceContract.ACTION_RESTORE
        const val ACTION_CANCEL = OptimizationServiceContract.ACTION_CANCEL
        const val EXTRA_TREE_URI = OptimizationServiceContract.EXTRA_TREE_URI
        const val EXTRA_RUN_INTENT = OptimizationServiceContract.EXTRA_RUN_INTENT
        const val EXTRA_UNDO_LOG_ID = OptimizationServiceContract.EXTRA_UNDO_LOG_ID
        const val EXTRA_RESTORE_SELECTION = OptimizationServiceContract.EXTRA_RESTORE_SELECTION

        fun start(context: Context, request: ServiceRunRequest) {
            val encoded = OptimizationServiceRequestCodec.encode(request)
            val intent = Intent(context, OptimizationService::class.java).setAction(encoded.action)
            encoded.extras.forEach { (name, value) ->
                intent.putExtra(name, value as String)
            }
            if (request is ServiceRunRequest.Restore) {
                ProcessRestoreLaunchOwnership.instance
                    .captureForService(request)
                    ?.let { claim ->
                        intent.putExtra(
                            OptimizationServiceContract.EXTRA_RESTORE_CLAIM_ID,
                            claim.id
                        )
                    }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun cancelIntent(context: Context): Intent =
            Intent(context, OptimizationService::class.java).setAction(ACTION_CANCEL)

        fun cancel(context: Context) {
            context.startService(cancelIntent(context))
        }
    }
}
