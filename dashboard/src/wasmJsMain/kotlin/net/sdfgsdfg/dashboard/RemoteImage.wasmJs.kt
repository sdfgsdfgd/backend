package net.sdfgsdfg.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.browser.window
import org.jetbrains.skia.Image
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.w3c.fetch.Response
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
@Composable
internal actual fun rememberRemoteImageBitmap(url: String?): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = null
        if (url.isNullOrBlank()) return@LaunchedEffect
        window.fetch(url).then(
            onFulfilled = { response: Response ->
                if (response.ok) {
                    response.arrayBuffer().then(
                        onFulfilled = { buffer ->
                            val bytes = Uint8Array(buffer, 0, buffer.byteLength).toByteArray()
                            bitmap = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
                            null
                        },
                        onRejected = { null },
                    )
                }
                null
            },
            onRejected = { null },
        )
    }
    return bitmap
}

private fun Uint8Array.toByteArray() = ByteArray(length) { this[it] }
