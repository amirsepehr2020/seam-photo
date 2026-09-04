package ir.seam.photo

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import coil3.decode.VideoFrameDecoder
import android.content.Context
import android.net.Uri

private data class MediaItem(val uri: Uri, val isVideo: Boolean, val bucket: String, val date: Long)
private data class Album(val name: String, val count: Int, val cover: Uri?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SeamPhotoApp() }
    }
}

@Composable
private fun SeamPhotoApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it }
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(requiredPermissions())
    }

    var dark by remember { mutableStateOf(true) }
    var screen by remember { mutableStateOf("photos") }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }

    MaterialTheme(colorScheme = if (dark) darkScheme() else lightScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (!granted) {
                PermissionView { launcher.launch(requiredPermissions()) }
            } else {
                val media = remember { loadMedia(context) }
                val albums = remember(media) { buildAlbums(media) }
                if (screen == "albums") {
                    AlbumsScreen(albums, dark, { dark = !dark }, onBack = { screen = "photos" }) { album ->
                        selectedAlbum = album
                        screen = "photos"
                    }
                } else {
                    val visible = selectedAlbum?.let { name -> media.filter { it.bucket == name } } ?: media
                    GalleryScreen(visible, selectedAlbum ?: "All Photos", dark, { dark = !dark }, onAlbums = { screen = "albums" }, onAll = { selectedAlbum = null })
                }
            }
        }
    }
}

@Composable
private fun PermissionView(onRequest: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(72.dp))
        Spacer(Modifier.height(18.dp))
        Text("SEAM Photo", style = MaterialTheme.typography.headlineMedium)
        Text("برای نمایش عکس‌ها و ویدئوها دسترسی بده", modifier = Modifier.padding(16.dp))
        androidx.compose.material3.Button(onClick = onRequest) { Text("اجازه دسترسی") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GalleryScreen(items: List<MediaItem>, title: String, dark: Boolean, toggleTheme: () -> Unit, onAlbums: () -> Unit, onAll: () -> Unit) {
    var columns by remember { mutableIntStateOf(3) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title) },
            actions = {
                IconButton(onClick = onAll) { Icon(Icons.Default.GridView, "All Photos") }
                IconButton(onClick = onAlbums) { Icon(Icons.Default.Album, "Albums") }
                IconButton(onClick = toggleTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTransformGestures { _, _, zoom, _ ->
                if (zoom > 1.03f) columns = (columns - 1).coerceIn(2, 6)
                if (zoom < 0.97f) columns = (columns + 1).coerceIn(2, 6)
            }
        }) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("عکسی پیدا نشد") }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items, key = { it.uri.toString() }) { item -> MediaTile(item) }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(item: MediaItem) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp))) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(item.uri)
                .crossfade(false)
                .decoderFactory(if (item.isVideo) VideoFrameDecoder.Factory() else null)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (item.isVideo) {
            Icon(Icons.Default.Movie, "Video", Modifier.align(Alignment.BottomEnd).padding(7.dp), tint = Color.White)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsScreen(albums: List<Album>, dark: Boolean, toggleTheme: () -> Unit, onBack: () -> Unit, openAlbum: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Albums") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            actions = { IconButton(onClick = toggleTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme") } }
        )
        LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(albums, key = { it.name }) { album ->
                Card(onClick = { openAlbum(album.name) }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.15f)) {
                            album.cover?.let { uri -> AsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Column(Modifier.padding(12.dp)) {
                            Text(album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text("${album.count} items", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun hasMediaPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
} else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun loadMedia(context: Context): List<MediaItem> {
    val result = ArrayList<MediaItem>(512)
    val resolver = context.contentResolver
    val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED)
    resolver.query(imageUri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID); val bucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME); val date = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        while (c.moveToNext()) result += MediaItem(ContentUris.withAppendedId(imageUri, c.getLong(id)), false, c.getString(bucket) ?: "Pictures", c.getLong(date))
    }
    resolver.query(videoUri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID); val bucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME); val date = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
        while (c.moveToNext()) result += MediaItem(ContentUris.withAppendedId(videoUri, c.getLong(id)), true, c.getString(bucket) ?: "Videos", c.getLong(date))
    }
    return result.sortedByDescending { it.date }
}

private fun buildAlbums(media: List<MediaItem>): List<Album> = listOf(Album("All Photos", media.size, media.firstOrNull()?.uri)) + media.groupBy { it.bucket }.map { (name, list) -> Album(name, list.size, list.firstOrNull()?.uri) }.sortedBy { it.name }

@Composable private fun lightScheme() = androidx.compose.material3.lightColorScheme()
@Composable private fun darkScheme() = androidx.compose.material3.darkColorScheme()
