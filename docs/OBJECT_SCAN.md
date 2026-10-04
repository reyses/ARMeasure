# Object scan (objscan/)

Pure-Kotlin pipeline that turns a depth-from-motion point cloud into the size, volume and mesh of ONE object (a 20 cm item up to
furniture) resting on a support plane. No Android / ARCore imports; everything is JVM-testable. All lengths are meters, volumes m^3.

## Pipeline

```
ar/ depth frames -> world points -> ObjectVoxelCloud (object-local, QUICK 5 mm / FINE 3 mm)
  user places ObjectBox on the support plane
  -> ObjectIsolation.isolate(points, hits, box, plane)
       0 min-hits  1 box crop (grown by denoise radius) -> ObjectDenoise (local planes) -> exact box crop
       2 support margin  3 statistical outlier removal (k=8, mean+2 std, voxel-hash kNN)  4 largest 26-connected component
  -> ObjectMeasures.measure(...)      footprint L x W, height, hull / occupancy / box volumes, range + recommended
  -> ObjectMeshBuilder.build(...)     TriMesh -> volume(), toObj(), toBinaryPly()
  CoverageDome.observe(cameraPosition) in parallel during capture
```

| File | Role |
|---|---|
| ObjectBox.kt | `SupportPlane`, `ObjectBox` (base centre on plane, yaw about +Y, w x d x h; contains / corners / toLocal / toWorld / move / resize) |
| ObjectQuality.kt | `ObjectQuality` presets, `ObjectVoxelCloud` (cloud centred on the box) |
| ObjectIsolation.kt | the 4-stage cut-out + `IsolationStats` (kept / removed per stage) |
| ObjectDenoise.kt | local-plane projection, kills the depth-noise slab |
| ObjectMeasures.kt | measurements, min-area rectangle, 3D quickhull |
| MarchingCubes.kt, ObjectMesh.kt | 256-case table (generated, crack free), occupancy -> mesh, OBJ / PLY export |
| CoverageDome.kt | viewing-direction coverage, ~100 bins (icosphere level 2, upper hemisphere + 15 deg) |

## Quality presets (voxel size is an explicit parameter everywhere)

| | voxel | support margin | outlier k / std | denoise ball |
|---|---|---|---|---|
| QUICK | 5 mm | 8 mm | 8 / 2.0 | 12 mm |
| FINE (mid/high phones) | 3 mm | 5 mm | 8 / 2.0 | 12 mm |

The margin must clear plane-fit error (~2 mm) plus table-surface noise left after voxel averaging (~2 mm sigma at 4 mm raw noise),
about 1.6 voxels in both presets. Too small and a fuzz layer of table survives and fattens the footprint; too large and the base is
cut (the base is put back: the hull adds the footprint projected onto the plane, the height-field fill starts at the plane).
The denoise ball is ~3 x the assumed 4 mm depth noise. Defaults for isolation alone (no preset) are 1.5 cm margin, no denoise.

`VoxelCloud` packs 10 bits per axis around the SESSION ORIGIN: +-512 voxels = +-1.54 m at 3 mm. An object 3 m from where
tracking started would be rejected, so use `ObjectVoxelCloud` (wraps VoxelCloud, subtracts the box centre; range +-1.54 m
around the object at 3 mm, +-2.56 m at 5 mm). `depth/VoxelCloud.kt` is untouched. Memory: ~28 bytes per voxel; a 20 cm object at 3 mm is
about 100-250 k voxels including noise fuzz; the cloud cap `maxVoxels` default 400 k should be raised to ~1 M for FINE.

## Volume definitions and when to trust which

| Value | Definition | Trust | Error mode |
|---|---|---|---|
| hull | 3D convex hull of the isolated points + footprint hull projected to the plane (quickhull, expected O(n log n)) | convex things: boxes, cans, balls, bottles | over for anything concave (chair, mug with handle, L-shape) |
| occupancy | footprint gridded at the voxel size; each column filled from the plane to its highest point; sum(cell area x height) | objects with no overhang (cabinet, box, bin) | over for overhangs (table top counts the space under it as solid); rim columns over by up to half a voxel |
| mesh | divergence-theorem volume of the marching-cubes mesh (closing, column fill, blur, iso 0.7) | same as occupancy, smoother | same overhang fill as occupancy; ~1 % low from the iso choice |
| bounding box | W x D x H axis-aligned in the box frame, and footprint rectangle x height | upper bound only | over for anything not a box |

`volumeLow = min(occupancy, hull)`, `volumeHigh = max(...)`, `volumeRecommended` = midpoint, as for PILE in ShapeCapture. Rule of
thumb for the result card: if hull / occupancy < 1.1 the object is convex-ish, show the hull number; otherwise show the range and
say "approximate".

Footprint: the min-area rectangle of the footprint hull gives the orientation (every hull-edge direction is tried, O(h^2) in the hull
size, i.e. microseconds); its sides are then re-placed at the 0.25 % / 99.75 % quantiles of the projected points. A flat face holds
far more than 0.25 % of the points, so its quantile is the face, while the thin corner tails of the noise are cut. Height is the maximum
height above the plane (after denoise the flat top is exact; a sharp apex (cone) keeps its noise).

## Accuracy expectations on depth-from-motion (Pixel, no ToF)

Measured on synthetic scenes only (seeded, isotropic Gaussian noise, 3 samples per voxel face cell, 2 % outliers, table points
adjacent to the object; `FootprintAccuracyTest`, harness `FootprintBiasHarness`). Footprint = oriented-rectangle length / width error
in mm (measured - true, per dimension), mean over seeds:

| Object | noise | preset | footprint | height | hull volume | mesh volume |
|---|---|---|---|---|---|---|
| cube 200 mm | 4 mm | QUICK | +2.2 mm (worst side +3.6) | +3.5 mm | +4.3 % | -0.5 % |
| cube 200 mm | 4 mm | FINE | +0.9 mm (worst side +1.1) | +0.6 mm | +2.5 % | -0.5 % |
| cube 200 mm | 2 mm | FINE | +0.5 mm | +0.3 mm | +2.3 % | -0.6 % |
| cylinder r 100 h 200 | 4 mm | FINE | -0.8 mm | +0.6 mm | | |
| box 500 x 300 x 400 | 4 mm | FINE | +1.0 / +1.1 mm | | +2.2 % | |
| box 500 x 300 x 400 | 4 mm | QUICK | +2.2 / +2.7 mm | | +4.1 % | |

Residual footprint bias is about +0.25 sigma per side at FINE and +0.3 sigma at QUICK (noise up to 4 mm; 20 seeds, sizes 100 / 200 /
400 mm, yaw 0 / 30 deg all agree within the seed sd of 0.1-0.7 mm). It does not depend on object size or yaw. At 6 mm noise the
12 mm denoise ball is only 2 sigma and the footprint reads +2.9 mm (FINE) / +6.5 mm (QUICK): out of the designed range.

Where the footprint error comes from (200 mm cube, FINE, 4 mm noise, length error): raw noisy points +18.0 mm (the outermost 0.25 %
of a Gaussian slab is ~2.25 sigma out per side), after voxel means +18.9, after the local-plane denoise +3.5, after isolation
(outlier removal) +1.0 with no table points near the object, and **+4.4 with them**. The table points were the cause of the
reported +4-5 mm: next to a wall the denoise mixes wall and floor neighbours and lifts floor fuzz into a fillet 5-18 mm high that
survives the 5 mm support margin and extends up to the box slack. The footprint rectangle now ignores points below
`ObjectMeasures.FLOOR_GATE` (18 mm = 1.5 x the denoise ball, capped at a quarter of the object height); volumes still use all points.
Cost: an object that widens toward its base (cone, pile) reads its width at the gate height. Quantile choice and voxelisation are
second order (+0.3-0.8 mm each).

Hull volume is a closed-hull check now: the incremental quickhull produced a non-manifold surface on nearly coplanar denoised faces
once in 20 seeds (hull +845 %); the build is verified and redone on a 1e-6 .. 1e-4 jittered copy (2 attempts were enough).

Real data adds what the synthetic data lacks: scale drift of the visual-inertial tracking (typically 1-2 % of the camera path), plane
error (a tilted table), gaps (shiny, dark or textureless surfaces get no depth), noise that is correlated between frames rather than
independent (averaging does not remove it), and moving objects. Expect roughly: 200 mm object, +-5-10 mm per side and +-10-15 %
volume; 50 mm object, +-3-5 mm and +-25 % volume (noise is then 10 % of the size); furniture (> 0.5 m) +-1-2 cm and +-5 % (tracking
drift dominates). Below about 5 cm the noise exceeds the denoise ball and the result should be labelled indicative.

Known behaviours: the denoise rounds sharp edges by ~2-3 mm (volume reads a fraction of a percent low on a perfect box); the mesh
volume of a 100 mm object reads ~5 % low (closing radius); the floor gate costs a tapered base (see above).

## Performance (JVM desktop, single thread, FINE 3 mm)

Scene: 30 cm cube with floor, 249 k voxels in, 135 k kept after the box crop.

| Stage | ms |
|---|---|
| cloud insert | 1.2 ms per 5 000-point frame |
| isolation (box, denoise x3, support, outliers, component) | ~1 800 |
| measures (hull, footprint, occupancy) | ~110 |
| mesh (125 k triangles) | ~120 |

Run isolation + measures + mesh once at the end of the scan (or on "preview" taps) on a background thread; on a phone budget 3-5 x
the desktop figure (estimate, not measured). Denoise dominates; `ObjectDenoise.smooth(maxNeighbours = 48, iterations = 3)` can be
lowered (iterations = 2 halves it) for a live preview.

## Coverage dome

`CoverageDome(box)`: ~100 bins; `observe(cameraPosition)` takes the direction object -> camera; counts only when the camera is within
the distance window derived from the box: largest side < 30 cm -> 0.25-0.8 m, otherwise 0.3-2.0 m (`CoverageDome.windowFor`), and not
more than 15 deg below the horizon. A bin turns green after 3 frames. `coverage()` is the green fraction (0..1), `bins()` gives
direction + observed + count for drawing. Suggested "good enough" threshold: 0.6 for a free-standing object (the back of a wall-hugging object never fills).

## Wiring plan for ar/ and ui/

Feed (ar/ layer, on every tracked frame while "scan object" is active):

1. Points: the existing depth sampler's world points -> `ObjectVoxelCloud(box, quality.voxelSize, maxVoxels = 1_000_000).add(x, y, z)`. Skip points
   outside `box.contains(p, 0.15f)` early (cheap, keeps the cloud small). Create the cloud when the box is confirmed; if the box moves
   by more than ~2 cm, rebuild (clear + re-add from stored frames is not possible, so lock the box before capture).
2. Support plane: the ARCore horizontal plane the user tapped (or the table plane from PlaneExtractor): `SupportPlane.through(hitPose.position, planeNormal)`.
   Assumed horizontal (normal ~ +Y); reject tilted planes (> 5 deg) with a message. Place the box centre on the plane
   (`box.onPlane(plane)`).
3. Camera positions: `camera.pose.translation` each frame -> `dome.observe(Vec3(...))`.
4. At "Done": `pts = cloud.points()`, `hits = cloud.hitsFor(pts)`, then `isolate` -> `measure` -> `ObjectMeshBuilder.build`
   on `Dispatchers.Default`, results to the UI as one immutable object.

UI needed:

- Box placement gizmo: tap a plane to drop a default box (20 x 20 x 20 cm); drag on the plane to move (`translated`), two-finger twist = `rotated`,
  handles or sliders for w / d / h (`resized`), live wireframe from `corners()`. The box should be slightly bigger than the object (1-2 cm slack).
- Dome markers: ring of small spheres or an arc HUD at `dome.bins()` directions around the box (green observed, grey not), plus a coverage
  percent and a "move closer / back off" hint when the camera is outside the distance window.
- Result card: footprint L x W, height, recommended volume with the range (low - high), hull / occupancy ratio flag, quality preset, points kept per stage
  (`IsolationStats`) for debugging. Buttons: Save mesh (OBJ / PLY), Re-measure with a different box.
- 3D view: hand the `TriMesh` (vertices, normals, indices, world frame) to the scan3d viewer another drone is building; the mesh is already in world
  coordinates, y up, outward counter-clockwise triangles, so it loads as a standard indexed mesh. Export: `toObj()` text or `toBinaryPly()` bytes via a
  SAF create-document intent.
- Quality toggle: QUICK / FINE; FINE only on devices where the isolation stage stays under ~10 s.

## Limitations

- Support plane must be horizontal; height-field volumes assume the object rests on it.
- Overhangs are filled (see volume table); the hull is the upper bound for concave objects.
- Meshing a very large object at 3 mm is capped by `maxCells` (40 M cells, null returned); use QUICK above ~0.8 m.
- The marching-cubes table is generated at class load, separating ambiguous faces; the mesh is closed unless the occupancy touches the grid
  border (never, the grid is padded). Edge shared by more than 2 triangles can in principle occur at ambiguous faces; the sphere and a random-blob test have none.
- Denoise parameters are tuned for 3-4 mm noise; lower-noise sources will lose ~2 mm at edges for nothing, set `denoiseRadius = 0` then.
