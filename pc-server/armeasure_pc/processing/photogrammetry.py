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

OPENMVS_EXES = ["InterfaceCOLMAP", "DensifyPointCloud", "ReconstructMesh", "RefineMesh", "TextureMesh"]
PINHOLE = 1  # COLMAP camera model id


def find_tool(name: str) -> Path | None:
    exe = name if name.lower().endswith(".exe") else name + ".exe"
    hit = shutil.which(exe)
    if hit:
        return Path(hit)
    if config.TOOLS_DIR.exists():
        for p in config.TOOLS_DIR.rglob(exe):
            return p
    return None


def detect_tools() -> dict:
    return {"colmap": find_tool("colmap"), **{n: find_tool(n) for n in OPENMVS_EXES}}


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
        raise JobError(f"{Path(str(args[0])).stem} {args[1] if len(args) > 1 else ''} failed "
                       f"(exit {p.returncode}). Log tail: {tail}")


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
    by_name = {Path(p["file"]).name: p for p in poses}
    con = sqlite3.connect(str(db))
    try:
        rows = con.execute("SELECT image_id, name, camera_id FROM images").fetchall()
        out = {}
        for image_id, name, cam_id in rows:
            base = Path(name).name
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


def write_text_model(model_dir: Path, db: Path, poses: list[dict]) -> int:
    """Write cameras.txt / images.txt / points3D.txt (poses = ARCore->COLMAP converted)."""
    by_name = {Path(p["file"]).name: p for p in poses}
    con = sqlite3.connect(str(db))
    try:
        cams = con.execute("SELECT camera_id, model, width, height, params FROM cameras").fetchall()
        imgs = con.execute("SELECT image_id, name, camera_id FROM images ORDER BY image_id").fetchall()
    finally:
        con.close()
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
            q, t = arcore_to_colmap(by_name[Path(name).name]["pose"])
            q, t = [float(x) for x in q], [float(x) for x in t]
            f.write(f"{iid} {q[0]!r} {q[1]!r} {q[2]!r} {q[3]!r} {t[0]!r} {t[1]!r} {t[2]!r} {cid} {name}\n\n")
    (model_dir / "points3D.txt").write_text("# empty\n")
    return len(imgs)


# ---------------------------------------------------------------- pipeline
def run(upload_zip: Path, outdir: Path, ctx: Ctx) -> dict:
    t0 = time.time()
    tools = detect_tools()
    msg = missing_tools_message(tools)
    if msg:
        raise JobError(msg)
    colmap = tools["colmap"]
    ctx.progress(0.01, "unpack")
    root = ctx.workdir / "in"
    safe_extract(upload_zip, root)
    manifest = read_manifest(root, required=False)
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

    ctx.progress(0.3, "known_pose_model")
    known = ws / "sparse_known"
    write_text_model(known, db, poses)
    tri = ws / "sparse_tri"
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

    ctx.progress(0.4, "bundle_adjust")
    ba = ws / "sparse_ba"
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

    ctx.progress(0.5, "undistort")
    dense = ws / "dense"
    run_cmd([colmap, "image_undistorter", "--image_path", image_dir, "--input_path", ba,
             "--output_path", dense, "--output_type", "COLMAP", "--max_image_size", "2400"], ctx, log)

    ctx.progress(0.55, "openmvs_interface")
    d = str(dense)
    run_cmd([tools["InterfaceCOLMAP"], "-i", d, "-o", "scene.mvs", "-w", d], ctx, log, cwd=dense)
    ctx.progress(0.6, "densify")
    run_cmd([tools["DensifyPointCloud"], "scene.mvs", "-o", "scene_dense.mvs", "-w", d,
             "--resolution-level", "0"], ctx, log, cwd=dense)
    ctx.progress(0.75, "reconstruct_mesh")
    run_cmd([tools["ReconstructMesh"], "scene_dense.mvs", "-o", "scene_mesh.mvs", "-w", d], ctx, log, cwd=dense)
    ctx.progress(0.82, "refine_mesh")
    tex_in = "scene_mesh.mvs"
    try:
        run_cmd([tools["RefineMesh"], "scene_mesh.mvs", "-o", "scene_refine.mvs", "-w", d], ctx, log, cwd=dense)
        tex_in = "scene_refine.mvs"
    except JobError:
        pass  # refinement is optional; texture the unrefined mesh
    ctx.progress(0.9, "texture")
    run_cmd([tools["TextureMesh"], tex_in, "-o", "scene_texture.mvs", "--export-type", "obj", "-w", d],
            ctx, log, cwd=dense)

    ctx.progress(0.96, "collect")
    outdir.mkdir(parents=True, exist_ok=True)
    mesh = collect_outputs(dense, outdir)
    from .objmesh import to_protocol
    measures = to_protocol(measure_obj(outdir / "mesh.obj", manifest))
    return {"measures": measures,
            "stats": {"backend": "pc", "duration_ms": int((time.time() - t0) * 1000),
                      "versions": {**versions(), "colmap": str(colmap)},
                      "notes": [f"{n_img} images, {'one shared camera' if same else 'one camera per image'}"]}}


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


def measure_obj(obj: Path, manifest: dict) -> dict:
    import open3d as o3d
    from .objmesh import _largest_component, box_mask, measure
    mesh = o3d.io.read_triangle_mesh(str(obj))
    if len(mesh.vertices) < 10:
        raise JobError("textured mesh is empty")
    box = manifest.get("box")
    if box:
        mesh.remove_vertices_by_mask(~box_mask(np.asarray(mesh.vertices), box, pad=0.01))
    mesh = _largest_component(mesh)
    pts = np.asarray(mesh.vertices)
    n, dd = np.array([0.0, 1.0, 0.0]), -float(pts[:, 1].min())
    sp = manifest.get("support_plane")
    if sp:
        k = np.linalg.norm(sp["normal"])
        n, dd = np.asarray(sp["normal"], float) / k, float(sp["d"]) / k
        if pts.mean(axis=0) @ n + dd < 0:
            n, dd = -n, -dd
    return measure(mesh, pts, n, dd, voxel=0.0)
