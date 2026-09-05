package ir.seam.photo

import android.Manifest
import android.content.ContentValues
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.media3.common.MediaItem as PMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private data class M(val uri:Uri,val video:Boolean,val bucket:String,val date:Long,val name:String,val size:Long,val fav:Boolean,val w:Int,val h:Int)
private data class A(val name:String,val count:Int,val cover:Uri?)
private enum class Sort{NEW,OLD,NAME}

class MainActivity:ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{App()}}}

@Composable private fun App(){
 val c=LocalContext.current;val prefs=c.getSharedPreferences("seam",Context.MODE_PRIVATE)
 var ok by remember{mutableStateOf(hasPermission(c))}
 val perm=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){ok=hasPermission(c)}
 LaunchedEffect(Unit){if(!ok)perm.launch(perms())}
 var dark by remember{mutableStateOf(prefs.getBoolean("dark",true))};var list by remember{mutableStateOf(emptyList<M>())};var reload by remember{mutableIntStateOf(0)}
 var albums by remember{mutableStateOf(false)};var album by remember{mutableStateOf<String?>(null)};var viewer by remember{mutableIntStateOf(-1)}
 var search by remember{mutableStateOf("")};var sort by remember{mutableStateOf(Sort.NEW)};var cols by remember{mutableIntStateOf(prefs.getInt("cols",3))};var settings by remember{mutableStateOf(false)};var selected by remember{mutableStateOf(setOf<String>())}
 LaunchedEffect(ok,reload){if(ok)list=withContext(Dispatchers.IO){load(c)}}
 val delete=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){reload++;selected=emptySet()}
 fun remove(us:List<Uri>){if(us.isEmpty())return;if(Build.VERSION.SDK_INT>=30){val p=MediaStore.createDeleteRequest(c.contentResolver,us);delete.launch(IntentSenderRequest.Builder(p.intentSender).build())}else{us.forEach{c.contentResolver.delete(it,null,null)};reload++}}
 MaterialTheme(if(dark)darkColorScheme()else lightColorScheme()){Surface(Modifier.fillMaxSize()){if(!ok)Permission{perm.launch(perms())}else{
  val base=when(album){"Favorites"->list.filter{it.fav};"Videos"->list.filter{it.video};"Screenshots"->list.filter{it.bucket.contains("screenshot",true)};"Camera"->list.filter{it.bucket.contains("camera",true)};else->album?.let{n->list.filter{it.bucket==n}}?:list}
  val shown=base.filter{search.isBlank()||it.name.contains(search,true)||it.bucket.contains(search,true)}.let{when(sort){Sort.NEW->it.sortedByDescending{m->m.date};Sort.OLD->it.sortedBy{m->m.date};Sort.NAME->it.sortedBy{m->m.name.lowercase()}}}
  Box(Modifier.fillMaxSize()){
   if(albums)AlbumPage(makeAlbums(list),dark,{dark=!dark;prefs.edit().putBoolean("dark",dark).apply()},{albums=false}){n->album=if(n=="All Photos")null else n;albums=false}
   else Gallery(shown,album?:"All Photos",dark,search,cols,selected,{search=it},{dark=!dark;prefs.edit().putBoolean("dark",dark).apply()},{albums=true},{settings=true},{viewer=list.indexOfFirst{m->m.uri==it.uri}},{u->selected=if(u.toString()in selected)selected-u.toString()else selected+u.toString()},{selected=emptySet()},{remove(selected.map(Uri::parse))},{shareMany(c,selected.map(Uri::parse))})
   if(viewer>=0&&list.isNotEmpty())Viewer(list,viewer,{viewer=-1},{m->remove(listOf(m.uri));viewer=-1},{m,f->fav(c,m,f);reload++})
  }
  if(settings)Settings(cols,sort,{cols=it;prefs.edit().putInt("cols",it).apply()},{sort=it},{settings=false})
 }}}
}

private fun perms():Array<String> = if(Build.VERSION.SDK_INT>=34) arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else if(Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
private fun hasPermission(c:Context)=if(Build.VERSION.SDK_INT>=34)listOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED).any{ContextCompat.checkSelfPermission(c,it)==PackageManager.PERMISSION_GRANTED}else if(Build.VERSION.SDK_INT>=33)listOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO).any{ContextCompat.checkSelfPermission(c,it)==PackageManager.PERMISSION_GRANTED}else ContextCompat.checkSelfPermission(c,Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED

@Composable private fun Permission(on:()->Unit)=Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface,MaterialTheme.colorScheme.surfaceVariant))),contentAlignment=Alignment.Center){Card(shape=RoundedCornerShape(32.dp),modifier=Modifier.padding(24.dp)){Column(Modifier.padding(30.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.PhotoLibrary,null,Modifier.size(62.dp),tint=MaterialTheme.colorScheme.primary);Text("SEAM Photo",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("گالری سریع و مدرن",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(10.dp));Button(onClick=on){Text("اجازه دسترسی")}}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Gallery(xs:List<M>,title:String,dark:Boolean,search:String,cols:Int,sel:Set<String>,onSearch:(String)->Unit,onTheme:()->Unit,onAlbums:()->Unit,onSettings:()->Unit,onOpen:(M)->Unit,onSelect:(Uri)->Unit,onClear:()->Unit,onDelete:()->Unit,onShare:()->Unit){
 var searching by remember{mutableStateOf(false)}
 Column(Modifier.fillMaxSize()){
  if(sel.isNotEmpty())TopAppBar(title={Text("${sel.size} انتخاب شده",fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onClick=onClear){Icon(Icons.Default.Close,null)}},actions={IconButton(onClick=onShare){Icon(Icons.Default.Share,null)};IconButton(onClick=onDelete){Icon(Icons.Default.Delete,null)}})
  else if(searching)TopAppBar(title={OutlinedTextField(value=search,onValueChange=onSearch,modifier=Modifier.fillMaxWidth().padding(end=8.dp),singleLine=true,placeholder={Text("جستجو…")},leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={IconButton(onClick={searching=false;onSearch("")}){Icon(Icons.Default.Close,null)}},shape=RoundedCornerShape(18.dp))})
  else TopAppBar(title={Column{Text(title,fontWeight=FontWeight.Bold);Text("${xs.size} مورد",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},actions={IconButton({searching=true}){Icon(Icons.Default.Search,"جستجو")};IconButton(onAlbums){Icon(Icons.Default.Album,"آلبوم")};IconButton(onSettings){Icon(Icons.Default.Tune,"تنظیمات")};IconButton(onTheme){Icon(if(dark)Icons.Default.LightMode else Icons.Default.DarkMode,"تم")}})
  if(xs.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.PhotoLibrary,null,Modifier.size(60.dp),tint=MaterialTheme.colorScheme.primary);Text("چیزی برای نمایش نیست",fontWeight=FontWeight.Bold)}}
  else LazyVerticalGrid(GridCells.Fixed(cols),contentPadding=PaddingValues(5.dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){items(xs,key={it.uri.toString()}){Tile(it,it.uri.toString()in sel,onOpen,onSelect)}}
 }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun Tile(m:M,selected:Boolean,open:(M)->Unit,pick:(Uri)->Unit){val c=LocalContext.current;Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(9.dp)).combinedClickable(onClick={open(m)},onLongClick={pick(m.uri)}).border(if(selected)3.dp else 0.dp,MaterialTheme.colorScheme.primary,RoundedCornerShape(9.dp))){AsyncImage(model=ImageRequest.Builder(c).data(m.uri).apply{if(m.video)decoderFactory(VideoFrameDecoder.Factory())}.build(),contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop);if(m.video)Icon(Icons.Default.PlayArrow,null,Modifier.align(Alignment.BottomEnd).padding(7.dp).size(29.dp).clip(CircleShape).background(Color.Black.copy(.65f)).padding(5.dp),tint=Color.White);if(m.fav)Icon(Icons.Default.Favorite,null,Modifier.align(Alignment.TopEnd).padding(7.dp).size(18.dp),tint=Color.White);if(selected)Icon(Icons.Default.CheckCircle,null,Modifier.align(Alignment.TopStart).padding(7.dp),tint=MaterialTheme.colorScheme.primary)}}

@OptIn(ExperimentalMaterial3Api::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun AlbumPage(as_:List<A>,dark:Boolean,theme:()->Unit,back:()->Unit,open:(String)->Unit){Column(Modifier.fillMaxSize()){TopAppBar(title={Text("Albums",fontWeight=FontWeight.Bold)},navigationIcon={IconButton(onClick=back){Icon(Icons.Default.ArrowBack,null)}},actions={IconButton(theme){Icon(if(dark)Icons.Default.LightMode else Icons.Default.DarkMode,null)}});LazyVerticalGrid(GridCells.Fixed(2),contentPadding=PaddingValues(10.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){items(as_,key={it.name}){a->Card(Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(22.dp)).combinedClickable(onClick={open(a.name)},onLongClick={}),shape=RoundedCornerShape(22.dp)){Box(Modifier.fillMaxSize()){a.cover?.let{AsyncImage(it,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)};Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(.8f)))));Column(Modifier.align(Alignment.BottomStart).padding(14.dp)){Text(a.name,color=Color.White,fontWeight=FontWeight.Bold);Text("${a.count} مورد",color=Color.White.copy(.8f))}}}}}}}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Settings(cols:Int,sort:Sort,setCols:(Int)->Unit,setSort:(Sort)->Unit,close:()->Unit){ModalBottomSheet(onDismissRequest=close){Column(Modifier.padding(22.dp).padding(bottom=30.dp)){Text("تنظیمات نمایش",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Spacer(Modifier.height(18.dp));Text("تعداد ستون‌ها: $cols");Slider(value=cols.toFloat(),onValueChange={setCols(it.toInt().coerceIn(2,6))},valueRange=2f..6f,steps=3);Text("مرتب‌سازی",fontWeight=FontWeight.Bold);Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=8.dp)){Sort.entries.forEach{FilterChip(selected=sort==it,onClick={setSort(it)},label={Text(when(it){Sort.NEW->"جدیدترین";Sort.OLD->"قدیمی‌ترین";Sort.NAME->"نام"})})}}}}}

@Composable private fun Viewer(xs:List<M>,start:Int,close:()->Unit,del:(M)->Unit,favorite:(M,Boolean)->Unit){val c=LocalContext.current;var i by remember(start){mutableIntStateOf(start.coerceIn(0,xs.lastIndex))};var controls by remember{mutableStateOf(true)};var scale by remember{mutableFloatStateOf(1f)};var favv by remember(i){mutableStateOf(xs[i].fav)};val m=xs[i];Box(Modifier.fillMaxSize().background(Color.Black)){if(m.video)Video(m.uri,controls){controls=!controls}else AsyncImage(model=ImageRequest.Builder(c).data(m.uri).build(),contentDescription=null,modifier=Modifier.fillMaxSize().pointerInput(m.uri){detectTransformGestures{_,_,z,_->scale=(scale*z).coerceIn(1f,6f)}}.graphicsLayer(scaleX=scale,scaleY=scale),contentScale=ContentScale.Fit);if(controls){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=close,modifier=Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(.55f))){Icon(Icons.Default.Close,null,tint=Color.White)};Spacer(Modifier.weight(1f));Text("${i+1} / ${xs.size}",color=Color.White,modifier=Modifier.background(Color.Black.copy(.55f),RoundedCornerShape(20.dp)).padding(10.dp))};Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start=18.dp,end=18.dp,top=18.dp,bottom=if(m.video)122.dp else 18.dp),horizontalArrangement=Arrangement.SpaceEvenly){VBtn(Icons.Default.Share){shareOne(c,m.uri)};VBtn(if(favv)Icons.Default.Favorite else Icons.Default.FavoriteBorder){favv=!favv;favorite(m,favv)};VBtn(Icons.Default.Info){};VBtn(Icons.Default.Delete){del(m)}};if(i>0)Nav(Icons.Default.ChevronLeft,Modifier.align(Alignment.CenterStart)){i--;scale=1f};if(i<xs.lastIndex)Nav(Icons.Default.ChevronRight,Modifier.align(Alignment.CenterEnd)){i++;scale=1f}}}}
@Composable private fun VBtn(ic:androidx.compose.ui.graphics.vector.ImageVector,click:()->Unit){IconButton(onClick=click,modifier=Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(.55f))){Icon(ic,null,tint=Color.White)}}
@Composable private fun Nav(ic:androidx.compose.ui.graphics.vector.ImageVector,mod:Modifier,click:()->Unit)=IconButton(onClick=click,modifier=mod.size(55.dp).clip(CircleShape).background(Color.Black.copy(.5f))){Icon(ic,null,tint=Color.White,modifier=Modifier.size(34.dp))}

@Composable private fun Video(uri:Uri,controls:Boolean,toggle:()->Unit){val c=LocalContext.current;val p=remember(uri){ExoPlayer.Builder(c).build().apply{setMediaItem(PMediaItem.fromUri(uri));prepare();playWhenReady=true}};var playing by remember(uri){mutableStateOf(true)};var pos by remember(uri){mutableLongStateOf(0)};var dur by remember(uri){mutableLongStateOf(0)};DisposableEffect(p){val l=object:Player.Listener{override fun onIsPlayingChanged(v:Boolean){playing=v}};p.addListener(l);onDispose{p.removeListener(l);p.release()}};LaunchedEffect(p,playing){while(playing){pos=p.currentPosition;dur=p.duration.coerceAtLeast(0);delay(250)}};Box(Modifier.fillMaxSize().background(Color.Black).clickable{toggle()}){AndroidView(factory={ctx->PlayerView(ctx).apply{player=p;useController=false;layoutParams=android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)}},modifier=Modifier.fillMaxSize());if(controls){Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(.35f),Color.Transparent,Color.Black.copy(.7f)))));IconButton(onClick={if(p.isPlaying)p.pause()else p.play()},modifier=Modifier.align(Alignment.Center).size(78.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)){Icon(if(playing)Icons.Default.Pause else Icons.Default.PlayArrow,null,tint=Color.White,modifier=Modifier.size(40.dp))};Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp).navigationBarsPadding()){Row(verticalAlignment=Alignment.CenterVertically){Text(time(pos),color=Color.White);Slider(value=if(dur>0)pos.toFloat()/dur else 0f,onValueChange={p.seekTo((it*dur).toLong())},modifier=Modifier.weight(1f));Text(time(dur),color=Color.White)}}}}}

private fun load(c:Context):List<M>{val out=mutableListOf<M>();val sources=listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true);for((uri,v)in sources){val pr=arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,MediaStore.MediaColumns.DATE_ADDED,MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.WIDTH,MediaStore.MediaColumns.HEIGHT,MediaStore.MediaColumns.IS_FAVORITE);try{c.contentResolver.query(uri,pr,null,null,"${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use{q->val id=q.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);while(q.moveToNext()){val bi=q.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);val ni=q.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);val fi=q.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE);out+=M(Uri.withAppendedPath(uri,q.getLong(id).toString()),v,if(bi>=0)q.getString(bi).orEmpty()else"Unknown",q.getLong(q.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))*1000,if(ni>=0)q.getString(ni).orEmpty()else"",q.getLong(q.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),fi>=0&&q.getInt(fi)==1,q.getInt(q.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)),q.getInt(q.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)))}}}catch(_:Exception){}};return out.sortedByDescending{it.date}}
private fun makeAlbums(xs:List<M>):List<A>{val r=mutableListOf(A("All Photos",xs.size,xs.firstOrNull()?.uri),A("Favorites",xs.count{it.fav},xs.firstOrNull{it.fav}?.uri),A("Videos",xs.count{it.video},xs.firstOrNull{it.video}?.uri),A("Screenshots",xs.count{it.bucket.contains("screenshot",true)},xs.firstOrNull{it.bucket.contains("screenshot",true)}?.uri),A("Camera",xs.count{it.bucket.contains("camera",true)},xs.firstOrNull{it.bucket.contains("camera",true)}?.uri));r+=xs.groupBy{it.bucket}.filter{it.key.isNotBlank()}.map{A(it.key,it.value.size,it.value.firstOrNull()?.uri)};return r.distinctBy{it.name}}
private fun fav(c:Context,m:M,v:Boolean){if(Build.VERSION.SDK_INT>=29)try{c.contentResolver.update(m.uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_FAVORITE,if(v)1 else 0)},null,null)}catch(_:Exception){}}
private fun shareOne(c:Context,u:Uri){c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type=c.contentResolver.getType(u) ?: "*/*";putExtra(Intent.EXTRA_STREAM,u);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"اشتراک‌گذاری"))}
private fun shareMany(c:Context,us:List<Uri>){if(us.isEmpty())return;c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).apply{type="image/*";putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(us));addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"اشتراک‌گذاری"))}
private fun time(ms:Long)=String.format(Locale.US,"%d:%02d",ms.coerceAtLeast(0)/60000,(ms.coerceAtLeast(0)/1000)%60)
