package com.example.arruler.scan3d

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.depth.PlaneKind
import com.example.arruler.geometry.ColorRamp
import com.google.android.filament.Box as FilamentBox
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager.PrimitiveType
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.SceneView
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader

/** How the point cloud is coloured. */
enum class ColourMode(val label: String) { QUALITY("Colour by quality"), KIND("Colour by kind") }

/**
 * Pure grouping of the cloud into (colour, point indices) buckets, one rendered mesh per bucket, because
 * the SceneView colour materials take ONE uniform colour per material instance (no per-vertex colour material
 * ships with 4.52). Quality mode: [QUALITY_BUCKETS] buckets along the ramp. Kind mode: one bucket per plane kind plus grey.
 * The cloud is thinned evenly to at most [maxDisplay] points first.
 */
internal object ViewerGroups {
    const val QUALITY_BUCKETS = 10

    class Group(val rgb: Int, val indices: IntArray)

    fun build(s: ScanSnapshot, mode: ColourMode, maxDisplay: Int): List<Group> {
        val n = s.pointCount
        val take = minOf(n, maxDisplay)
        if (take == 0) return emptyList()
        val src = IntArray(take) { if (take == n) it else (it.toLong() * n / take).toInt() }
        val buckets = HashMap<Int, MutableList<Int>>()
        if (mode == ColourMode.QUALITY) {
            for (i in src) {
                val b = (s.quality[i] * QUALITY_BUCKETS).toInt().coerceIn(0, QUALITY_BUCKETS - 1)
                buckets.getOrPut(ColorRamp.rgb((b + 0.5f) / QUALITY_BUCKETS)) { ArrayList() }.add(i)
            }
        } else {
            val planeOf = s.planeIndexPerPoint()
            for (i in src) {
                val c = if (planeOf[i] < 0) KindColors.UNASSIGNED else KindColors.rgb(s.planes[planeOf[i]].kind)
                buckets.getOrPut(c) { ArrayList() }.add(i)
            }
        }
        return buckets.map { (c, l) -> Group(c, l.toIntArray()) }.sortedBy { it.rgb }
    }
}

private fun rgbToFloat4(rgb: Int, a: Float = 1f) =
    Float4(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, a)

private const val DOTS_MAX = 60_000
private const val BIG_DOTS_MAX = 25_000
private const val BIG_DOT_SIZE_M = 0.016f

/** POINTS primitive geometry: one vertex and one index per point. */
private fun pointsGeometry(engine: Engine, s: ScanSnapshot, idx: IntArray): Geometry {
    val verts = ArrayList<Geometry.Vertex>(idx.size)
    val ind = ArrayList<Int>(idx.size)
    for ((k, i) in idx.withIndex()) {
        verts += Geometry.Vertex(position = Float3(s.points[i * 3], s.points[i * 3 + 1], s.points[i * 3 + 2]))
        ind += k
    }
    return Geometry.Builder(PrimitiveType.POINTS).vertices(verts).indices(ind).build(engine)
}

/** Fallback for renderers that draw POINTS at 1 px: one small tetrahedron per point (4 vertices, 4 triangles). */
private fun tetraGeometry(engine: Engine, s: ScanSnapshot, idx: IntArray): Geometry {
    val h = BIG_DOT_SIZE_M
    val verts = ArrayList<Geometry.Vertex>(idx.size * 4)
    val ind = ArrayList<Int>(idx.size * 12)
    for ((k, i) in idx.withIndex()) {
        val x = s.points[i * 3]; val y = s.points[i * 3 + 1]; val z = s.points[i * 3 + 2]
        verts += Geometry.Vertex(position = Float3(x + h, y + h, z + h))
        verts += Geometry.Vertex(position = Float3(x + h, y - h, z - h))
        verts += Geometry.Vertex(position = Float3(x - h, y + h, z - h))
        verts += Geometry.Vertex(position = Float3(x - h, y - h, z + h))
        val b = k * 4
        // both windings so the tiles show whichever way the material culls
        for (t in intArrayOf(0, 1, 2, 0, 3, 1, 0, 2, 3, 1, 3, 2)) ind += b + t
        for (t in intArrayOf(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3)) ind += b + t
    }
    return Geometry.Builder(PrimitiveType.TRIANGLES).vertices(verts).indices(ind).build(engine)
}

/** Fan-triangulated polygon, both windings (so the fill shows from either side regardless of culling). */
private fun planeGeometry(engine: Engine, p: SnapshotPlane): Geometry {
    val n = p.vertexCount
    val normal = Float3(p.nx, p.ny, p.nz)
    val verts = ArrayList<Geometry.Vertex>(n)
    for (i in 0 until n) {
        verts += Geometry.Vertex(position = Float3(p.outline[i * 3], p.outline[i * 3 + 1], p.outline[i * 3 + 2]), normal = normal, uvCoordinate = Float2(0f, 0f))
    }
    val ind = ArrayList<Int>((n - 2) * 6)
    for (i in 1 until n - 1) { ind += 0; ind += i; ind += i + 1 }
    for (i in 1 until n - 1) { ind += 0; ind += i + 1; ind += i }
    return Geometry.Builder(PrimitiveType.TRIANGLES).vertices(verts).indices(ind).build(engine)
}

/**
 * Full-screen 3D view of a [ScanSnapshot]: orbit with one finger, pan with two, pinch to zoom (SceneView's
 * camera manipulator). Toggles for Points / Surfaces / colour mode / big dots, a Top view button and a legend.
 */
@Composable
fun Scan3DViewer(snapshot: ScanSnapshot, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    var showPoints by remember { mutableStateOf(true) }
    var showSurfaces by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(ColourMode.QUALITY) }
    var bigDots by remember { mutableStateOf(false) }
    var topView by remember { mutableStateOf(false) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val b = remember(snapshot) { snapshot.bounds() ?: floatArrayOf(-1f, 0f, -1f, 1f, 2f, 1f) }
    val target = remember(b) { Float3((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2) }
    val span = remember(b) { maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2]).coerceAtLeast(1f) }

    Box(modifier.fillMaxSize().background(Color(0xFF101114))) {
        key(topView) {
            val home = if (topView) Float3(target.x, target.y + span * 1.6f, target.z + span * 0.01f)
            else Float3(target.x + span * 0.8f, target.y + span * 0.7f, target.z + span * 1.2f)
            val manipulator = rememberCameraManipulator(orbitHomePosition = home, targetPosition = target)
            val cameraNode = rememberCameraNode(engine) {
                position = home
                lookAt(target)
            }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                cameraNode = cameraNode,
                cameraManipulator = manipulator,
            ) {
                if (showPoints) {
                    val groups = remember(snapshot, mode, bigDots) {
                        ViewerGroups.build(snapshot, mode, if (bigDots) BIG_DOTS_MAX else DOTS_MAX)
                    }
                    for (g in groups) key(g.rgb, bigDots) {
                        val geo = remember(snapshot, g, bigDots) {
                            if (bigDots) tetraGeometry(engine, snapshot, g.indices) else pointsGeometry(engine, snapshot, g.indices)
                        }
                        val mat = remember(g.rgb) { materialLoader.createUnlitColorInstance(rgbToFloat4(g.rgb)) }
                        MeshNode(
                            primitiveType = geo.primitiveType,
                            vertexBuffer = geo.vertexBuffer,
                            indexBuffer = geo.indexBuffer,
                            boundingBox = geo.boundingBox,
                            materialInstance = mat,
                        )
                    }
                }
                if (showSurfaces) {
                    for ((pi, p) in snapshot.planes.withIndex()) {
                        if (p.vertexCount < 3) continue
                        key(pi) {
                            val geo = remember(snapshot, pi) { planeGeometry(engine, p) }
                            val mat = remember(p.kind) { materialLoader.createUnlitColorInstance(rgbToFloat4(KindColors.rgb(p.kind), 0.32f)) }
                            MeshNode(
                                primitiveType = geo.primitiveType,
                                vertexBuffer = geo.vertexBuffer,
                                indexBuffer = geo.indexBuffer,
                                boundingBox = geo.boundingBox,
                                materialInstance = mat,
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill { TextButton(onClick = onBack) { Text("Back", color = Color.White) } }
            Pill {
                Text(
                    "${snapshot.pointCount} points" + (snapshot.room?.let { "  |  %.1f m2".format(java.util.Locale.US, it.areaM2) } ?: ""),
                    color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Legend(mode, snapshot)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(showPoints, { showPoints = !showPoints }, { Text("Points") })
                FilterChip(showSurfaces, { showSurfaces = !showSurfaces }, { Text("Surfaces") })
                FilterChip(
                    mode == ColourMode.QUALITY,
                    { mode = if (mode == ColourMode.QUALITY) ColourMode.KIND else ColourMode.QUALITY },
                    { Text(mode.label) },
                )
                FilterChip(bigDots, { bigDots = !bigDots }, { Text("Big dots") })
                FilterChip(topView, { topView = !topView }, { Text("Top view") })
            }
        }
    }
}

@Composable
private fun Pill(content: @Composable () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f))) { content() }
}

@Composable
private fun Legend(mode: ColourMode, s: ScanSnapshot) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (mode == ColourMode.QUALITY) {
            Text("Green = measured well, red = weak. Sweep the red areas again.", color = Color.White, fontSize = 13.sp)
            Box(
                Modifier.fillMaxWidth().size(width = 0.dp, height = 10.dp).clip(RoundedCornerShape(5.dp)).background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00)),
                    ),
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1 hit", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                Text("${Quality.FULL_HITS}+ hits", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                for (k in listOf(PlaneKind.FLOOR, PlaneKind.WALL, PlaneKind.CEILING, PlaneKind.OTHER)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or KindColors.rgb(k))))
                        Text(KindColors.label(k), color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
        Text(
            "${s.planes.size} surfaces found. Drag to orbit, two fingers to pan, pinch to zoom.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, style = MaterialTheme.typography.bodySmall,
        )
    }
}
