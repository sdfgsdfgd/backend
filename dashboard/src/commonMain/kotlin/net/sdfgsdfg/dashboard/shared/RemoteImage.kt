package net.sdfgsdfg.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

@Composable
internal expect fun rememberRemoteImageBitmap(url: String?): ImageBitmap?
