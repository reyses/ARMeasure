package com.example.arruler.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.arruler.R
import com.example.arruler.measure.Units
import com.example.arruler.scan3d.ObjectThumbnail
import com.example.arruler.scan3d.ScanInfo
import com.example.arruler.scan3d.scansRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A saved object as the gallery lists it: its scan info and the name of its project. */
class ObjectItem(val info: ScanInfo, val projectName: String?)

/** The gallery: 2-column grid of cards (thumbnail, name, L x W x H, volume, date). */
@Composable
fun ObjectsGrid(
    items: List<ObjectItem>,
    units: Units,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    showProject: Boolean = false,
) {
    if (items.isEmpty()) {
        Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No objects yet. Use OBJECT mode on the AR screen: tap the object, scan it, then Save.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.info.id }) { item -> ObjectCard(item, units, showProject) { onOpen(item.info.id) } }
    }
}

@Composable
private fun ObjectCard(item: ObjectItem, units: Units, showProject: Boolean, onClick: () -> Unit) {
    val info = item.info
    val root = scansRoot(LocalContext.current)
    val thumb by produceState<Bitmap?>(null, info.id) {
        value = withContext(Dispatchers.IO) {
            ObjectThumbnail.ensure(root, info.id)?.let { BitmapFactory.decodeFile(it.path) }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFF1B1D22)), contentAlignment = Alignment.Center) {
            val bmp = thumb
            if (bmp != null) {
                Image(bmp.asImageBitmap(), info.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(painterResource(R.drawable.ic_objects), null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(40.dp))
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(info.name ?: "Object", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            info.summary?.let {
                Text(ObjectFormat.dims(units, it), style = MaterialTheme.typography.bodyMedium)
                Text(ObjectFormat.volume(units, it.volumeM3), style = MaterialTheme.typography.bodyMedium)
            }
            val sub = listOfNotNull(if (showProject) item.projectName else null, ObjectFormat.date(info.createdAt)).joinToString("  |  ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Top-level gallery of every saved object (Projects, Objects). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectsScreen(items: List<ObjectItem>, units: Units, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Objects") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        ObjectsGrid(items, units, onOpen, Modifier.padding(padding), showProject = true)
    }
}
