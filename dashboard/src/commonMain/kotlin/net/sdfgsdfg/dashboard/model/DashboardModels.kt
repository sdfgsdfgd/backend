package net.sdfgsdfg.dashboard

import androidx.compose.ui.graphics.Color
import net.sdfgsdfg.data.model.IssueItemDto
import net.sdfgsdfg.data.model.IssueSummaryDto
import net.sdfgsdfg.data.model.OpsSummaryDto

internal enum class DashboardTab(val label: String) {
    Home("Home"),
    Ci("CI Results"),
    Issues("Issues"),
    Arcana("Arcana Sessions");

    companion object {
        fun fromStoredName(value: String) = if (value == "IssuesNew") Issues else entries.firstOrNull { it.name == value }
    }
}

internal sealed interface OpsLoadState {
    data object Loading : OpsLoadState
    data class Ready(val summary: OpsSummaryDto) : OpsLoadState
    data class Failed(val message: String) : OpsLoadState
}

internal enum class OpsSocketStatus { CONNECTING, CONNECTED, DISCONNECTED }

internal data class OpsSocketState(
    val status: OpsSocketStatus = OpsSocketStatus.CONNECTING,
    val latencyMs: Long? = null,
)

data class OpsGithubAuthWindowState(
    val title: String,
    val code: String? = null,
    val detail: String,
    val status: String,
    val terminal: Boolean = false,
    val success: Boolean = false,
)

internal data class FieldSpec(val name: String, val value: String, val detail: String? = null)

internal data class IssueLaneSpec(val label: String, val status: String, val color: Color, val count: (IssueSummaryDto) -> Int) {
    fun items(issues: IssueSummaryDto): List<IssueItemDto> =
        issues.items.filter { it.status == status }.sortedByCreation()
}

internal fun List<IssueItemDto>.sortedByCreation() = sortedByDescending { it.createdAtMs ?: Long.MIN_VALUE }

internal data class BadgeSpec(val label: String, val color: Color, val strong: Boolean = false)
