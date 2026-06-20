package net.sdfgsdfg.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.net.URI

@Composable
internal actual fun rememberRemoteImageBitmap(url: String?): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = url?.let {
            runCatching {
                withContext(Dispatchers.IO) {
                    Image.makeFromEncoded(URI(it).toURL().readBytes()).toComposeImageBitmap()
                }
            }.getOrNull()
        }
    }
    return bitmap
}
