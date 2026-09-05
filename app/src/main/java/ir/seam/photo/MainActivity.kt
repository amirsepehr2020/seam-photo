package ir.seam.photo

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class MediaItem(val uri: Uri, val isVideo: Boolean, val bucket: String, val date: Long, val name: String, val size: Long)
private data class Album(val name: String, val count: Int, val cover: Uri?)

class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SeamPhotoApp() } } }

@Composable private fun SeamPhotoApp() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.all { ok -> ok } }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(requiredPermissions()) }
    var dark by remember { mutableStateOf(true) }
    var screen by remember { mutableStateOf("photos") }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var viewerIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(granted) { if (granted) media = withContext(Dispatchers.IO) { loadMedia(context) } }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (!granted) PermissionView { launcher.launch(requiredPermissions()) } else {
                val albums = remember(media) { buildAlbums(media) }
                Box(Modifier.fillMaxSize()) {
                    if (screen == "albums") AlbumsScreen(albums, dark, { dark = !dark }, { screen = "photos" }) { a -> selectedAlbum = if (a == "All Photos") null else a; screen = "photos" }
                    else {
                        val visible = selectedAlbum?.let { n -> media.filter { it.bucket == n } } ?: media
                        GalleryScreen(visible, selectedAlbum ?: "All Photos", dark, { dark = !dark }, { screen = "albums" }, { selectedAlbum = null }) { item -> viewerIndex = media.indexOfFirst { it.uri == item.uri } }
                    }
                    if (viewerIndex >= 0) MediaViewer(media, viewerIndex, onDismiss = { viewerIndex = -1 }, onDelete = { deleted -> media = media.filterNot { it.uri == deleted.uri }; viewerIndex = -1 }, onFavorite = { item, fav -> updateFavorite(context, item, fav) })
                }
            }
        }
    }
}

@Composable private fun PermissionView(onRequest: () -> Unit) = Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant))), contentAlignment = Alignment.Center) {
    Card(shape = RoundedCornerShape(32.dp), modifier = Modifier.padding(24.dp)) { Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.PhotoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(16.dp)); Text("SEAM Photo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("گالری سریع و مینیمال شما", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)); Button(onClick = onRequest, shape = RoundedCornerShape(16.dp)) { Text("اجازه دسترسی") } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun GalleryScreen(items: List<MediaItem>, title: String, dark: Boolean, toggleTheme: () -> Unit, onAlbums: () -> Unit, onAll: () -> Unit, onOpen: (MediaItem) -> Unit) {
    var columns by remember { mutableIntStateOf(3) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Column { Text(title, fontWeight = FontWeight.Bold); Text("${items.size} items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = { GlassIconButton(onAll) { Icon(Icons.Default.GridView, "All Photos") }; GlassIconButton(onAlbums) { Icon(Icons.Default.Album, "Albums") }; GlassIconButton(toggleTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .94f)))
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> if (zoom > 1.03f) columns = (columns - 1).coerceIn(2, 6) else if (zoom < .97f) columns = (columns + 1).coerceIn(2, 6) } }) {
            if (items.isEmpty()) EmptyGallery() else LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { items(items, key = { it.uri.toString() }) { MediaTile(it) { onOpen(it) } } }
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).shadow(10.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f)).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .25f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Tune, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(7.dp)); Text("$columns ستون • pinch برای تغییر", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable private fun GlassIconButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) = Row(Modifier.padding(horizontal = 2.dp).size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)).clickable(onClick = onClick), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content)
@Composable private fun EmptyGallery() = Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(Icons.Default.PhotoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f)); Spacer(Modifier.height(14.dp)); Text("گالری خالیه", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("عکس یا ویدئویی برای نمایش پیدا نشد", color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable private fun MediaTile(item: MediaItem, onClick: () -> Unit) { val context = LocalContext.current; Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) { AsyncImage(model = ImageRequest.Builder(context).data(item.uri).apply { if (item.isVideo) decoderFactory(VideoFrameDecoder.Factory()) }.build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop); if (item.isVideo) Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = .58f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Movie, "Video", Modifier.size(16.dp), tint = Color.White) } } }

@Composable private fun MediaViewer(items: List<MediaItem>, initialIndex: Int, onDismiss: () -> Unit, onDelete: (MediaItem) -> Unit, onFavorite: (MediaItem, Boolean) -> Unit) {
    val context = LocalContext.current
    var index by remember(initialIndex) { mutableIntStateOf(initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    var controls by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var favorite by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    if (items.isEmpty()) return
    val item = items.getOrNull(index) ?: return

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(index) {
        detectHorizontalDragGestures(onDragEnd = { if (kotlin.math.abs(offsetX) > 80f) { if (offsetX < 0 && index < items.lastIndex) index++ else if (offsetX > 0 && index > 0) index--; offsetX = 0f; scale = 1f; offsetY = 0f } else offsetX = 0f }, onHorizontalDrag = { _, drag -> if (scale <= 1.02f) offsetX += drag })
    }) {
        if (item.isVideo) {
            Media3VideoPlayer(item.uri, controls, onControlsToggle = { controls = !controls })
        } else {
            AsyncImage(model = ImageRequest.Builder(context).data(item.uri).build(), contentDescription = null, modifier = Modifier.fillMaxSize().pointerInput(item.uri) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); if (scale > 1f) { offsetX += pan.x; offsetY += pan.y } else { offsetX = 0f; offsetY = 0f } } }.clickable { controls = !controls }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY), contentScale = ContentScale.Fit)
        }
        if (controls) {
            Row(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f))) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Spacer(Modifier.weight(1f)); Text("${index + 1} / ${items.size}", color = Color.White, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 13.dp, vertical = 8.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = if (item.isVideo) 112.dp else 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ViewerButton(Icons.Default.Share, "اشتراک", { shareMedia(context, item.uri) })
                ViewerButton(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "موردعلاقه", { favorite = !favorite; onFavorite(item, favorite) })
                ViewerButton(Icons.Default.Info, "اطلاعات", { showInfo = true })
                ViewerButton(Icons.Default.Delete, "حذف", { onDelete(item) })
            }
            if (index > 0) FloatingNav(Icons.Default.ChevronLeft, Modifier.align(Alignment.CenterStart)) { index--; scale = 1f; offsetX = 0f; offsetY = 0f }
            if (index < items.lastIndex) FloatingNav(Icons.Default.ChevronRight, Modifier.align(Alignment.CenterEnd)) { index++; scale = 1f; offsetX = 0f; offsetY = 0f }
        }
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("اطلاعات فایل") }, text = { Column { Text(item.name.ifBlank { "بدون نام" }); Spacer(Modifier.height(8.dp)); Text(if (item.isVideo) "ویدئو" else "عکس"); Text("حجم: ${formatBytes(item.size)}"); Text("آلبوم: ${item.bucket}") } }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("بستن") } })
}

@Composable private fun Media3VideoPlayer(uri: Uri, controlsVisible: Boolean, onControlsToggle: () -> Unit) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply { setMediaItem(PlayerMediaItem.fromUri(uri)); prepare(); playWhenReady = true }
    }
    var playing by remember(uri) { mutableStateOf(true) }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { duration = player.duration.coerceAtLeast(0L) }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(player, playing) {
        while (playing) { position = player.currentPosition; duration = player.duration.coerceAtLeast(0L); delay(250) }
    }
    Box(Modifier.fillMaxSize().clickable(onClick = onControlsToggle)) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false; layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, modifier = Modifier.fillMaxSize())
        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .5f), Color.Transparent, Color.Black.copy(alpha = .72f)))) )
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f))) { Icon(Icons.Default.Replay10, "10 seconds back", tint = Color.White) }
                    IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.size(78.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", Modifier.size(38.dp), tint = Color.White) }
                    IconButton(onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(player.duration.coerceAtLeast(0L))) }, modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f))) { Icon(Icons.Default.Forward10, "10 seconds forward", tint = Color.White) }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Slider(value = if (duration > 0) position.coerceIn(0L, duration).toFloat() / duration else 0f, onValueChange = { position = (it * duration).toLong() }, onValueChangeFinished = { player.seekTo(position) }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String { val totalSeconds = (ms / 1000).coerceAtLeast(0L); return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}" }
@Composable private fun ViewerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) = Column(horizontalAlignment = Alignment.CenterHorizontally) { IconButton(onClick = action, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f))) { Icon(icon, label, tint = Color.White) }; Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall) }
@Composable private fun FloatingNav(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, action: () -> Unit) = IconButton(onClick = action, modifier = modifier.padding(12.dp).size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f))) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
private fun shareMedia(context: Context, uri: Uri) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = context.contentResolver.getType(uri) ?: "*/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "اشتراک‌گذاری")) }
private fun updateFavorite(context: Context, item: MediaItem, favorite: Boolean) { if (Build.VERSION.SDK_INT >= 29) { val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0) }; runCatching { context.contentResolver.update(item.uri, values, null, null) } } }
private fun formatBytes(bytes: Long): String { if (bytes <= 0) return "نامشخص"; val units = arrayOf("B", "KB", "MB", "GB"); var n = bytes.toDouble(); var i = 0; while (n >= 1024 && i < units.lastIndex) { n /= 1024; i++ }; return "%.1f %s".format(n, units[i]) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AlbumsScreen(albums: List<Album>, dark: Boolean, toggleTheme: () -> Unit, onBack: () -> Unit, openAlbum: (String) -> Unit) { Column(Modifier.fillMaxSize()) { TopAppBar(title = { Text("Albums", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { IconButton(onClick = toggleTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Theme") } }); LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(albums, key = { it.name }) { album -> Card(onClick = { openAlbum(album.name) }, shape = RoundedCornerShape(24.dp)) { Column { Box(Modifier.fillMaxWidth().aspectRatio(1.08f).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))) { album.cover?.let { AsyncImage(ImageRequest.Builder(LocalContext.current).data(it).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }; Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .5f))))); Text(album.name, Modifier.align(Alignment.BottomStart).padding(12.dp), color = Color.White, fontWeight = FontWeight.Bold) }; Row(Modifier.fillMaxWidth().padding(12.dp)) { Text("${album.count} items", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.weight(1f)); Icon(Icons.Default.Album, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) } } } } } } }

private fun hasMediaPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
private fun loadMedia(context: Context): List<MediaItem> { val result = ArrayList<MediaItem>(512); val resolver = context.contentResolver; fun query(uri: Uri, video: Boolean) { val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME); resolver.query(uri, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c -> val id=c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID); val name=c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME); val size=c.getColumnIndex(MediaStore.MediaColumns.SIZE); val date=c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED); val bucket=c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME); while(c.moveToNext()) result += MediaItem(ContentUris.withAppendedId(uri,c.getLong(id)),video,c.getString(bucket) ?: if(video) "Videos" else "Pictures",c.getLong(date),c.getString(name) ?: "",c.getLong(size)) } }; query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false); query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true); return result.sortedByDescending { it.date } }
private fun buildAlbums(media: List<MediaItem>): List<Album> = listOf(Album("All Photos", media.size, media.firstOrNull()?.uri)) + media.groupBy { it.bucket }.map { (name,list) -> Album(name,list.size,list.firstOrNull()?.uri) }.sortedBy { it.name }
