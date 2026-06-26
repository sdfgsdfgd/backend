package net.sdfgsdfg.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import net.sdfgsdfg.dashboard.tools.FrameWindowProfiler
import net.sdfgsdfg.dashboard.tools.issueFrameTrace
import net.sdfgsdfg.dashboard.tools.issueFrameTraceEnabled
import net.sdfgsdfg.dashboard.tools.issueJfrProfile
import net.sdfgsdfg.dashboard.tools.issueProfileEnabled

internal data class IssueLaneDiagnostics(
    val traceEnabled: Boolean,
    val movedKeys: Set<String>,
    val measureCounts: MutableMap<String, Int>,
    val placeCounts: MutableMap<String, Int>,
)

@Composable
internal fun rememberIssueLaneDiagnostics(
    repoId: String,
    laneStatus: String,
    ticketKeys: List<String>,
    countBackfill: Int,
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
): IssueLaneDiagnostics {
    val laneId = "$repoId:$laneStatus"
    val previousKeys = remember { arrayOf<List<String>?>(null) }
    val measureCounts = remember { mutableMapOf<String, Int>() }
    val placeCounts = remember { mutableMapOf<String, Int>() }
    var removalSeq by remember { mutableStateOf(0) }
    var removalKey by remember { mutableStateOf<String?>(null) }
    var removalDetail by remember { mutableStateOf("") }
    var movedKeys by remember { mutableStateOf(emptySet<String>()) }
    val traceEnabled = issueFrameTraceEnabled()
    val profileEnabled = issueProfileEnabled()

    LaunchedEffect(ticketKeys, countBackfill, profileEnabled) {
        val previous = previousKeys[0]
        previousKeys[0] = ticketKeys
        if (!profileEnabled || previous == null) return@LaunchedEffect

        val previousSet = previous.toSet()
        val currentSet = ticketKeys.toSet()
        val created = ticketKeys.filter { it !in previousSet }
        val removed = previous.filter { it !in currentSet }
        val moved = ticketKeys.filter { it in previousSet && previous.indexOf(it) != ticketKeys.indexOf(it) }
        if (created.isEmpty() && removed.isEmpty() && moved.isEmpty()) return@LaunchedEffect

        val removedIndex = removed.firstOrNull()?.let(previous::indexOf) ?: -1
        val shifted = if (removedIndex >= 0) previous.drop(removedIndex + 1).filter { it in currentSet } else moved
        val detail =
            "lane=$laneId keys=${previous.size}->${ticketKeys.size} backfill=$countBackfill first=$firstVisibleItemIndex offset=$firstVisibleItemScrollOffset removedIndex=$removedIndex shifted=${shifted.size} created=${created.traceKeys()} removed=${removed.traceKeys()} moved=${moved.traceKeys()}"
        issueFrameTrace("lane-change") { detail }
        if (removed.isNotEmpty()) {
            measureCounts.clear()
            placeCounts.clear()
            movedKeys = shifted.toSet()
            removalSeq += 1
            removalDetail = detail
            removalKey = "$laneId:$removalSeq:${removed.joinToString("|")}"
        }
    }

    removalKey?.let { key ->
        FrameWindowProfiler(
            enabled = profileEnabled,
            key = key,
            windowMs = 2_200L,
            jfr = issueJfrProfile("remove", removalDetail),
            onSevereFrame = { sample ->
                issueFrameTrace("remove-frame-skip") {
                    "$removalDetail frame=${sample.frame} delta=${sample.deltaMs}ms"
                }
            },
            onSummary = { summary ->
                issueFrameTrace("remove-frame-summary") {
                    "$removalDetail complete=${summary.complete} elapsed=${summary.elapsedMs}ms frames=${summary.frames} slowOver34=${summary.slowFrames} severeOver80=${summary.severeFrames} worst=${summary.worstFrameMs}ms measured=${measureCounts.traceCounts()} placed=${placeCounts.traceCounts()}"
                }
            },
        )
    }

    return IssueLaneDiagnostics(traceEnabled, movedKeys, measureCounts, placeCounts)
}

internal fun Modifier.issueLayoutTrace(diagnostics: IssueLaneDiagnostics, key: String) =
    if (!diagnostics.traceEnabled || key !in diagnostics.movedKeys) {
        this
    } else {
        layout { measurable, constraints ->
            diagnostics.measureCounts[key] = (diagnostics.measureCounts[key] ?: 0) + 1
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                diagnostics.placeCounts[key] = (diagnostics.placeCounts[key] ?: 0) + 1
                placeable.place(0, 0)
            }
        }
    }

private fun List<String>.traceKeys(limit: Int = 5) =
    take(limit).joinToString(prefix = "[", postfix = if (size > limit) ",+${size - limit}]" else "]")

private fun Map<String, Int>.traceCounts(limit: Int = 5) =
    entries.sortedByDescending { it.value }
        .take(limit)
        .joinToString(prefix = "[", postfix = if (size > limit) ",+${size - limit}]" else "]") {
            "${it.key.substringAfterLast(':')}=${it.value}"
        }
