package local.skippy.gallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import local.skippy.gallery.compositor.GalleryPalette

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(viewModel: GalleryViewModel) {
    val images  by viewModel.images.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error   by viewModel.error.collectAsState()

    // Load on first composition.
    LaunchedEffect(Unit) { viewModel.load() }

    // Full-screen preview state
    var preview: DriveImage? by remember { mutableStateOf(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GalleryPalette.Black)
    ) {
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = GalleryPalette.Green,
            )

            error != null -> Text(
                text      = error ?: "",
                color     = GalleryPalette.Red.copy(alpha = 0.8f),
                fontSize  = 14.sp,
                textAlign = TextAlign.Center,
                modifier  = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )

            images.isEmpty() -> Column(
                modifier             = Modifier.align(Alignment.Center),
                horizontalAlignment  = Alignment.CenterHorizontally,
            ) {
                Text(
                    text      = "No images in Drive yet.",
                    color     = GalleryPalette.Green.copy(alpha = 0.5f),
                    fontSize  = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.load() }) {
                    Text("Retry", color = GalleryPalette.Green)
                }
            }

            else -> LazyVerticalGrid(
                columns               = GridCells.Fixed(3),
                contentPadding        = PaddingValues(2.dp),
                verticalArrangement   = Arrangement.spacedBy(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier              = Modifier.fillMaxSize(),
            ) {
                items(images, key = { it.fileId }) { img ->
                    GalleryThumb(
                        image    = img,
                        thumbUrl = viewModel.thumbUrl(img),
                        onTap    = { preview = img },
                    )
                }
            }
        }
    }

    // ── Full-screen preview ───────────────────────────────────────────────
    preview?.let { img ->
        Dialog(onDismissRequest = { preview = null }) {
            FullScreenImage(
                image     = img,
                imageUrl  = viewModel.imageUrl(img.fileId),
                onDismiss = { preview = null },
            )
        }
    }
}

// ── Thumbnail ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryThumb(
    image:    DriveImage,
    thumbUrl: String,
    onTap:    () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(GalleryPalette.DimSurface)
            .combinedClickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model              = thumbUrl,
            contentDescription = image.name,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier.fillMaxSize(),
        )
    }
}

// ── Full-screen image ─────────────────────────────────────────────────────────

@Composable
private fun FullScreenImage(
    image:     DriveImage,
    imageUrl:  String,
    onDismiss: () -> Unit,
) {
    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(GalleryPalette.Black),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model              = imageUrl,
            contentDescription = image.name,
            contentScale       = ContentScale.Fit,
            modifier           = Modifier.fillMaxSize(),
        )

        // Action bar at bottom
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(GalleryPalette.Black.copy(alpha = 0.7f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onDismiss) {
                Text("Close", color = GalleryPalette.Green)
            }
            Text(
                text     = image.name,
                color    = GalleryPalette.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 1,
            )
        }
    }
}
