package net.sdfgsdfg.dashboard.desktop

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BaseMultiResolutionImage
import javax.imageio.ImageIO

internal object DashboardCursors {
    val default = load("dashboard-default", Point(4, 3), PointerIcon.Default)
    val windowMove = load("dashboard-window-move", Point(16, 16), PointerIcon.Hand)
    val text = load("dashboard-text", Point(16, 16), PointerIcon.Text)

    private fun load(
        name: String,
        hotSpot: Point,
        fallback: PointerIcon,
    ): PointerIcon = try {
        fun image(suffix: String) = requireNotNull(
            DashboardCursors::class.java.getResourceAsStream("/cursors/$name$suffix.png"),
        ) { "Missing cursor resource: $name$suffix.png" }.use {
            requireNotNull(ImageIO.read(it)) { "Unreadable cursor resource: $name$suffix.png" }
        }

        PointerIcon(
            Toolkit.getDefaultToolkit().createCustomCursor(
                BaseMultiResolutionImage(image(""), image("@2x")),
                hotSpot,
                name,
            ),
        )
    } catch (exception: Exception) {
        System.err.println("Dashboard cursor '$name' unavailable; using platform fallback (${exception.message}).")
        fallback
    }
}
