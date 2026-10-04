package com.example.arruler.scan3d

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLContext
import android.util.Log
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.arruler.depth.PlaneKind
import com.example.arruler.objscan.TexturedMeshData
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager.PrimitiveType
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.texture.ImageTexture
import io.github.sceneview.utils.OpenGL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "Scan3DViewer"

private fun rgbToFloat4(rgb: Int, a: Float = 1f) =
    Float4(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, a)

/**
 * The viewer's EGL context, created WITHOUT taking over the main thread. SceneView's default `createEglContext()` makes
 * its new context current on the calling (main) thread and never gives it back; the paused AR view below shares that
 * thread, and ARCore's `Session.update()` updates the camera texture in whatever context is current there. Without
 * this, the AR camera came back on a destroyed foreign context after Back (no camera image or an ARCore error).
 */
internal object ViewerEgl {
    fun createKeepingCurrent(): EGLContext {
        val display = EGL14.eglGetCurrentDisplay()
        val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val previous = EGL14.eglGetCurrentContext()
        val created = OpenGL.createEglContext()
        if (previous != EGL14.EGL_NO_CONTEXT && display != EGL14.EGL_NO_DISPLAY) {
            if (!EGL14.eglMakeCurrent(display, draw, read, previous)) {
                Log.w(TAG, "could not restore the previous EGL context: 0x${Integer.toHexString(EGL14.eglGetError())}")
            }
        }
        return created
    }
}

/** Filament upload of checked arrays (main thread, the engine's thread). */
private fun MeshArrays.toGeometry(engine: Engine): Geometry {
    val n = vertexCount
    val verts = ArrayList<Geometry.Vertex>(n)
    val nr = normals
    val uv = uvs
    for (i in 0 until n) {
        verts += Geometry.Vertex(
            position = Float3(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]),
            normal = nr?.let { Float3(it[i * 3], it[i * 3 + 1], it[i * 3 + 2]) },
            uvCoordinate = uv?.let { Float2(it[i * 2], it[i * 2 + 1]) },
        )
    }
    val type = if (primitive == Primitive.POINTS) PrimitiveType.POINTS else PrimitiveType.TRIANGLES
    return Geometry.Builder(type).vertices(verts).indices(indices.asList()).build(engine)
}

/**
 * One part as a MeshNode. The arrays were checked by [ViewerGeometry]; a Filament-side failure is reported through
 * [onError] once and the part is skipped instead of taking the app down.
 */
@Composable
private fun SceneScope.PartNode(
    engine: Engine,
    materialLoader: MaterialLoader,
    part: ScenePart,
    atlas: Bitmap?,
    meshMaterial: MaterialInstance?,
    onError: (Throwable) -> Unit,
) {
    val texture = remember(part.material, atlas) {
        if (part.material == PartMaterial.Atlas && atlas != null) {
            try { ImageTexture.Builder().bitmap(atlas).build(engine) } catch (e: Exception) { onError(e); null }
        } else null
    }
    // declared before the node so that, disposing in reverse order, the texture outlives the renderable
    DisposableEffect(texture) { onDispose { texture?.let { t -> runCatching { engine.destroyTexture(t) } } } }
    val geo = remember(part) {
        try { part.arrays.toGeometry(engine) } catch (e: Exception) { onError(e); null }
    } ?: return
    val mat = remember(part.material, texture, meshMaterial) {
        try {
            when (val m = part.material) {
                is PartMaterial.Unlit -> materialLoader.createUnlitColorInstance(rgbToFloat4(m.rgb, m.alpha))
                is PartMaterial.Lit -> meshMaterial ?: materialLoader.createColorInstance(0xFF000000.toInt() or m.rgb, 0f, 0.6f, 0.5f)
                PartMaterial.Atlas -> texture?.let { materialLoader.createTextureInstance(it, true, 0f, 0.85f, 0.3f) }
            }
        } catch (e: Exception) { onError(e); null }
    } ?: return
    MeshNode(
        primitiveType = geo.primitiveType,
        vertexBuffer = geo.vertexBuffer,
        indexBuffer = geo.indexBuffer,
        boundingBox = geo.boundingBox,
        materialInstance = mat,
    )
}

/** Loading, a built scene, or the reason building failed. */
private sealed interface SceneState {
    data object Loading : SceneState
    class Ready(val scene: ViewerScene) : SceneState
    class Failed(val error: Throwable) : SceneState
}

/**
 * Full-screen 3D view of a [ScanSnapshot]: orbit with one finger, pan with two, pinch to zoom (SceneView's
 * camera manipulator). Toggles for Points / Surfaces / colour mode / big dots, a Top view button and a legend.
 * The drawable arrays are built off the main thread ([ViewerScene.build]); a scan with nothing drawable shows
 * [ViewerScene.EMPTY_TEXT], a room scan without a room shows which pieces are missing ([ViewerScene.missingNote]).
 * Any failure while building or uploading goes to [onError] (the caller shows the error card).
 */
@Composable
fun Scan3DViewer(
    snapshot: ScanSnapshot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** The photo-textured (or vertex-coloured) version of the mesh; the grey mesh only when this is null. */
    textured: TexturedMeshData? = null,
    onError: (Throwable) -> Unit = { Log.e(TAG, "3D view failed", it) },
) {
    BackHandler(enabled = !compact, onBack = onBack)
    var showPoints by remember { mutableStateOf(!compact || snapshot.mesh == null) }
    var showSurfaces by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf(ColourMode.QUALITY) }
    var bigDots by remember { mutableStateOf(false) }
    var topView by remember { mutableStateOf(false) }
    val reportError by rememberUpdatedState(onError)
    // one report per viewer: a broken scan would otherwise report once per part
    val reported = remember(snapshot) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val fail: (Throwable) -> Unit = { e -> if (reported.compareAndSet(false, true)) reportError(e) }

    val engine = rememberEngine(eglContextCreator = ViewerEgl::createKeepingCurrent)
    val materialLoader = rememberMaterialLoader(engine)

    val sceneState by produceState<SceneState>(SceneState.Loading, snapshot, textured, showPoints, showSurfaces, mode, bigDots) {
        value = SceneState.Loading
        value = try {
            SceneState.Ready(
                withContext(Dispatchers.Default) {
                    val uv = ViewerHooks.meshUv?.let { f -> snapshot.mesh?.let { f(snapshot) } }
                    ViewerScene.build(snapshot, textured, showPoints, showSurfaces, mode, bigDots, uv)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SceneState.Failed(e)
        } catch (e: OutOfMemoryError) {
            SceneState.Failed(e)
        }
    }
    (sceneState as? SceneState.Failed)?.let { f -> androidx.compose.runtime.LaunchedEffect(f) { fail(f.error) } }
    val scene = (sceneState as? SceneState.Ready)?.scene

    val framing = remember(snapshot) { ViewerScene.framing(snapshot) }
    val target = remember(framing) { Float3(framing[0], framing[1], framing[2]) }
    val span = framing[3]
    val meshMaterial = remember(materialLoader, snapshot) {
        ViewerHooks.meshMaterial?.let { f -> runCatching { f(materialLoader, snapshot) }.getOrNull() }
    }

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
                scene?.parts?.forEach { part ->
                    key(part.key) { PartNode(engine, materialLoader, part, textured?.atlas, meshMaterial, fail) }
                }
            }
        }

        if (scene != null && scene.isEmpty && (showPoints || showSurfaces)) {
            Box(Modifier.align(Alignment.Center).padding(24.dp)) {
                Pill {
                    Text(
                        ViewerScene.EMPTY_TEXT, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        if (!compact) Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill { TextButton(onClick = onBack) { Text("Back", color = Color.White) } }
                Pill {
                    Text(
                        "${snapshot.pointCount} points" + (snapshot.room?.let { "  |  %.1f m2".format(java.util.Locale.US, it.areaM2) } ?: "") +
                            (snapshot.mesh?.let { "  |  ${it.triangleCount} triangles" } ?: ""),
                        color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            (scene?.note ?: ViewerScene.missingNote(snapshot))?.let { note ->
                Pill {
                    Text(note, color = Color(0xFFFFD60A), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }

        if (!compact) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Legend(mode, snapshot)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(showPoints, { showPoints = !showPoints }, { Text("Points") })
                FilterChip(showSurfaces, { showSurfaces = !showSurfaces }, { Text(if (snapshot.mesh != null) "Mesh" else "Surfaces") })
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
            (if (s.mesh != null) "Object mesh." else "${s.planes.size} surfaces found.") + " Drag to orbit, two fingers to pan, pinch to zoom.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Hooks for the textured mesh (texture drone, see docs/WIRING_ROUND3.md). Set both once at start-up:
 * [meshUv] returns two floats (u, v) per mesh vertex of the snapshot, and [meshMaterial] a material whose base colour
 * is the texture PNG; while they are null the viewer shows the grey mesh.
 */
object ViewerHooks {
    var meshMaterial: ((MaterialLoader, ScanSnapshot) -> MaterialInstance?)? = null
    var meshUv: ((ScanSnapshot) -> FloatArray?)? = null
}
