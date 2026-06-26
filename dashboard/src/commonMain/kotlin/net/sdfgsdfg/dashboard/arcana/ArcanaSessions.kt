package net.sdfgsdfg.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ArcanaSessionsTab() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        WorkSurface(
            title = "Arcana Sessions",
            detail = "WIP. Ask the user what to steal from frontend-compose and frontend-next before this tab is implemented.",
            items = listOf("session chat", "patch review", "local artifacts", "desktop-only actions"),
        )
    }
}
