package ir.seam.photo

import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class MediaItem(val uri: Uri, val isVideo: Boolean, val bucket: String, val date: Long, val name: String, val size: Long, val favorite: Boolean, val width: Int, val height: Int)
private data class Album(val name: String, val count: Int, val cover: Uri?, val type: String)
private enum class SortMode { NEWEST, OLDEST, NAME }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SeamPhotoApp() } }
}

@Composable
private fun SeamPhotoApp() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasMediaPermission(context) }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(requiredPermissions()) }
    var dark by remember { mutableStateOf(context.getPreferences(0).getBoolean("dark", true)) }
    var media by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var screen by remember { mutableStateOf("photos") }
    var selectedAlbum by remember { mutableStateOf<String?>(null) }
    var viewerIndex by remember { mutableIntStateOf(-1) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortMode.NEWEST) }
    var gridColumns by remember { mutableIntStateOf(context.getPreferences(0).getInt("grid", 3)) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var settings by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(granted, reloadKey) { if (granted) media = withContext(Dispatchers.IO) { loadMedia(context) } }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reloadKey++ ; selectedUris = emptySet() }
    fun requestDelete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else { uris.forEach { context.contentResolver.delete(it, null, null) }; reloadKey++ ; selectedUris = emptySet() }
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            if (!granted) PermissionView { permissionLauncher.launch(requiredPermissions()) }
            else {
                val albums = remember(media) { buildAlbums(media) }
                val base = when (selectedAlbum) {
                    "Favorites" -> media.filter { it.favorite }
                    "Videos" -> media.filter { it.isVideo }
                    "Screenshots" -> media.filter { it.bucket.contains("screenshot", true) }
                    "Camera" -> media.filter { it.bucket.contains("camera", true) }
                    else -> selectedAlbum?.let { n -> media.filter { it.bucket == n } } ?: media
                }
                val visible = remember(base, search, sort) {
                    val q = search.trim()
                    base.filter { q.isBlank() || it.name.contains(q, true) || it.bucket.contains(q, true) }
                        .let { list -> when (sort) { SortMode.NEWEST -> list.sortedByDescending { it.date }; SortMode.OLDEST -> list.sortedBy { it.date }; SortMode.NAME -> list.sortedBy { it.name.lowercase(Locale.getDefault()) } } }
                }
                Box(Modifier.fillMaxSize()) {
                    if (screen == "albums") {
                        AlbumsScreen(albums, dark, { dark = !dark; context.getPreferences(0).edit().putBoolean("dark", dark).apply() }, { screen = "photos" }) { a -> selectedAlbum = if (a == "All Photos") null else a; screen = "photos" }
                    } else {
                        GalleryScreen(
                            items = visible, title = selectedAlbum ?: "All Photos", dark = dark, search = search,
                            columns = gridColumns, selected = selectedUris,
                            onSearch = { search = it }, onTheme = { dark = !dark; context.getPreferences(0).edit().putBoolean("dark", dark).apply() },
                            onAlbums = { screen = "albums" }, onSettings = { settings = true }, onOpen = { viewerIndex = media.indexOfFirst { m -> m.uri == it.uri } },
                            onSelect = { uri -> selectedUris = if (uri.toString() in selectedUris) selectedUris - uri.toString() else selectedUris + uri.toString() },
                            onClearSelection = { selectedUris = emptySet() }, onDeleteSelected = { requestDelete(selectedUris.map(Uri::parse)) },
                            onShareSelected = { shareUris(context, selectedUris.map(Uri::parse)) }
                        )
                    }
                    if (viewerIndex >= 0 && media.isNotEmpty()) MediaViewer(
                        items = media, initialIndex = viewerIndex, onDismiss = { viewerIndex = -1 },
                        onDelete = { requestDelete(listOf(it.uri)); viewerIndex = -1 },
                        onFavorite = { item, fav -> updateFavorite(context, item, fav); reloadKey++ }
                    )
                }
                if (settings) SettingsSheet(gridColumns, sort, { gridColumns = it; context.getPreferences(0).edit().putInt("grid", it).apply() }, { sort = it }, { settings = false })
            }
        }
    }
}

@Composable private fun PermissionView(onRequest: () -> Unit) = Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant))), contentAlignment = Alignment.Center) {
    Card(shape = RoundedCornerShape(32.dp), modifier = Modifier.padding(24.dp)) { Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.PhotoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(16.dp)); Text("SEAM Photo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("گالری سریع، مدرن و خصوصی شما", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp)); Button(onClick = onRequest, shape = RoundedCornerShape(16.dp)) { Text("اجازه دسترسی به عکس‌ها") } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun GalleryScreen(items: List<MediaItem>, title: String, dark: Boolean, search: String, columns: Int, selected: Set<String>, onSearch: (String) -> Unit, onTheme: () -> Unit, onAlbums: () -> Unit, onSettings: () -> Unit, onOpen: (MediaItem) -> Unit, onSelect: (Uri) -> Unit, onClearSelection: () -> Unit, onDeleteSelected: () -> Unit, onShareSelected: () -> Unit) {
    var searching by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (selected.isNotEmpty()) {
            TopAppBar(title = { Text("${selected.size} انتخاب شده", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onClearSelection) { Icon(Icons.Default.Close, "بستن") } }, actions = { IconButton(onClick = onShareSelected) { Icon(Icons.Default.Share, "اشتراک") }; IconButton(onClick = onDeleteSelected) { Icon(Icons.Default.Delete, "حذف") } })
        } else if (searching) {
            TopAppBar(title = { OutlinedTextField(value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth().padding(end = 8.dp), singleLine = true, placeholder = { Text("جستجوی عکس و ویدئو…") }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { IconButton(onClick = { searching = false; onSearch("") }) { Icon(Icons.Default.Close, null) } }, shape = RoundedCornerShape(18.dp)) })
        } else {
            TopAppBar(title = { Column { Text(title, fontWeight = FontWeight.Bold); Text("${items.size} مورد", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = {
                IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "جستجو") }
                IconButton(onClick = onAlbums) { Icon(Icons.Default.Album, "آلبوم‌ها") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Tune, "تنظیمات") }
                IconButton(onClick = onTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "تم") }
            })
        }
        Box(Modifier.fillMaxSize().pointerInput(columns) { detectTransformGestures { _, _, zoom, _ -> /* grid size is persisted from settings; pinch remains handled by settings */ } }) {
            if (items.isEmpty()) EmptyGallery(search.isNotBlank()) else LazyVerticalGrid(columns = GridCells.Fixed(columns), contentPadding = PaddingValues(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(items, key = { it.uri.toString() }) { item -> MediaTile(item, item.uri.toString() in selected, onOpen, onSelect) }
            }
            if (selected.isEmpty()) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).shadow(10.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = .92f)).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .22f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.GridView, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(7.dp)); Text("$columns ستون", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun MediaTile(item: MediaItem, selected: Boolean, onClick: (MediaItem) -> Unit, onSelect: (Uri) -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant).combinedClickable(onClick = { onClick(item) }, onLongClick = { onSelect(item.uri) }).border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp))) {
        AsyncImage(model = ImageRequest.Builder(context).data(item.uri).apply { if (item.isVideo) decoderFactory(VideoFrameDecoder.Factory()) }.build(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.isVideo) Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(29.dp).clip(CircleShape).background(Color.Black.copy(alpha = .62f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlayArrow, "ویدئو", Modifier.size(18.dp), tint = Color.White) }
        if (item.favorite) Icon(Icons.Default.Favorite, "موردعلاقه", Modifier.align(Alignment.TopEnd).padding(7.dp).size(18.dp), tint = Color.White)
        if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .22f)), contentAlignment = Alignment.TopStart) { Icon(Icons.Default.CheckCircle, null, Modifier.padding(7.dp), tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable private fun EmptyGallery(searching: Boolean) = Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(if (searching) Icons.Default.SearchOff else Icons.Default.PhotoLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f)); Spacer(Modifier.height(14.dp)); Text(if (searching) "چیزی پیدا نشد" else "گالری خالیه", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(if (searching) "نام فایل یا پوشه را تغییر بده و دوباره جستجو کن" else "عکس یا ویدئویی برای نمایش پیدا نشد", color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable private fun AlbumsScreen(albums: List<Album>, dark: Boolean, toggleTheme: () -> Unit, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Albums", fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "بازگشت") } }, actions = { IconButton(onClick = toggleTheme) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "تم") } })
        LazyVerticalGrid(columns = GridCells.Fixed(2), contentPadding = PaddingValues(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(albums, key = { it.name }) { album -> Card(Modifier.fillMaxWidth().aspectRatio(1.12f).combinedClickable(onClick = { onOpen(album.name) }, onLongClick = {}), shape = RoundedCornerShape(22.dp)) { Box(Modifier.fillMaxSize()) { album.cover?.let { AsyncImage(model = it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }; Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f))))); Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) { Text(album.name, color = Color.White, fontWeight = FontWeight.Bold); Text("${album.count} مورد", color = Color.White.copy(alpha = .82f), style = MaterialTheme.typography.labelMedium) } } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsSheet(columns: Int, sort: SortMode, onColumns: (Int) -> Unit, onSort: (SortMode) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 32.dp)) {
            Text("تنظیمات نمایش", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(22.dp))
            Text("تعداد ستون‌ها: $columns", fontWeight = FontWeight.SemiBold); Slider(value = columns.toFloat(), onValueChange = { onColumns(it.toInt().coerceIn(2, 6)) }, valueRange = 2f..6f, steps = 3)
            Spacer(Modifier.height(12.dp)); Text("مرتب‌سازی", fontWeight = FontWeight.SemiBold); Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) { SortMode.values().forEach { mode -> FilterChip(selected = sort == mode, onClick = { onSort(mode) }, label = { Text(when (mode) { SortMode.NEWEST -> "جدیدترین"; SortMode.OLDEST -> "قدیمی‌ترین"; SortMode.NAME -> "نام" }) }) } }
            Spacer(Modifier.height(18.dp)); Text("SEAM Photo • تجربه‌ای سریع و تمیز برای عکس و ویدئو", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun MediaViewer(items: List<MediaItem>, initialIndex: Int, onDismiss: () -> Unit, onDelete: (MediaItem) -> Unit, onFavorite: (MediaItem, Boolean) -> Unit) {
    val context = LocalContext.current
    var index by remember(initialIndex) { mutableIntStateOf(initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) }
    var controls by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var favorite by remember(items, index) { mutableStateOf(items.getOrNull(index)?.favorite == true) }
    var showInfo by remember { mutableStateOf(false) }
    if (items.isEmpty()) return
    val item = items.getOrNull(index) ?: return
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(index, scale) { detectHorizontalDragGestures(onDragEnd = { if (scale <= 1.02f && kotlin.math.abs(offsetX) > 90f) { if (offsetX < 0 && index < items.lastIndex) index++ else if (offsetX > 0 && index > 0) index--; scale = 1f; offsetX = 0f; offsetY = 0f }; offsetX = 0f }, onHorizontalDrag = { _, drag -> if (scale <= 1.02f) offsetX += drag }) }) {
        if (item.isVideo) Media3VideoPlayer(item.uri, controls, { controls = !controls }) else AsyncImage(model = ImageRequest.Builder(context).data(item.uri).build(), contentDescription = null, modifier = Modifier.fillMaxSize().pointerInput(item.uri) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); if (scale > 1f) { offsetX += pan.x; offsetY += pan.y } else { offsetX = 0f; offsetY = 0f } } }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY), contentScale = ContentScale.Fit)
        if (controls) {
            Row(Modifier.fillMaxWidth().padding(14.dp).align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onDismiss, modifier = Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f))) { Icon(Icons.Default.Close, "بستن", tint = Color.White) }; Spacer(Modifier.weight(1f)); Text("${index + 1} / ${items.size}", color = Color.White, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 13.dp, vertical = 8.dp)) }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = if (item.isVideo) 118.dp else 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ViewerButton(Icons.Default.Share, "اشتراک") { shareMedia(context, item.uri) }
                ViewerButton(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "موردعلاقه") { favorite = !favorite; onFavorite(item, favorite) }
                ViewerButton(Icons.Default.Info, "اطلاعات") { showInfo = true }
                ViewerButton(Icons.Default.Delete, "حذف") { onDelete(item) }
            }
            if (index > 0) FloatingNav(Icons.Default.ChevronLeft, Modifier.align(Alignment.CenterStart)) { index--; scale = 1f; offsetX = 0f; offsetY = 0f }
            if (index < items.lastIndex) FloatingNav(Icons.Default.ChevronRight, Modifier.align(Alignment.CenterEnd)) { index++; scale = 1f; offsetX = 0f; offsetY = 0f }
        }
    }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("اطلاعات فایل") }, text = { Column { Text(item.name.ifBlank { "بدون نام" }, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(if (item.isVideo) "ویدئو" else "عکس"); Text("اندازه: ${item.width} × ${item.height}"); Text("حجم: ${formatBytes(item.size)}"); Text("پوشه: ${item.bucket}"); Text("تاریخ: ${formatDate(item.date)}") } }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("بستن") } })
}

@Composable private fun ViewerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) { Column(horizontalAlignment = Alignment.CenterHorizontally) { IconButton(onClick = onClick, modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f))) { Icon(icon, label, tint = Color.White) }; Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall) } }
@Composable private fun FloatingNav(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) = IconButton(onClick = onClick, modifier = modifier.size(54.dp).clip(CircleShape).background(Color.Black.copy(alpha = .48f))) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(34.dp)) }

@Composable private fun Media3VideoPlayer(uri: Uri, controlsVisible: Boolean, onControlsToggle: () -> Unit) {
    val context = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply { setMediaItem(PlayerMediaItem.fromUri(uri)); prepare(); playWhenReady = true } }
    var playing by remember(uri) { mutableStateOf(true) }; var position by remember(uri) { mutableLongStateOf(0L) }; var duration by remember(uri) { mutableLongStateOf(0L) }
    DisposableEffect(player) { val listener = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }; override fun onPlaybackStateChanged(state: Int) { duration = player.duration.coerceAtLeast(0L) } }; player.addListener(listener); onDispose { player.removeListener(listener); player.release() } }
    LaunchedEffect(player, playing) { while (playing) { position = player.currentPosition; duration = player.duration.coerceAtLeast(0L); delay(250) } }
    Box(Modifier.fillMaxSize().clickable(onClick = onControlsToggle)) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false; layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } }, modifier = Modifier.fillMaxSize())
        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .42f), Color.Transparent, Color.Black.copy(alpha = .74f)))) )
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f))) { Icon(Icons.Default.Replay10, null, tint = Color.White) }; IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.size(78.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(38.dp), tint = Color.White) }; IconButton(onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(player.duration.coerceAtLeast(0L))) }, modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f))) { Icon(Icons.Default.Forward10, null, tint = Color.White) } } }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp).navigationBarsPadding()) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelSmall); Slider(value = if (duration > 0) position.coerceIn(0L, duration).toFloat() / duration else 0f, onValueChange = { player.seekTo((it * duration).toLong()) }, modifier = Modifier.weight(1f)); Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelSmall) } }
        }
    }
}

private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
private fun hasMediaPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 34) ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED else if (Build.VERSION.SDK_INT >= 33) ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun loadMedia(context: Context): List<MediaItem> {
    val out = mutableListOf<MediaItem>(); val resolver = context.contentResolver
    val sources = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false, MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true)
    for ((collection, video) in sources) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.IS_FAVORITE)
        try { resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID); val bucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME); val date = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED); val name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME); val size = c.getColumnIndex(MediaStore.MediaColumns.SIZE); val w = c.getColumnIndex(MediaStore.MediaColumns.WIDTH); val h = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT); val fav = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
            while (c.moveToNext()) out += MediaItem(ContentUris.withAppendedId(collection, c.getLong(id)), video, if (bucket >= 0) c.getString(bucket).orEmpty() else "Unknown", if (date >= 0) c.getLong(date) * 1000L else 0L, if (name >= 0) c.getString(name).orEmpty() else "", if (size >= 0) c.getLong(size) else 0L, fav >= 0 && c.getInt(fav) == 1, if (w >= 0) c.getInt(w) else 0, if (h >= 0) c.getInt(h) else 0)
        } } catch (_: Exception) { }
    }
    return out.sortedByDescending { it.date }
}

private fun buildAlbums(media: List<MediaItem>): List<Album> {
    val result = mutableListOf<Album>(); result += Album("All Photos", media.size, media.firstOrNull()?.uri, "all"); result += Album("Favorites", media.count { it.favorite }, media.firstOrNull { it.favorite }?.uri, "favorite"); result += Album("Videos", media.count { it.isVideo }, media.firstOrNull { it.isVideo }?.uri, "video"); result += Album("Screenshots", media.count { it.bucket.contains("screenshot", true) }, media.firstOrNull { it.bucket.contains("screenshot", true) }?.uri, "folder"); result += Album("Camera", media.count { it.bucket.contains("camera", true) }, media.firstOrNull { it.bucket.contains("camera", true) }?.uri, "folder"); result += media.groupBy { it.bucket }.filter { it.key.isNotBlank() && it.key !in setOf("Camera", "Screenshots") }.map { (name, list) -> Album(name, list.size, list.firstOrNull()?.uri, "folder") }; return result.distinctBy { it.name }
}

private fun updateFavorite(context: Context, item: MediaItem, favorite: Boolean) { if (Build.VERSION.SDK_INT >= 29) { try { val v = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0) }; context.contentResolver.update(item.uri, v, null, null) } catch (_: Exception) {} } }
private fun shareMedia(context: Context, uri: Uri) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = context.contentResolver.getType(uri) ?: "*/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "اشتراک‌گذاری")) }
private fun shareUris(context: Context, uris: List<Uri>) { if (uris.isEmpty()) return; val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری ${uris.size} مورد")) }
private fun formatBytes(bytes: Long): String { if (bytes < 1024) return "$bytes B"; val kb = bytes / 1024.0; if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb); val mb = kb / 1024.0; if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb); return String.format(Locale.US, "%.1f GB", mb / 1024.0) }
private fun formatDate(ms: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(ms))
private fun formatTime(ms: Long): String { val total = ms.coerceAtLeast(0L) / 1000; return String.format(Locale.US, "%d:%02d", total / 60, total % 60) }
