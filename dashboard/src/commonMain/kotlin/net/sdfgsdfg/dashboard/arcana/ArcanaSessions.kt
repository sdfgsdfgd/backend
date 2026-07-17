package net.sdfgsdfg.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.sdfgsdfg.data.model.OPS_CAPABILITY_SESSIONS_RUN
import net.sdfgsdfg.data.model.OpsAgentDto
import net.sdfgsdfg.data.model.OpsArcanaModeDto
import net.sdfgsdfg.data.model.OpsPacingProfileDto
import net.sdfgsdfg.data.model.OpsRepositoryDto
import net.sdfgsdfg.data.model.OpsSessionActionDto
import net.sdfgsdfg.data.model.OpsSessionChannelDto
import net.sdfgsdfg.data.model.OpsSessionCommandDto
import net.sdfgsdfg.data.model.OpsSessionEventDto
import net.sdfgsdfg.data.model.OpsSessionEventKindDto
import net.sdfgsdfg.data.model.OpsSessionStateDto
import net.sdfgsdfg.data.model.OpsSessionSummaryDto
import net.sdfgsdfg.data.model.OpsViewerDto
import net.sdfgsdfg.data.model.OpsWorkspaceActionDto
import net.sdfgsdfg.data.model.OpsWorkspaceCommandDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventKindDto
import net.sdfgsdfg.data.model.OpsWorkspaceEventStatusDto
import net.sdfgsdfg.data.model.isActiveRuntime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

private enum class XPhase { REPOSITORIES, WORKSPACE, SESSION }
private enum class XDelivery { ACKNOWLEDGED, NOT_SENT, TIMED_OUT }
private enum class XAgent(val label: String, val wire: OpsAgentDto) {
    ARCANA("Arcana", OpsAgentDto.ARCANA),
    CODEX("Codex", OpsAgentDto.CODEX),
}

private enum class XArcanaModel(val label: String, val compact: String, val wire: String) {
    DEEPSEEK_EXPERT("DeepSeek Expert", "Expert", "deepseek-expert"),
    DEEPSEEK_INSTANT("DeepSeek Instant", "Instant", "deepseek-instant"),
    GPT_56_THINKING_STANDARD("GPT 5.6 Thinking", "5.6 Think", "5.6-thinking-standard"),
    GPT_56_THINKING_HEAVY("GPT 5.6 Thinking Heavy", "5.6 Heavy", "5.6-thinking-heavy"),
    GPT_56_PRO("GPT 5.6 Pro", "5.6 Pro", "5.6-pro");

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value } ?: DEEPSEEK_EXPERT
    }
}

private val XArcanaModel.pacingProvider get() = if (wire.startsWith("deepseek")) "deepseek" else "chatgpt"

private data class XArcanaLaunch(
    val model: XArcanaModel,
    val noPace: Boolean,
    val paceProvider: String?,
    val paceMinSeconds: Double?,
    val paceMaxSeconds: Double?,
    val auto: Boolean,
    val indexSync: Boolean,
    val mode: OpsArcanaModeDto,
)

private fun XArcanaLaunch.paceFor(provider: String) =
    if (paceProvider == provider && paceMinSeconds != null && paceMaxSeconds != null) paceMinSeconds to paceMaxSeconds else null

private fun Double.xPaceDuration(): String {
    val seconds = roundToInt()
    return if (seconds >= 60 && seconds % 60 == 0) "${seconds / 60}m" else "${seconds}s"
}

private val OpsArcanaModeDto.label get() = when (this) {
    OpsArcanaModeDto.WORKSPACE -> "Workspace"
    OpsArcanaModeDto.ISSUES -> "Issues"
    OpsArcanaModeDto.GENERAL -> "General"
}

private val OpsArcanaModeDto.detail get() = when (this) {
    OpsArcanaModeDto.WORKSPACE -> "repository comprehension"
    OpsArcanaModeDto.ISSUES -> "repository + issue commands"
    OpsArcanaModeDto.GENERAL -> "isolated non-repository chat"
}

private data class XRepoPreview(
    val id: Long,
    val name: String,
    val description: String,
    val language: String,
    val stars: Int,
    val updated: String,
)

private data class XInputRequestUi(val kind: String, val prompt: String, val allowEmpty: Boolean)
private data class XInputUi(val placeholder: String, val action: String? = null, val actionColor: Color = Color.Transparent, val enabled: Boolean = true)
internal data class XArcanaActivity(val id: String, val label: String)
private const val ARCANA_ACTIVITY_SCHEMA = "arcana.activity.v1"
private const val X_REPOSITORY_PREF = "ops.x.repositoryId"
private const val X_AGENT_PREF = "ops.x.agent"
private const val X_NATIVE_SESSION_PREF = "ops.x.nativeSessionId"
private const val X_STREAM_BLOCK_CHARS = 4_096
private val xCodexVisibleItems = setOf(
    "userMessage", "agentMessage", "commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "plan",
    "webSearch", "imageView", "imageGeneration", "collabAgentToolCall", "subAgentActivity", "enteredReviewMode",
    "exitedReviewMode", "contextCompaction",
)
internal data class XRenderedEvent(
    val event: OpsSessionEventDto,
    val text: AnnotatedString? = null,
    val rawText: AnnotatedString? = text,
    val latestArcanaResponse: Boolean = false,
    val key: Any = event.xTransportKey(),
    val revision: Any = event.xTransportKey(),
    val streamContinuation: Boolean = false,
    val trimLeadingStreamNewlines: Boolean = false,
    val trimTrailingStreamNewlines: Boolean = false,
)
private data class XAnsiState(var color: Color? = null, var bold: Boolean = false, var dim: Boolean = false, var carry: String = "")

internal fun OpsSessionEventDto.xTransportKey(): Any = sequence?.let { "${runtimeId ?: "-"}:$it" } ?: this

private val OpsSessionEventDto.xReplayGap get() = replay && sequence == null
private val OpsSessionEventDto.xAffectsSessionControl get() = when (kind) {
    OpsSessionEventKindDto.SESSIONS,
    OpsSessionEventKindDto.LIFECYCLE,
    OpsSessionEventKindDto.ERROR,
    OpsSessionEventKindDto.PACING_PROFILE -> true
    OpsSessionEventKindDto.STRUCTURED -> structured?.type == "session_state"
    OpsSessionEventKindDto.STREAM -> false
}

private val OpsSessionEventDto.xAffectsRuntimeView get() = when {
    xReplayGap || kind == OpsSessionEventKindDto.LIFECYCLE || kind == OpsSessionEventKindDto.ERROR ||
        channel == OpsSessionChannelDto.STDIN -> true
    else -> when (structured?.type) {
        "session_state", "input_request", "input_resolved", "input_timeout" -> true
        else -> false
    }
}

@Stable
internal class XSessionLedger(private val limit: Int = 4_000) {
    private val events = mutableListOf<OpsSessionEventDto>()
    private val keys = mutableSetOf<Any>()
    private val controlEventChannel = Channel<OpsSessionEventDto>(256, BufferOverflow.DROP_OLDEST)
    private val controlRevision = MutableStateFlow(0L)
    private val presentationRevisions = mutableMapOf<String, MutableLongState>()
    private val runtimeViewRevisions = mutableMapOf<String, MutableLongState>()
    private var normalCount = 0
    val controlEvents: ReceiveChannel<OpsSessionEventDto> = controlEventChannel

    fun append(event: OpsSessionEventDto) {
        if (event.xReplayGap) {
            events.indexOfFirst { it.xReplayGap && it.runtimeId == event.runtimeId }.takeIf { it >= 0 }?.let { index ->
                val previous = events[index]
                if (previous.xTransportKey() == event.xTransportKey()) return
                keys.remove(events.removeAt(index).xTransportKey())
            }
        }
        if (!keys.add(event.xTransportKey())) return
        events += event
        event.runtimeId?.let { presentationRevisions.state(it).longValue++ }
        if (event.xAffectsRuntimeView) event.runtimeId?.let { runtimeViewRevisions.state(it).longValue++ }
        if (event.xAffectsSessionControl) {
            controlRevision.value++
            check(controlEventChannel.trySend(event).isSuccess)
        }
        if (!event.xReplayGap && ++normalCount > limit) trim()
    }

    fun presentationRevision(runtimeId: String?) = runtimeId?.let { presentationRevisions.state(it).longValue } ?: 0L

    fun runtimeViewRevision(runtimeId: String?) = runtimeId?.let { runtimeViewRevisions.state(it).longValue } ?: 0L

    fun snapshot(): List<OpsSessionEventDto> = events.toList()

    fun lastOrNull(predicate: (OpsSessionEventDto) -> Boolean) = events.lastOrNull(predicate)

    suspend fun awaitControl(predicate: (OpsSessionEventDto) -> Boolean) =
        controlRevision.mapNotNull { events.lastOrNull(predicate) }.first()

    fun maxSequence(runtimeId: String) = events.asSequence()
        .filter { it.runtimeId == runtimeId }
        .mapNotNull(OpsSessionEventDto::sequence)
        .maxOrNull()

    private fun trim() {
        val target = if (limit < 32) limit else limit - maxOf(1, limit / 8)
        var drop = normalCount - target
        val retained = ArrayList<OpsSessionEventDto>(events.size - drop)
        events.forEach { event ->
            if (!event.xReplayGap && drop > 0) {
                keys.remove(event.xTransportKey())
                drop--
            } else {
                retained += event
            }
        }
        normalCount = target
        events.clear()
        events.addAll(retained)
    }

    private fun MutableMap<String, MutableLongState>.state(runtimeId: String) =
        getOrPut(runtimeId) { mutableLongStateOf(0L) }
}

internal fun List<OpsSessionEventDto>.xRuntimeEvents(runtimeId: String?) = runtimeId?.let { id ->
    filter { it.runtimeId == id }
}.orEmpty()

private val OpsSessionSummaryDto.xKey get() = runtimeId ?: sessionId
private fun OpsSessionSummaryDto.withEvent(event: OpsSessionEventDto): OpsSessionSummaryDto {
    val next = event.state ?: state
    val nextSessionId = event.sessionId ?: sessionId
    val nextDetail = if (next?.isActiveRuntime == true) "live" else "retained"
    val nextChangesKnown = event.structured?.payload?.boolean("changes_known") ?: changesKnown
    val nextHasChanges = event.structured?.payload?.boolean("has_changes") ?: hasChanges
    if (
        nextSessionId == sessionId && next == state && nextDetail == detail &&
        nextChangesKnown == changesKnown && nextHasChanges == hasChanges
    ) return this
    return copy(
        sessionId = nextSessionId,
        updatedAtMs = event.timestampMs ?: updatedAtMs,
        state = next,
        detail = nextDetail,
        changesKnown = nextChangesKnown,
        hasChanges = nextHasChanges,
    )
}

private data class XRuntimeView(
    val sessionState: JsonObject? = null,
    val pendingInput: XInputRequestUi? = null,
    val runtimeState: OpsSessionStateDto? = null,
    val replayGap: OpsSessionEventDto? = null,
    val exitCode: Int? = null,
) {
    companion object { val Empty = XRuntimeView() }
}

private fun List<OpsSessionEventDto>.xRuntimeView(runtimeId: String?): XRuntimeView {
    val runtimeEvents = xRuntimeEvents(runtimeId)
    val sessionStateEvent = runtimeEvents.lastOrNull { it.structured?.type == "session_state" }
    val inputBoundary = maxOf(
        sessionStateEvent
            ?.takeIf { it.structured?.payload?.field("status") == "concluded" }
            ?.sequence ?: 0,
        runtimeEvents.lastOrNull { it.channel == OpsSessionChannelDto.STDIN }?.sequence ?: 0,
        runtimeEvents.lastOrNull {
            it.structured?.type == "input_resolved" || it.structured?.type == "input_timeout"
        }?.sequence ?: 0,
        runtimeEvents.lastOrNull { it.state?.isActiveRuntime == false }?.sequence ?: 0,
    )
    val runtimeStateEvent = runtimeEvents.lastOrNull { it.state != null }
    return XRuntimeView(
        sessionState = sessionStateEvent?.structured?.payload,
        pendingInput = runtimeEvents.lastOrNull { it.structured?.type == "input_request" }
            ?.takeIf { (it.sequence ?: 0) > inputBoundary }
            ?.structured?.payload
            ?.let { XInputRequestUi(it.field("kind"), it.field("prompt"), it.boolean("allow_empty") != false) },
        runtimeState = runtimeStateEvent?.state,
        replayGap = runtimeEvents.lastOrNull { it.xReplayGap },
        exitCode = runtimeStateEvent?.exitCode,
    )
}

@Composable
private fun rememberXRuntimeView(ledger: XSessionLedger, runtimeId: String?): XRuntimeView =
    produceState(XRuntimeView.Empty, ledger, runtimeId) {
        snapshotFlow { ledger.runtimeViewRevision(runtimeId) }
            .conflate()
            .collect {
                val events = ledger.snapshot()
                val next = withContext(Dispatchers.Default) { events.xRuntimeView(runtimeId) }
                if (next != value) value = next
            }
    }.value

@Composable
internal fun ArcanaSessionsTab(
    windowKeys: DashboardWindowKeyRouter?,
    freshXSignal: Int,
    viewer: OpsViewerDto,
    socketState: OpsSocketState,
    pageHeight: Dp,
    workspaceEvent: OpsWorkspaceEventDto?,
    sessionLedger: XSessionLedger,
    sendWorkspaceCommand: (OpsWorkspaceCommandDto) -> Boolean,
    sendSessionCommand: (OpsSessionCommandDto) -> Boolean,
) {
    var phase by remember { mutableStateOf(XPhase.REPOSITORIES) }
    var handledFreshXSignal by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var repositories by remember { mutableStateOf(emptyList<XRepoPreview>()) }
    var cursorRepositoryId by remember { mutableStateOf(readDashboardPref(X_REPOSITORY_PREF)?.toLongOrNull()) }
    var selectedRepoId by remember { mutableStateOf(cursorRepositoryId) }
    var repositoryRequestId by remember { mutableStateOf<String?>(null) }
    var syncRequestId by remember { mutableStateOf<String?>(null) }
    var selectedAgent by remember {
        val storedAgent = readDashboardPref(X_AGENT_PREF)
        mutableStateOf(XAgent.entries.firstOrNull { it.name == storedAgent } ?: XAgent.ARCANA)
    }
    var nativeSessionCursor by remember { mutableStateOf(readDashboardPref(X_NATIVE_SESSION_PREF)?.takeIf(String::isNotBlank)) }
    var arcana by remember {
        mutableStateOf(
            XArcanaLaunch(
                XArcanaModel.fromWire(readDashboardPref("ops.x.arcana.model")),
                readDashboardPref("ops.x.arcana.noPace")?.toBooleanStrictOrNull() ?: false,
                readDashboardPref("ops.x.arcana.paceProvider")?.takeIf(String::isNotBlank),
                readDashboardPref("ops.x.arcana.paceMinSeconds")?.toDoubleOrNull(),
                readDashboardPref("ops.x.arcana.paceMaxSeconds")?.toDoubleOrNull(),
                readDashboardPref("ops.x.arcana.auto")?.toBooleanStrictOrNull() ?: false,
                readDashboardPref("ops.x.arcana.indexSync")?.toBooleanStrictOrNull() ?: false,
                OpsArcanaModeDto.entries.firstOrNull { it.name == readDashboardPref("ops.x.arcana.mode") }
                    ?: OpsArcanaModeDto.WORKSPACE,
            ),
        )
    }
    var repositoryStatus by remember { mutableStateOf(XSyncUiState()) }
    var workspaceStatus by remember { mutableStateOf(XSyncUiState()) }
    var workspaceId by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf(emptyList<OpsSessionSummaryDto>()) }
    var sessionScope by remember { mutableStateOf<Pair<Long, XAgent>?>(null) }
    var pacingProfile by remember { mutableStateOf<OpsPacingProfileDto?>(null) }
    var pacingRequestId by remember { mutableStateOf<String?>(null) }
    var sessionListRequestId by remember { mutableStateOf<String?>(null) }
    var sessionListRetry by remember { mutableStateOf(0) }
    var globalSessions by remember { mutableStateOf(emptyList<OpsSessionSummaryDto>()) }
    var globalSessionRequestId by remember { mutableStateOf<String?>(null) }
    var globalSessionRefresh by remember { mutableStateOf(0) }
    var workspaceRebindAttempted by remember { mutableStateOf(false) }
    var selectedSessionKey by remember { mutableStateOf<String?>(null) }
    var startRequestId by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf<OpsSessionCommandDto?>(null) }
    var arcanaSubmitting by remember { mutableStateOf(false) }
    var startRetry by remember { mutableStateOf(0) }
    var runtimeId by remember { mutableStateOf<String?>(null) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var sessionMessage by remember { mutableStateOf("Select or create a session") }
    var detached by remember { mutableStateOf(false) }
    val discoveredGlobalRuntimes = remember { mutableSetOf<String>() }
    val filtered = remember(phase, query.text, repositories) {
        if (phase != XPhase.REPOSITORIES) repositories
        else repositories.filter { repo -> query.text.isBlank() || query.text.isSubsequenceOf("${repo.name} ${repo.description} ${repo.language}") }
    }
    val selectedRepo = repositories.firstOrNull { it.id == selectedRepoId }
    val currentSelectedRepo by rememberUpdatedState(selectedRepo)
    val currentSendWorkspaceCommand by rememberUpdatedState(sendWorkspaceCommand)
    val runtimeView = rememberXRuntimeView(sessionLedger, runtimeId)
    val sessionState = runtimeView.sessionState
    val pendingInput = runtimeView.pendingInput
    val awaitingAcceptance = sessionState?.field("status") == "awaiting_acceptance" && pendingInput?.kind == "objective_acceptance"
    val runtimeState = runtimeView.runtimeState
    val live = runtimeState?.isActiveRuntime == true && sessionState?.field("status") != "concluded"
    val activeSessions = globalSessions.filter { it.runtimeId != null && it.state?.isActiveRuntime == true }
    val inputUi = when (phase) {
        XPhase.REPOSITORIES -> XInputUi("Filter repositories · ↑↓ select · Enter open")
        XPhase.WORKSPACE -> XInputUi("Describe the first turn · Enter create ${selectedAgent.label}")
        XPhase.SESSION -> {
            val sandboxed = sessionState?.boolean("sandboxed") == true
            val changesKnown = sessionState?.boolean("changes_known") == true
            val canAccept = !sandboxed || changesKnown
            val enabled = live && when {
                pendingInput != null -> (pendingInput.allowEmpty && (!awaitingAcceptance || canAccept)) || query.text.isNotBlank()
                else -> query.text.isNotBlank()
            }
            val action = if (awaitingAcceptance) when {
                query.text.isNotBlank() -> "Continue"
                sandboxed && !changesKnown -> "Checking…"
                sandboxed && sessionState.boolean("has_changes") == true -> "Apply & Quit"
                else -> "Quit"
            } else null
            XInputUi(
                pendingInput?.prompt?.takeIf(String::isNotBlank) ?: when {
                    runtimeState == OpsSessionStateDto.INTERRUPTED ->
                        "Session interrupted · Esc returns to sessions"
                    sessionState?.field("status") == "concluded" || runtimeState in setOf(OpsSessionStateDto.EXITED, OpsSessionStateDto.FAILED, OpsSessionStateDto.STOPPED) ->
                        "Session concluded · Esc returns to sessions"
                    live -> "Queue feedback"
                    else -> "Session is not attached"
                },
                action,
                if (query.text.isNotBlank()) Color(0xFF56307D) else Color(0xFF856321),
                enabled,
            )
        }
    }
    val replayGap = runtimeView.replayGap
    val sessionStatus = XSyncUiState(
        stage = when {
            replayGap != null || runtimeState == OpsSessionStateDto.FAILED -> XSyncStage.ERROR
            runtimeState == OpsSessionStateDto.STARTING -> XSyncStage.INITIALIZING
            runtimeState == OpsSessionStateDto.RUNNING -> XSyncStage.SYNCING
            awaitingAcceptance || pendingInput != null || runtimeState in setOf(
                OpsSessionStateDto.READY,
                OpsSessionStateDto.AWAITING_ACCEPTANCE,
                OpsSessionStateDto.CONCLUDED,
                OpsSessionStateDto.EXITED,
            ) -> XSyncStage.SYNCHRONIZED
            else -> XSyncStage.IDLE
        },
        progress = 100,
        message = when {
            replayGap != null -> listOfNotNull(
                "Replay gap · ${replayGap.text ?: "older output expired"}",
                runtimeState?.xLabel(),
            ).joinToString(" · ")
            sessionState?.field("recovery") == "claimed_inflight" -> "Reclaimed persisted in-flight result"
            sessionState?.field("recovery") == "last_checkpoint" -> "Recovered from last committed checkpoint"
            awaitingAcceptance -> "Awaiting objective acceptance"
            pendingInput != null -> pendingInput.prompt.takeIf(String::isNotBlank) ?: "Awaiting ${pendingInput.kind.replace('_', ' ')}"
            runtimeState != null -> listOfNotNull(
                selectedAgent.label,
                runtimeState.xLabel(),
                runtimeView.exitCode?.let { "exit $it" },
            ).joinToString(" · ")
            else -> sessionMessage
        },
    )
    val topStatus = when (phase) {
        XPhase.REPOSITORIES -> repositoryStatus
        XPhase.WORKSPACE -> workspaceStatus
        XPhase.SESSION -> sessionStatus
    }

    fun updateArcana(value: XArcanaLaunch) {
        arcana = value
        writeDashboardPref("ops.x.arcana.model", value.model.wire)
        writeDashboardPref("ops.x.arcana.noPace", value.noPace.toString())
        writeDashboardPref("ops.x.arcana.paceProvider", value.paceProvider.orEmpty())
        writeDashboardPref("ops.x.arcana.paceMinSeconds", value.paceMinSeconds?.toString().orEmpty())
        writeDashboardPref("ops.x.arcana.paceMaxSeconds", value.paceMaxSeconds?.toString().orEmpty())
        writeDashboardPref("ops.x.arcana.auto", value.auto.toString())
        writeDashboardPref("ops.x.arcana.indexSync", value.indexSync.toString())
        writeDashboardPref("ops.x.arcana.mode", value.mode.name)
    }

    suspend fun deliver(
        command: OpsSessionCommandDto,
        onAttempt: (Int) -> Unit = {},
        accept: (OpsSessionEventDto) -> Boolean = { it.requestId == command.requestId },
    ): XDelivery {
        repeat(3) { attempt ->
            onAttempt(attempt)
            if (!sendSessionCommand(command)) return XDelivery.NOT_SENT
            if (withTimeoutOrNull(4_000) { sessionLedger.awaitControl(accept) } != null) return XDelivery.ACKNOWLEDGED
        }
        return XDelivery.TIMED_OUT
    }

    fun resume(summary: OpsSessionSummaryDto) {
        arcanaSubmitting = false
        val targetAgent = XAgent.entries.first { it.wire == summary.agent }
        selectedAgent = targetAgent
        writeDashboardPref(X_AGENT_PREF, targetAgent.name)
        summary.repositoryId?.let {
            cursorRepositoryId = it
            writeDashboardPref(X_REPOSITORY_PREF, it.toString())
        }
        nativeSessionCursor = summary.sessionId.takeUnless { it == summary.runtimeId }
        writeDashboardPref(X_NATIVE_SESSION_PREF, nativeSessionCursor)
        val targetScope = (summary.repositoryId ?: selectedRepoId)?.let { it to targetAgent }
        if (sessionScope != targetScope) {
            sessions = emptyList()
            sessionScope = null
        }
        val liveRuntime = summary.runtimeId?.takeIf { summary.state?.isActiveRuntime == true }
        if (liveRuntime != null) {
            val targetWorkspace = summary.workspaceId ?: workspaceId
            if (targetWorkspace != workspaceId) {
                sessions = emptyList()
                sessionMessage = "Loading ${summary.workspaceName ?: "workspace"} sessions…"
            }
            summary.repositoryId?.let { selectedRepoId = it }
            workspaceId = targetWorkspace
            workspaceRebindAttempted = false
            runtimeId = liveRuntime
            activeSessionId = summary.sessionId
            selectedSessionKey = summary.xKey
            workspaceStatus = XSyncUiState(
                XSyncStage.SYNCHRONIZED,
                100,
                "${summary.workspaceName ?: "Workspace"} active ✨",
            )
            phase = XPhase.SESSION
            sendSessionCommand(
                OpsSessionCommandDto(
                    requestId = "attach-${Clock.System.now().toEpochMilliseconds()}",
                    action = OpsSessionActionDto.ATTACH_SESSION,
                    workspaceId = targetWorkspace,
                    agent = summary.agent,
                    runtimeId = liveRuntime,
                    sessionId = summary.sessionId,
                    afterSequence = sessionLedger.maxSequence(liveRuntime),
                ),
            )
            return
        }
        val requestId = "resume-${Clock.System.now().toEpochMilliseconds()}"
        startRequestId = requestId
        sessionMessage = "Resuming ${summary.title}…"
        pendingStart = OpsSessionCommandDto(
            requestId = requestId,
            action = OpsSessionActionDto.RESUME_SESSION,
            workspaceId = workspaceId,
            agent = summary.agent,
            sessionId = summary.sessionId,
            model = arcana.model.wire.takeIf { summary.agent == OpsAgentDto.ARCANA },
            noPace = arcana.noPace.takeIf { summary.agent == OpsAgentDto.ARCANA },
            paceMinSeconds = arcana.paceFor(arcana.model.pacingProvider)?.first.takeIf { summary.agent == OpsAgentDto.ARCANA && !arcana.noPace },
            paceMaxSeconds = arcana.paceFor(arcana.model.pacingProvider)?.second.takeIf { summary.agent == OpsAgentDto.ARCANA && !arcana.noPace },
            auto = arcana.auto.takeIf { summary.agent == OpsAgentDto.ARCANA },
            indexSync = arcana.indexSync.takeIf { summary.agent == OpsAgentDto.ARCANA },
            arcanaMode = arcana.mode.takeIf { summary.agent == OpsAgentDto.ARCANA },
        )
        startRetry = 0
    }

    LaunchedEffect(socketState.status, viewer.userId, viewer.capabilities) {
        if (socketState.status != OpsSocketStatus.CONNECTED || OPS_CAPABILITY_SESSIONS_RUN !in viewer.capabilities) return@LaunchedEffect
        val requestId = "repositories-${Clock.System.now().toEpochMilliseconds()}"
        repositoryRequestId = requestId
        if (repositories.isEmpty()) repositoryStatus = XSyncUiState(XSyncStage.INITIALIZING, 0, "Loading GitHub repositories…")
        if (!sendWorkspaceCommand(OpsWorkspaceCommandDto(requestId, OpsWorkspaceActionDto.LIST_REPOSITORIES))) {
            repositoryStatus = XSyncUiState(XSyncStage.ERROR, 0, "Ops socket is not ready")
        }
    }

    LaunchedEffect(workspaceEvent) {
        val event = workspaceEvent ?: return@LaunchedEffect
        when (event.kind) {
            OpsWorkspaceEventKindDto.REPOSITORIES -> {
                if (event.requestId != repositoryRequestId) return@LaunchedEffect
                when (event.status) {
                    OpsWorkspaceEventStatusDto.LOADING -> repositoryStatus = XSyncUiState(XSyncStage.INITIALIZING, 0, event.message ?: "Loading GitHub repositories…")
                    OpsWorkspaceEventStatusDto.READY -> {
                        repositories = event.repositories.map(OpsRepositoryDto::toXPreview)
                        repositoryStatus = XSyncUiState(
                            XSyncStage.SYNCHRONIZED,
                            100,
                            event.message ?: "${event.repositories.size} repositories ready",
                        )
                    }
                    OpsWorkspaceEventStatusDto.ERROR -> repositoryStatus = XSyncUiState(XSyncStage.ERROR, 0, event.message ?: "Repository lookup failed")
                    else -> Unit
                }
            }

            OpsWorkspaceEventKindDto.SYNC -> {
                if (event.requestId != syncRequestId) return@LaunchedEffect
                if (event.status == OpsWorkspaceEventStatusDto.SYNCHRONIZED) {
                    workspaceId = event.workspaceId
                    if (workspaceRebindAttempted) sessionListRetry++
                }
                workspaceStatus = XSyncUiState(
                    stage = when (event.status) {
                        OpsWorkspaceEventStatusDto.INITIALIZING -> XSyncStage.INITIALIZING
                        OpsWorkspaceEventStatusDto.SYNCING -> XSyncStage.SYNCING
                        OpsWorkspaceEventStatusDto.SYNCHRONIZED -> XSyncStage.SYNCHRONIZED
                        OpsWorkspaceEventStatusDto.ERROR -> XSyncStage.ERROR
                        else -> workspaceStatus.stage
                    },
                    progress = event.progress ?: workspaceStatus.progress,
                    message = event.message ?: workspaceStatus.message,
                )
            }
        }
    }

    LaunchedEffect(socketState.status, viewer.userId, globalSessionRefresh) {
        if (socketState.status != OpsSocketStatus.CONNECTED || OPS_CAPABILITY_SESSIONS_RUN !in viewer.capabilities) return@LaunchedEffect
        val requestId = "active-sessions-${Clock.System.now().toEpochMilliseconds()}-$globalSessionRefresh"
        globalSessionRequestId = requestId
        deliver(OpsSessionCommandDto(requestId, OpsSessionActionDto.LIST_SESSIONS)) {
            it.requestId == requestId && it.kind in setOf(OpsSessionEventKindDto.SESSIONS, OpsSessionEventKindDto.ERROR)
        }
    }

    LaunchedEffect(socketState.status, viewer.userId) {
        if (socketState.status != OpsSocketStatus.CONNECTED || OPS_CAPABILITY_SESSIONS_RUN !in viewer.capabilities) return@LaunchedEffect
        val requestId = "pacing-${Clock.System.now().toEpochMilliseconds()}"
        pacingRequestId = requestId
        deliver(OpsSessionCommandDto(requestId, OpsSessionActionDto.PACING_PROFILE)) {
            it.requestId == requestId && it.kind in setOf(OpsSessionEventKindDto.PACING_PROFILE, OpsSessionEventKindDto.ERROR)
        }
    }

    LaunchedEffect(workspaceId, selectedAgent, socketState.status, phase, sessionListRetry) {
        val workspace = workspaceId ?: return@LaunchedEffect
        if (phase != XPhase.WORKSPACE || socketState.status != OpsSocketStatus.CONNECTED) return@LaunchedEffect
        val requestId = "sessions-${selectedAgent.name.lowercase()}-${Clock.System.now().toEpochMilliseconds()}-$sessionListRetry"
        sessionListRequestId = requestId
        when (deliver(
            OpsSessionCommandDto(requestId, OpsSessionActionDto.LIST_SESSIONS, workspace, selectedAgent.wire),
            onAttempt = { attempt ->
                sessionMessage = if (attempt == 0) "Loading ${selectedAgent.label} sessions…" else "Retrying ${selectedAgent.label} sessions · ${attempt + 1}/3"
            },
            accept = { it.requestId == requestId && it.kind in setOf(OpsSessionEventKindDto.SESSIONS, OpsSessionEventKindDto.ERROR) },
        )) {
            XDelivery.NOT_SENT -> sessionMessage = "Ops socket is not ready · Enter to retry"
            XDelivery.TIMED_OUT -> sessionMessage = "Session history timed out · Enter to retry"
            XDelivery.ACKNOWLEDGED -> Unit
        }
    }

    LaunchedEffect(pendingStart, startRetry, socketState.status) {
        val command = pendingStart ?: return@LaunchedEffect
        if (socketState.status != OpsSocketStatus.CONNECTED) return@LaunchedEffect
        when (deliver(command, onAttempt = { attempt ->
            sessionMessage = when {
                command.action == OpsSessionActionDto.RESUME_SESSION && attempt == 0 -> "Resuming session…"
                command.action == OpsSessionActionDto.CREATE_SESSION && attempt == 0 -> "Creating ${selectedAgent.label} session…"
                else -> "Retrying session start · ${attempt + 1}/3"
            }
        })) {
            XDelivery.NOT_SENT -> sessionMessage = "Ops socket is not ready · Enter to retry"
            XDelivery.TIMED_OUT -> sessionMessage = "Session start was not acknowledged · Enter to retry"
            XDelivery.ACKNOWLEDGED -> Unit
        }
    }

    LaunchedEffect(sessionLedger) {
        fun applySessionEvent(event: OpsSessionEventDto) {
            if (event.kind == OpsSessionEventKindDto.SESSIONS && event.requestId == globalSessionRequestId) {
                globalSessions = event.sessions
            }
            if (event.kind == OpsSessionEventKindDto.PACING_PROFILE && event.requestId == pacingRequestId) {
                pacingProfile = event.pacing
            }
            event.runtimeId?.let { id ->
                val index = globalSessions.indexOfFirst { it.runtimeId == id }
                if (index >= 0) {
                    val current = globalSessions[index]
                    val updated = current.withEvent(event)
                    globalSessions = when {
                        updated.state?.isActiveRuntime != true -> globalSessions.filterNot { it.runtimeId == id }
                        updated != current -> globalSessions.toMutableList().also { it[index] = updated }
                        else -> globalSessions
                    }
                } else if (event.state?.isActiveRuntime == true && discoveredGlobalRuntimes.add(id)) {
                    globalSessionRefresh++
                }
            }
            if (event.workspaceId != null && event.workspaceId != workspaceId) return
            if (event.kind == OpsSessionEventKindDto.SESSIONS && event.requestId == sessionListRequestId && event.agent == selectedAgent.wire) {
                sessions = event.sessions
                sessionScope = currentSelectedRepo?.let { it.id to selectedAgent }
                workspaceRebindAttempted = false
                sessionMessage = if (sessions.isEmpty()) "No ${selectedAgent.label} sessions in this workspace" else "${sessions.size} resumable ${selectedAgent.label} sessions"
                selectedSessionKey = selectedSessionKey
                    ?.takeIf { key -> event.sessions.any { it.xKey == key } }
                    ?: event.sessions.firstOrNull { it.sessionId == nativeSessionCursor }?.xKey
                    ?: (event.sessions.firstOrNull { it.runtimeId != null } ?: event.sessions.firstOrNull())?.xKey
            }
            if (event.kind == OpsSessionEventKindDto.ERROR && event.requestId in setOf(sessionListRequestId, startRequestId)) {
                val repo = currentSelectedRepo
                if (event.requestId == sessionListRequestId && event.text?.contains("Select and synchronize a repository first") == true &&
                    repo != null && !workspaceRebindAttempted
                ) {
                    workspaceRebindAttempted = true
                    val requestId = "rebind-${repo.id}-${Clock.System.now().toEpochMilliseconds()}"
                    syncRequestId = requestId
                    sessionMessage = "Reattaching ${repo.name} workspace…"
                    if (!currentSendWorkspaceCommand(OpsWorkspaceCommandDto(requestId, OpsWorkspaceActionDto.SELECT_REPOSITORY, repo.id))) {
                        sessionMessage = "Ops socket is not ready · Enter to retry"
                    }
                } else {
                    if (event.requestId == startRequestId) {
                        pendingStart?.text?.let { query = TextFieldValue(it) }
                        pendingStart = null
                    }
                    sessionMessage = event.text ?: "Session command failed"
                }
            }
            event.runtimeId?.let { id ->
                val index = sessions.indexOfFirst { it.runtimeId == id }
                if (index >= 0) {
                    val current = sessions[index]
                    val updated = current.withEvent(event)
                    if (updated != current) sessions = sessions.toMutableList().also { it[index] = updated }
                }
            }
            if (startRequestId != null && event.requestId == startRequestId && event.runtimeId != null) {
                val firstReceipt = pendingStart != null
                pendingStart = null
                runtimeId = event.runtimeId
                activeSessionId = event.sessionId
                phase = XPhase.SESSION
                if (firstReceipt) globalSessionRefresh++
            }
            if (event.runtimeId == runtimeId && event.sessionId != null) {
                activeSessionId = event.sessionId
                if (event.sessionId != event.runtimeId && event.sessionId != nativeSessionCursor) {
                    nativeSessionCursor = event.sessionId
                    writeDashboardPref(X_NATIVE_SESSION_PREF, event.sessionId)
                }
            }
            if (event.runtimeId == runtimeId &&
                (event.kind == OpsSessionEventKindDto.ERROR || event.state?.isActiveRuntime == false)
            ) arcanaSubmitting = false
        }

        for (event in sessionLedger.controlEvents) {
            applySessionEvent(event)
        }
    }

    LaunchedEffect(socketState.status, runtimeId) {
        val runtime = runtimeId
        if (runtime != null && socketState.status == OpsSocketStatus.DISCONNECTED) detached = true
        if (runtime != null && detached && socketState.status == OpsSocketStatus.CONNECTED) {
            sendSessionCommand(
                OpsSessionCommandDto(
                    requestId = "attach-${Clock.System.now().toEpochMilliseconds()}",
                    action = OpsSessionActionDto.ATTACH_SESSION,
                    workspaceId = workspaceId,
                    agent = selectedAgent.wire,
                    runtimeId = runtime,
                    sessionId = activeSessionId,
                    afterSequence = sessionLedger.maxSequence(runtime),
                ),
            )
            detached = false
        }
    }

    LaunchedEffect(phase, filtered.map(XRepoPreview::id)) {
        if (phase == XPhase.REPOSITORIES && filtered.isNotEmpty() && filtered.none { it.id == selectedRepoId }) {
            selectedRepoId = filtered.firstOrNull()?.id
        }
    }

    fun openWorkspace() {
        val repo = selectedRepo ?: return
        arcanaSubmitting = false
        if (cursorRepositoryId != repo.id) {
            cursorRepositoryId = repo.id
            nativeSessionCursor = null
            writeDashboardPref(X_REPOSITORY_PREF, repo.id.toString())
            writeDashboardPref(X_NATIVE_SESSION_PREF, null)
        }
        val requestId = "sync-${repo.id}-${Clock.System.now().toEpochMilliseconds()}"
        syncRequestId = requestId
        phase = XPhase.WORKSPACE
        query = TextFieldValue("")
        workspaceId = null
        workspaceRebindAttempted = false
        if (sessionScope != (repo.id to selectedAgent)) {
            sessions = emptyList()
            selectedSessionKey = null
        }
        runtimeId = null
        activeSessionId = null
        pendingStart = null
        workspaceStatus = XSyncUiState(XSyncStage.INITIALIZING, 0, "Preparing ${repo.name}…")
        if (!sendWorkspaceCommand(OpsWorkspaceCommandDto(requestId, OpsWorkspaceActionDto.SELECT_REPOSITORY, repo.id))) {
            workspaceStatus = XSyncUiState(XSyncStage.ERROR, 0, "Ops socket is not ready")
        }
    }
    fun send() {
        when (phase) {
            XPhase.REPOSITORIES -> openWorkspace()
            XPhase.WORKSPACE -> {
                val prompt = query.text
                val workspace = workspaceId ?: return
                if (workspaceStatus.stage != XSyncStage.SYNCHRONIZED) return
                if (prompt.isBlank()) {
                    if (pendingStart != null) {
                        startRetry++
                        return
                    }
                    sessions.firstOrNull { it.xKey == selectedSessionKey }?.let(::resume)
                    if (sessions.isEmpty()) {
                        workspaceRebindAttempted = false
                        sessionListRetry++
                    }
                    return
                }
                val requestId = "create-${Clock.System.now().toEpochMilliseconds()}"
                startRequestId = requestId
                pendingStart = OpsSessionCommandDto(
                    requestId = requestId,
                    action = OpsSessionActionDto.CREATE_SESSION,
                    workspaceId = workspace,
                    agent = selectedAgent.wire,
                    text = prompt,
                    model = arcana.model.wire.takeIf { selectedAgent == XAgent.ARCANA },
                    noPace = arcana.noPace.takeIf { selectedAgent == XAgent.ARCANA },
                    paceMinSeconds = arcana.paceFor(arcana.model.pacingProvider)?.first.takeIf { selectedAgent == XAgent.ARCANA && !arcana.noPace },
                    paceMaxSeconds = arcana.paceFor(arcana.model.pacingProvider)?.second.takeIf { selectedAgent == XAgent.ARCANA && !arcana.noPace },
                    auto = arcana.auto.takeIf { selectedAgent == XAgent.ARCANA },
                    indexSync = arcana.indexSync.takeIf { selectedAgent == XAgent.ARCANA },
                    arcanaMode = arcana.mode.takeIf { selectedAgent == XAgent.ARCANA },
                )
                startRetry = 0
                query = TextFieldValue("")
            }
            XPhase.SESSION -> runtimeId?.let { runtime ->
                if (!inputUi.enabled) return
                if (!sendSessionCommand(
                        OpsSessionCommandDto(
                            requestId = "input-${Clock.System.now().toEpochMilliseconds()}",
                            action = OpsSessionActionDto.INPUT,
                            workspaceId = workspaceId,
                            agent = selectedAgent.wire,
                            runtimeId = runtime,
                            sessionId = activeSessionId,
                            text = query.text,
                        ),
                    )
                ) {
                    sessionMessage = "Ops socket is not ready · input retained"
                    return
                }
                arcanaSubmitting = selectedAgent == XAgent.ARCANA
                query = TextFieldValue("")
            }
        }
    }
    fun back() {
        arcanaSubmitting = false
        when (phase) {
            XPhase.REPOSITORIES -> Unit
            XPhase.WORKSPACE -> phase = XPhase.REPOSITORIES
            XPhase.SESSION -> {
                selectedSessionKey = runtimeId
                phase = XPhase.WORKSPACE
            }
        }
    }
    fun resetToRepositories() {
        arcanaSubmitting = false
        query = TextFieldValue("")
        selectedRepoId = null
        selectedSessionKey = runtimeId
        phase = XPhase.REPOSITORIES
    }
    LaunchedEffect(freshXSignal) {
        if (freshXSignal > handledFreshXSignal) {
            resetToRepositories()
            handledFreshXSignal = freshXSignal
        }
    }
    fun control(action: OpsSessionActionDto): Boolean {
        val runtime = runtimeId ?: return false
        if (action == OpsSessionActionDto.INTERRUPT && runtimeState != OpsSessionStateDto.RUNNING) return false
        if (action == OpsSessionActionDto.STOP && runtimeState?.isActiveRuntime != true) return false
        return sendSessionCommand(
            OpsSessionCommandDto(
                requestId = "${action.name.lowercase()}-${Clock.System.now().toEpochMilliseconds()}",
                action = action,
                workspaceId = workspaceId,
                agent = selectedAgent.wire,
                runtimeId = runtime,
                sessionId = activeSessionId,
            ),
        )
    }
    val currentXKeyHandler = rememberUpdatedState<(KeyEvent) -> Boolean> { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            val modified = event.isCtrlPressed || event.isShiftPressed || event.isAltPressed || event.isMetaPressed
            when (event.key) {
                Key.DirectionUp, Key.DirectionDown -> if (!modified && phase == XPhase.REPOSITORIES && filtered.isNotEmpty()) {
                    val current = filtered.indexOfFirst { it.id == selectedRepoId }.coerceAtLeast(0)
                    val shift = if (event.key == Key.DirectionUp) -1 else 1
                    selectedRepoId = filtered[(current + shift).coerceIn(0, filtered.lastIndex)].id
                    true
                } else false
                Key.Tab -> if (event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && activeSessions.isNotEmpty()) {
                    val current = activeSessions.indexOfFirst { it.runtimeId == runtimeId || it.xKey == selectedSessionKey }
                    val shift = if (event.isShiftPressed) -1 else 1
                    resume(activeSessions[(current.coerceAtLeast(if (shift > 0) -1 else 0) + shift + activeSessions.size) % activeSessions.size])
                    true
                } else false
                Key.C -> if (phase == XPhase.SESSION && event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed && !event.isMetaPressed) {
                    control(OpsSessionActionDto.INTERRUPT)
                } else false
                Key.X -> if (event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed && !event.isMetaPressed) {
                    resetToRepositories()
                    true
                } else false
                Key.Escape -> if (!modified && phase != XPhase.REPOSITORIES) { back(); true } else false
                Key.Enter, Key.NumPadEnter -> if (!modified) { send(); true } else false
                else -> false
            }
        }
    }
    DisposableEffect(windowKeys) {
        val router = windowKeys
        val route: (KeyEvent) -> Boolean = { currentXKeyHandler.value(it) }
        if (router != null) router.x = route
        onDispose {
            if (router?.x === route) router.x = { false }
        }
    }

    val panelHeight = (pageHeight - 132.dp).coerceAtLeast(648.dp)
    val shape = RoundedCornerShape(18.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(panelHeight)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.42f), shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xB8070C12),
                        Color(0x74000000),
                        Color(0x36151D2A),
                        Color.Transparent,
                    ),
                ),
                shape,
            )
            .border(BorderStroke(1.dp, cyan.copy(alpha = 0.32f)), shape)
            .let { base ->
                if (windowKeys == null) base.onPreviewKeyEvent { currentXKeyHandler.value(it) }.focusable() else base
            },
    ) {
        val wide = maxWidth >= 980.dp
        Column(Modifier.fillMaxSize()) {
            XSessionTopBar(
                status = topStatus,
                socketState = socketState,
                repo = selectedRepo?.takeIf { phase != XPhase.REPOSITORIES },
                phase = phase,
                selectedAgent = selectedAgent,
                onAgentSelected = {
                    if (phase != XPhase.SESSION && it != selectedAgent) {
                        selectedAgent = it
                        writeDashboardPref(X_AGENT_PREF, it.name)
                        nativeSessionCursor = null
                        writeDashboardPref(X_NATIVE_SESSION_PREF, null)
                        sessions = emptyList()
                        sessionScope = null
                        selectedSessionKey = null
                    }
                },
                arcana = arcana,
                pacingProfile = pacingProfile,
                onArcanaChanged = ::updateArcana,
                runtimeState = runtimeState,
                onSessionAction = { control(it) },
                onBack = ::back,
            )
            AnimatedVisibility(
                visible = activeSessions.isNotEmpty(),
                enter = fadeIn(tween(520)) + expandVertically(tween(620, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(180)) + shrinkVertically(tween(360, easing = FastOutSlowInEasing)),
            ) {
                XGlobalActiveRail(activeSessions.take(5), runtimeId, ::resume)
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (wide) {
                    XWideBody(
                        phase = phase,
                        repos = filtered,
                        selectedRepoId = selectedRepoId,
                        selectedRepo = selectedRepo,
                        selectedAgent = selectedAgent,
                        query = query,
                        viewer = viewer,
                        sessions = sessions,
                        selectedSessionKey = selectedSessionKey,
                        sessionMessage = sessionMessage,
                        sessionLedger = sessionLedger,
                        runtimeId = runtimeId,
                        inputUi = inputUi,
                        arcanaSubmitting = arcanaSubmitting,
                        onRepoSelected = { selectedRepoId = it },
                        onSessionSelected = ::resume,
                        onQueryChanged = { query = it },
                        onArcanaActivityObserved = { arcanaSubmitting = false },
                        onSend = ::send,
                    )
                } else {
                    XCompactBody(
                        phase = phase,
                        repos = filtered,
                        selectedRepoId = selectedRepoId,
                        selectedRepo = selectedRepo,
                        selectedAgent = selectedAgent,
                        query = query,
                        viewer = viewer,
                        sessions = sessions,
                        selectedSessionKey = selectedSessionKey,
                        sessionMessage = sessionMessage,
                        sessionLedger = sessionLedger,
                        runtimeId = runtimeId,
                        inputUi = inputUi,
                        arcanaSubmitting = arcanaSubmitting,
                        onRepoSelected = { selectedRepoId = it },
                        onSessionSelected = ::resume,
                        onQueryChanged = { query = it },
                        onArcanaActivityObserved = { arcanaSubmitting = false },
                        onSend = ::send,
                    )
                }
            }
        }
    }
}

@Composable
private fun XGlobalActiveRail(
    sessions: List<OpsSessionSummaryDto>,
    runtimeId: String?,
    onSelected: (OpsSessionSummaryDto) -> Unit,
) {
    LazyRow(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 18.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        itemsIndexed(sessions, key = { _, session -> session.runtimeId!! }) { _, session ->
            val selected = session.runtimeId == runtimeId
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .widthIn(min = 168.dp, max = 256.dp)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(if (selected) Color(0xFF2D173D).copy(alpha = 0.74f) else Color(0xC9070A0E))
                    .border(1.dp, (if (selected) Color(0xFF77DCA2) else Color(0xFF8755C9)).copy(alpha = 0.46f), shape)
                    .clickable { onSelected(session) }
                    .padding(horizontal = 9.dp, vertical = 4.dp)
                    .animateItem(
                        fadeInSpec = tween(620, easing = FastOutSlowInEasing),
                        fadeOutSpec = tween(140),
                        placementSpec = tween(480, easing = FastOutSlowInEasing),
                    ),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "${session.workspaceName ?: "workspace"}  ·  ${session.agent.name.lowercase()}",
                    color = if (selected) Color(0xFF9BE9BE) else Color(0xFFD9C986),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(session.title, color = text.copy(alpha = 0.82f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${session.state?.xLabel() ?: "active"}  ·  ${session.updatedAtMs.xSessionTime()}",
                    color = muted,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun XSessionTopBar(
    status: XSyncUiState,
    socketState: OpsSocketState,
    repo: XRepoPreview?,
    phase: XPhase,
    selectedAgent: XAgent,
    onAgentSelected: (XAgent) -> Unit,
    arcana: XArcanaLaunch,
    pacingProfile: OpsPacingProfileDto?,
    onArcanaChanged: (XArcanaLaunch) -> Unit,
    runtimeState: OpsSessionStateDto?,
    onSessionAction: (OpsSessionActionDto) -> Unit,
    onBack: () -> Unit,
) {
    val shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(Color.Black.copy(alpha = 0.72f), shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.56f)),
                ),
                shape,
            )
            .border(BorderStroke(1.dp, Color(0xFF8FB5DA).copy(alpha = 0.26f)), shape)
    ) {
        val compact = maxWidth < 1_120.dp
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            XGlowComparison(
                connected = socketState.status == OpsSocketStatus.CONNECTED,
                latencyMs = socketState.latencyMs,
                modifier = Modifier.width(210.dp),
            )
            XAgentToggle(selectedAgent, phase != XPhase.SESSION, onAgentSelected)
            if (selectedAgent == XAgent.ARCANA) {
                XArcanaRecipe(
                    arcana,
                    pacingProfile,
                    phase != XPhase.SESSION,
                    onArcanaChanged,
                )
            }
            if (!compact) XWorkspaceStatus(status, Modifier.weight(1f).widthIn(min = 180.dp, max = 660.dp))
            Column(Modifier.widthIn(min = 210.dp, max = 260.dp), horizontalAlignment = Alignment.End) {
                if (phase != XPhase.REPOSITORIES) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (phase == XPhase.SESSION) "← sessions" else "← repositories",
                            color = cyan.copy(alpha = 0.78f),
                            fontSize = 11.sp,
                            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 3.dp),
                        )
                        if (phase == XPhase.SESSION) {
                            val canInterrupt = runtimeState == OpsSessionStateDto.RUNNING
                            val canStop = runtimeState?.isActiveRuntime == true
                            Text(
                                "interrupt",
                                color = cyan.copy(alpha = if (canInterrupt) 0.78f else 0.24f),
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable(enabled = canInterrupt) { onSessionAction(OpsSessionActionDto.INTERRUPT) }
                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                            )
                            Text(
                                "stop",
                                color = Color(0xFFFF6B7A).copy(alpha = if (canStop) 0.82f else 0.24f),
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .clickable(enabled = canStop) { onSessionAction(OpsSessionActionDto.STOP) }
                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Text(repo?.name ?: "workspace multiplex", color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (compact) status.message else phase.name.lowercase(),
                    color = muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun XArcanaRecipe(
    settings: XArcanaLaunch,
    profile: OpsPacingProfileDto?,
    enabled: Boolean,
    onSettingsChanged: (XArcanaLaunch) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val provider = settings.model.pacingProvider
    val serverRange = profile?.ranges?.firstOrNull { it.provider == provider }
    val override = settings.paceFor(provider)
    val minimum = override?.first ?: serverRange?.minSeconds
    val maximum = override?.second ?: serverRange?.maxSeconds
    fun updatePace(minimum: Double, maximum: Double) = onSettingsChanged(
        settings.copy(paceProvider = provider, paceMinSeconds = minimum, paceMaxSeconds = maximum),
    )
    Box {
        Row(
            Modifier
                .width(200.dp)
                .height(42.dp)
                .clip(shape)
                .background(Color(0xD608090D))
                .border(1.dp, Color(0xFF8755C9).copy(alpha = 0.38f), shape)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("${settings.model.compact} · ${settings.mode.label.lowercase()}", color = Color(0xFFE2C77E), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${if (settings.auto) "auto" else "manual"} · ${if (settings.noPace) "no pace" else minimum?.let { "${it.xPaceDuration()}–${maximum?.xPaceDuration()}" } ?: "pace loading"} · ${if (settings.indexSync) "sync" else "no sync"}",
                    color = muted,
                    fontSize = 8.sp,
                    letterSpacing = 0.3.sp,
                )
            }
            Text("⌄", color = Color(0xFFB68BDA), fontSize = 13.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(246.dp).background(Color(0xFF0A0710)),
        ) {
            XArcanaModel.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${if (option == settings.model) "◆" else "◇"}  ${option.label}",
                            color = if (option == settings.model) Color(0xFFE2C77E) else Color(0xFFC4B8CF),
                            fontSize = 11.sp,
                        )
                    },
                    onClick = { onSettingsChanged(settings.copy(model = option)) },
                    modifier = Modifier.height(38.dp),
                )
            }
            HorizontalDivider(color = Color(0xFF7541A8).copy(alpha = 0.42f))
            DropdownMenuItem(
                text = {
                    Column {
                        Text("◆  ${if (settings.noPace) "Provider pacing off" else "Provider pacing on"}", color = Color(0xFFD9C986), fontSize = 11.sp)
                        Text(if (settings.noPace) "fastest · higher throttle risk" else "provider-safe default", color = muted, fontSize = 8.sp)
                    }
                },
                onClick = { onSettingsChanged(settings.copy(noPace = !settings.noPace)) },
                modifier = Modifier.height(48.dp),
            )
            if (serverRange == null || minimum == null || maximum == null) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("◇  Provider pace unavailable", color = muted, fontSize = 10.sp)
                            Text("Waiting for the live server_py profile", color = muted.copy(alpha = 0.68f), fontSize = 8.sp)
                        }
                    },
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.height(48.dp),
                )
            } else {
                Text(
                    "${provider} default  ${serverRange.minSeconds.xPaceDuration()}–${serverRange.maxSeconds.xPaceDuration()}${if (override == null) "" else "  ·  next run override"}",
                    color = muted,
                    fontSize = 8.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
                XPaceAdjust(
                    "minimum",
                    minimum,
                    enabled && !settings.noPace,
                    onDecrease = { updatePace((minimum - 30).coerceAtLeast(1.0).coerceAtMost(maximum), maximum) },
                    onIncrease = { updatePace((minimum + 30).coerceAtMost(maximum), maximum) },
                )
                XPaceAdjust(
                    "maximum",
                    maximum,
                    enabled && !settings.noPace,
                    onDecrease = { updatePace(minimum, (maximum - 30).coerceAtLeast(minimum)) },
                    onIncrease = { updatePace(minimum, maximum + 30) },
                )
                if (override != null) Text(
                    "↺  restore server default",
                    color = muted,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onSettingsChanged(settings.copy(paceProvider = null, paceMinSeconds = null, paceMaxSeconds = null)) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            DropdownMenuItem(
                text = {
                    Column {
                        Text("◆  ${if (settings.auto) "Automatic flow" else "Manual gates"}", color = Color(0xFFD9C986), fontSize = 11.sp)
                        Text(if (settings.auto) "continue non-essential prompts" else "wait for explicit input", color = muted, fontSize = 8.sp)
                    }
                },
                onClick = { onSettingsChanged(settings.copy(auto = !settings.auto)) },
                modifier = Modifier.height(48.dp),
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("◆  ${if (settings.indexSync) "Index synchronization" else "Index sync skipped"}", color = Color(0xFFD9C986), fontSize = 11.sp)
                        Text(if (settings.mode == OpsArcanaModeDto.GENERAL) "forced off in general mode" else if (settings.indexSync) "heal indexes before chat" else "start without pre-chat sync", color = muted, fontSize = 8.sp)
                    }
                },
                onClick = { onSettingsChanged(settings.copy(indexSync = !settings.indexSync)) },
                enabled = settings.mode != OpsArcanaModeDto.GENERAL,
                modifier = Modifier.height(48.dp),
            )
            HorizontalDivider(color = Color(0xFF7541A8).copy(alpha = 0.42f))
            OpsArcanaModeDto.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                "${if (option == settings.mode) "◆" else "◇"}  ${option.label}",
                                color = if (option == settings.mode) Color(0xFFB68BDA) else Color(0xFFC4B8CF),
                                fontSize = 11.sp,
                            )
                            Text(option.detail, color = muted, fontSize = 8.sp)
                        }
                    },
                    onClick = {
                        onSettingsChanged(
                            settings.copy(
                                mode = option,
                                indexSync = settings.indexSync && option != OpsArcanaModeDto.GENERAL,
                            ),
                        )
                    },
                    modifier = Modifier.height(46.dp),
                )
            }
        }
    }
}

@Composable
private fun XPaceAdjust(
    label: String,
    seconds: Double,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("−", color = Color(0xFFB68BDA).copy(alpha = if (enabled) 1f else 0.3f), fontSize = 15.sp, modifier = Modifier.clickable(enabled = enabled, onClick = onDecrease).padding(horizontal = 8.dp))
        Text("$label  ${seconds.xPaceDuration()}", color = Color(0xFFD9C986), fontSize = 10.sp)
        Text("+", color = Color(0xFFB68BDA).copy(alpha = if (enabled) 1f else 0.3f), fontSize = 15.sp, modifier = Modifier.clickable(enabled = enabled, onClick = onIncrease).padding(horizontal = 8.dp))
    }
}

@Composable
private fun XAgentToggle(selected: XAgent, enabled: Boolean, onSelected: (XAgent) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .width(148.dp)
            .height(42.dp)
            .clip(shape)
            .background(Color(0xD608090D))
            .border(1.dp, Color(0xFF8755C9).copy(alpha = 0.48f), shape)
            .padding(3.dp),
    ) {
        XAgent.entries.forEach { agent ->
            val active = agent == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(
                        if (active) Brush.horizontalGradient(
                            listOf(Color(0xFF4D267A).copy(alpha = 0.88f), Color(0xFFB18A35).copy(alpha = 0.34f)),
                        ) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)),
                    )
                    .clickable(enabled = enabled) { onSelected(agent) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    agent.label,
                    color = if (active) Color(0xFFE7C77D) else muted,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                )
            }
        }
    }
}

@Composable
private fun XWideBody(
    phase: XPhase,
    repos: List<XRepoPreview>,
    selectedRepoId: Long?,
    selectedRepo: XRepoPreview?,
    selectedAgent: XAgent,
    query: TextFieldValue,
    viewer: OpsViewerDto,
    sessions: List<OpsSessionSummaryDto>,
    selectedSessionKey: String?,
    sessionMessage: String,
    sessionLedger: XSessionLedger,
    runtimeId: String?,
    inputUi: XInputUi,
    arcanaSubmitting: Boolean,
    onRepoSelected: (Long) -> Unit,
    onSessionSelected: (OpsSessionSummaryDto) -> Unit,
    onQueryChanged: (TextFieldValue) -> Unit,
    onArcanaActivityObserved: () -> Unit,
    onSend: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(18.dp)) {
        val listWidth = maxWidth * 0.43f
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            AnimatedVisibility(
                visible = phase == XPhase.REPOSITORIES,
                modifier = Modifier.width(listWidth).fillMaxHeight(),
                enter = fadeIn(tween(1_200)),
                exit = fadeOut(tween(440)),
            ) {
                XRepoList(repos, selectedRepoId, onRepoSelected)
            }
            XRightStage(
                phase = phase,
                selectedRepo = selectedRepo,
                selectedAgent = selectedAgent,
                query = query,
                viewer = viewer,
                sessions = sessions,
                selectedSessionKey = selectedSessionKey,
                sessionMessage = sessionMessage,
                sessionLedger = sessionLedger,
                runtimeId = runtimeId,
                inputUi = inputUi,
                arcanaSubmitting = arcanaSubmitting,
                onSessionSelected = onSessionSelected,
                onQueryChanged = onQueryChanged,
                onArcanaActivityObserved = onArcanaActivityObserved,
                onSend = onSend,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun XCompactBody(
    phase: XPhase,
    repos: List<XRepoPreview>,
    selectedRepoId: Long?,
    selectedRepo: XRepoPreview?,
    selectedAgent: XAgent,
    query: TextFieldValue,
    viewer: OpsViewerDto,
    sessions: List<OpsSessionSummaryDto>,
    selectedSessionKey: String?,
    sessionMessage: String,
    sessionLedger: XSessionLedger,
    runtimeId: String?,
    inputUi: XInputUi,
    arcanaSubmitting: Boolean,
    onRepoSelected: (Long) -> Unit,
    onSessionSelected: (OpsSessionSummaryDto) -> Unit,
    onQueryChanged: (TextFieldValue) -> Unit,
    onArcanaActivityObserved: () -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(phase == XPhase.REPOSITORIES, modifier = Modifier.weight(1f, fill = true)) {
            XRepoList(repos.take(3), selectedRepoId, onRepoSelected)
        }
        XRightStage(
            phase,
            selectedRepo,
            selectedAgent,
            query,
            viewer,
            sessions,
            selectedSessionKey,
            sessionMessage,
            sessionLedger,
            runtimeId,
            inputUi,
            arcanaSubmitting,
            onSessionSelected,
            onQueryChanged,
            onArcanaActivityObserved,
            onSend,
            Modifier.fillMaxWidth().weight(1f, fill = true),
        )
    }
}

@Composable
private fun XRepoList(repos: List<XRepoPreview>, selectedRepoId: Long?, onRepoSelected: (Long) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedRepoId, repos) {
        val target = repos.indexOfFirst { it.id == selectedRepoId }
        if (target < 0) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        val layout = listState.layoutInfo
        val visible = layout.visibleItemsInfo
        val range = (visible.firstOrNull()?.index ?: 0)..(visible.lastOrNull()?.index ?: 0)
        if (target !in range) {
            val down = target > range.last
            val average = visible.map { it.size }.average().toFloat().takeIf { it > 0f }
                ?: (layout.viewportEndOffset - layout.viewportStartOffset).toFloat() / visible.size.coerceAtLeast(1)
            val delta = if (down) target - range.last + 0.5f else range.first - target + 0.5f
            listState.animateScrollBy((if (down) 1 else -1) * average * delta, tween(800, easing = FastOutSlowInEasing))
        }
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }?.let { item ->
            val viewportCenter = (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2f
            val centerDelta = item.offset + item.size / 2f - viewportCenter
            if (abs(centerDelta) > 2f) listState.animateScrollBy(centerDelta, tween(600, easing = FastOutSlowInEasing))
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(repos, key = { _, repo -> repo.id }) { index, repo ->
            XGlassCard(
                selected = repo.id == selectedRepoId,
                onClick = { onRepoSelected(repo.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 92.dp, max = 132.dp)
                    .animateContentSize()
                    .animateItem(
                        fadeInSpec = tween(1_400, easing = FastOutSlowInEasing),
                        fadeOutSpec = tween(100, easing = FastOutSlowInEasing),
                        placementSpec = tween(676, easing = FastOutSlowInEasing),
                    ),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    XSkeuoText(
                        "${index + 1}:  ${repo.name}",
                        fontSize = 27.sp,
                        color = Color(0xFFD4CC38).copy(alpha = 0.78f),
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    if (repo.description.isNotBlank()) {
                        XSkeuoText(repo.description, 13.sp, color = Color(0xFF898989), maxLines = 2)
                    }
                    Text(
                        "◉ ${repo.language}    ✦ ${repo.stars}    ·    ${repo.updated}",
                        color = Color(0x88B0B0B0),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun XRightStage(
    phase: XPhase,
    selectedRepo: XRepoPreview?,
    selectedAgent: XAgent,
    query: TextFieldValue,
    viewer: OpsViewerDto,
    sessions: List<OpsSessionSummaryDto>,
    selectedSessionKey: String?,
    sessionMessage: String,
    sessionLedger: XSessionLedger,
    runtimeId: String?,
    inputUi: XInputUi,
    arcanaSubmitting: Boolean,
    onSessionSelected: (OpsSessionSummaryDto) -> Unit,
    onQueryChanged: (TextFieldValue) -> Unit,
    onArcanaActivityObserved: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inputFocusRequester = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val projection = rememberXTranscriptProjection(sessionLedger, runtimeId)
    val working = phase == XPhase.SESSION && selectedAgent == XAgent.ARCANA &&
        (arcanaSubmitting || projection.activity != null)
    LaunchedEffect(arcanaSubmitting, projection.activity) {
        if (arcanaSubmitting && projection.activity != null) onArcanaActivityObserved()
    }
    LaunchedEffect(phase, windowFocused) {
        if (windowFocused) runCatching { inputFocusRequester.requestFocus() }
    }
    val inputBias = animateFloatAsState(
        if (phase == XPhase.REPOSITORIES) 0.62f else 1f,
        tween(600, easing = FastOutSlowInEasing),
        label = "x-input-position",
    )
    BoxWithConstraints(modifier) {
        val inputLaneHeight = 176.dp
        val inputTravel = (maxHeight - inputLaneHeight).coerceAtLeast(0.dp)
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(tween(800, easing = FastOutSlowInEasing)) +
                    scaleIn(tween(800, easing = FastOutSlowInEasing), transformOrigin = TransformOrigin.Center) +
                    expandVertically(tween(800, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                    slideInVertically(tween(800, easing = FastOutSlowInEasing)) { it }) togetherWith
                    (fadeOut(tween(1_600, easing = FastOutSlowInEasing)) +
                        scaleOut(tween(1_600, easing = FastOutSlowInEasing), transformOrigin = TransformOrigin.Center) +
                        shrinkVertically(tween(1_600, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
                        slideOutVertically(tween(1_600, easing = FastOutSlowInEasing)) { it })
            },
            label = "x-phase-transition",
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (phase == XPhase.SESSION) 0.dp else inputLaneHeight),
        ) { current ->
            when (current) {
                XPhase.REPOSITORIES -> XWelcome(viewer)
                XPhase.WORKSPACE -> XSessionChooser(selectedRepo, selectedAgent, sessions, selectedSessionKey, sessionMessage, onSessionSelected)
                XPhase.SESSION -> key(runtimeId) {
                    XTranscript(selectedRepo, selectedAgent, projection.events)
                }
            }
        }
        XLuxuryInput(
            value = query,
            onValueChange = onQueryChanged,
            placeholder = inputUi.placeholder,
            focusRequester = inputFocusRequester,
            actionLabel = inputUi.action,
            actionColor = inputUi.actionColor,
            enabled = inputUi.enabled,
            working = working,
            onSend = onSend,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, (inputTravel * inputBias.value).roundToPx()) }
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .height(inputLaneHeight),
        )
    }
}

@Composable
private fun rememberXTranscriptProjection(ledger: XSessionLedger, runtimeId: String?): XTranscriptProjection {
    return produceState(XTranscriptProjection.EMPTY, ledger, runtimeId) {
        var arcanaSource = emptyList<OpsSessionEventDto>()
        var arcanaProjection: XArcanaProjection? = null
        var runtimeSource = emptyList<OpsSessionEventDto>()
        snapshotFlow { ledger.presentationRevision(runtimeId) }
            .conflate()
            .collect {
                val events = ledger.snapshot()
                val previous = value
                val next = withContext(Dispatchers.Default) {
                    val runtimeEvents = events.xRuntimeEvents(runtimeId)
                    val relevant = runtimeEvents.filter(OpsSessionEventDto::xAffectsArcanaProjection)
                    val appended = arcanaProjection?.takeIf {
                        relevant.size >= arcanaSource.size && relevant.subList(0, arcanaSource.size) == arcanaSource
                    }?.let { previousArcana ->
                        relevant.subList(arcanaSource.size, relevant.size)
                            .takeIf { tail -> tail.all { it.structured == null && '\r' !in it.text.orEmpty() } }
                            ?.let(previousArcana::appendStdout)
                    }
                    val arcana = appended ?: arcanaProjection?.takeIf { relevant == arcanaSource }
                        ?: relevant.xArcanaProjection()
                    arcanaSource = relevant
                    arcanaProjection = arcana
                    val appendedRuntime = runtimeEvents.takeIf {
                        it.size >= runtimeSource.size && it.subList(0, runtimeSource.size) == runtimeSource
                    }?.subList(runtimeSource.size, runtimeEvents.size)
                    runtimeSource = runtimeEvents
                    val incremental = appendedRuntime
                        ?.takeIf { tail -> tail.isNotEmpty() && tail.all(OpsSessionEventDto::xPlainArcanaStdout) }
                        ?.let { tail -> previous.appendArcanaStdout(tail, arcana) }
                    val projected = incremental ?: runtimeEvents.xTranscriptProjection(arcana)
                    if (incremental != null) return@withContext projected
                    val previousByKey = previous.events.associateBy(XRenderedEvent::key)
                    val sharedEvents = projected.events.map { rendered ->
                        previousByKey[rendered.key]?.takeIf { it == rendered } ?: rendered
                    }
                    val stableEvents = previous.events.takeIf { old ->
                        old.size == sharedEvents.size && old.indices.all { old[it] === sharedEvents[it] }
                    } ?: sharedEvents
                    projected.copy(events = stableEvents)
                }
                if (next != previous) value = next
                delay(80)
            }
    }.value
}

@Composable
private fun XWelcome(viewer: OpsViewerDto) {
    val time by produceState("--:--:--") {
        while (true) {
            value = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).time.toString().take(8)
            delay(1_000)
        }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        XSkeuoText("Welcome, ${viewer.displayName.ifBlank { "traveller" }}!", 42.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        XSkeuoText(time, 74.sp, maxLines = 1)
    }
}

@Composable
private fun XSessionChooser(
    repo: XRepoPreview?,
    agent: XAgent,
    sessions: List<OpsSessionSummaryDto>,
    selectedSessionKey: String?,
    message: String,
    onSelected: (OpsSessionSummaryDto) -> Unit,
) {
    val active = sessions.filter { it.runtimeId != null && it.state?.isActiveRuntime == true }
    val history = sessions.filterNot { it in active }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        XSkeuoText(repo?.name.orEmpty(), 34.sp, color = Color(0xFFD4CC38).copy(alpha = 0.82f), maxLines = 1)
        Text("RESUME ${agent.label.uppercase()}  ·  OR CREATE BELOW", color = muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(message, color = Color(0xFFB99ADB).copy(alpha = 0.76f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        AnimatedVisibility(
            visible = active.isNotEmpty(),
            enter = fadeIn(tween(520)) + expandVertically(tween(620, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(360, easing = FastOutSlowInEasing)),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ACTIVE RITUALS", color = Color(0xFF77DCA2), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("ESC COLLAPSES  ·  CTRL-TAB SWITCHES", color = muted.copy(alpha = 0.72f), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                LazyRow(Modifier.fillMaxWidth().height(78.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    itemsIndexed(active, key = { _, session -> session.runtimeId!! }) { _, session ->
                        XSessionCard(
                            session = session,
                            selected = session.xKey == selectedSessionKey,
                            live = true,
                            onClick = { onSelected(session) },
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight()
                                .animateItem(
                                    fadeInSpec = tween(700, easing = FastOutSlowInEasing),
                                    fadeOutSpec = tween(140),
                                    placementSpec = tween(540, easing = FastOutSlowInEasing),
                                ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("RECOVERABLE · HISTORY", color = Color(0xFFB99ADB), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text("${history.size}", color = muted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(5.dp))
        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No durable ritual in this workspace", color = muted.copy(alpha = 0.64f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(history, key = { _, session -> session.runtimeId ?: session.sessionId }) { index, session ->
                    XSessionCard(
                        session = session,
                        selected = session.xKey == selectedSessionKey,
                        onClick = { onSelected(session) },
                        titlePrefix = "${index + 1}. ",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 68.dp, max = 94.dp)
                            .animateItem(
                                fadeInSpec = tween(700, easing = FastOutSlowInEasing),
                                fadeOutSpec = tween(140),
                                placementSpec = tween(540, easing = FastOutSlowInEasing),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun XSessionCard(
    session: OpsSessionSummaryDto,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    live: Boolean = false,
    titlePrefix: String = "",
) {
    XGlassCard(selected = live || selected, onClick = onClick, modifier = modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${if (selected) "◆ " else ""}$titlePrefix${session.title}",
                color = Color(0xFFD9C986),
                fontSize = if (live) 12.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    session.state?.xLabel(),
                    "changes ready".takeIf { session.changesKnown && session.hasChanges },
                    session.detail?.takeIf { it.isNotBlank() && it != "live" },
                    session.updatedAtMs.xSessionTime(),
                ).joinToString("  ·  "),
                color = if (live) Color(0xFF77DCA2).copy(alpha = 0.82f) else Color(0xFFA995B9).copy(alpha = 0.76f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun XTranscript(repo: XRepoPreview?, agent: XAgent, events: List<XRenderedEvent>) {
    val listState = rememberLazyListState()
    val tailLandingPx = with(LocalDensity.current) { 56.dp.toPx() }
    val tailSpring = remember {
        spring<Float>(stiffness = Spring.StiffnessLow, dampingRatio = 0.15f)
    }
    var follow by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    var followedCount by remember { mutableStateOf(0) }
    var followedRevision by remember { mutableStateOf<Any?>(null) }
    val visibleCount by rememberUpdatedState(events.size)
    val tailRevision = events.lastOrNull()?.revision
    val currentTail = rememberUpdatedState(events.size to tailRevision)
    val currentFollow = rememberUpdatedState(follow)
    val unread = when {
        events.size > followedCount -> events.size - followedCount
        events.isNotEmpty() && tailRevision != followedRevision -> 1
        else -> 0
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (moving, canScrollForward) ->
            if (moving && !autoScrolling) follow = !canScrollForward
            if (!canScrollForward) {
                followedCount = visibleCount
                followedRevision = currentTail.value.second
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { currentTail.value to currentFollow.value }
            .collectLatest { (_, shouldFollow) ->
                if (!shouldFollow) return@collectLatest
                delay(90)
                val (count, revision) = currentTail.value
                if (count == 0 || !currentFollow.value) return@collectLatest
                if (followedCount == count && followedRevision == revision && !listState.canScrollForward) return@collectLatest
                if (!listState.canScrollForward) {
                    followedCount = count
                    followedRevision = revision
                    return@collectLatest
                }
                autoScrolling = true
                try {
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == count - 1 }) {
                        listState.animateScrollToItem(count - 1)
                    }
                    fun remainingTailDistance() = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == count - 1 }
                        ?.let { tail -> (tail.offset + tail.size - listState.layoutInfo.viewportEndOffset).toFloat() }
                    if (currentFollow.value && listState.canScrollForward) {
                        val layout = listState.layoutInfo
                        if (layout.viewportEndOffset > layout.viewportStartOffset) {
                            remainingTailDistance()?.takeIf { it > tailLandingPx }?.let { distance ->
                                listState.scrollBy(distance - tailLandingPx)
                            }
                            remainingTailDistance()?.takeIf { it > 0.5f }?.let { distance ->
                                listState.animateScrollBy(distance.coerceAtMost(tailLandingPx), tailSpring)
                            }
                        }
                    }
                    if (!listState.canScrollForward) {
                        val (settledCount, settledRevision) = currentTail.value
                        followedCount = settledCount
                        followedRevision = settledRevision
                    }
                } finally {
                    autoScrolling = false
                }
            }
        }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            XSkeuoText(agent.label, 27.sp, maxLines = 1)
            Text(repo?.name.orEmpty(), color = cyan.copy(alpha = 0.75f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.56f))
                .border(BorderStroke(1.dp, Color(0xFF7544A8).copy(alpha = 0.34f)), RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    itemsIndexed(events, key = { _, rendered -> rendered.key }) { _, rendered ->
                        XSessionEvent(
                            rendered,
                            Modifier.animateItem(
                                fadeInSpec = tween(360, easing = FastOutSlowInEasing),
                                fadeOutSpec = tween(120),
                                placementSpec = tween(460, easing = FastOutSlowInEasing),
                            ),
                        )
                    }
                }
            }
            if (!follow && unread > 0) {
                Text(
                    "↓ $unread new",
                    color = Color(0xFFE6C878),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xEE29173B))
                        .border(1.dp, Color(0xFF9B62DA), RoundedCornerShape(50))
                        .clickable { follow = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun XSessionEvent(rendered: XRenderedEvent, modifier: Modifier = Modifier) {
    val event = rendered.event
    val structured = event.structured
    if (structured != null) {
        if (structured.type == "phase") {
            XPhaseBeacon(event, modifier)
            return
        }
        if (structured.type in setOf("input_request", "input_resolved", "input_timeout", "session_state")) {
            XProtocolEvent(event, modifier)
            return
        }
        if (structured.phase == "codex") {
            XCodexEvent(event, modifier)
            return
        }
        XRitualEvent(rendered, modifier)
        return
    }
    val channel = event.channel ?: OpsSessionChannelDto.SYSTEM
    val replayGap = event.replay && event.kind == OpsSessionEventKindDto.ERROR
    val label = when {
        replayGap -> "REPLAY"
        event.kind == OpsSessionEventKindDto.LIFECYCLE -> "STATE"
        event.agent == OpsAgentDto.ARCANA && channel == OpsSessionChannelDto.STDIN -> "YOU"
        else -> channel.name
    }
    val color = when {
        replayGap -> Color(0xFFE0B65C)
        event.kind == OpsSessionEventKindDto.LIFECYCLE -> Color(0xFFA980D4)
        else -> when (channel) {
        OpsSessionChannelDto.STDIN -> Color(0xFFE0B65C)
        OpsSessionChannelDto.STDOUT -> Color(0xFF81CFA6)
        OpsSessionChannelDto.STDERR -> Color(0xFFE58A95)
        OpsSessionChannelDto.SYSTEM -> Color(0xFFA980D4)
        }
    }
    val rowModifier = modifier
        .fillMaxWidth()
        .then(
            if (event.agent == OpsAgentDto.ARCANA && channel == OpsSessionChannelDto.STDOUT) {
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White.copy(alpha = 0.025f))
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            } else {
                Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
            },
        )
    Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(if (rendered.streamContinuation) "" else label, color = color, fontSize = 8.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
        Text(
            rendered.text ?: AnnotatedString(""),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.5.sp,
            lineHeight = 13.sp,
            modifier = Modifier.weight(1f),
        )
        event.sequence?.let { Text("#$it", color = muted.copy(alpha = 0.45f), fontSize = 8.sp) }
            ?: if (replayGap) Text("gap", color = color.copy(alpha = 0.7f), fontSize = 8.sp) else Unit
    }
}

@Composable
private fun XPhaseBeacon(event: OpsSessionEventDto, modifier: Modifier = Modifier) {
    val structured = event.structured ?: return
    val (phase, title) = when (structured.phase.lowercase()) {
        "phase1" -> "PHASE 01" to "DISCOVERY"
        "phase2" -> "PHASE 02" to "COMPREHENSION"
        "3_x" -> "PHASE 03" to "ARCANA MULTIROUND"
        else -> structured.phase.uppercase() to "TRANSITION"
    }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                phase,
                color = amber.copy(alpha = 0.96f),
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.75.sp,
            )
            Text(
                title,
                color = ritualPurpleGlow.copy(alpha = 0.82f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
            )
        }
        HorizontalDivider(
            color = ritualPurpleGlow.copy(alpha = 0.3f),
        )
    }
}

@Composable
private fun XCodexEvent(event: OpsSessionEventDto, modifier: Modifier = Modifier) {
    val structured = event.structured ?: return
    val payload = structured.payload
    val item = payload["item"] as? JsonObject
    val type = item?.field("type").orEmpty()
    val history = payload.boolean("history") == true
    val detail = when (type) {
        "agentMessage" -> item?.field("text").orEmpty()
        "userMessage" -> item?.xCodexText().orEmpty()
        "commandExecution" -> listOfNotNull(
            listOfNotNull(
                item?.field("command"),
                item?.field("status")?.takeIf(String::isNotBlank),
                item?.get("exitCode").display().takeIf(String::isNotBlank)?.let { "exit $it" },
                runCatching { item?.get("durationMs")?.jsonPrimitive?.longOrNull }.getOrNull()?.takeIf { it > 0 }?.let { "${it}ms" },
            ).joinToString("  ·  ").takeIf(String::isNotBlank),
            item?.field("aggregatedOutput")?.trim()?.takeIf(String::isNotBlank),
        ).joinToString("\n")
        "fileChange" -> listOfNotNull(item?.field("status"), item?.get("changes").display(1_200).takeIf(String::isNotBlank)).joinToString("  ·  ")
        "mcpToolCall", "dynamicToolCall" -> listOfNotNull(item?.field("server"), item?.field("tool"), item?.field("status"), item?.get("error").display(480).takeIf(String::isNotBlank)).joinToString("  ·  ")
        "plan" -> item?.field("text").orEmpty()
        "webSearch" -> item?.field("query").orEmpty()
        "imageView" -> item?.field("path").orEmpty()
        "imageGeneration" -> listOfNotNull(item?.field("status"), item?.field("result")).joinToString("  ·  ")
        "collabAgentToolCall" -> listOfNotNull(item?.field("tool"), item?.field("status"), item?.field("prompt")).joinToString("  ·  ")
        "subAgentActivity" -> listOfNotNull(item?.field("agentPath"), item?.field("kind")).joinToString("  ·  ")
        "enteredReviewMode", "exitedReviewMode" -> item?.field("review").orEmpty()
        "contextCompaction" -> "Context compacted"
        else -> ""
    }
    if (detail.isBlank()) return
    val label = when (type) {
        "userMessage" -> "YOU"
        "agentMessage" -> if (item?.field("phase") == "final_answer") "ANSWER" else "CODEX"
        "commandExecution" -> "COMMAND"
        "fileChange" -> "FILES"
        "mcpToolCall", "dynamicToolCall" -> "TOOL"
        "collabAgentToolCall", "subAgentActivity" -> "AGENT"
        "plan" -> "PLAN"
        "webSearch" -> "SEARCH"
        "imageView", "imageGeneration" -> "IMAGE"
        "enteredReviewMode", "exitedReviewMode" -> "REVIEW"
        "contextCompaction" -> "CONTEXT"
        else -> "CODEX"
    }
    val tone = when (type) {
        "userMessage" -> Color(0xFFE0B65C)
        "agentMessage" -> Color(0xFF81CFA6)
        "commandExecution" -> Color(0xFFE0B65C)
        "fileChange" -> Color(0xFFB989E5)
        "mcpToolCall", "dynamicToolCall" -> Color(0xFF73CBE4)
        else -> Color(0xFFA980D4)
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(tone.copy(alpha = if (history) 0.045f else 0.08f))
            .border(1.dp, tone.copy(alpha = if (history) 0.22f else 0.34f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = tone, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(58.dp))
        Text(
            detail,
            color = Color(0xFFD0CDD5).copy(alpha = if (history) 0.78f else 1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            modifier = Modifier.weight(1f),
        )
        if (history) Text("HISTORY", color = tone.copy(alpha = 0.48f), fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun XProtocolEvent(event: OpsSessionEventDto, modifier: Modifier = Modifier) {
    val structured = event.structured ?: return
    val input = structured.type == "input_request"
    val resolved = structured.type == "input_resolved"
    val timeout = structured.type == "input_timeout"
    val payload = structured.payload
    val resolution = (payload["response"] as? JsonObject)?.let { response ->
        response.field("decision").ifBlank { response.field("action") }
    }.orEmpty()
    val resolutionKey = resolution.lowercase()
    val options = (payload["options"] as? JsonArray).orEmpty().map { it.display(80) }.filter(String::isNotBlank)
    val color = when {
        input -> amber
        timeout -> rose
        resolved -> when (resolutionKey) {
            "accept", "acceptforsession" -> green
            "decline" -> rose
            "cancel" -> amber
            else -> ritualPurpleGlow
        }
        else -> ritualPurpleGlow
    }
    val detail = when {
        input -> payload.field("prompt")
        resolved -> listOfNotNull(
            resolution.takeIf(String::isNotBlank),
            payload.field("method").substringAfterLast('/').takeIf(String::isNotBlank),
        ).joinToString("  ·  ")
        timeout -> "Approval timed out"
        else -> buildString {
            append(payload.field("status").replace('_', ' '))
            if (payload.boolean("changes_known") == true) append(if (payload.boolean("has_changes") == true) "  ·  changes ready" else "  ·  clean")
        }
    }
    val label = when {
        input -> "INPUT"
        timeout -> "TIMEOUT"
        resolved -> when (resolutionKey) {
            "accept", "acceptforsession" -> "APPROVED"
            "decline" -> "DECLINED"
            "cancel" -> "CANCELLED"
            else -> "RESOLVED"
        }
        else -> "STATE"
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = 0.09f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = color, fontSize = 8.5.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(54.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                detail,
                color = text.copy(alpha = 0.9f),
                fontFamily = if (input) FontFamily.Default else FontFamily.Monospace,
                fontSize = if (input) 10.5.sp else 9.5.sp,
                lineHeight = if (input) 15.sp else 13.sp,
            )
            if (input && options.isNotEmpty()) {
                Text(
                    "OPTIONS  ${options.joinToString("  ·  ")}",
                    color = color.copy(alpha = 0.68f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                    lineHeight = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun XRitualEvent(rendered: XRenderedEvent, modifier: Modifier = Modifier) {
    val event = rendered.event
    val structured = event.structured ?: return
    val payload = structured.payload
    val shape = RoundedCornerShape(10.dp)
    if (structured.type != "agent_response") {
        Column(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ritualPurple.copy(alpha = 0.07f))
                .border(1.dp, ritualPurple.copy(alpha = 0.24f), shape)
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${structured.phase.uppercase()}  ·  ${structured.type.replace('_', ' ')}",
                    color = amber.copy(alpha = 0.82f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                )
                event.sequence?.let { Text("#$it", color = muted.copy(alpha = 0.46f), fontSize = 8.sp) }
            }
            payload.forEach { (name, value) ->
                XRitualRow(name.replace('_', ' ').uppercase(), value.display(), text.copy(alpha = 0.82f), FontFamily.Monospace)
            }
        }
        return
    }

    val command = payload["command"] as? JsonObject
    val commandName = command?.field("name").orEmpty()
    val commandArgs = command?.get("args") as? JsonObject
    val args = commandArgs.orEmpty()
    val showCommand = commandName.isNotBlank() && commandName != "ask_user_input"
    val commandDetail = if (showCommand) args.entries.joinToString("  ·  ") { (name, value) ->
        val detail = value.display(if (args.size == 1) 900 else 320)
        if (args.size == 1 && name in xCompactCommandArgs) detail else "$name  $detail"
    } else ""
    val root = payload.field("root_objective").ifBlank { payload.field("obj_root") }.trim()
    val current = payload.field("current_objective")
        .ifBlank { payload.field("main_objective") }
        .ifBlank { payload.field("obj_cur") }
        .trim()
    val summary = payload.field("summary").trim()
    val body = payload.field("text").trim()
    val memory = payload.field("memory")
    val symbols = (payload["relevant_symbols"] as? JsonArray).orEmpty().joinToString("  ·  ") { it.display(160) }
    val provenance = listOfNotNull(
        "COMPLETE".takeIf { payload.boolean("completed") == true },
        "REPLAY".takeIf { event.replay },
        event.sequence?.let { "#$it" },
    ).joinToString("  ·  ")
    val borderColor = if (rendered.latestArcanaResponse) ritualPurpleGlow else ritualPurple
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xEE160F20), Color(0xD9080A0E))))
            .border(1.dp, borderColor.copy(alpha = if (event.replay) 0.18f else if (rendered.latestArcanaResponse) 0.42f else 0.28f), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                structured.round?.let { "ROUND ${it + 1}" } ?: structured.phase.uppercase(),
                color = amber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.7.sp,
            )
            if (showCommand) {
                Text(
                    commandName,
                    color = ritualPurpleGlow.copy(alpha = 0.92f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ritualPurpleGlow.copy(alpha = 0.08f))
                        .border(1.dp, ritualPurpleGlow.copy(alpha = 0.22f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (provenance.isNotBlank()) Text(provenance, color = muted.copy(alpha = 0.5f), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
        }
        if (root.isNotBlank() || current.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (root.isNotBlank()) XRitualRow("ROOT", root, Color(0xFFE3BD70))
                if (current.isNotBlank()) XRitualRow("NOW", current, Color(0xFFC9CED7))
            }
        }
        if (summary.isNotBlank() && summary != body) {
            Text(summary, color = amber.copy(alpha = 0.78f), fontSize = 10.sp, lineHeight = 14.sp)
        }
        if (body.isNotBlank()) {
            Text(body.replace(xBlankLines, "\n\n"), color = Color(0xFFD8DBDF), fontSize = 11.sp, lineHeight = 15.sp)
        }
        if (symbols.isNotBlank()) XRitualRow("SYMBOLS", symbols, cyan.copy(alpha = 0.76f), FontFamily.Monospace)
        if (showCommand && commandDetail.isNotBlank()) {
            Text(
                commandDetail,
                color = text.copy(alpha = 0.76f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                lineHeight = 13.5.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.dp))
                    .background(amber.copy(alpha = 0.055f))
                    .border(1.dp, amber.copy(alpha = 0.18f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }
        if (memory.isNotBlank()) XMemory(memory, rendered.latestArcanaResponse, rendered.key)
    }
}

@Composable
private fun XMemory(memory: String, latest: Boolean, key: Any) {
    val lines = remember(memory) {
        memory.lineSequence().map { it.trimEnd() }.fold(mutableListOf<String>()) { out, line ->
            if (line.isNotBlank() || out.lastOrNull()?.isNotBlank() == true) out += line
            out
        }.dropLastWhile(String::isBlank)
    }
    val summary = remember(lines) {
        val statuses = lines.mapNotNull(String::xMemoryStatus)
        val open = statuses.count { it in xMemoryOpenStatuses }
        val done = statuses.count { it in xMemoryDoneStatuses }
        val bridge = lines.lastOrNull { it.trimStart().startsWith("status:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
        listOfNotNull("$open open".takeIf { open > 0 }, "$done done".takeIf { done > 0 }, bridge?.let { "bridge $it" })
            .ifEmpty { listOf("${lines.count(String::isNotBlank)} lines") }
            .joinToString("  ·  ")
    }
    val focus = remember(lines) { lines.firstOrNull { it.xMemoryStatus() in xMemoryOpenStatuses }?.trim() }
    val styledMemory = remember(lines) { lines.xMemoryText() }
    var expanded by remember(key) { mutableStateOf(latest) }
    LaunchedEffect(latest) { if (!latest) expanded = false }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ritualPurple.copy(alpha = 0.09f))
            .border(1.dp, ritualPurpleGlow.copy(alpha = 0.2f), shape),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("MeMoRia", color = ritualPurpleGlow.copy(alpha = 0.92f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    summary,
                    color = muted.copy(alpha = 0.74f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(if (expanded) "COLLAPSE" else "EXPAND", color = amber.copy(alpha = 0.72f), fontFamily = FontFamily.Monospace, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            if (!expanded && !focus.isNullOrBlank()) {
                Text(focus, color = text.copy(alpha = 0.68f), fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 7.dp)) {
                HorizontalDivider(color = ritualPurpleGlow.copy(alpha = 0.14f))
                Text(
                    styledMemory,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    lineHeight = 13.5.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

private val xMemoryStatus = Regex("""\[(TODO|WIP|STALE|DONE|COMPLETED|BLOCKED)(?:\s*:[^]]*)?]""", RegexOption.IGNORE_CASE)
private val xMemoryOpenStatuses = setOf("TODO", "WIP", "STALE", "BLOCKED")
private val xMemoryDoneStatuses = setOf("DONE", "COMPLETED")
private val xMemorySection = Regex("""(?i)\b(?:ACTIVE TASK TREE|CRITICAL NOTES|EXTERNAL MEMORY BRIDGE)\b""")

private fun String.xMemoryStatus() = xMemoryStatus.find(this)?.groupValues?.get(1)?.uppercase()

private fun List<String>.xMemoryText(): AnnotatedString {
    val out = AnnotatedString.Builder()
    forEachIndexed { index, raw ->
        val line = raw.trimEnd()
        val start = out.length
        out.append(line)
        val status = line.xMemoryStatus()
        val tone = when {
            xMemorySection.containsMatchIn(line) -> amber.copy(alpha = 0.88f)
            status == "BLOCKED" || status == "STALE" -> rose.copy(alpha = 0.9f)
            status == "WIP" -> ritualPurpleGlow
            status in xMemoryDoneStatuses -> green.copy(alpha = 0.62f)
            status == "TODO" -> amber.copy(alpha = 0.68f)
            line.trimStart().startsWith("path:", true) || line.trimStart().startsWith("anchors:", true) -> cyan.copy(alpha = 0.78f)
            else -> Color(0xFFB9B6C2)
        }
        out.addStyle(
            SpanStyle(color = tone, fontWeight = if (xMemorySection.containsMatchIn(line)) FontWeight.Bold else FontWeight.Normal),
            start,
            out.length,
        )
        if (index != lastIndex) out.append('\n')
    }
    return out.toAnnotatedString()
}

@Composable
private fun XRitualRow(label: String, value: String, color: Color, family: FontFamily = FontFamily.Default) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = muted.copy(alpha = 0.72f), fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, lineHeight = 11.sp, modifier = Modifier.width(54.dp))
        Text(value, color = color, fontFamily = family, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
    }
}

private fun JsonObject.field(name: String) = this[name].display()

private fun JsonObject.xCodexText() = (this["content"] as? JsonArray).orEmpty().mapNotNull { content ->
    (content as? JsonObject)?.field("text")?.takeIf(String::isNotBlank)
}.joinToString("\n")

private fun OpsSessionEventDto.xWithCodexItem(item: JsonObject) = copy(
    structured = structured?.let { it.copy(payload = JsonObject(it.payload + ("item" to item))) },
)

private fun JsonElement?.display(limit: Int = 1_200): String {
    if (this == null || this is JsonNull) return ""
    val value = runCatching { jsonPrimitive.contentOrNull }.getOrNull() ?: toString()
    return if (value.length <= limit) value else value.take(limit) + "…"
}

private fun Long.xSessionTime(): String {
    val value = runCatching { Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault()) }.getOrNull() ?: return ""
    return "${value.day.toString().padStart(2, '0')} ${value.month.name.lowercase().take(3)} · ${value.hour.toString().padStart(2, '0')}:${value.minute.toString().padStart(2, '0')}"
}

private val xInputMarker = Regex("(?m)^.*```+INPUT[^\\n]*(?:\\n|$)")
private val xBlankLines = Regex("\\n{3,}")
private val xCompactCommandArgs = setOf("command", "code", "reason", "question")
private val xCodexDiagnostic = Regex("""^\d{4}-\d{2}-\d{2}T\S+\s+(?:TRACE|DEBUG|INFO|WARN|ERROR)\s+\S+:""")
private val xArcanaResponsePanels = setOf("Round Cockpit", "Summary", "Text", "MeMoRia", "Agent JSON", "CmD")
private val xArcanaPanel = Regex(
    """(?ms)^╭[^\n]*(Round Cockpit|Summary|Text|MeMoRia|Agent JSON|CmD|History|Command Preview|ASK USER)[^\n]*(?:\n|$).*?^╰[^\n]*╯(?:\n|$)""",
)
private val xArcanaCommandName = Regex("""(?m)^\s*│\s*(?:\{\s*)?"name"\s*:\s*("(?:\\.|[^"\\])*")""")
private val xArcanaQueryLine = Regex("""(?m)^\s*\[\s*◷\s*Query\s*-\s*\d+\s*⋮\s*\d{2}:\d{2}:\d{2}\s*](?:\n|$)""")

private data class XArcanaOwners(
    val responsePanels: MutableMap<String, Int> = mutableMapOf(),
    val commands: MutableMap<String, Int> = mutableMapOf(),
    var askUserInputs: Int = 0,
    var objectiveAcceptanceSequence: Long? = null,
    var finalStateKey: Any? = null,
)

private fun MutableMap<String, Int>.own(panel: String) {
    this[panel] = getOrElse(panel) { 0 } + 1
}

private data class XArcanaChunk(val key: Any, val start: Int, val text: AnnotatedString)
private data class XArcanaProjection(
    val owners: Map<String, XArcanaOwners>,
    val stdout: Map<Any, AnnotatedString>,
    val legacyActivity: XArcanaActivity?,
    val terminalStates: Map<String, XAnsiState>,
)
private data class XTranscriptProjection(val events: List<XRenderedEvent>, val activity: XArcanaActivity?) {
    companion object {
        val EMPTY = XTranscriptProjection(emptyList(), null)
    }
}
private data class XLegacyWorkingProjection(val hidden: BooleanArray, val active: Boolean)

private val xLegacyWorkingPrefix = Regex("""^•\s*Working\s+[🌑🌒🌓🌔🌕🌖🌗🌘]\s+\(\d+s\)""")

private fun String.xSkipLegacySgr(from: Int, limit: Int): Int {
    if (from + 2 >= limit || this[from] != '\u001B' || this[from + 1] != '[') return from
    var cursor = from + 2
    while (cursor < limit && (this[cursor].isDigit() || this[cursor] == ';')) cursor++
    return if (cursor < limit && this[cursor] == 'm') cursor + 1 else from
}

private fun String.xLegacyWorkingProjection(): XLegacyWorkingProjection {
    val hidden = BooleanArray(length)
    var lastWorking = -1
    var lastClear = -1
    var start = indexOf('\r')
    while (start >= 0) {
        val end = indexOf('\r', start + 1).let { if (it < 0) length else it }
        val plain = StringBuilder(128)
        val rawEnds = IntArray(128)
        var raw = start + 1
        while (raw < end && plain.length < rawEnds.size) {
            val afterSgr = xSkipLegacySgr(raw, end)
            if (afterSgr != raw) {
                raw = afterSgr
            } else {
                plain.append(this[raw])
                raw++
                rawEnds[plain.lastIndex] = raw
            }
        }
        val working = xLegacyWorkingPrefix.find(plain)?.takeIf { it.range.first == 0 }
        if (working != null) {
            var hiddenEnd = rawEnds[working.range.last]
            while (hiddenEnd < end) {
                val afterSgr = xSkipLegacySgr(hiddenEnd, end)
                if (afterSgr == hiddenEnd) break
                hiddenEnd = afterSgr
            }
            for (index in start until hiddenEnd) hidden[index] = true
            lastWorking = start
        } else if (end - start > 2 && (start + 1 until end).all { this[it] == ' ' }) {
            for (index in start until minOf(end + 1, length)) hidden[index] = true
            lastClear = end
        }
        start = end.takeIf { it < length } ?: -1
    }
    return XLegacyWorkingProjection(hidden, lastWorking > lastClear)
}

private fun String.xTerminalSource(trim: Boolean = true): String {
    val source = replace(xInputMarker, "")
    val visible = StringBuilder(source.length)
    source.forEach { char ->
        if (char == '\r') {
            val lineStart = visible.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
            visible.setLength(lineStart)
        } else {
            visible.append(char)
        }
    }
    val normalized = visible.toString().replace(xBlankLines, "\n\n")
    return if (trim) normalized.trim('\n') else normalized
}

private fun List<OpsSessionEventDto>.xArcanaProjection(): XArcanaProjection {
    val owners = mutableMapOf<String, XArcanaOwners>()
    forEach { event ->
        val structured = event.structured?.takeIf { event.agent == OpsAgentDto.ARCANA } ?: return@forEach
        val owner = owners.getOrPut(event.runtimeId ?: "-", ::XArcanaOwners)
        when (structured.type) {
            "agent_response" -> {
                val payload = structured.payload
                val command = payload["command"] as? JsonObject
                val hasCockpit = listOf("root_objective", "obj_root", "current_objective", "main_objective", "obj_cur", "summary")
                    .any { payload.field(it).isNotBlank() } ||
                    (payload["relevant_symbols"] as? JsonArray).orEmpty().isNotEmpty() || "completed" in payload || command != null
                if (hasCockpit) {
                    owner.responsePanels.own("Round Cockpit")
                }
                if (payload.field("summary").isNotBlank()) owner.responsePanels.own("Summary")
                if (payload.field("text").isNotBlank()) owner.responsePanels.own("Text")
                if (payload.field("memory").isNotBlank()) owner.responsePanels.own("MeMoRia")
                owner.responsePanels.own("Agent JSON")
                command?.field("name")?.takeIf(String::isNotBlank)?.let { name ->
                    owner.commands.own(name)
                    owner.responsePanels.own("CmD")
                }
            }
            "input_request" -> {
                when (structured.payload.field("kind")) {
                    "ask_user_input" -> owner.askUserInputs++
                    "objective_acceptance" -> owner.objectiveAcceptanceSequence = event.sequence
                }
            }
            "session_state" -> {
                owner.finalStateKey = event.xTransportKey()
            }
        }
    }

    val projected = mutableMapOf<Any, AnnotatedString>()
    val terminalStates = mutableMapOf<String, XAnsiState>()
    var legacyActivity: XArcanaActivity? = null
    var legacyActivitySequence = Long.MIN_VALUE
    filter { event ->
        event.agent == OpsAgentDto.ARCANA && event.structured == null && event.channel == OpsSessionChannelDto.STDOUT
    }.groupBy { it.runtimeId ?: "-" }.forEach { (runtime, events) ->
        val legacy = events.takeIf { stream -> stream.any { '\r' in it.text.orEmpty() } }
            ?.joinToString("") { it.text.orEmpty() }
            ?.xLegacyWorkingProjection()
        if (legacy?.active == true) {
            val sequence = events.lastOrNull()?.sequence ?: Long.MIN_VALUE
            if (sequence >= legacyActivitySequence) {
                legacyActivity = XArcanaActivity("legacy:$runtime", "Working")
                legacyActivitySequence = sequence
            }
        }
        var rawOffset = 0
        val ansi = XAnsiState()
        val stream = StringBuilder()
        val chunks = events.map { event ->
            val raw = event.text.orEmpty()
            val source = if (legacy == null) raw else buildString(raw.length) {
                raw.forEachIndexed { index, char ->
                    if (!legacy.hidden[rawOffset + index]) append(char)
                }
            }
            rawOffset += raw.length
            val text = ansi.render(source.xTerminalSource(trim = false), event.replay)
            XArcanaChunk(event.xTransportKey(), stream.length, text).also { stream.append(text.text) }
        }
        val owner = owners[runtime]
        val claimed = mutableMapOf<String, Int>()
        fun claim(key: String, available: Int): Boolean {
            val used = claimed.getOrElse(key) { 0 }
            if (used >= available) return false
            claimed[key] = used + 1
            return true
        }
        val blocks = xArcanaPanel.findAll(stream).toList()
        val blockCounts = blocks.groupingBy { it.groupValues[1] }.eachCount()
        val commandByJsonName = owner?.commands.orEmpty().keys.associateBy { JsonPrimitive(it).toString() }
        val previewCommands = blocks.mapNotNull { block ->
            if (block.groupValues[1] != "Command Preview") return@mapNotNull null
            val encodedName = xArcanaCommandName.find(block.value)?.groupValues?.get(1) ?: return@mapNotNull null
            commandByJsonName[encodedName]?.let { block.range to it }
        }.toMap()
        val commandBlockCounts = previewCommands.values.groupingBy { it }.eachCount()
        val ranges = buildList {
            blocks.forEach { block ->
                val title = block.groupValues[1]
                val owned = when (title) {
                    "ASK USER" -> (blockCounts[title] ?: 0) <= (owner?.askUserInputs ?: 0) &&
                        claim("input:ask_user", owner?.askUserInputs ?: 0)
                    "Command Preview" -> previewCommands[block.range]?.let { command ->
                        owner?.commands?.get(command)?.let { count ->
                            (commandBlockCounts[command] ?: 0) <= count &&
                                claim("command:$command", count)
                        }
                    } == true
                    in xArcanaResponsePanels -> {
                        val available = owner?.responsePanels?.get(title) ?: 0
                        (blockCounts[title] ?: 0) <= available && claim("panel:$title", available)
                    }
                    else -> false
                }
                if (owned) add(block.range)
            }
            val queryLines = xArcanaQueryLine.findAll(stream).toList()
            if (queryLines.size <= (owner?.responsePanels?.get("Agent JSON") ?: 0)) queryLines.forEach { add(it.range) }
        }.sortedBy(IntRange::first).fold(mutableListOf<IntRange>()) { merged, range ->
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.last + 1) {
                merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else {
                merged += range
            }
            merged
        }
        var rangeIndex = 0
        chunks.forEach { chunk ->
            val end = chunk.start + chunk.text.length
            while (rangeIndex < ranges.size && ranges[rangeIndex].last < chunk.start) rangeIndex++
            val projectedChunk = AnnotatedString.Builder()
            var cursor = 0
            var index = rangeIndex
            while (index < ranges.size && ranges[index].first < end) {
                val range = ranges[index]
                val from = maxOf(chunk.start, range.first)
                val to = minOf(end, range.last + 1)
                val localFrom = from - chunk.start
                val localTo = to - chunk.start
                if (localFrom > cursor) projectedChunk.append(chunk.text.subSequence(cursor, localFrom))
                cursor = maxOf(cursor, localTo)
                if (range.last < end) {
                    index++
                    rangeIndex = index
                } else {
                    break
                }
            }
            if (cursor < chunk.text.length) projectedChunk.append(chunk.text.subSequence(cursor, chunk.text.length))
            projected[chunk.key] = projectedChunk.toAnnotatedString()
        }
        terminalStates[runtime] = ansi
    }
    return XArcanaProjection(owners, projected, legacyActivity, terminalStates)
}

private fun XArcanaProjection.appendStdout(events: List<OpsSessionEventDto>): XArcanaProjection {
    if (events.isEmpty()) return this
    val projected = stdout.toMutableMap()
    val states = terminalStates.mapValuesTo(mutableMapOf()) { (_, state) -> state.copy() }
    events.forEach { event ->
        val runtime = event.runtimeId ?: "-"
        projected[event.xTransportKey()] = states.getOrPut(runtime, ::XAnsiState)
            .render(event.text.orEmpty().xTerminalSource(trim = false), event.replay)
    }
    return copy(stdout = projected, terminalStates = states)
}

internal fun List<OpsSessionEventDto>.xActiveArcanaActivity() = xTranscriptProjection().activity

internal fun List<OpsSessionEventDto>.xRenderedEvents() = xTranscriptProjection().events

private fun OpsSessionEventDto.xAffectsArcanaProjection(): Boolean {
    if (agent != OpsAgentDto.ARCANA) return false
    val event = structured
    return if (event == null) {
        channel == OpsSessionChannelDto.STDOUT
    } else {
        event.type in setOf("agent_response", "command_preview", "input_request", "session_state")
    }
}

private val OpsSessionEventDto.xPlainArcanaStdout get() =
    agent == OpsAgentDto.ARCANA && structured == null && channel == OpsSessionChannelDto.STDOUT && '\r' !in text.orEmpty()

private val XRenderedEvent.xArcanaStdout get() =
    event.agent == OpsAgentDto.ARCANA && event.structured == null && event.channel == OpsSessionChannelDto.STDOUT

private val XRenderedEvent.xStderrStream get() =
    event.structured == null && event.kind == OpsSessionEventKindDto.STREAM && event.channel == OpsSessionChannelDto.STDERR

private fun AnnotatedString.xTrimNewlines(trimLeading: Boolean = true, trimTrailing: Boolean = true): AnnotatedString {
    val start = if (trimLeading) text.indexOfFirst { it != '\n' }.takeIf { it >= 0 } ?: return AnnotatedString("") else 0
    val end = if (trimTrailing) text.indexOfLast { it != '\n' } + 1 else length
    return subSequence(start, end.coerceAtLeast(start))
}

private fun XRenderedEvent.withStreamText(
    event: OpsSessionEventDto = this.event,
    rawText: AnnotatedString,
    trimTrailing: Boolean = trimTrailingStreamNewlines,
) = copy(
    event = event,
    text = rawText.xTrimNewlines(trimLeadingStreamNewlines, trimTrailing),
    rawText = rawText,
    revision = event.xTransportKey(),
    trimTrailingStreamNewlines = trimTrailing,
)

private fun MutableList<XRenderedEvent>.appendArcanaStdout(event: OpsSessionEventDto, text: AnnotatedString) {
    var offset = 0
    while (offset < text.length) {
        val previous = lastOrNull()
        val continues = previous?.xArcanaStdout == true && previous.event.runtimeId == event.runtimeId
        val previousRaw = previous?.rawText
        if (continues && previousRaw != null && previousRaw.length < X_STREAM_BLOCK_CHARS) {
            val count = minOf(X_STREAM_BLOCK_CHARS - previousRaw.length, text.length - offset)
            val raw = AnnotatedString.Builder().apply {
                append(previousRaw)
                append(text.subSequence(offset, offset + count))
            }.toAnnotatedString()
            this[lastIndex] = previous.withStreamText(event, raw)
            offset += count
            continue
        }
        if (continues && previousRaw != null) {
            this[lastIndex] = previous.withStreamText(rawText = previousRaw, trimTrailing = false)
        }
        val count = minOf(X_STREAM_BLOCK_CHARS, text.length - offset)
        val raw = text.subSequence(offset, offset + count)
        this += XRenderedEvent(
            event = event,
            text = raw.xTrimNewlines(trimLeading = !continues, trimTrailing = true),
            rawText = raw,
            key = if (offset == 0) event.xTransportKey() else "${event.xTransportKey()}:$offset",
            revision = event.xTransportKey(),
            streamContinuation = continues,
            trimLeadingStreamNewlines = !continues,
            trimTrailingStreamNewlines = true,
        )
        offset += count
    }
}

private fun MutableList<XRenderedEvent>.appendStderrStream(event: OpsSessionEventDto, text: AnnotatedString) {
    val previous = lastOrNull()
    if (previous?.xStderrStream == true &&
        previous.event.runtimeId == event.runtimeId &&
        previous.event.agent == event.agent &&
        previous.event.replay == event.replay
    ) {
        val raw = AnnotatedString.Builder().apply {
            append(previous.rawText ?: previous.text ?: AnnotatedString(""))
            append(text)
        }.toAnnotatedString()
        this[lastIndex] = previous.withStreamText(event, raw)
    } else {
        this += XRenderedEvent(event, text)
    }
}

private fun MutableList<XRenderedEvent>.appendLegacyArcanaStdout(event: OpsSessionEventDto, text: AnnotatedString) {
    val previous = lastOrNull()
    if (previous?.xArcanaStdout == true && previous.event.runtimeId == event.runtimeId) {
        val raw = AnnotatedString.Builder().apply {
            append(previous.rawText ?: previous.text ?: AnnotatedString(""))
            append(text)
        }.toAnnotatedString()
        this[lastIndex] = previous.withStreamText(event, raw)
    } else {
        this += XRenderedEvent(
            event = event,
            text = text.xTrimNewlines(),
            rawText = text,
            trimLeadingStreamNewlines = true,
            trimTrailingStreamNewlines = true,
        )
    }
}

private fun XTranscriptProjection.appendArcanaStdout(
    events: List<OpsSessionEventDto>,
    arcana: XArcanaProjection,
): XTranscriptProjection {
    val rendered = this.events.toMutableList()
    events.forEach { event ->
        arcana.stdout[event.xTransportKey()]?.takeIf { it.text.isNotEmpty() }?.let { text ->
            rendered.appendArcanaStdout(event, text)
        }
    }
    return copy(events = rendered)
}

private fun List<OpsSessionEventDto>.xTranscriptProjection(
    arcana: XArcanaProjection = xArcanaProjection(),
): XTranscriptProjection {
    val active = linkedMapOf<String, XArcanaActivity>()
    var typedActivitySeen = false
    var runtimeActive = true
    val states = mutableMapOf<OpsSessionChannelDto, XAnsiState>()
    val rendered = mutableListOf<XRenderedEvent>()
    val codexItems = mutableMapOf<String, Int>()
    val codexInputs = mutableMapOf<String, Int>()
    val codexDeltas = mutableMapOf<String, StringBuilder>()
    val codexDeltaRevisions = mutableMapOf<String, Any>()
    val failures = mutableSetOf<String>()
    var pendingCodexInput: String? = null
    val codexHasStructuredText = any { event ->
        event.structured?.takeIf { it.phase == "codex" }?.type in setOf("item/agentMessage/delta", "item/commandExecution/outputDelta")
    }
    fun codexKey(payload: JsonObject, itemId: String) = listOf(payload.field("threadId"), payload.field("turnId"), itemId).joinToString(":")

    fun withDelta(item: JsonObject, key: String): JsonObject {
        val delta = codexDeltas[key]?.toString().orEmpty()
        if (delta.isEmpty()) return item
        return when (item.field("type")) {
            "agentMessage" -> if (item.field("text").isBlank()) JsonObject(item + ("text" to JsonPrimitive(delta))) else item
            "commandExecution" -> if (item.field("aggregatedOutput").isBlank()) JsonObject(item + ("aggregatedOutput" to JsonPrimitive(delta))) else item
            else -> item
        }
    }

    for (event in this) {
        val structured = event.structured
        if (event.agent == OpsAgentDto.ARCANA) {
            event.state?.let { state ->
                runtimeActive = state.isActiveRuntime
                if (!runtimeActive) active.clear()
            }
            if (structured?.type == "activity" && structured.schema == ARCANA_ACTIVITY_SCHEMA) {
                typedActivitySeen = true
                structured.payload.field("id").takeIf(String::isNotBlank)?.let { id ->
                    when (structured.payload.field("status")) {
                        "started" -> {
                            active.remove(id)
                            active[id] = XArcanaActivity(
                                id = id,
                                label = structured.payload.field("label").ifBlank { "Working" },
                            )
                        }
                        "completed", "failed" -> active.remove(id)
                    }
                }
                continue
            }
        }
        val failureIdentity = event.agent
            ?.takeIf { it == OpsAgentDto.ARCANA || it == OpsAgentDto.CODEX }
            ?.let { "${it.name}:${event.runtimeId ?: "-"}" }
        if (structured == null && event.kind == OpsSessionEventKindDto.ERROR && event.state == OpsSessionStateDto.FAILED &&
            !event.text.isNullOrBlank() && failureIdentity != null
        ) {
            failures += failureIdentity
        }
        if (structured == null && event.kind == OpsSessionEventKindDto.LIFECYCLE &&
            (event.agent == OpsAgentDto.ARCANA || event.agent == OpsAgentDto.CODEX)
        ) {
            val agent = event.agent ?: continue
            val runtime = event.runtimeId ?: "-"
            if (event.state == OpsSessionStateDto.FAILED && !event.text.isNullOrBlank() && failures.add(failureIdentity ?: "${agent.name}:$runtime")) {
                rendered += XRenderedEvent(event, key = "${agent.name.lowercase()}-failure:$runtime")
            }
            continue
        }
        if (event.agent == OpsAgentDto.ARCANA && structured != null) {
            val runtime = event.runtimeId ?: "-"
            when (structured.type) {
                "agent_response" -> {
                    val identity = structured.round?.toString() ?: event.sequence?.toString() ?: event.xTransportKey()
                    rendered += XRenderedEvent(event, key = "arcana-round:$runtime:${structured.phase}:$identity")
                }
                "session_state" -> {
                    val owner = arcana.owners[runtime]
                    if (owner?.finalStateKey != event.xTransportKey()) continue
                    val status = structured.payload.field("status")
                    val gateOwnsAwaiting = status == "awaiting_acceptance" &&
                        owner.objectiveAcceptanceSequence?.let { input -> event.sequence?.let { input > it } } == true
                    if (status != "running" && !gateOwnsAwaiting) {
                        rendered += XRenderedEvent(
                            event,
                            key = "arcana-state:$runtime",
                        )
                    }
                }
                "phase" -> if (structured.payload.field("state") != "completed") {
                    rendered += XRenderedEvent(event)
                }
                else -> rendered += XRenderedEvent(event)
            }
            continue
        }
        if (event.agent == OpsAgentDto.CODEX && structured?.phase == "codex") {
            val payload = structured.payload
            when (structured.type) {
                "item/started", "item/completed" -> {
                    val item = payload["item"] as? JsonObject ?: continue
                    if (item.field("type") !in xCodexVisibleItems) continue
                    val itemId = item.field("id").takeIf(String::isNotBlank) ?: continue
                    val key = codexKey(payload, itemId)
                    val projected = event.xWithCodexItem(withDelta(item, key))
                    val userText = item.takeIf { it.field("type") == "userMessage" }?.xCodexText()?.trim()
                    val index = codexItems[key] ?: userText?.takeIf(String::isNotBlank)?.let { text ->
                        rendered.indexOfLast { row ->
                            row.event.agent == OpsAgentDto.CODEX && row.event.channel == OpsSessionChannelDto.STDIN && row.text?.text?.trim() == text
                        }.takeIf { it >= 0 }
                    }
                    val row = XRenderedEvent(
                        event = projected,
                        key = "codex:$key",
                    )
                    if (index == null) {
                        codexItems[key] = rendered.size
                        rendered += row
                    } else {
                        codexItems[key] = index
                        rendered[index] = row
                    }
                    codexDeltaRevisions.remove(key)
                }
                "item/agentMessage/delta", "item/commandExecution/outputDelta" -> {
                    val itemId = payload.field("itemId").takeIf(String::isNotBlank) ?: continue
                    val key = codexKey(payload, itemId)
                    codexDeltas.getOrPut(key, ::StringBuilder).append(payload.field("delta"))
                    codexDeltaRevisions[key] = event.xTransportKey()
                }
                "input_request" -> {
                    val requestId = payload.field("request_id").ifBlank { event.sequence.toString() }
                    pendingCodexInput = requestId
                    codexInputs[requestId] = rendered.size
                    rendered += XRenderedEvent(event, key = "codex-input:$requestId")
                }
                "input_resolved", "input_timeout" -> {
                    val requestId = payload.field("request_id").ifBlank { event.sequence.toString() }
                    val index = codexInputs[requestId]
                    val row = XRenderedEvent(
                        event,
                        key = "codex-input:$requestId",
                    )
                    if (index == null) rendered += row else rendered[index] = row
                    pendingCodexInput = null
                }
            }
            continue
        }
        if (event.agent == OpsAgentDto.CODEX && structured == null) {
            if (codexHasStructuredText && event.channel == OpsSessionChannelDto.STDOUT) continue
            if (event.channel == OpsSessionChannelDto.STDERR && xCodexDiagnostic.containsMatchIn(event.text.orEmpty().trimStart())) continue
            if (event.channel == OpsSessionChannelDto.STDIN && pendingCodexInput != null) continue
        }
        if (structured != null) {
            rendered += XRenderedEvent(event)
            continue
        }
        val channel = event.channel ?: OpsSessionChannelDto.SYSTEM
        val text = if (event.agent == OpsAgentDto.ARCANA && channel == OpsSessionChannelDto.STDOUT) {
            arcana.stdout[event.xTransportKey()] ?: AnnotatedString("")
        } else {
            states.getOrPut(channel, ::XAnsiState).render(
                event.text.orEmpty().xTerminalSource(trim = channel != OpsSessionChannelDto.STDERR || event.kind != OpsSessionEventKindDto.STREAM),
                event.replay,
            )
        }
        if (text.text.isEmpty()) continue
        if (event.agent == OpsAgentDto.ARCANA && channel == OpsSessionChannelDto.STDOUT) {
            if (event.xPlainArcanaStdout) rendered.appendArcanaStdout(event, text)
            else rendered.appendLegacyArcanaStdout(event, text)
        } else if (channel == OpsSessionChannelDto.STDERR && event.kind == OpsSessionEventKindDto.STREAM) {
            rendered.appendStderrStream(event, text)
        } else {
            rendered += XRenderedEvent(event, text)
        }
    }
    codexDeltaRevisions.forEach { (key, revision) ->
        val index = codexItems[key] ?: return@forEach
        val current = rendered[index]
        val item = current.event.structured?.payload?.get("item") as? JsonObject ?: return@forEach
        rendered[index] = current.copy(
            event = current.event.xWithCodexItem(withDelta(item, key)),
            revision = revision,
        )
    }
    val latestArcanaResponse = rendered.indexOfLast { it.event.agent == OpsAgentDto.ARCANA && it.event.structured?.type == "agent_response" }
    return XTranscriptProjection(
        events = rendered.mapIndexed { index, event ->
            event.copy(
                text = if (event.xArcanaStdout) event.text else event.text?.xTrimNewlines(),
                latestArcanaResponse = index == latestArcanaResponse,
            )
        }.filterNot { it.text?.text?.isBlank() == true },
        activity = active.values.lastOrNull()
            ?: arcana.legacyActivity.takeIf { !typedActivitySeen && runtimeActive },
    )
}

private fun XAnsiState.render(source: String, replay: Boolean): AnnotatedString {
    val text = carry + source
    carry = ""
    val out = AnnotatedString.Builder()
    var cursor = 0

    fun append(value: String) {
        if (value.isEmpty()) return
        val start = out.length
        out.append(value)
        out.addStyle(
            SpanStyle(
                color = (color ?: Color(0xFFC8CFD8)).copy(alpha = when {
                    dim -> 0.52f
                    replay -> 0.7f
                    else -> 0.92f
                }),
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            ),
            start,
            out.length,
        )
    }

    while (cursor < text.length) {
        val escape = text.indexOf('\u001B', cursor)
        if (escape < 0) {
            append(text.substring(cursor))
            break
        }
        append(text.substring(cursor, escape))
        if (escape + 1 >= text.length || text[escape + 1] != '[') {
            cursor = escape + 1
            continue
        }
        val end = (escape + 2 until text.length).firstOrNull { text[it] in '@'..'~' }
        if (end == null) {
            carry = text.substring(escape).take(64)
            break
        }
        if (text[end] == 'm') applySgr(text.substring(escape + 2, end))
        cursor = end + 1
    }
    return out.toAnnotatedString()
}

private fun XAnsiState.applySgr(value: String) {
    val codes = value.split(';').mapNotNull(String::toIntOrNull).ifEmpty { listOf(0) }
    var index = 0
    while (index < codes.size) {
        when (val code = codes[index]) {
            0 -> { color = null; bold = false; dim = false }
            1 -> bold = true
            2 -> dim = true
            22 -> { bold = false; dim = false }
            39 -> color = null
            in 30..37 -> color = xAnsiColors[code - 30]
            in 90..97 -> color = xAnsiColors[code - 82]
            38 -> when (codes.getOrNull(index + 1)) {
                2 -> if (index + 4 < codes.size) {
                    color = Color(codes[index + 2], codes[index + 3], codes[index + 4])
                    index += 4
                }
                5 -> codes.getOrNull(index + 2)?.let { color = it.xAnsi256(); index += 2 }
            }
        }
        index++
    }
}

private val xAnsiColors = listOf(
    Color(0xFF1D2021), Color(0xFFE06C75), Color(0xFF98C379), Color(0xFFE5C07B),
    Color(0xFF61AFEF), Color(0xFFC678DD), Color(0xFF56B6C2), Color(0xFFD7DAE0),
    Color(0xFF5C6370), Color(0xFFFF7A85), Color(0xFFB5E890), Color(0xFFFFD68A),
    Color(0xFF82C7FF), Color(0xFFE89AFF), Color(0xFF7EE6F2), Color(0xFFFFFFFF),
)
private fun Int.xAnsi256(): Color = when {
    this < 16 -> xAnsiColors[this.coerceAtLeast(0)]
    this < 232 -> {
        val n = this - 16
        fun level(value: Int) = if (value == 0) 0 else 55 + value * 40
        Color(level(n / 36), level(n / 6 % 6), level(n % 6))
    }
    else -> Color(8 + (this - 232).coerceIn(0, 23) * 10, 8 + (this - 232).coerceIn(0, 23) * 10, 8 + (this - 232).coerceIn(0, 23) * 10)
}

private fun OpsSessionStateDto.xLabel() = when (this) {
    OpsSessionStateDto.RUNNING -> "active"
    OpsSessionStateDto.ONGOING -> "ongoing"
    OpsSessionStateDto.AWAITING_ACCEPTANCE -> "awaiting acceptance"
    OpsSessionStateDto.CONCLUDED -> "concluded"
    else -> name.lowercase()
}

private fun JsonObject.boolean(name: String) = this[name]?.jsonPrimitive?.booleanOrNull

private fun String.isSubsequenceOf(candidate: String): Boolean {
    val needle = lowercase()
    if (needle.isEmpty()) return true
    var index = 0
    candidate.lowercase().forEach { char -> if (index < needle.length && needle[index] == char) index++ }
    return index == needle.length
}

private fun OpsRepositoryDto.toXPreview(): XRepoPreview {
    val timestamp = runCatching { Instant.parse(updatedAt).toLocalDateTime(TimeZone.currentSystemDefault()) }.getOrNull()
    val updated = timestamp?.let {
        val month = it.month.name.lowercase().replaceFirstChar(Char::uppercase).take(3)
        "${it.day.toString().padStart(2, '0')} $month ${it.year} · ${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
    } ?: updatedAt
    return XRepoPreview(
        id = id,
        name = name,
        description = description.orEmpty(),
        language = language ?: "Unknown",
        stars = stars,
        updated = updated,
    )
}
