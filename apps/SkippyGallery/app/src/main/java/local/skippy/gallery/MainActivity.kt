package local.skippy.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import local.skippy.gallery.compositor.GalleryPalette
import local.skippy.gallery.ui.GalleryScreen
import local.skippy.gallery.ui.GalleryViewModel

/**
 * SkippyGallery — single-activity host.
 *
 * Fetches images from SkippyTel /dropship/list (Google Drive Images/ folder).
 * No local storage permissions needed — all images are served via SkippyTel proxy.
 * Requires INTERNET permission only (declared in AndroidManifest).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color    = GalleryPalette.Black,
            ) {
                GalleryScreen(viewModel = viewModel)
            }
        }
        // GalleryScreen calls viewModel.load() via LaunchedEffect(Unit).
        // No permissions needed — images come from SkippyTel HTTP proxy.
    }
}
