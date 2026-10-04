# GPU compute (package `com.example.arruler.gpu`)

Status 2026-10-03: code and JVM tests are done and green; the phone verifies itself with Settings -> Diagnostics (see "In-app self-test and the per-device gate"). The GLSL kernels and the instrumented tests have NOT been executed
anywhere yet: the PC has no hypervisor driver (`emulator -accel-check` -> "Android Emulator hypervisor driver is not
installed", exit 6; `-accel off` exits at once for the x86_64 image), so no emulator could boot. They run on the first phone
(see "Running the instrumented tests").

## What runs where

| Stage | CPU (reference and fallback) | GPU wrapper | Notes |
|---|---|---|---|
| Local-plane denoise (objscan/ObjectDenoise) | `ObjectDenoise.smooth` | `GpuDenoise.smooth` | grid rebuilt on the CPU every iteration (as the CPU does), per-point plane fit on the GPU, 1 invocation per point |
| Atlas texel sampling (texture/TextureBaker stage 4) | `GpuTextureBake.cpuSample` (same `sampleBilinear`) | `GpuTextureBake.sample` | chart packing, solid cells, gutter dilation, output mesh stay on the CPU; texel->triangle map built on the CPU |
| RANSAC hypothesis scoring (depth/PlaneExtractor) | `GpuRansac.cpuBestPlane` (copy of `PlaneExtractor.ransac`) | `GpuRansac.bestPlane` | CPU draws the triplets (same seeded RNG, same order); GPU counts inliers, one work group per hypothesis; refit stays in PlaneExtractor |
| NV21 -> ARGB | `GpuImageOps.nv21ToArgbCpu` | `GpuImageOps.nv21ToArgb` | BT.601 full range |
| Laplacian variance (texture/KeyframePolicy `Sharpness`) | `Sharpness.laplacianVariance` | `GpuImageOps.laplacianVariance` | box downsample kernel, then one invocation per row; the CPU sums the row partials in double |
| Spin ROI luma signature (`SpinRoi.signature`) | `SpinRoi.signature` | `GpuImageOps.roiSignature` | one invocation per grid cell, integer sums, identical loops |

Every wrapper returns `GpuRun(value, usedGpu, millis, fallbackReason)` and takes a `GpuContext?` (null = CPU) and an optional
`GpuProfile` (the size gate).

## ES 3.1 compute rather than Vulkan

* The app already needs ES 3.0 (`glEsVersion 0x00030000`); ES 3.1 compute is a plain upgrade on every Android 8+ GPU
  that matters, through `android.opengl.GLES31` with no native code, no NDK, no SPIR-V toolchain.
* The context is an EGL pbuffer on its own `HandlerThread` (`GpuContext`). EGL contexts are per thread, so SceneView's Filament
  context is untouched. `GpuContext.call` refuses to run on the main thread.
* Vulkan compute would need the NDK or a binding library, SPIR-V shaders built at compile time and its own memory management,
  for no gain on these workloads (all of them are bandwidth- and latency-bound, a few dispatches each). The probe still records
  `FEATURE_VULKAN_HARDWARE_LEVEL/VERSION` so the option stays visible in `GpuInfo`.
* Targets (owner, 2026-10-03): Pixel 8 Pro (Tensor G3) and Pixel 9 Pro (Tensor G4), believed to be Mali-G715 class. Unverified:
  the probe logs the real `GL_RENDERER`.

## Mali tuning applied

* 64-thread work groups everywhere (8 x 8 for 2-D kernels); nothing at 256 or more.
* std430 buffers with vec4 / ivec4 elements for points, cells, hypotheses; per-triangle parameters are one vec4.
* No atomics. The only shared memory is the RANSAC tree reduction (64 ints, `barrier()` between steps).
* Short dispatches: `GpuContext.dispatchChunked` splits 1-D launches into chunks of at most 4096 groups (262 144 threads)
  with `glFinish` between chunks; the 2-D kernels dispatch in row bands (texture 32 768 groups, NV21 16 384 groups).
  Intent: each dispatch well below ~16 ms. This is a budget to verify on the phone (the bench prints per-call ms), not a measured fact.

## CPU fallback rules

The CPU implementation runs when any of these holds, and `GpuRun.fallbackReason` says which:
1. `ctx == null` (no EGL, creation failed), or the context came up below ES 3.1 (`computeSupported == false`).
2. A `GpuProfile` was passed and says the input is below the break-even size.
3. Any `GpuException`: shader compile/link failure (log included), GL error code after a stage, buffer larger than
   `MAX_SHADER_STORAGE_BLOCK_SIZE`, dispatch larger than `MAX_COMPUTE_WORK_GROUP_COUNT`, a call that does not return within 20 s.
4. Empty inputs (nothing to do).
Non-GL exceptions (for example `IllegalStateException` for a call on the main thread) are programmer errors and propagate.

## Equivalence targets (what the instrumented tests assert)

| Kernel | Target | Why it can differ |
|---|---|---|
| Denoise | max per-point difference < 0.1 mm (1e-4 m) after 2 iterations | float32 vs double Jacobi; neighbour order and stride rule are replicated exactly; a point exactly on the ball radius can flip |
| Texture sampling | <= 2 levels per channel; the filled/unfilled texel set identical | fused multiply-add, `cMin + (px - ox)/scale` evaluated in float |
| RANSAC | chosen plane bit-identical (double equality) for 8 seeds | GPU counts in float; every hypothesis within 3 + n/4000 inliers of the GPU best is re-counted exactly in double on the CPU, then the CPU's first-best rule picks |
| NV21 -> ARGB | <= 1 level per channel | rounding |
| Laplacian variance | relative difference < 1e-4 | row partials accumulate in float |
| ROI signature | < 1e-3 luma levels | integer sums, same loops |

The JVM tests prove the pure halves: the grid returns the SAME neighbours in the SAME order as `PointGrid`; the RANSAC draw
sequence equals a verbatim copy of `PlaneExtractor.ransac` for 6 seeds and 2 sample sizes; texel map, keyframe packing, buffer
packing, break-even arithmetic.

## Timings

None measured yet (no emulator could run, see the status line). Whatever is later measured on an emulator, an RTX 3060 host
GPU says nothing about Mali: the in-app `GpuBench` on the phones is the real measurement. The instrumented test prints
`GPUTEST TIMING ...` lines (logcat tag `GPUTEST`) per kernel, CPU vs GPU, best of 3.

## Deciding GPU vs CPU: GpuBench and GpuProfile

* `GpuBench.run(ctx)`: about 1 s. Denoise on 60 000 synthetic points (4 mm noise slab, 250 000 points/m^2, 12 mm radius) on the
  GPU (compile cost, 64-point round trip = fixed cost, warm-up, best of 2), plus the CPU on 6 000 points at the same density,
  extrapolated. Returns `GpuBenchResult`: `gpuMs`, `cpuMsEstimated`, `fixedMs`, `compileMs`, `gpuScore` (million points/s),
  `speedup`.
* `GpuProfile.fromBench(result)`: per kernel `KernelCost(cpuNsPerUnit, gpuNsPerUnit, gpuFixedMs)`; `useGpu(kernel, size)` is
  true when `gpuFixedMs + size x gpuNs < 0.8 x size x cpuNs`; `breakEvenUnits(kernel)` is the threshold (null = never).
  Only denoise is measured by the bench; the other kernels use the same speed-up scaled by a nominal relative unit cost
  (`GpuKernel.relativeCost`). Replace them with real numbers via `withMeasured(kernel, KernelCost)` after the first phone run
  (the TIMING lines give the inputs).

### How processing/DeviceProfile and Router/WorkSplit should use it (not edited)

* `DeviceSignals` gets `gpuScore: Double? = null` (Mpt/s; null = not measured, 0 = no compute) next to `benchMs`; the bench
  runs once, on a worker thread, with the CPU micro-benchmark, and is cached with the profile.
* `ProfileRules.tier`: keep the tier rules as they are (they describe the CPU). Add a separate `gpuAvailable` flag and the
  `GpuProfile`; do not let a high gpuScore lift the tier (a fast GPU does not help ARCore or memory pressure).
* `Router` / `WorkSplit`: where they estimate the on-device cost of denoise / texture bake / plane extraction, divide the
  CPU estimate by `GpuProfile.costs[k]` ratio when `useGpu(k, size)`; where a stage is offloaded to the PC, compare against the
  GPU time instead of the CPU time. Never choose the GPU for a stage whose `useGpu` is false.

## Wiring plan (call sites; the other drones own these files)

1. `objscan/ObjectPipeline`: replace `ObjectDenoise.smooth(...)` with `GpuDenoise.smooth(gpuContext, ...).value`.
2. `texture/TextureBaker` (object flow): after chart packing, build a `TexelPlan` from the packed arrays and call `GpuTextureBake.sample(...)` instead of stage 4's loop (the CPU stages around it unchanged; unfilled texels are 0).
3. `depth/PlaneExtractor.ransac`: call `GpuRansac.bestPlane(ctx, points, remaining, remainingCount, iterations, ransacSample, inlierThreshold, minInliers, random)`; it needs a constructor parameter for the context (default null) since `ransac` is private today.
4. `texture/KeyframeCapture`: use `GpuImageOps.laplacianVariance` for the sharpness of each candidate frame and `GpuImageOps.roiSignature` for the spin ROI; `nv21ToArgb` where the frame is decoded for a `Keyframe`.
5. App start (or first object scan): `GpuContext.create(appContext)` once on a worker thread, `GpuBench.run` once, store the `GpuProfile` next to the device profile; `close()` on app exit.

## Running the instrumented tests

```
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.example.arruler.gpu
```
on a phone (USB debugging) or on an emulator with hardware acceleration (WHPX/AEHD, then `-gpu host`). Tests skip themselves
(JUnit assumption) when the GL context has no ES 3.1 compute. Look for the `GPUTEST` lines in logcat for GL_VERSION,
GL_RENDERER and the timings.

## In-app self-test and the per-device gate (2026-10-03)

The GPU code has not run on a GPU in development, so the phone verifies itself: Settings -> Diagnostics -> "Run GPU self-test"
(`diag/GpuSelfTest`, about 10-30 s on a worker thread with progress). It runs the same equivalence checks as the instrumented
test on the same fixtures (`gpu/GpuFixtures`, shared with androidTest; slightly smaller sizes to fit the time budget) for all six
kernels, each in its own try with a GL error check before and after. A driver exception, a GL error, a CPU fallback
(`usedGpu == false`) or an error above the tolerance below is a FAIL for that kernel, never an app crash; after a 20 s GL timeout
the remaining kernels are skipped as FAIL. The result per kernel is PASS/FAIL with the measured max error vs tolerance, CPU ms,
GPU ms and speed-up, plus `GpuBench`.

| Kernel | Error measured | Tolerance |
|---|---|---|
| DENOISE | max per-point difference, mm | 0.1 mm |
| TEXTURE_BAKE | worst channel difference, levels (a filled/unfilled mismatch = fail) | 2 levels |
| RANSAC | max abs difference of the 4 plane parameters, 4 seeds | 0 (identical) |
| YUV_CONVERT | worst channel difference, levels | 1 level |
| SHARPNESS | relative difference of the variance | 1e-4 |
| ROI_SIGNATURE | max difference, luma levels | 1e-3 |

### The gate (`gpu/GpuGate`)

The outcome is stored (SharedPreferences `gpu_gate`) as a `GpuVerification`: key = `Build.FINGERPRINT | v<versionCode> | GL_RENDERER | GL_VERSION`,
the set of PASSED kernels, and a `KernelCost` per kernel built from the measured CPU/GPU times (GPU fixed cost from the bench).
A kernel runs on the GPU only when ALL hold:
1. the Settings switch "Use GPU when verified" is on (default on);
2. a record exists whose fingerprint and app versionCode match this build (checked without GL) and whose GL_RENDERER/GL_VERSION match the lazily created context (checked on first use; a driver update therefore closes the gate);
3. that kernel PASSED on that record;
4. `GpuProfile.useGpu(kernel, size)` is true for the job size (`gpuFixedMs + size x gpuNs < 0.8 x size x cpuNs`, measured costs);
5. the caller is not on the main thread (a GL call there is refused, so the CPU runs).
Otherwise the CPU path runs unchanged. Before any self-test the default is CPU. Settings shows "GPU: verified n/6 kernels on <renderer>" or
"GPU: not verified - run Diagnostics".

Wired call sites (all through `GpuGate`, closed gate = the original code): `objscan/ObjectIsolation` denoise (`GpuGate.denoise`),
`texture/TextureBaker` stage 4 (`GpuGate.textureSample`; null = the original loop), `depth/PlaneExtractor` (constructor parameters `gpu`, `gpuPolicy`;
the three production call sites pass `GpuGate.ransacContext()` / `GpuGate.profile()`), keyframe sharpness (`KeyframeCapture`, `AndroidSpinCapture`) and the spin ROI
signature (`SpinSession`). `GpuGate.nv21ToArgb` exists but no app code decodes NV21 to ARGB today (the NV21 in `YuvConvert.toNv21` is for JPEG), so it has no call site.
`GpuContext` of the gate is separate from the self-test's and from Filament's.

Diagnostics also reports (`diag/DeviceReport`): device/SoC/build, ARCore apk, depth modes, the camera configs ARCore offers and the one `CameraConfigChooser` picks,
every Camera2 id including physical lenses (focal length, sensor size, hFOV, minimum focus in cm, lens pose, capabilities, DEPTH16 / DEPTH_POINT_CLOUD streams),
"Depth sensor (ToF): yes/no", concurrent camera ids (API 30+), the main<->ultrawide baseline in mm from LENS_POSE_TRANSLATION, GL strings and compute limits,
Vulkan level, thermal status, tier and benchmark. "Share report" saves `Download/ARMeasure/diagnostics/<model> <date>.txt` and opens a text/plain share; "Copy to clipboard" copies the same text.
The AR view is held paused while the screen is open and the survey uses a short-lived ARCore Session that is paused and closed.
