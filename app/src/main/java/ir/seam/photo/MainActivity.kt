package ir.seam.photo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> granted = result.values.all { it } }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(requiredPermissions()) }
    var dark by remember { mutableStateOf(true) }
    var screen by remember { mutableStateOf("photos") }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var viewerItem by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(granted) { if (granted) media = withContext(Dispatchers.IO) { loadMedia(context) } }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (!granted) PermissionView { launcher.launch(requiredPermissions()) }
            else {
                val albums = remember(media) { buildAlbums(media) }
                Box(Modifier.fillMaxSize()) {
                    if (screen == "albums") {
                        AlbumsScreen(albums, dark, { dark = !dark }, { screen = "photos" }) { album ->
                            selectedAlbum = if (album == "All Photos") null else album
                            screen = "photos"
                        }
                    } else {
                        val visible = selectedAlbum?.let { name -> media.filter { it.bucket == name } } ?: media
                        GalleryScreen(visible, selectedAlbum ?: "All Photos", dark, { dark = !dark }, { screen = "albums" }, { selectedAlbum = null }, { viewerItem = it })
                    }
                    viewerItem?.let { item -> MediaViewer(item) { viewerItem = null } }
                }
            }
        }
    }
}

@Composable
private fun PermissionView(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant))), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .92f)), modifier = Modifier.padding(24.dp)) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(92.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Default.PhotoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(20.dp)); Text("SEAM Photo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("گالری سریع و مینیمال شما", modifier = Modifier.padding(top = 6.dp, bottom = 22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onRequest, shape = RoundedCornerShape(16.dp)) { Text("اجازه دسترسی") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScreen(items: List<MediaItem>, title: String, dark: Boolean, toggleTheme: () -> Unit, onAlbums: () -> Unit, onAll: () -> Unit, onOpen: (MediaItem) -> Unit) {
    var columns by remember { mutableIntStateOf(3) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Column { Text(title, fontWeight = FontWeight.Bold); Text("${items.size} items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = {
            GlassIconButton(onClick = onAll) { Icon(Icons.Default.GridView, "All Photos") }
            GlassIconButton(onClick = onAlbums) { Icon(Icons.Default.Album, "Albums") }
            GlassIconButton(onClick = toggleTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)))
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> if (zoom > 1.03f) columns = (columns - 1).coerceIn(2, 6) else if (zoom < 0.97f) columns = (columns + 1).coerceIn(2, 6) } }) {
            if (items.isEmpty()) EmptyGallery() else LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { items(items, key = { it.uri.toString() }) { MediaTile(it) { onOpen(it) } } }
            GridHint(columns, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
        }
    }
}

@Composable
private fun GlassIconButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.padding(horizontal = 2.dp).size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)).clickable(onClick = onClick), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
private fun GridHint(columns: Int, modifier: Modifier = Modifier) {
    Row(modifier.shadow(10.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Tune, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(7.dp)); Text("$columns ستون  •  pinch برای تغییر", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EmptyGallery() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(Icons.Default.PhotoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f)); Spacer(Modifier.height(14.dp)); Text("گالری خالیه", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("عکس یا ویدئویی برای نمایش پیدا نشد", color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun MediaTile(item: MediaItem, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        AsyncImage(model = ImageRequest.Builder(context).data(item.uri).apply { if (item.isVideo) decoderFactory(VideoFrameDecoder.Factory()) }.build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.isVideo) Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = .58f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Movie, "Video", Modifier.size(16.dp), tint = Color.White) }
    }
}

@Composable
private fun MediaViewer(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var controlsVisible by remember(item.uri) { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (item.isVideo) {
            AndroidView(factory = { ctx -> VideoView(ctx).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); setVideoURI(item.uri); setOnPreparedListener { mp -> mp.isLooping = true; start() } } }, modifier = Modifier.fillMaxSize(), update = {})
        } else {
            AsyncImage(model = ImageRequest.Builder(context).data(item.uri).build(), contentDescription = null, modifier = Modifier.fillMaxSize().clickable { controlsVisible = !controlsVisible }, contentScale = ContentScale.Fit)
        }
        if (controlsVisible) {
            Row(Modifier.fillMaxWidth().padding(18.dp).align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = .52f))) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f)); Text(if (item.isVideo) "VIDEO" else "PHOTO", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .52f)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsScreen(albums: List<Album>, dark: Boolean, toggleTheme: () -> Unit, onBack: () -> Unit, openAlbum: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Albums", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { IconButton(onClick = toggleTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme") } })
        LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(albums, key = { it.name }) { album ->
                Card(onClick = { openAlbum(album.name) }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
                    Column {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.08f).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))) {
                            album.cover?.let { AsyncImage(ImageRequest.Builder(LocalContext.current).data(it).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .5f)))))
                            Text(album.name, Modifier.align(Alignment.BottomStart).padding(12.dp), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text("${album.count} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.weight(1f)); Icon(Icons.Default.Album, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

private fun hasMediaPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun loadMedia(context: Context): List<MediaItem> {
    val result = ArrayList<MediaItem>(512); val resolver = context.contentResolver; val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; val bucketColumn = MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME; val projection = arrayOf(MediaStore.MediaColumns._ID, bucketColumn, MediaStore.MediaColumns.DATE_ADDED)
    resolver.query(imageUri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c -> val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID); val bucket = c.getColumnIndex(bucketColumn); val date = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED); while (c.moveToNext()) result += MediaItem(ContentUris.withAppendedId(imageUri, c.getLong(id)), false, c.getString(bucket) ?: "Pictures", c.getLong(date)) }
    resolver.query(videoUri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c -> val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID); val bucket = c.getColumnIndex(bucketColumn); val date = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED); while (c.moveToNext()) result += MediaItem(ContentUris.withAppendedId(videoUri, c.getLong(id)), true, c.getString(bucket) ?: "Videos", c.getLong(date)) }
    return result.sortedByDescending { it.date }
}

private fun buildAlbums(media: List<MediaItem>): List<Album> = listOf(Album("All Photos", media.size, media.firstOrNull()?.uri)) + media.groupBy { it.bucket }.map { (name, list) -> Album(name, list.size, list.firstOrNull()?.uri) }.sortedBy { it.name }
