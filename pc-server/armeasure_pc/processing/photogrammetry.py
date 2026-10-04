"""PHOTOGRAMMETRY: known-pose COLMAP + OpenMVS pipeline driver.

Stages: feature_extractor -> (patch DB cameras with the phone's per-image intrinsics) -> matcher ->
manual sparse model from ARCore poses -> point_triangulator (poses fixed) -> bundle_adjuster (focal length
refined, poses fixed) -> image_undistorter -> InterfaceCOLMAP -> DensifyPointCloud --resolution-level 0 ->
ReconstructMesh -> RefineMesh -> TextureMesh (OBJ).
"""
from __future__ import annotations

import json
import os
import shutil
import sqlite3
import struct
import subprocess
import time
from pathlib import Path

import numpy as np

from .. import config
from .common import Cancelled, Ctx, JobError, read_manifest, safe_extract, versions
from .posec import arcore_to_colmap

# DensifyPointCloud --resolution-level of the photo paths. Level 0 on the GPU is 4x denser but its stray points at the
# cube edges and base inflate the oriented box by 1.5-3 mm (measured: 203.5 mm height, +3.5 mm), level 1 measures better.
KNOWN_POSE_LEVEL = 1
OPENMVS_EXES = ["InterfaceCOLMAP", "DensifyPointCloud", "ReconstructMesh", "RefineMesh", "TextureMesh"]
PINHOLE = 1  # COLMAP camera model id


def find_tool(name: str) -> Path | None:
    exe = name if name.lower().endswith(".exe") else name + ".exe"
    hit = shutil.which(exe)
    if hit:
        return Path(hit)
    if config.TOOLS_DIR.exists():
        for p in config.TOOLS_DIR.rglob(exe):
            if ALT_MVS_DIR.name not in p.parts:        # the alternative OpenMVS build is only used for densifying
                return p
    return None


ALT_MVS_DIR = config.TOOLS_DIR / "openmvs_alt"


def find_gpu_densify() -> Path | None:
    """OpenMVS 2.3.0 DensifyPointCloud (tools/openmvs_alt/v230): its CUDA kernels carry PTX that runs on the RTX 3060
    (sm_86); the 2.4.0 release binary only holds sm_89 machine code and fails with 'named symbol not found'."""
    p = ALT_MVS_DIR / "v230" / "DensifyPointCloud.exe"
    return p if p.exists() else None


def detect_tools() -> dict:
    return {"colmap": find_tool("colmap"), **{n: find_tool(n) for n in OPENMVS_EXES}}


def with_gpu_densify(tools: dict) -> dict:
    g = find_gpu_densify()
    return {**tools, "DensifyPointCloud_gpu": g} if g else dict(tools)


def missing_tools_message(tools: dict) -> str | None:
    miss = [k for k, v in tools.items() if v is None]
    if not miss:
        return None
    names = ", ".join((m if m == "colmap" else m) + ".exe" for m in miss)
    return (f"Photogrammetry tools missing: {names}. Install COLMAP (CUDA build) and OpenMVS into "
            f"{config.TOOLS_DIR} (or put them on PATH) - see pc-server/INSTALL_PHOTOGRAMMETRY.md")


# ---------------------------------------------------------------- process helpers
def run_cmd(args: list, ctx: Ctx, log: Path, cwd: Path | None = None) -> None:
    ctx.check()
    with open(log, "ab") as lf:
        lf.write(("\n$ " + " ".join(map(str, args)) + "\n").encode())
        lf.flush()
        p = subprocess.Popen([str(a) for a in args], stdout=lf, stderr=subprocess.STDOUT, cwd=cwd,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        ctx.register_proc(p)
        try:
            while p.poll() is None:
                time.sleep(0.25)
                try:
                    ctx.check()
                except Cancelled:
                    subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)], capture_output=True)
                    raise
        finally:
            ctx.register_proc(None)
    if p.returncode != 0:
        tail = log.read_bytes()[-1500:].decode("utf-8", "replace")
        mvs = _openmvs_log_tail(args, cwd)      # OpenMVS prints to its own <Exe>-<stamp>.log, not to stdout
        if mvs:
            tail += "\nOpenMVS log tail: " + mvs
            with open(log, "ab") as lf:
                lf.write(("\n" + mvs + "\n").encode())
        raise JobError(f"{Path(str(args[0])).stem} {args[1] if len(args) > 1 else ''} failed "
                       f"(exit {p.returncode}). Log tail: {tail}")


_mvs_cpu_only = False     # set once a CUDA error shows the OpenMVS GPU kernels do not run on this machine


def run_openmvs(args: list, ctx: Ctx, log: Path, cwd: Path, notes: list | None = None,
                cpu_args: list | None = None) -> None:
    """Run an OpenMVS tool that accepts --cuda-device: GPU first; on a CUDA runtime error (the 2.4.0 CUDA build
    prints 'CUDA error ... named symbol not found (code 500)' on the RTX 3060) repeat on the CPU (-2), with
    `cpu_args` instead of `args` when given (e.g. a coarser --resolution-level, the CPU being ~20x slower)."""
    global _mvs_cpu_only
    if not _mvs_cpu_only:
        try:
            return run_cmd(args, ctx, log, cwd=cwd)
        except JobError as e:
            if "CUDA error" not in str(e):
                raise
            _mvs_cpu_only = True
    if notes is not None and not any("CUDA kernels failed" in n for n in notes):
        notes.append("OpenMVS CUDA kernels failed on this GPU/driver (CUDA error 500): OpenMVS ran on the CPU")
    run_cmd([*(cpu_args or args), "--cuda-device", "-2"], ctx, log, cwd=cwd)


def _openmvs_log_tail(args: list, cwd: Path | None, n: int = 1200) -> str:
    stem = Path(str(args[0])).stem
    if cwd is None or not stem[:1].isupper():
        return ""
    logs = sorted(Path(cwd).glob(f"{stem}-*.log"), key=lambda p: p.stat().st_mtime)
    return logs[-1].read_text(errors="replace").split("MEMORYINFO")[0][-n:] if logs else ""
    logs = sorted(Path(cwd).glob(f"{stem}-*.log"), key=lambda p: p.stat().st_mtime)
    return logs[-1].read_text(errors="replace").split("MEMORYINFO")[0][-n:] if logs else ""


_help_cache: dict = {}


def help_text(exe: Path, cmd: str) -> str:
    key = (str(exe), cmd)
    if key not in _help_cache:
        r = subprocess.run([str(exe), cmd, "-h"], capture_output=True, text=True, timeout=60)
        _help_cache[key] = r.stdout + r.stderr
    return _help_cache[key]


def pick_flag(exe: Path, cmd: str, *names: str) -> str | None:
    """First of the alternative option spellings that this COLMAP build lists in `<cmd> -h`."""
    txt = help_text(exe, cmd)
    for n in names:
        if f"--{n}" in txt:
            return f"--{n}"
    return None


# ---------------------------------------------------------------- COLMAP database / model
def _round_half(v: float) -> float:
    return round(v * 2) / 2


def load_poses(path: Path) -> list[dict]:
    """Accepts the protocol shape {"schema":1,"images":[{file,pose,fx,fy,cx,cy,width,height}]} and the older
    list shape with a nested "intrinsics" {fx,fy,cx,cy,w,h}; returns entries with nested "intrinsics"."""
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise JobError(f"poses.json unreadable: {e}")
    items = doc.get("images") if isinstance(doc, dict) else doc
    if not isinstance(items, list) or len(items) < 3:
        raise JobError("poses.json must list at least 3 images")
    out = []
    try:
        for it in items:
            i = it.get("intrinsics") or {"fx": it["fx"], "fy": it["fy"], "cx": it["cx"], "cy": it["cy"],
                                         "w": it["width"], "h": it["height"]}
            if len(it["pose"]) != 16:
                raise JobError(f"pose of {it['file']} is not 16 floats")
            out.append({"file": it["file"], "pose": it["pose"], "intrinsics": i})
    except (KeyError, TypeError) as e:
        raise JobError(f"poses.json entry malformed: {e!r}")
    return out


def intrinsics_key(i: dict) -> tuple:
    return tuple(_round_half(float(i[k])) for k in ("fx", "fy", "cx", "cy", "w", "h"))


def patch_database_cameras(db: Path, poses: list[dict], image_dir: Path) -> dict:
    """Write each image's own PINHOLE intrinsics into the COLMAP database.

    Returns {image_name: (image_id, camera_id)}.  Cameras stay one-per-image (autofocus => per-image focal
    length); when every image has the same intrinsics (to 0.5 px) the caller used a single shared camera.
    """
    from PIL import Image
    by_name = {p.get("name", Path(p["file"]).name): p for p in poses}
    con = sqlite3.connect(str(db))
    try:
        rows = con.execute("SELECT image_id, name, camera_id FROM images").fetchall()
        out = {}
        for image_id, name, cam_id in rows:
            base = name if name in by_name else Path(name).name
            p = by_name.get(base)
            if p is None:
                raise JobError(f"image {name} has no entry in poses.json")
            i = p["intrinsics"]
            with Image.open(image_dir / name) as im:
                aw, ah = im.size
            sx, sy = aw / float(i["w"]), ah / float(i["h"])   # tolerate intrinsics given for another resolution
            params = np.array([i["fx"] * sx, i["fy"] * sy, i["cx"] * sx, i["cy"] * sy], "<f8")
            con.execute("UPDATE cameras SET model=?, width=?, height=?, params=?, prior_focal_length=1 "
                        "WHERE camera_id=?", (PINHOLE, aw, ah, params.tobytes(), cam_id))
            out[base] = (image_id, cam_id)
        con.commit()
    finally:
        con.close()
    return out


def write_text_model(model_dir: Path, db: Path, poses: list[dict], only_listed: bool = False,
                     colmap_poses: bool = False) -> int:
    """Write cameras.txt / images.txt / points3D.txt. poses = ARCore camera->world (converted), or COLMAP world->camera
    when colmap_poses (entries carry "q" wxyz and "t"). An entry's "name" (default: its file's base name) is the
    COLMAP image name; only_listed skips database images without an entry."""
    by_name = {p.get("name", Path(p["file"]).name): p for p in poses}
    con = sqlite3.connect(str(db))
    try:
        cams = con.execute("SELECT camera_id, model, width, height, params FROM cameras").fetchall()
        imgs = con.execute("SELECT image_id, name, camera_id FROM images ORDER BY image_id").fetchall()
    finally:
        con.close()
    if only_listed:
        imgs = [r for r in imgs if r[1] in by_name or Path(r[1]).name in by_name]
    model_dir.mkdir(parents=True, exist_ok=True)
    used_cams = {c for _, _, c in imgs}
    with open(model_dir / "cameras.txt", "w") as f:
        f.write("# CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n")
        for cid, model, w, h, blob in cams:
            if cid not in used_cams:
                continue
            if model != PINHOLE:
                raise JobError("internal: camera not PINHOLE after patching")
            fx, fy, cx, cy = struct.unpack("<4d", blob)
            f.write(f"{cid} PINHOLE {w} {h} {fx!r} {fy!r} {cx!r} {cy!r}\n")
    with open(model_dir / "images.txt", "w") as f:
        f.write("# IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n")
        for iid, name, cid in imgs:
            e = by_name[name] if name in by_name else by_name[Path(name).name]
            q, t = (e["q"], e["t"]) if colmap_poses else arcore_to_colmap(e["pose"])
            q, t = [float(x) for x in q], [float(x) for x in t]
            f.write(f"{iid} {q[0]!r} {q[1]!r} {q[2]!r} {q[3]!r} {t[0]!r} {t[1]!r} {t[2]!r} {cid} {name}\n\n")
    (model_dir / "points3D.txt").write_text("# empty\n")
    return len(imgs)


# ---------------------------------------------------------------- pipeline
def sparse_stats(text: str) -> dict:
    """Registered images / points / mean reprojection error (px) from `colmap model_analyzer` output."""
    import re
    out = {}
    for key, pat in (("registered_images", r"Registered images: (\d+)"), ("points", r"Points: (\d+)"),
                     ("mean_reprojection_error_px", r"Mean reprojection error: ([0-9.]+)px")):
        m = re.findall(pat, text)
        if m:
            out[key] = float(m[-1]) if "." in m[-1] else int(m[-1])
    return out


def openmvs_chain(tools: dict, dense: Path, level: int, ctx: Ctx, log: Path, notes: list) -> None:
    """COLMAP undistorted workspace -> scene_texture.obj. Files: scene.mvs, scene_dense.mvs/.ply, scene_mesh.ply,
    scene_refine.ply, scene_texture.obj/.mtl/_material_00_map_Kd.jpg. (ReconstructMesh/RefineMesh write PLY only when
    the project is in interface format, so every later stage takes the mesh with -m and the cameras from the .mvs.)
    On the CPU fallback the depth-maps run one --resolution-level coarser: CPU patch-match is ~20x slower."""
    d = str(dense)
    ctx.progress(0.55, "openmvs_interface")
    run_cmd([tools["InterfaceCOLMAP"], "-i", d, "-o", "scene.mvs", "-w", d], ctx, log, cwd=dense)
    ctx.progress(0.6, "densify")
    dens = [tools["DensifyPointCloud"], "scene.mvs", "-o", "scene_dense.mvs", "-w", d]
    gpu_exe = tools.get("DensifyPointCloud_gpu")
    gpu = [gpu_exe, *dens[1:]] if gpu_exe and not _mvs_cpu_only else dens
    run_openmvs([*gpu, "--resolution-level", str(level)], ctx, log, dense, notes,
                cpu_args=[*dens, "--resolution-level", str(level + 1)])
    if gpu_exe and not _mvs_cpu_only:
        notes.append("OpenMVS 2.3.0 densify on the GPU (CUDA)")
    if _mvs_cpu_only:
        notes.append(f"OpenMVS densify on the CPU at resolution level {level + 1} (GPU path unavailable)")
    ctx.progress(0.75, "reconstruct_mesh")
    run_openmvs([tools["ReconstructMesh"], "scene_dense.mvs", "-o", "scene_mesh.mvs", "-w", d],
                ctx, log, dense, notes)
    ctx.progress(0.82, "refine_mesh")
    mesh = "scene_mesh.ply"
    try:
        run_cmd([tools["RefineMesh"], "scene_dense.mvs", "-m", mesh, "-o", "scene_refine.mvs", "--export-type",
                 "ply", "--resolution-level", str(max(level, 1)), "-w", d], ctx, log, cwd=dense)
        if (dense / "scene_refine.ply").exists():
            mesh = "scene_refine.ply"
        else:
            notes.append("RefineMesh produced no mesh; unrefined mesh textured")
    except JobError:
        notes.append("RefineMesh failed; unrefined mesh textured")    # refinement is optional
    ctx.progress(0.9, "texture")
    run_openmvs([tools["TextureMesh"], "scene_dense.mvs", "-m", mesh, "-o", "scene_texture.mvs", "--export-type",
                 "obj", "-w", d], ctx, log, dense, notes)


def known_pose_model(colmap: Path, ws: Path, db: Path, image_dir: Path, poses: list[dict], ctx: Ctx, log: Path,
                     tag: str = "", p0: float = 0.3, colmap_poses: bool = False) -> tuple[Path, dict]:
    """Poses fixed: manual sparse model -> point_triangulator -> bundle_adjuster (focal refined). Only the images that
    have an entry in `poses` take part. Returns (bundle-adjusted model dir, sparse stats)."""
    ctx.progress(p0, "known_pose_model")
    known = ws / f"sparse_known{tag}"
    write_text_model(known, db, poses, only_listed=True, colmap_poses=colmap_poses)
    tri = ws / f"sparse_tri{tag}"
    tri.mkdir(exist_ok=True)
    args = [colmap, "point_triangulator", "--database_path", db, "--image_path", image_dir,
            "--input_path", known, "--output_path", tri]
    fix = pick_flag(colmap, "point_triangulator", "Mapper.fix_existing_frames", "Mapper.fix_existing_images")
    if fix:
        args += [fix, "1"]
    ri = pick_flag(colmap, "point_triangulator", "refine_intrinsics")
    if ri:
        args += [ri, "0"]
    run_cmd(args, ctx, log)

    ctx.progress(p0 + 0.1, "bundle_adjust")
    ba = ws / f"sparse_ba{tag}"
    ba.mkdir(exist_ok=True)
    args = [colmap, "bundle_adjuster", "--input_path", tri, "--output_path", ba,
            "--BundleAdjustment.refine_focal_length", "1"]
    for names, val in ((("BundleAdjustment.refine_principal_point",), "0"),
                       (("BundleAdjustment.refine_extra_params",), "0"),
                       (("BundleAdjustment.refine_rig_from_world", "BundleAdjustment.refine_extrinsics"), "0")):
        fl = pick_flag(colmap, "bundle_adjuster", *names)
        if fl:
            args += [fl, val]
    run_cmd(args, ctx, log)

    mark = log.stat().st_size
    run_cmd([colmap, "model_analyzer", "--path", ba], ctx, log)
    return ba, sparse_stats(log.read_bytes()[mark:].decode("utf-8", "replace"))


def dense_and_measure(tools: dict, colmap: Path, image_dir: Path, model: Path, dense: Path, outdir: Path | None,
                      manifest: dict, level: int, ctx: Ctx, log: Path, notes: list, p0: float = 0.5,
                      max_image_size: int = 2400) -> dict:
    """image_undistorter -> OpenMVS chain -> (collect mesh.obj/texture.png into outdir) -> measure_obj."""
    from .objmesh import to_protocol
    ctx.progress(p0, "undistort")
    run_cmd([colmap, "image_undistorter", "--image_path", image_dir, "--input_path", model,
             "--output_path", dense, "--output_type", "COLMAP", "--max_image_size", str(max_image_size)], ctx, log)
    openmvs_chain(tools, dense, level, ctx, log, notes)
    if outdir is not None:
        outdir.mkdir(parents=True, exist_ok=True)
        obj = collect_outputs(dense, outdir)
        return to_protocol(measure_obj(outdir / "mesh.obj", manifest))
    objs = sorted(dense.glob("scene_texture*.obj"), key=lambda p: p.stat().st_mtime)
    if not objs:
        raise JobError("TextureMesh produced no OBJ")
    return to_protocol(measure_obj(objs[-1], manifest))


def run(upload_zip: Path, outdir: Path, ctx: Ctx) -> dict:
    t0 = time.time()
    tools = detect_tools()
    msg = missing_tools_message(tools)
    if msg:
        raise JobError(msg)
    tools = with_gpu_densify(tools)
    colmap = tools["colmap"]
    ctx.progress(0.01, "unpack")
    root = ctx.workdir / "in"
    safe_extract(upload_zip, root)
    manifest = read_manifest(root, required=False)
    capture = manifest.get("capture")
    if capture in ("spin", "hybrid"):
        from . import spin
        return spin.run(root, manifest, outdir, ctx, tools, t0)
    poses = load_poses(root / "poses.json")
    image_dir = root / "images"
    n_img = len(list(image_dir.glob("*.jpg"))) if image_dir.exists() else 0
    if n_img < 3:
        raise JobError("images/*.jpg: need at least 3 images")

    ws = ctx.workdir / "colmap"
    ws.mkdir(exist_ok=True)
    db, log = ws / "database.db", ws / "log.txt"
    same = len({intrinsics_key(p["intrinsics"]) for p in poses}) == 1
    gpu = pick_flag(colmap, "feature_extractor", "FeatureExtraction.use_gpu", "SiftExtraction.use_gpu")
    gpu_m = pick_flag(colmap, "exhaustive_matcher", "FeatureMatching.use_gpu", "SiftMatching.use_gpu")

    ctx.progress(0.05, "features")
    args = [colmap, "feature_extractor", "--database_path", db, "--image_path", image_dir,
            "--ImageReader.camera_model", "PINHOLE",
            "--ImageReader.single_camera" if same else "--ImageReader.single_camera_per_image", "1"]
    if gpu:
        args += [gpu, "1"]
    run_cmd(args, ctx, log)

    ctx.progress(0.15, "camera_intrinsics")
    patch_database_cameras(db, poses, image_dir)

    ctx.progress(0.2, "matching")
    matcher = "exhaustive_matcher" if n_img <= 200 else "sequential_matcher"
    args = [colmap, matcher, "--database_path", db]
    if gpu_m:
        args += [gpu_m, "1"]
    run_cmd(args, ctx, log)

    ba, sparse = known_pose_model(colmap, ws, db, image_dir, poses, ctx, log)
    notes = [f"{n_img} images, {'one shared camera' if same else 'one camera per image'}"]
    outdir.mkdir(parents=True, exist_ok=True)
    measures = dense_and_measure(tools, colmap, image_dir, ba, ws / "dense", outdir, manifest,
                                 0 if str(manifest.get("quality") or "").upper() == "DETAILED" else KNOWN_POSE_LEVEL,
                                 ctx, log, notes)
    return {"measures": measures,
            "stats": {"backend": "pc", "duration_ms": int((time.time() - t0) * 1000), "images": n_img,
                      "sparse": sparse, "versions": {**versions(), "colmap": str(colmap)}, "notes": notes}}


def collect_outputs(dense: Path, outdir: Path) -> Path:
    """Flat result entries only (protocol): mesh.obj (UVs, no mtllib) and texture.png (first texture atlas)."""
    import re
    objs = sorted(dense.glob("scene_texture*.obj"), key=lambda p: p.stat().st_mtime)
    if not objs:
        raise JobError("TextureMesh produced no OBJ")
    obj = objs[-1]
    text = re.sub(r"^(mtllib|usemtl) .*\n", "", obj.read_text(errors="replace"), flags=re.M)
    (outdir / "mesh.obj").write_text(text)
    texs = sorted(p for p in dense.iterdir()
                  if p.suffix.lower() in (".png", ".jpg") and p.stem.startswith("scene_texture"))
    if texs:
        from PIL import Image
        Image.open(texs[0]).save(outdir / "texture.png")
    return obj


SUPPORT_SEARCH_M = 0.03     # vertices this close to the phone's support plane are used to find the real floor
MIN_MARGIN_M = 0.003
OBJ_BOX_PAD_M = 0.02


def refine_support_plane(pts: np.ndarray, n: np.ndarray, d: float, tol: float = 0.002, iters: int = 300,
                         seed: int = 0) -> tuple[np.ndarray, float, float | None]:
    """The phone's support plane (ARCore plane estimate) is only good to about a centimetre, but the mesh floor sits
    exactly where the photos put it. Fit the dominant plane (RANSAC, normal within 15 deg of n) to the vertices within
    3 cm of the given plane. Returns (n, d, residual std m) of the fitted floor, or the input plane and None."""
    cand = pts[np.abs(pts @ n + d) < SUPPORT_SEARCH_M]
    if len(cand) < 200:
        return n, d, None
    rng = np.random.default_rng(seed)
    if len(cand) > 50000:
        cand = cand[rng.choice(len(cand), 50000, replace=False)]
    best, best_cnt = None, 0
    for _ in range(iters):
        s = cand[rng.choice(len(cand), 3, replace=False)]
        nn = np.cross(s[1] - s[0], s[2] - s[0])
        k = np.linalg.norm(nn)
        if k < 1e-12:
            continue
        nn /= k
        if abs(nn @ n) < np.cos(np.radians(15)):
            continue
        nn = nn if nn @ n > 0 else -nn
        dd = -float(nn @ s[0])
        cnt = int((np.abs(cand @ nn + dd) < tol).sum())
        if cnt > best_cnt:
            best, best_cnt = (nn, dd), cnt
    if best is None or best_cnt < 100:
        return n, d, None
    nn, dd = best
    inl = cand[np.abs(cand @ nn + dd) < tol]
    c = inl.mean(axis=0)
    n2 = np.linalg.svd(inl - c, full_matrices=False)[2][-1]
    n2 = n2 if n2 @ n > 0 else -n2
    d2 = -float(n2 @ c)
    return n2, d2, float(np.std(inl @ n2 + d2))


def measure_obj(obj: Path, manifest: dict) -> dict:
    """Dimensions and volume of the object in the textured mesh (which also contains the floor it stands on):
    crop to the manifest box, find the real floor plane in the mesh, drop everything within a margin of it, keep the
    largest connected piece, close the unseen bottom by projecting its lowest layer onto the floor, then measure."""
    import open3d as o3d
    from .objmesh import _largest_component, box_mask, measure
    mesh = o3d.io.read_triangle_mesh(str(obj))
    if len(mesh.vertices) < 10:
        raise JobError("textured mesh is empty")
    mesh.remove_duplicated_vertices()      # the OBJ repeats vertices along UV seams: re-join the surface pieces
    box = manifest.get("box")
    if box:
        mesh.remove_vertices_by_mask(~box_mask(np.asarray(mesh.vertices), box, pad=OBJ_BOX_PAD_M))
    pts = np.asarray(mesh.vertices)
    if len(pts) < 10:
        raise JobError("no mesh vertices inside the manifest box")
    n, dd = np.array([0.0, 1.0, 0.0]), -float(pts[:, 1].min())
    sp = manifest.get("support_plane")
    if sp:
        k = np.linalg.norm(sp["normal"])
        n, dd = np.asarray(sp["normal"], float) / k, float(sp["d"]) / k
    ref = np.asarray(box["center"], float) if box else pts.mean(axis=0)
    if ref @ n + dd < 0:
        n, dd = -n, -dd
    n, dd, resid = refine_support_plane(pts, n, dd)
    margin = max(MIN_MARGIN_M, 4 * resid) if resid is not None else 0.008
    mesh.remove_vertices_by_mask(pts @ n + dd <= margin)
    mesh = _largest_component(mesh)
    P = np.asarray(mesh.vertices)
    if len(P) < 100:
        raise JobError("almost nothing is left above the floor plane of the mesh")
    low = P[P @ n + dd < margin + 0.006]
    foot = low - np.outer(low @ n + dd, n)
    return measure(mesh, np.vstack([P, foot]), n, dd, voxel=0.0)
