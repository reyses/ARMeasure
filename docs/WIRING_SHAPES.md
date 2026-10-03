# Wiring the SHAPES mode into MainActivity

New files (nothing existing was edited):
`measure/shapes/{ShapeCapture,ShapeFormat,ShapePreview}.kt`, `ui/ShapeControls.kt`.
`ShapeCapture` is immutable: every call returns a new state, so keep it in one `mutableStateOf`.
Taps are `geometry.Vec3` or `MeasurePoint` (world, meters, +Y up); `ar.createAnchor(hit)` points are `MeasurePoint`, which `add()` accepts.

## 1. State holder (MainActivity fields)

```kotlin
import androidx.compose.runtime.mutableStateOf
import com.example.arruler.measure.shapes.*
import com.example.arruler.ui.ShapeControls

private var shapesActive by mutableStateOf(false)            // true while SHAPES mode is on
private var capture by mutableStateOf(ShapeCapture(ShapeKind.BOX))
```

## 2. Mode switch

Add a SHAPES pill next to DISTANCE / AREA (or a button calling):

```kotlin
private fun onShapesToggle(on: Boolean) {
    shapesActive = on
    capture = ShapeCapture(capture.kind)
    renderer.clear()
}
```
When switching back to DISTANCE/AREA call `shapesActive = false` and `renderer.clear()`.

## 3. Route taps (the shutter / `onArTap` path)

At the top of the shutter handler (`onShutter`, ~line 148) and `onArTap` (~line 195), before the existing logic:

```kotlin
if (shapesActive) { onShapeTap(hit); return }   // use the same hit the area path uses
```
```kotlin
private fun onShapeTap(hit: HitResult) {          // whatever type ar.hitTestCenter()/onArTap give
    haptic()
    val p = ar.createAnchor(hit)                   // MeasurePoint
    capture = capture.add(p)
    renderShapes()
}
private fun renderShapes() {
    renderer.render(capture.points)                // tapped points + joining line (final = false)
    renderer.renderExtra(ShapePreview.segments(capture))   // wireframe
}
```
Unlike the AREA path nothing needs to be gated on `Phase`; the capture owns its own progress.

## 4. Callbacks for the controls

```kotlin
private fun onShapeKind(k: ShapeKind) { capture = ShapeCapture(k); renderShapes() }
private fun onShapeUndo()  { capture = capture.undo();      renderShapes() }
private fun onShapeDone()  { capture = capture.closeBase(); renderShapes() }   // PILE only
private fun onShapeReset() { capture = capture.reset();     renderShapes() }
```
(`renderShapes()` with no taps calls `renderer.render(emptyList())` and `renderExtra(emptyList())`, which clears.)

## 5. Overlay (inside the Box in setContent, next to `AreaControls(...)`, ~line 115)

```kotlin
if (shapesActive) {
    ShapeControls(capture, state.unit, ::onShapeKind, ::onShapeUndo, ::onShapeDone, ::onShapeReset)
} else {
    AreaControls(state, ::onSetMode, ::onAreaClose, ::onAreaUndo, ::onAreaHeight)
}
```
`ShapeControls` is a `BoxScope` extension (like `AreaControls`) and occupies the same bottom slot
(bottom padding 136 dp). Its own picker row (Box / Cylinder / Cone / Sphere / Frustum / Pile) scrolls horizontally;
it shows the prompt, Undo / Reset / (Done for PILE) and, once `capture.isComplete`, the result card
(named dimensions, volume, surface area, in `state.unit`). If AreaControls' DISTANCE/AREA pills must stay
visible in SHAPES mode, put ShapeControls in a Column above them instead of the else-branch.

## 6. Per-frame / crosshair

Nothing needed: no live rubber band is rendered. (If wanted: `renderer.renderExtra(ShapePreview.segments(capture) + (lastTap to crosshairPoint))`.)

## Notes

- `capture.result?.volume` etc. are in m / m2 / m3; format with `ShapeFormat.length/area/volume(units, v)`.
- PILE: tap >= 3 outline points, press Done (`closeBase()`), tap the apex. The card shows the volume as the midpoint of
  [pyramid (lower bound), prism (upper bound)] with that range printed; surface area is not shown (null).
- SPHERE is approximate (two taps are rarely exactly diametral).
- Cylinder / cone / frustum assume an upright axis (world +Y); radius is the horizontal distance of the rim tap from the centre tap.
