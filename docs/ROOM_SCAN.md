# Room scan (depth/)

How SCAN mode turns ARCore depth-from-motion into walls, floor, ceiling and a room outline, why it was rebuilt on
2026-10-03, and what the tests measure. All lengths are meters unless stated. Objects are in OBJECT_SCAN.md.

## What went wrong on the first real scan

The owner's first scan on a Pixel (2026-10-03, fixture `app/src/test/resources/fixtures/real_scan_2026-10-03_surfaces.obj`)
gave 8 planes, no floor, no ceiling. 7 of the 8 were ONE wall about 2.3 m away. The extractor had cut it into parallel slices
tilted 3-17 deg, with offsets of 2.14-2.42 m along their own normals (a 0.28 m spread). 3 of the slices were tilted past the
WALL tolerance and came out as `other`. The cause: depth-from-motion error at 2-2.5 m is around a decimetre, while RANSAC kept
points within a fixed 2 cm of a plane. Every synthetic test had used 5 mm of noise.

## Pipeline

```
ArSessionController frame -> ThrottledDepthSampler (every 3rd frame) -> DepthFrameSampler.process
   world points + per-point range (depth along the optical axis, m)
-> VoxelCloud.add(x, y, z, range)       2 cm voxels: mean position, hit count, mean observation range
Analyze:
   VoxelCloud.observations(minHits = 2)   aligned xyz / hits / range  (CloudObservations)
   ArSessionController.trackedPlanes()    TRACKING, non-subsumed ARCore planes (ArPlaneObservation)
-> PlaneExtractor.extract(cloud, arPlanes)
     self-calibrate noise -> gravity-aware RANSAC -> robust refit -> claim tails -> coplanar merge
     -> classify -> bounding check -> floor fallback -> ArPlaneFusion
-> RoomFromPlanes.assemble(planes)       complete RoomModel, or partial (heights, footprint, open sides)
-> ScanLogic.assess                      ScanAnalysis.Room | ScanAnalysis.Incomplete(missing words, partial)
```

| File | Role |
|---|---|
| DepthNoiseModel.kt | sigma(z) = a + b z^2, the calibrated defaults |
| VoxelCloud.kt | sparse voxel grid, now with mean observation range per voxel; `CloudObservations` |
| DepthFrameSampler.kt | depth image -> world points; `DepthSample.range` |
| PlaneExtractor.kt | the extraction; `ExtractedPlane` carries sigma, gravity flag, free tilt, free / final RMS, source, merge count |
| ArPlaneObservation.kt, ArPlaneFusion.kt | ARCore planes as plain data, and how they anchor the depth planes |
| RoomFromPlanes.kt | `RoomModel`, `RoomAssembly` (partial result), `RoomSide` |
| ScanController.kt | `ScanLogic.analyze / assess / missingPieces`, `ScanController.analyze(arPlanes)` |
| ar/ArSessionController.kt | `trackedPlanes()` snapshot taken when Analyze is pressed |

The old behaviour is still available as `PlaneExtractor.legacy()`, for comparisons only. A cloud without ranges
(`extract(points)`, the PC path, the device benchmark) keeps the fixed 2 cm band.

## Noise model

`DepthNoiseModel`: sigma(z) = a + b z^2, with a = 5 mm and b = 0.030 m/m^2. That gives sigma(1 m) = 3.5 cm,
sigma(2.3 m) = 16 cm and sigma(4 m) = 49 cm.

How b was calibrated: the test simulator (below) re-runs the owner's capture. The person stands near the origin and turns,
mostly facing a wall 2.3 m away. The simulated frames go through the production voxel cloud and the OLD extractor, and the
resulting slices are compared with the real ones:

| b (m/m^2) | wall-A slices (4 seeds) | offset spread (m) |
|---|---|---|
| 0.02 (tilt +-1.5 deg) | 6 | 0.16-0.21 |
| **0.03** (tilt +-2 deg, the default) | 6-7 | 0.20-0.29 |
| 0.04 (tilt +-1.5 deg) | 6-7 | 0.21-0.31 |
| real scan | 7 | 0.28 |

`RoomScanRealismTest.noiseModelReproducesTheOwnersSliceSpread` asserts that the mean simulated spread is within 35 % of
the real one.

Depth quality depends on how the phone moved: turning on the spot gives little parallax and is much worse than walking. So the
extractor does not trust the model blindly. Before extracting, it finds the largest plane and takes the points within
4 model-sigma of it. It then estimates the true-to-model ratio as median(|residual| / sigma) / 0.6745, clamps it to 1-2.5,
and inflates sigma when the ratio is above 1.15 (`PlaneExtractor.noiseScale`). With the simulator 33 % noisier than the model
(b = 0.04 against 0.03, tilt +-1.5 deg), the wall still came out as one plane in 3 of 4 seeds. Without this step it was 1 of 4.

### Test simulator (app/src/test/.../depth/RoomScanSimulator.kt)

It simulates the sampling, not noise added to the final points:
- **Camera paths.** A person walks a loop about 1.2 m inside the walls with the phone at 1.4 m. They pause at each station,
  turn slowly, sweep yaw +-35 deg and pitch -55..+55 deg, with hand jitter. About 400-500 frames, which is 40-50 s at the
  10 Hz sampling rate.
- **Depth images.** Each frame ray-casts a true 64x48 depth image of a prismatic room (`SimRoom`: a box, an L or any
  footprint) and corrupts it (`DepthNoiseSim`):
  - per pixel: Gaussian error with sigma(z);
  - per frame: a depth scale error of +-3 % and a pose tilt of +-2 deg about a random axis. These are correlated across the
    whole frame, which is what fans one wall out into tilted slices once frames are fused;
  - speckle: 2 % of pixels get a random depth in front of the true one and a random confidence.
- **Production path.** The image becomes DEPTH16 + confidence and goes through `DepthFrameSampler.process` (unprojection,
  confidence cut 0.4, ranges) into `VoxelCloud`.
- **ARCore planes for tests.** `arFloorAndCeiling` gives floor and ceiling patches with 1 cm height error.

## Extraction (PlaneExtractor)

| Step | Rule | Default |
|---|---|---|
| Inlier band | per point: clamp(k sigma(range), 2 cm, 0.60 m) | k = 2.5 |
| Far points | points with sigma > 0.5 m are left out while they are under half the cloud | 0.5 m |
| Weights | hits x (2 cm / band)^2, i.e. hits / sigma^2: near, often-seen voxels dominate | |
| RANSAC | 300 hypotheses on a 4000-point sample. One third each: free 3-point (snapped when within 15 deg of vertical or horizontal), vertical through 2 points, horizontal through 3. Scored by weighted MSAC, sum w (1 - (r / band)^2). Oblique hypotheses score x 0.5 | |
| Robust refit | 3 rounds of weighted least squares with a Tukey biweight at 2 sigma. Corner points of the adjacent surface, which all sit on the room side, pull much less | |
| Tail claim | once a plane is accepted, the remaining points within 3.5 sigma of it and inside its extent (+20 cm) are removed with it but not fitted. Otherwise a big noisy wall leaves a few hundred tail points that become a phantom parallel plane | 3.5 sigma |
| Gravity prior | ARCore world +Y is gravity. Within 15 deg of vertical, the plane is refit exactly vertical (a 2D line fit in x, z). Within 15 deg of horizontal, it is refit exactly horizontal (the weighted mean height). `freeTiltDeg`, `freeRms` and `fitRms` report the free fit against the constrained one | 15 deg |
| Coplanar merge | normals within 12 deg, each centroid within max(10 cm, 2 sigma) of the other plane, and in-plane extents (2-98 % rectangles) overlapping or within 30 cm. The union is refit and the step repeats until nothing changes. A new plane is merged as soon as it is found and does not count against `maxPlanes`; only gravity-aligned planes count | |
| Classification | horizontal: FLOOR within 0.3 m + 2 sigma of the cloud's low end, CEILING within it of the high end, otherwise OTHER. Vertical: WALL. In between: OTHER | |
| Bounding check | a room boundary has one empty side. Count the points beyond max(30 cm, 3 sigma) on each side, inside the extent shrunk by the same margin. If both sides hold more than 20 % of the plane's inliers, it is furniture, a table or a speckle plane and becomes OTHER. A floor with points below it, or a ceiling with points above it, also becomes OTHER. A wall whose populated side is behind its normal is flipped, so normals point into the room even in L-shapes | 20 % |
| Floor fallback | no FLOOR: of the points no plane took, the lowest 15 cm window in the lower half of the cloud with at least minInliers / 3 points covering at least 1.5 m^2 (20 cm cells) is fitted as a horizontal floor (source FALLBACK). A thinly swept floor breaks into patches smaller than minInliers. A missing ceiling is never guessed | 1.5 m^2 |

## ARCore plane fusion (ArPlaneFusion)

ARCore fits its planes to tracked feature points. A floor polygon's height is usually within 1-2 cm, far better than
depth-from-motion at room range. `ArSessionController.trackedPlanes()` takes a snapshot of every TRACKING, non-subsumed plane
when Analyze is pressed: type, centre pose, normal, world polygon and extents. `ScanController.analyze(arPlanes)` passes it on,
and `snapshot()` (View 3D) reuses the same snapshot.

- **Floor.** The lowest HORIZONTAL_UP plane of at least 0.3 m^2.
  - A depth FLOOR within 30 cm of it moves to ARCore's height (source FUSED).
  - With no depth floor, an unclassified depth horizontal plane at that height becomes the floor. Otherwise ARCore's
    polygon becomes the floor (source ARCORE), as long as the cloud has nothing well below it.
  - If ARCore's floor is more than 30 cm BELOW the depth "floor" and covers at least 1 m^2, the depth plane was a table and
    becomes OTHER.
- **Ceiling.** The same with the highest HORIZONTAL_DOWN plane. ARCore seldom finds ceilings.
- **Walls.** Each VERTICAL plane of at least 0.25 m^2 is matched to a depth WALL: normals within 12 deg, and ARCore's centre
  within max(10 cm, 2 sigma) of the wall. A matched wall takes ARCore's normal (flattened to horizontal) and offset. An
  unmatched ARCore wall of at least 1 m^2 is added.

## Room assembly (RoomFromPlanes)

- `build(planes)`: a complete `RoomModel` or null, as before.
- `assemble(planes)`: a `RoomAssembly` in every case. It holds floor / ceiling heights (null when missing), the closed
  footprint (`outline`, available without a ceiling), the wall count, and `openSides`.
  - Room frame: taken from the walls' dominant direction (the weighted circular mean of 4 x the normal angle).
  - Open side: a side of that frame that no wall faces (within 30 deg). It is named from the scan's start: ARCore's origin
    is the first camera pose, looking down -Z, so the names are "the wall ahead of / behind / to the left of / to the right of
    where you started".
- Walls smaller than 0.5 m^2 are ignored. Parallel walls closer than 30 cm count as one.
- A footprint is rejected when it is not simple, or when a corner lies more than 2 m beyond both of its walls' observed
  extents. Such a corner is a bogus intersection of a wrong wall.

`ScanAnalysis.Incomplete(missing, partial)`. Each `missing` line is shown as "Missing: ...":
- "the floor (point the phone down and sweep it)"
- "the ceiling (tilt the phone up and sweep it; the height is not measured without it)"
- "the walls (sweep each wall from about 1.5 m away)", when no walls were found
- one line per open side, e.g. "the wall to the right of where you started"
- "the corners (the walls do not meet yet; sweep the corners)"

## Measured on the simulator (RoomScanRealismTest)

| Scene | Old extractor | New pipeline |
|---|---|---|
| 4 x 5 x 2.5 m box, walking sweep, seeds 1-3 | 2-3 floor slices + 3-5 ceiling slices fill the 8-plane budget; only 1-3 walls; no room in any seed | 4 walls + floor + ceiling |
| The owner's capture re-simulated (wall A 2.3 m, wall B 2.17 m), seeds 1-2 | 6 slices of wall A, 2 ceiling slices, no floor (the real scan: 7 slices, no floor, no ceiling) | wall A as ONE plane at 2.275 m (true 2.300), wall B as one plane, plus floor and ceiling; partial result with the open sides named |
| Box, 10 seeds | | 10 of 10 exactly 4 walls + 1 floor + 1 ceiling (acceptance: at least 9 of 10) |
| Box: area | | -0.36 to -0.83 % (acceptance +-5 %) |
| Box: height, depth only | | -0.8 to -1.7 cm (acceptance +-10 cm) |
| Box: height with ARCore floor + ceiling (1 cm error each) | | -1.9 to +3.1 cm (acceptance +-5 cm) |
| L-shape (two 3 m arms, 27 m^2), 5 seeds | | 6 walls in 4 of 5 seeds (seed 1's walk barely sees one arm end, about 1000 points); area -0.6 to -1.2 %, height -0.9 to -1.7 cm |
| Depth missed the floor, ARCore floor given | | floor from ARCore; height within 10 cm, area within 5 % |

The remaining bias is inward. Walls come out about 2 cm in, so the area is about -0.6 % and the height about -1.3 cm. It comes
from adjacent-surface points inside the wide inlier band near corners, which the Tukey refit reduces but does not remove.

Run time (desktop JVM): simulating a 400-frame scan takes about 0.45 s, and extraction on about 180 k voxels takes 0.15-0.5 s.

## Scanning advice (for the user)

- Walk, do not stand and turn. Depth-from-motion needs the phone to move sideways. Standing in one spot and spinning is
  exactly what produced the sliced wall.
- Stay within about 1.5 m of the wall you are sweeping. The error grows with the square of the distance: about 3.5 cm at 1 m,
  16 cm at 2.3 m and 49 cm at 4 m.
- Sweep the floor (point down) and the ceiling (tilt up) along the walk. Without the ceiling the room height is not measured,
  and the app says so instead of guessing.
- Let ARCore find the floor first (the floor grid appears). Its floor height anchors the room to within a couple of
  centimetres.
- Go into the corners and the ends of alcoves. A wall seen only from far away or at a grazing angle may be missed, and the
  result then names the missing side.

## Device-only (not covered by the JVM tests)

- Real depth noise and how it varies with motion, texture and light. `noiseScale` adapts to it, but the calibration rests on
  one capture.
- `ArSessionController.trackedPlanes()` against live ARCore: polygon frame, subsumption, and HORIZONTAL_DOWN availability.
- Extraction time on the phone (CPU path; GPU RANSAC scoring is only used for clouds without ranges).
