package net.sdfgsdfg.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.sdfgsdfg.data.model.OpsSessionEventDto

private const val ARCANA_TELEMETRY_SCHEMA = "arcana.telemetry.v1"
private const val ARCANA_COMMAND_SCHEMA = "arcana.command.v1"
internal const val X_STREAM_BLOCK_CHARS = 4_096
internal val xDisclosureEnter = fadeIn(tween(150)) + expandVertically(tween(260, easing = FastOutSlowInEasing))
internal val xDisclosureExit = fadeOut(tween(90)) + shrinkVertically(tween(190, easing = FastOutSlowInEasing))

internal enum class XDiffLineKind {
    HEADER,
    HUNK,
    ADDITION,
    REMOVAL,
    CONTEXT;

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CONTEXT
    }
}

@Immutable
internal data class XTelemetryDiffLine(val kind: XDiffLineKind, val text: String)

@Immutable
internal data class XTelemetryDiff(val label: String, val lines: List<XTelemetryDiffLine>)

@Immutable
internal data class XTelemetryReceipt(
    val id: String,
    val kind: String,
    val status: String,
    val severity: String,
    val visibility: String,
    val operation: String,
    val model: String,
    val outcome: String,
    val schema: String,
    val stage: String,
    val attempt: Long?,
    val elapsedMs: Long?,
    val responseBytes: Long?,
    val requestId: String,
    val traceStatus: String,
    val tracePath: String,
    val traceReason: String,
    val details: List<String>,
    val diffs: List<XTelemetryDiff>,
    val revision: Any,
)

@Immutable
internal data class XCommandReceipt(
    val id: String,
    val name: String,
    val status: String,
    val invocation: String,
    val output: String,
    val outputChars: Long,
    val outputLines: Long,
    val stderrLines: Long,
    val exitCode: Long?,
    val truncated: Boolean,
    val revision: Any,
)

internal fun OpsSessionEventDto.xTelemetryReceipt(): XTelemetryReceipt? {
    val value = structured?.takeIf { it.type == "telemetry" && it.schema == ARCANA_TELEMETRY_SCHEMA } ?: return null
    val payload = value.payload
    val fields = payload["fields"] as? JsonObject ?: JsonObject(emptyMap())
    val id = payload.string("id").takeIf(String::isNotBlank) ?: return null
    return XTelemetryReceipt(
        id = id,
        kind = payload.string("kind"),
        status = payload.string("status"),
        severity = payload.string("severity"),
        visibility = payload.string("visibility"),
        operation = fields.string("operation"),
        model = fields.string("model"),
        outcome = fields.string("outcome"),
        schema = fields.string("schema"),
        stage = fields.string("stage"),
        attempt = fields["attempt"]?.jsonPrimitive?.longOrNull,
        elapsedMs = fields["elapsed_ms"]?.jsonPrimitive?.longOrNull,
        responseBytes = fields["response_bytes"]?.jsonPrimitive?.longOrNull,
        requestId = fields.string("request_id"),
        traceStatus = fields.string("trace_status"),
        tracePath = fields.string("trace_path"),
        traceReason = fields.string("trace_reason"),
        details = (payload["details"] as? JsonArray).orEmpty().mapNotNull {
            runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
        },
        diffs = (payload["diffs"] as? JsonArray).orEmpty().mapNotNull diff@ { value ->
            val diff = value as? JsonObject ?: return@diff null
            val lines = (diff["lines"] as? JsonArray).orEmpty().mapNotNull line@ { candidate ->
                val line = candidate as? JsonObject ?: return@line null
                line.string("text").takeIf(String::isNotEmpty)?.let {
                    XTelemetryDiffLine(XDiffLineKind.fromWire(line.string("kind")), it)
                }
            }
            XTelemetryDiff(diff.string("label"), lines).takeIf { lines.isNotEmpty() }
        },
        revision = xTransportKey(),
    )
}

internal fun OpsSessionEventDto.xCommandReceipt(): XCommandReceipt? {
    val value = structured?.takeIf { it.type == "command" && it.schema == ARCANA_COMMAND_SCHEMA } ?: return null
    val payload = value.payload
    val id = payload.string("id").takeIf(String::isNotBlank) ?: return null
    return XCommandReceipt(
        id = id,
        name = payload.string("name").ifBlank { "command" },
        status = payload.string("status"),
        invocation = payload.string("invocation"),
        output = payload.string("output"),
        outputChars = payload["output_chars"]?.jsonPrimitive?.longOrNull ?: 0,
        outputLines = payload["output_lines"]?.jsonPrimitive?.longOrNull ?: 0,
        stderrLines = payload["stderr_lines"]?.jsonPrimitive?.longOrNull ?: 0,
        exitCode = payload["exit_code"]?.jsonPrimitive?.longOrNull,
        truncated = payload["truncated"]?.jsonPrimitive?.booleanOrNull == true,
        revision = xTransportKey(),
    )
}

private fun JsonObject.string(name: String) = runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

@Composable
internal fun XCommandBlock(
    command: XCommandReceipt,
    defaultExpanded: Boolean,
    onDefaultExpandedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(command.id) { mutableStateOf(defaultExpanded) }
    val shape = RoundedCornerShape(9.dp)
    val tone = command.xCommandTone()
    val meta = remember(command) { command.xCommandMeta() }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.76f),
                        ritualPurple.copy(alpha = 0.035f),
                        Color.Black.copy(alpha = 0.84f),
                    ),
                ),
            )
            .border(1.dp, ritualPurpleGlow.copy(alpha = 0.10f), shape)
            .xRitualDisclosure(
                identity = command.id,
                expanded = expanded,
                description = "${command.name} command. $meta",
                radius = 9.dp,
            ) {
                expanded = !expanded
                onDefaultExpandedChanged(expanded)
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                command.name.replace('_', ' ').uppercase(),
                color = tone.copy(alpha = 0.92f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.55.sp,
            )
            Text(
                command.invocation.ifBlank { "Command execution" },
                color = text.copy(alpha = 0.82f),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.5.sp,
                lineHeight = 13.sp,
                maxLines = if (expanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                meta,
                color = tone.copy(alpha = 0.68f),
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                lineHeight = 12.sp,
                maxLines = 1,
            )
        }
        AnimatedVisibility(
            visible = expanded && command.output.isNotEmpty(),
            enter = xDisclosureEnter,
            exit = xDisclosureExit,
        ) {
            val chunks = remember(command.output) { command.output.chunked(X_STREAM_BLOCK_CHARS) }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                HorizontalDivider(color = tone.copy(alpha = 0.13f))
                chunks.forEachIndexed { index, chunk ->
                    Text(
                        chunk,
                        color = text.copy(alpha = 0.76f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.5.sp,
                        lineHeight = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index == 0) 6.dp else 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun Modifier.xRitualDisclosure(
    identity: Any,
    expanded: Boolean,
    description: String,
    radius: Dp,
    onToggle: () -> Unit,
): Modifier {
    val interaction = remember(identity) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val emphasis = animateFloatAsState(
        targetValue = if (hovered || focused) 1f else 0f,
        animationSpec = tween(if (hovered || focused) 150 else 260, easing = FastOutSlowInEasing),
        label = "x-disclosure-emphasis",
    )
    return drawBehind {
        val lift = emphasis.value
        if (lift > 0f) {
            val corner = CornerRadius(radius.toPx())
            drawRoundRect(ritualPurple.copy(alpha = 0.035f * lift), cornerRadius = corner)
            drawRoundRect(
                ritualPurpleGlow.copy(alpha = 0.10f * lift),
                cornerRadius = corner,
                style = Stroke(1.dp.toPx()),
            )
        }
    }
        .hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onToggle)
        .semantics(mergeDescendants = true) {
            contentDescription = "$description. Activate anywhere on this block to ${if (expanded) "collapse" else "expand"}."
            stateDescription = if (expanded) "Expanded" else "Collapsed"
        }
}

private fun XCommandReceipt.xCommandTone() = when (status) {
    "failed" -> rose
    "completed" -> Color(0xFF81CFA6)
    else -> amber
}

private fun XCommandReceipt.xCommandMeta() = buildList {
    add(
        when (status) {
            "failed" -> "failed"
            "completed" -> "complete"
            else -> "running"
        },
    )
    exitCode?.let { add("exit $it") }
    outputLines.takeIf { it > 0 }?.let { add("$it ${if (it == 1L) "line" else "lines"}") }
    stderrLines.takeIf { it > 0 }?.let { add("$it stderr") }
    truncated.takeIf { it }?.let { add("trimmed") }
}.joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun XTelemetryRibbon(
    receipts: List<XTelemetryReceipt>,
    debugTelemetry: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        receipts.forEach { receipt ->
            key(receipt.id) { XTelemetryChip(receipt, debugTelemetry) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XTelemetryChip(receipt: XTelemetryReceipt, debugTelemetry: Boolean) {
    var disclosure by remember(receipt.id) { mutableStateOf<Boolean?>(null) }
    val expanded = disclosure ?: debugTelemetry
    val summary = remember(receipt) { receipt.xTelemetrySummary() }
    val hasDetail = remember(receipt) {
        receipt.diffs.isNotEmpty() || receipt.details.isNotEmpty() || listOf(
            receipt.requestId,
            receipt.outcome,
            receipt.schema,
            receipt.traceStatus,
            receipt.traceReason,
            receipt.tracePath,
        ).any(String::isNotBlank)
    }
    val hint = remember(receipt.kind) { receipt.xTelemetryHint() }
    val tone = receipt.xTelemetryTone()
    val shape = RoundedCornerShape(10.dp)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above, 8.dp),
        tooltip = {
            DisableSelection {
                PlainTooltip(
                    caretShape = TooltipDefaults.caretShape(),
                    containerColor = Color(0xFF120B19),
                    contentColor = text.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(8.dp),
                    shadowElevation = 10.dp,
                ) {
                    Text(hint, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp)
                }
            }
        },
        state = rememberTooltipState(),
    ) {
        Column(
            Modifier
                .widthIn(max = 760.dp)
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            tone.copy(alpha = 0.13f),
                            ritualPurple.copy(alpha = 0.13f),
                            Color.Black.copy(alpha = 0.82f),
                        ),
                    ),
                )
                .border(1.dp, tone.copy(alpha = if (expanded) 0.42f else 0.26f), shape)
                .xRitualDisclosure(receipt.id, expanded, "$summary. $hint", 10.dp) {
                    disclosure = !expanded
                },
        ) {
            Row(
                Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).background(tone.copy(alpha = 0.9f), CircleShape))
                Text(
                    summary,
                    color = text.copy(alpha = 0.88f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (expanded) "HIDE" else "DETAIL",
                    color = tone.copy(alpha = 0.66f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
            AnimatedVisibility(
                visible = expanded && hasDetail,
                enter = xDisclosureEnter,
                exit = xDisclosureExit,
            ) {
                val detail = remember(receipt) { receipt.xTelemetryDetail() }
                Column(Modifier.padding(horizontal = 9.dp).padding(bottom = 8.dp)) {
                    HorizontalDivider(color = tone.copy(alpha = 0.18f))
                    Text(
                        detail,
                        color = text.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.5.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

private fun XTelemetryReceipt.xTelemetryTone() = when {
    severity == "error" || traceStatus == "retained" -> rose
    severity == "warning" || outcome in setOf("provider_throttle", "http_error", "response_read_failed") -> amber
    kind == "transport" -> cyan
    status == "completed" -> Color(0xFFD9C986)
    else -> ritualPurpleGlow
}

private fun XTelemetryReceipt.xTelemetrySummary(): String {
    val modelLabel = XArcanaModel.entries.firstOrNull { it.wire == model }?.compact ?: model
    return when (kind) {
        "transport" -> buildList {
            add(
                when {
                    status == "failed" -> "RPC failed"
                    traceStatus == "retained" -> "RPC · trace retained"
                    outcome.startsWith("reclaimed") -> "RPC recovered"
                    outcome == "retrying" || outcome == "reconnecting" -> "RPC retrying"
                    status == "started" || status == "running" -> "RPC running"
                    outcome == "provider_throttle" -> "RPC throttled"
                    else -> "RPC"
                },
            )
            modelLabel.takeIf(String::isNotBlank)?.let(::add)
            elapsedMs?.let { add(it.xTelemetryDuration()) }
            responseBytes?.let { add(it.xTelemetryBytes()) }
        }.joinToString(" · ")
        "json_repair" -> buildList {
            add(
                when {
                    status == "failed" -> "JSON repair failed"
                    outcome == "valid" -> "JSON valid"
                    outcome == "schema_pruned" -> "JSON normalized"
                    status == "started" -> "JSON checking"
                    else -> "JSON repaired"
                },
            )
            stage.replace('_', ' ').takeIf(String::isNotBlank)?.let(::add)
            attempt?.let { add("$it ${if (it == 1L) "pass" else "passes"}") }
        }.joinToString(" · ")
        else -> listOf(kind.replace('_', ' '), status).filter(String::isNotBlank).joinToString(" · ")
    }
}

private fun XTelemetryReceipt.xTelemetryHint() = when (kind) {
    "transport" -> "Transport receipt · hover provenance · click for RPC and packet-trace detail"
    "json_repair" -> "JSON repair receipt · click for ordered validation and repair detail"
    else -> "Operational receipt · click for detail"
}

private fun XTelemetryReceipt.xTelemetryDetail(): AnnotatedString = buildAnnotatedString {
    val metadata = buildList {
        requestId.takeIf(String::isNotBlank)?.let { add("request  $it") }
        outcome.takeIf(String::isNotBlank)?.let { add("outcome  ${it.replace('_', ' ')}") }
        schema.takeIf(String::isNotBlank)?.let { add("schema   $it") }
        traceStatus.takeIf(String::isNotBlank)?.let { add("trace    ${it.replace('_', ' ')}") }
        traceReason.takeIf(String::isNotBlank)?.let { add("reason   $it") }
        tracePath.takeIf(String::isNotBlank)?.let { add("capture  $it") }
    }
    append(metadata.joinToString("\n"))
    diffs.forEach { diff ->
        if (length > 0) append("\n\n")
        withStyle(SpanStyle(color = amber.copy(alpha = 0.82f), fontWeight = FontWeight.Bold)) {
            append(diff.label.uppercase())
        }
        diff.lines.forEach { line ->
            append('\n')
            withStyle(
                SpanStyle(
                    color = when (line.kind) {
                        XDiffLineKind.HEADER -> amber.copy(alpha = 0.72f)
                        XDiffLineKind.HUNK -> cyan.copy(alpha = 0.78f)
                        XDiffLineKind.ADDITION -> Color(0xFF81CFA6).copy(alpha = 0.90f)
                        XDiffLineKind.REMOVAL -> rose.copy(alpha = 0.88f)
                        XDiffLineKind.CONTEXT -> text.copy(alpha = 0.58f)
                    },
                ),
            ) {
                append(line.text)
            }
        }
    }
    if (details.isNotEmpty()) {
        if (length > 0) append("\n\n")
        withStyle(SpanStyle(color = ritualPurpleGlow.copy(alpha = 0.72f), fontWeight = FontWeight.Bold)) {
            append("DIAGNOSTICS")
        }
        details.forEach { detail ->
            append('\n')
            append(detail)
        }
    }
}

private fun Long.xTelemetryDuration(): String {
    if (this < 1_000) return "${this}ms"
    val tenths = this / 100
    return if (tenths % 10L == 0L) "${tenths / 10}s" else "${tenths / 10}.${tenths % 10}s"
}

private fun Long.xTelemetryBytes(): String {
    if (this < 1_024) return "$this B"
    val tenths = this * 10 / 1_024
    return if (tenths % 10L == 0L) "${tenths / 10} KB" else "${tenths / 10}.${tenths % 10} KB"
}
