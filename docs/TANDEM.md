# Tandem: two phones capture and process together (package `com.example.arruler.tandem`)

Two phones, no PC: both capture the object (or room) at the same moment from different sides, each isolates and
denoises ITS OWN capture, the leader registers the helper's cloud into its frame, fuses, measures and meshes, and the
heavy final steps are split between the two in proportion to measured speed. The peer also works as a plain processing
backend (the same contract the PC uses), so the existing `ProcessingService` can send a job to it.

Target devices: Pixel 11 Pro (main phone, expected leader), Pixel 9 Pro, Pixel 8 Pro. A pair is heterogeneous, so the split
is proportional to measured speed (`PeerCaps.speed()`), never 50/50 by assumption, and the faster phone leads (`LeaderChoice`).

## Files

| File | What |
|---|---|
| `PeerChannel.kt` | interface: BYTES control frames, FILE blobs with progress, events, close. Everything above it is JVM-tested with an in-memory fake |
| `PeerLink.kt` | `NearbyPeerLink`: Google Nearby Connections, `Strategy.P2P_POINT_TO_POINT`, advertise / discover, auth digits on both screens, payloads |
| `PeerPermissions.kt` | runtime permissions per API level (pure `required(sdk)` + Android `missing(context)`) |
| `TandemProtocol.kt` | versioned JSON messages (`TandemCodec`), `ClockEstimator` (NTP style, minimum-RTT sample) |
| `TandemSession.kt` | typed layer over a channel: decoded message flow, files by tag, `closed` |
| `CloudRegistration.kt`, `KcRefine.kt` | helper cloud -> leader frame: constraints, yaw search, kernel-correlation refinement, fitness / ambiguity / under-constraint |
| `WorkSplit.kt` | speed model (tier / benchmark / thermal / battery), `LeaderChoice`, `WorkSplit.plan`, `ChunkScheduler` (re-planning) |
| `SlabMesher.kt` | marching cubes in z slabs with a 1-node overlap, deterministic weld, wire formats |
| `TextureSplit.kt` | `TriangleOwnership`, `MeshSplit`, `AtlasPacker` |
| `TandemMerge.kt` | capture / partial-result types, constraints from the boxes, voxel fusion, `ResultJson` of a tandem job |
| `TandemCoordinator.kt` | leader state machine and the whole job; `TandemHelper.kt` is the follower |
| `TandemBackend.kt` | `PcLink` over the peer; `TandemRouting` (PEER policy) |
| `ThermalSampler.kt` | PowerManager thermal status and headroom into `PeerCaps` |

## Architecture and message flow

```
   leader (e.g. 11 Pro)                                         helper (e.g. 9 Pro)
   ------------------                                           -------------------
   advertise / discover  <------- Nearby, auth digits on both screens, both tap "same digits" ------->
   HELLO(device, tier, depth, version, bench, peer_id)  ---->   <----  HELLO
   ROLE(leader)                                          ---->
   CLOCK_PING x16 (t0)                                   ---->   CLOCK_PONG(t0, t1, t2)   (min-RTT sample wins)
   CLOCK_SET(offset, rtt)                                ---->                                   state CLOCK_SYNCED
   START_CAPTURE(at leader-time T, mode, box, plane, axis, yaw prior)  ---->  start at T + offset  state CAPTURING
   (both capture; each keeps ITS OWN world frame)
   STOP_CAPTURE                                          ---->
   TASK PARTIAL_OBJECT                                   ---->   isolate + denoise own capture      state PROCESSING
   isolate own capture (parallel)                        <----   FILE partial-<id>.zip (a point-job ZIP: isolated cloud, box + plane in its frame)
                                                         <----   TASK_RESULT(ok, ref)
   register helper cloud into the leader frame, fuse voxels, measure
   build the occupancy field, cut it into z slabs
   FILE slab-*.bin + TASK MC_SLAB                        ---->   marching cubes on the slab
                                                         <----   FILE piece-*.bin + TASK_RESULT
   (leader meshes slabs too; a free device takes the next slab only if it would finish it no later than the other, re-planned after every slab)
   weld slabs, final mesh
   COMPLETE(job, single_phone, summary)                  ---->                                   state MERGED
   BYE                                                   ---->
```
STATUS (thermal status, headroom, battery, speed factor) can be sent by either side at any time; the leader re-plans from it.
Unknown JSON keys are ignored, unknown message types decode as `Malformed` (counted, never fatal), a newer `v` decodes as `UnsupportedVersion`.

### Room for a third phone (11 Pro + 9 Pro + 8 Pro is a later step)

Not built, but the protocol does not need a version break for it: HELLO carries `peer_id`, ROLE carries `peer_id` and
`leader_id`, TASK has a `target` peer, STATUS a `peer_id`. All are optional and empty in the two-phone case. A third phone would
connect to the leader (star topology; Nearby `P2P_STAR` instead of `P2P_POINT_TO_POINT`), and the slab queue in
`TandemCoordinator.distributedMesh` already pulls work per device, so a third worker is another `worker(dev, run)`.
`ChunkScheduler` and `WorkSplit` are written for two devices and would take a list.

## Permissions (checked against the Nearby Connections "get started" manifest)

Manifest entries added (all additive):
```
ACCESS_WIFI_STATE, CHANGE_WIFI_STATE
BLUETOOTH, BLUETOOTH_ADMIN                       maxSdkVersion 30
ACCESS_COARSE_LOCATION                           maxSdkVersion 28
ACCESS_FINE_LOCATION                             maxSdkVersion 32
BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN      (API 31+)
NEARBY_WIFI_DEVICES  (usesPermissionFlags=neverForLocation)  (API 33+)
ACCESS_LOCAL_NETWORK                                          (API 37+, targetSdk 37)
```
Runtime requests (`PeerPermissions.required(sdk)`), API 33+ first:

| API | requested at runtime |
|---|---|
| 37+ | BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, NEARBY_WIFI_DEVICES, ACCESS_LOCAL_NETWORK |
| 33-36 | BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, NEARBY_WIFI_DEVICES |
| 31-32 | BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, ACCESS_FINE_LOCATION |
| 29-30 | ACCESS_FINE_LOCATION |
| 24-28 | ACCESS_COARSE_LOCATION |

Dependency: `com.google.android.gms:play-services-nearby:19.5.1` (latest on Google Maven, 2026-10-03) via `gradle/libs.versions.toml`
(`nearby`, `play-services-nearby`) and `implementation libs.play.services.nearby` in `app/build.gradle`.

## What is split where

* **Per phone, locally, in parallel**: depth cloud -> `ObjectPipeline` isolation, denoise (the real objscan code) -> its own partial result.
  A phone that did not capture (single capture, shared processing) skips this.
* **Exchanged**: only the compact result: the isolated cloud as a normal point-job ZIP (15 bytes / point; about 130 KB for a 200 mm
  object at 5 mm), never the raw frames.
* **Leader**: registration (below), voxel fusion on its own grid, measures, the occupancy field.
* **Final marching cubes**: the field is cut into `slabChunks` z slabs (cube layers `[k0, k1)`, node layers `k0..k1`: ONE node layer of
  overlap, which is the cut plane). Each slab is meshed in grid-index space and shifted by `k0`, so a vertex on the cut plane gets
  bit-identical coordinates from both sides; `SlabMesher.stitch` welds by exact coordinates. Test: 1, 2, 3, 7 and uneven splits of a
  41^3 sphere field give the same 6,536 triangles as the unsplit mesh, 0 bad edges, 0 flipped edges, volume equal to 1e-5; on a cube
  point cloud `SlabMesher.mesh` equals `ObjectMeshBuilder.build` (triangle count equal, volume within 1e-4). The field builder in
  `SlabMesher` is a verbatim copy of stages 1-4 of `ObjectMeshBuilder` (those helpers are private); delete the copy when objscan
  exposes `buildField`. The field travels deflated (about 40x smaller).
* **Texture**: each phone bakes the triangles ITS keyframes see best (`TriangleOwnership`: best cosine with a small distance
  penalty), so no JPEG crosses the link; the two atlases are packed side by side (`AtlasPacker`, UVs re-mapped, test checks every
  texel). The helper's keyframe poses are moved into the leader frame with `Rigid.applyToPose`. The baker call itself is the app's
  `TextureRangeHandler` (device wiring below).
* **PC**: DETAILED / photogrammetry is planned as "send to the PC" (`PlanBackend.PC`); without a PC it is `BLOCKED`. Tandem does not
  replace the PC for photogrammetry.

### Speed model and re-planning

`PeerCaps.speed()` = base x derate x measured factor. Base: `1000 ms / benchMs` from DeviceProfile (tier guess LOW 0.5 / MID 1.0 /
HIGH 1.8 without a benchmark). Derate: thermal status none/light 1.0, moderate 0.8, severe 0.55, critical 0.35, emergency 0.2;
headroom forecast (`getThermalHeadroom(10)`) below 0.7 no effect, 0.7-1.0 down to 0.8, above 1.0 down to 0.4 at 1.4; the slower of
the two; low battery not charging x0.85. A 1.6x benchmark ratio gives slab weights 0.615 / 0.385 (tested). `LeaderChoice` picks the
faster phone, ties within 5 % keep the phone in the hand, a hot fast phone loses the lead to a cool slower one.

`TandemCoordinator.distributedMesh` pulls slabs: a device takes the next slab only if it would finish it no later than the other
could, using the CURRENT speeds (`ChunkScheduler.shouldTake`; STATUS messages update the helper, `local()` the leader). Simulated
halving of one peer at t = 2 s over 24 equal slabs: static split 10.0 s (12 + 12 slabs), re-planned 7.5 s (helper keeps 9).
The cost constants in `CostModel` are provisional (JVM timings x4); calibrate on the three devices.

## Registration (`CloudRegistration`)

ARCore frames are gravity aligned, so the helper-to-leader pose has four unknowns: yaw and a translation. The shared constraints give
most of it: the up vectors fix tilt (general `Rigid.between`, so tilted frames work), the support planes fix the height, the object
axis (box base centre, a point in both frames) fixes the horizontal shift, leaving the yaw: a 1-D search over the yaw (full circle, or
the window of a yaw prior) on voxel overlap, best peaks refined by `KcRefiner`. WALK (no shared axis) matches the footprints of the
points above the support plane with a coarse (yaw, tx, tz) grid, helper cells landing inside the leader's seen area without a match
count as conflicts; the result is a coarse alignment (tests: < 4 deg, < 0.6 m; refine before relying on it).

Measured (JVM, synthetic 200 mm cube, 3 mm isotropic noise on every coordinate, leader sees top + two sides, helper the opposite
two sides + top, overlap = the top, 41 % of the helper points by an 8 mm nearest-neighbour count, random yaw / translation / 2.5 deg tilt):

| case | rotation error | centre displacement |
|---|---|---|
| 6 seeds, yaw prior +-30 deg | mean 0.25 deg, worst 0.63 deg | worst 0.93 mm |
| tilted frames (2.5 deg), 4 seeds | worst 0.48 deg | worst 0.58 mm |
| overlap < 10 % (opposite cameras, narrow cones) | rejected (reason "overlap 0.0 % is below the 10 % minimum") in 6 of 6 | |

Time 1.5-3 s per registration on the JVM (a phone is slower; it runs once per job). The rotation target of 0.5 deg is met on
average and by 5 of 6 seeds; the worst seed is 0.63 deg. That is close to what 3 mm noise allows with only a top-face overlap; the
real input is the isolated, voxel-averaged cloud, which is cleaner.

Known limits, all reported by the result instead of hidden:
* **Symmetric objects without a yaw prior** (`yawPriorUsed = false`): a cube seen from opposite sides can come out rotated by a
  multiple of 90 degrees (the mirrored fit overlaps MORE than the truth). Geometry cannot decide it. The leader passes a prior from the
  compass headings of the two ARCore frames, or from the planned positions (`helper stands 90 degrees to the right` + each phone's own
  mean camera azimuth about the axis); the note "yaw came from the geometry alone" is added when there is none. Two complete symmetric
  scans without a prior are flagged `ambiguous`.
* **Under-constrained** (`underconstrained = true`): the overlap is a strip of parallel faces (a cube's two halves meeting along x), one
  shift direction is not determined by the data and is held at the axis value. Tandem job: that direction is exactly the one the user's
  box placement decides (axis accuracy `axisSigmaM` = 12 mm).
* Points are matched only to like-oriented surface (PCA normals), the cost is symmetric (helper -> leader and leader -> helper), because
  a one-sided cost slides the helper onto the part of the leader it overlaps most.

## The full simulated job (`TandemJobTest`)

Two in-memory peers, helper clock skewed by 1.23 s, a 200 mm cube scanned at FINE (3 mm voxels, 3 mm noise, table floor and outliers
included) as two halves: leader sees x >= -20 mm, helper x <= +20 mm, each in its own ARCore frame (helper frame rotated 71 deg and
moved by 2.5 m), real `ObjectPipeline` on each side, yaw prior 12 deg off the truth:

| | length | width | height | hull volume (truth 8.000 L) |
|---|---|---|---|---|
| merged (tandem) | 204.8 mm | 204.6 mm | 200.8 mm | 8.160 L, +2.0 % |
| leader alone | 204.1 | 120.6 (sees 60 %) | 201.1 | 4.877 L, -39.0 % |
| helper alone | 204.2 | 121.7 | 201.0 | 4.897 L, -38.8 % |

* Registration: fitness 0.24, rotation error 1.5 deg, centre displacement 0.18 mm, marked `underconstrained`.
* Merged mesh: 55,376 triangles, 0 bad edges, volume 7.91 L (truth 8.00 L); slabs: leader 3, helper 1.
* Accuracy against truth is bounded by objscan, not by the merge: with one phone seeing the full 200 mm length the pipeline already
  reads 204.1 mm on this noisy scene (about +4 mm). The merge adds 0.5 mm (width 204.6 vs 204.1), the test asserts both footprint sides
  within 1.5 mm of that single-phone reading, within 6 mm of truth, height within 3 mm of truth. The brief's "within 3 mm of truth"
  is therefore met for the height and NOT for length / width on this noise level; that +4 mm is in `ObjectIsolation` /
  `ObjectMeasures` and is present without tandem.
* Clock sync: offset error 0.001-0.025 ms on the in-memory link (bound proven separately: 400 trials of 16 pings with +-2.3 s skew,
  asymmetric exponential jitter, mean error 0.35 ms, worst 2.4 ms, test asserts < 5 ms). Both phones started within 0.002 ms of the
  same instant on the leader clock.
* JVM timings of one job: isolate leader 0.5 s, wait for helper 0.7 s, register 2.9 s, fuse 5 ms, mesh 0.24 s, total 3.8 s.
  A phone is several times slower; the plan's model for a large job (270k points, 60 M-cell grid, textured, 11 Pro + 9 Pro):
  about 19 s tandem against 21 s for one phone doing all the work, dominated by the registration and transfer constants; the larger
  the grid and the texture, the better it gets. These numbers are model values until measured on the devices.

Failure handling (all tested): the helper drops while sending its result -> `single-phone`, the leader's own numbers, result note
`single-phone`; the helper drops during meshing -> its slab is re-queued on the leader, result still `tandem`; the peer is not there
at all -> `single-phone` with "peer was not reachable"; registration fails (overlap < 10 %, drift beyond the limits) or is ambiguous
-> helper data not merged, `single-phone` with the reason. The helper also gets COMPLETE(`single_phone`) and shows it.

## The peer as a processing backend

`TandemBackend : PcLink` over a `TandemSession`: `submit` sends the normal job ZIP as a FILE + `TASK PACKAGE_JOB`, `status` mirrors
TASK_PROGRESS / TASK_RESULT, `download` hands back the normal result ZIP (`stats.backend = "peer"`, `mesh.ply` included), `cancel`
sends `cancel=1`, a lost link is a network `PcLinkException` (the service already treats that as "backend unreachable"). Tested:
result equals the local `DefaultPhoneRunner` run; and `ProcessingService(pc = TandemBackend)` runs a FINE job of a LOW phone through it
(Routed > Packaging > Uploading > Queued > Running > Downloading > Done).

## Wiring plan (not done here: MainActivity / ui / objscan / texture are being edited elsewhere)

**Router** (`processing/Job.kt`, `ProcessingService.kt`):
1. `enum class Backend { PHONE, PC, PEER }`.
2. `ProcessingService(..., peer: PcLink? = null)`; `runPc(job, s, link)` takes the link as a parameter (it already is generic), and
   after `Router.decide` call `TandemRouting.decide(job.type, job.quality, s.tier, peerPaired = peer != null, dualCapture = job.dual, pcAvailable = s.pcAvailable, pcWouldRun = decision.backend == Backend.PC)`; on `PEER` run `runPc(job, s, peer!!)` and report `Backend.PEER`.
3. Auto policy (what `TandemRouting` implements): PC for DETAILED when available; PEER for QUICK / FINE when a peer is paired and the
   local tier is LOW or MID, or the capture is dual (both phones captured: always merge); otherwise the existing Router decision.
4. `RouteSignals.peerPaired` / `dual` fields; `Router.availableQualities` can mark FINE on a LOW phone as enabled when a peer is paired.

**UI** (new screens, no changes to the tandem package):
1. Pairing screen: "Pair a second phone" -> `PeerPermissions.missing(...)` request -> role buttons "I am the leader" (advertise) / "join"
   (discover); list `PeerPhase.Found`; on `PeerPhase.Confirming` show `digits` large on BOTH phones with "Same digits? Confirm" / "No"
   (`confirm()` / `reject()`); on `Connected` the leader runs `LeaderChoice.pick(local, peer)` and offers to swap roles.
2. Role display: a chip "Leader / Helper - Pixel 9 Pro - Wi-Fi Direct" and the helper's `TandemState`.
3. Synced Start: the leader's Start button calls `coordinator.pair()`, `syncClock()`, `startCapture(startRequest(...))`; both phones show a
   shared 3-2-1 countdown to the same instant (`FakeCapture` shows the contract: `TandemCapture.start(req, atLocalNs)` waits on
   `System.nanoTime()`); the helper's UI places its own box on the same object (its box + support plane in its own frame go into the
   `TandemCapture.result()`), the leader sets `coordinator.yawPrior` (compass headings: `TYPE_ROTATION_VECTOR` azimuth at session
   start for each frame, or the planned separation) before Stop.
4. Progress: leader shows its own stage plus `coordinator.peerProgress`; helper shows `TandemHelper.progress` and `state`.
5. Result: the normal result card from `TandemResult.toResultJson`, badge "2 phones" or "single-phone" (+ the first note); the helper shows
   `TandemHelper.completed`.
6. Thermal: call `ThermalSampler.caps(...)` on both phones every 5 s while processing and `TandemHelper.reportStatus()`.
7. `TandemCapture` implementation = thin adapter over the existing ObjectScanController: `start` = begin sampling at the requested time,
   `result` = voxel cloud points + hits, box, plane, quality, keyframe camera positions.
8. Texture: implement `TextureRangeHandler` with `TextureBaker.bake(MeshSplit.subMesh(...), keyframes)`; the leader side uses
   `TriangleOwnership` + `AtlasPacker`.

## Device-only items (cannot be tested on the JVM)

* `NearbyPeerLink` (Play services, real radios; bandwidth upgrade to Wi-Fi Direct; the FILE payload copy from the ParcelFileDescriptor on
  API 29+; `onDisconnected` timing). Expected sustained rate 8 MB/s is an assumption.
* Runtime permission flow on Android 14-17 and `ACCESS_LOCAL_NETWORK` behaviour with targetSdk 37.
* Calibrating `CostModel` and `SpeedModel` on the 11 Pro / 9 Pro / 8 Pro (benchmark ratio, thermal derate under real load).
* Compass / planned-position yaw prior accuracy (and a visual or ARCore Cloud Anchor alternative for symmetric objects).
* Real depth noise and overlap of two phones scanning one object; the registration numbers above are synthetic.
* Texture bake split end to end with real keyframes.
