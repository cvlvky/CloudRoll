package com.cvlvky.cloudroll.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/** Build check for the image libraries' APIs. Not shown anywhere. */
@Composable
internal fun ProbeImages(path: String, onTap: () -> Unit, onZoom: (Float) -> Unit) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(java.io.File(path)).crossfade(true).build(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
    val state = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 8f)))
    ZoomableAsyncImage(
        model = ImageRequest.Builder(ctx).data(java.io.File(path)).build(),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        state = state,
        onClick = { onTap() },
    )
    onZoom(state.zoomableState.zoomFraction ?: 0f)
}
