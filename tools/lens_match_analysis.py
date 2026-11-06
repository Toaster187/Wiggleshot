#!/usr/bin/env python3
"""Independent offline check of Wiggleshot's lens matching.

Uses SIFT features + RANSAC (a completely different method from the app's direct
alignment) to measure the similarity transform between two lenses and compares it with
what the app reported.

Usage:
  # Diagnostics folder written by the app (Einstellungen -> Diagnose-Modus):
  python3 tools/lens_match_analysis.py capture "Download/Wiggleshot/capture_20261106_120000"

  # Folder written by the HAL zoom test (Einstellungen -> HAL-Test):
  python3 tools/lens_match_analysis.py halprobe "Download/Wiggleshot/hal_probe_20261106_120000"

  # Any two photos, e.g. taken with the Pixel camera app at 1x and 0.5x from a tripod:
  python3 tools/lens_match_analysis.py pair photo_1x.jpg photo_0.5x.jpg

All transforms use the app's convention: they map a primary pixel (x, y) to the secondary
pixel  xs = a*x - b*y + tx,  ys = b*x + a*y + ty  (pixel centres at +0.5).

Requires: pip install opencv-python-headless numpy
"""
import argparse
import json
import math
import os
import sys

import cv2
import numpy as np

WORK_MAX = 2000  # longest edge used for feature detection


def load(path, rotation_cw=0):
    """Loads an image upright. App frames are sensor oriented and need rotation_cw."""
    flags = cv2.IMREAD_COLOR if rotation_cw == 0 else (cv2.IMREAD_COLOR | cv2.IMREAD_IGNORE_ORIENTATION)
    img = cv2.imread(path, flags)
    if img is None:
        sys.exit(f"cannot read {path}")
    rot = {90: cv2.ROTATE_90_CLOCKWISE, 180: cv2.ROTATE_180, 270: cv2.ROTATE_90_COUNTERCLOCKWISE}
    if rotation_cw % 360 in rot:
        img = cv2.rotate(img, rot[rotation_cw % 360])
    return img


def similarity_to_dict(m):
    a, b, tx, ty = m
    return {
        "a": a, "b": b, "tx": tx, "ty": ty,
        "scale": math.hypot(a, b),
        "rotationDegrees": math.degrees(math.atan2(b, a)),
    }


def map_pt(m, x, y):
    a, b, tx, ty = m
    return a * x - b * y + tx, b * x + a * y + ty


def corner_deviation(m1, m2, w, h):
    """Largest corner displacement between two transforms, in primary pixels."""
    worst = 0.0
    scale = math.hypot(m1[0], m1[1])
    for x, y in [(0, 0), (w, 0), (0, h), (w, h), (w / 2, h / 2)]:
        x1, y1 = map_pt(m1, x, y)
        x2, y2 = map_pt(m2, x, y)
        worst = max(worst, math.hypot(x1 - x2, y1 - y2))
    return worst / scale


def measure(primary, secondary, label=""):
    """SIFT + RANSAC similarity, primary px -> secondary px in full resolution."""
    def prep(img):
        f = min(1.0, WORK_MAX / max(img.shape[:2]))
        g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        g = cv2.resize(g, None, fx=f, fy=f, interpolation=cv2.INTER_AREA) if f < 1 else g
        return g, f

    gp, fp = prep(primary)
    gs, fs = prep(secondary)
    sift = cv2.SIFT_create(nfeatures=8000)
    kp1, d1 = sift.detectAndCompute(gp, None)
    kp2, d2 = sift.detectAndCompute(gs, None)
    if d1 is None or d2 is None or len(kp1) < 10 or len(kp2) < 10:
        print(f"{label}: not enough features")
        return None
    matches = cv2.BFMatcher(cv2.NORM_L2).knnMatch(d1, d2, k=2)
    good = [m for m, n in (p for p in matches if len(p) == 2) if m.distance < 0.75 * n.distance]
    if len(good) < 12:
        print(f"{label}: only {len(good)} matches")
        return None
    # Feature coordinates are pixel centres; convert to the continuous convention (+0.5)
    # and to full resolution.
    p = np.float32([kp1[m.queryIdx].pt for m in good]) + 0.5
    s = np.float32([kp2[m.trainIdx].pt for m in good]) + 0.5
    p /= fp
    s /= fs
    m, inliers = cv2.estimateAffinePartial2D(p, s, method=cv2.RANSAC, ransacReprojThreshold=3.0 / fs,
                                             maxIters=20000, confidence=0.999)
    if m is None:
        print(f"{label}: RANSAC failed")
        return None
    inl = inliers.ravel().astype(bool)
    # Least squares refit on inliers.
    m, _ = cv2.estimateAffinePartial2D(p[inl], s[inl], method=cv2.LMEDS)
    a, b, tx, ty = float(m[0, 0]), float(m[1, 0]), float(m[0, 2]), float(m[1, 2])
    sim = (a, b, tx, ty)

    pred = np.stack([a * p[inl, 0] - b * p[inl, 1] + tx, b * p[inl, 0] + a * p[inl, 1] + ty], 1)
    res = (s[inl] - pred) / math.hypot(a, b)  # residual in primary px
    h, w = primary.shape[:2]
    cx, cy = w / 2, h / 2
    rvec = p[inl] - [cx, cy]
    r = np.linalg.norm(rvec, axis=1)
    radial = np.sum(res * rvec, axis=1) / np.maximum(r, 1e-6)
    rn = r / math.hypot(cx, cy)
    # Radial residual ~ k * rn^3 indicates a distortion difference between the lenses.
    k = float(np.sum(radial * rn ** 3) / max(np.sum(rn ** 6), 1e-9))
    stats = {
        "matches": len(good),
        "inliers": int(inl.sum()),
        "rmsResidualPx": float(np.sqrt(np.mean(np.sum(res ** 2, axis=1)))),
        "medianResidualPx": float(np.median(np.linalg.norm(res, axis=1))),
        "radialDistortionPxAtCorner": k,
        "horizontalResidualSpreadPx": float(np.std(res[:, 0])),
        "verticalResidualSpreadPx": float(np.std(res[:, 1])),
    }
    return sim, stats


def describe(label, sim, stats, pw, sw):
    d = similarity_to_dict(sim)
    zoom = sw / (pw * d["scale"])
    print(f"{label}: zoom {zoom:.4f}x  rot {d['rotationDegrees']:+.3f}°  t=({d['tx']:.1f}, {d['ty']:.1f})  "
          f"inliers {stats['inliers']}/{stats['matches']}  rms {stats['rmsResidualPx']:.2f}px  "
          f"radial@corner {stats['radialDistortionPxAtCorner']:+.1f}px")
    return zoom


def blend_outputs(primary, secondary, sim, out_dir, name):
    a, b, tx, ty = sim
    det = a * a + b * b
    ia, ib = a / det, -b / det
    inv = np.float32([[ia, -ib, -(ia * tx - ib * ty)], [ib, ia, -(ib * tx + ia * ty)]])
    # warpAffine maps output pixel centres; our transform uses continuous coordinates.
    shift = np.float32([[1, 0, -0.5], [0, 1, -0.5], [0, 0, 1]])
    unshift = np.float32([[1, 0, 0.5], [0, 1, 0.5], [0, 0, 1]])
    full = shift @ np.vstack([inv, [0, 0, 1]]) @ unshift
    h, w = primary.shape[:2]
    warped = cv2.warpAffine(secondary, full[:2], (w, h), flags=cv2.INTER_LINEAR)
    cv2.imwrite(os.path.join(out_dir, f"{name}_blend.jpg"), cv2.addWeighted(primary, 0.5, warped, 0.5, 0))
    diff = cv2.absdiff(cv2.cvtColor(primary, cv2.COLOR_BGR2GRAY), cv2.cvtColor(warped, cv2.COLOR_BGR2GRAY))
    cv2.imwrite(os.path.join(out_dir, f"{name}_diff.jpg"), cv2.normalize(diff, None, 0, 255, cv2.NORM_MINMAX))
    cv2.imwrite(os.path.join(out_dir, f"{name}_warped.jpg"), warped)


def cmd_pair(args):
    primary = load(args.primary, args.rot_primary)
    secondary = load(args.secondary, args.rot_secondary)
    result = measure(primary, secondary, "pair")
    if result is None:
        return 1
    sim, stats = result
    describe("SIFT", sim, stats, primary.shape[1], secondary.shape[1])
    out = os.path.dirname(os.path.abspath(args.primary))
    blend_outputs(primary, secondary, sim, out, "analysis")
    json.dump({"transform": similarity_to_dict(sim), "stats": stats}, open(os.path.join(out, "analysis.json"), "w"), indent=2)
    print(f"wrote analysis.json and analysis_*.jpg to {out}")
    return 0


def cmd_capture(args):
    meta = json.load(open(os.path.join(args.folder, "capture.json")))
    frames = meta["frames"]
    if len(frames) < 2 or not frames[0].get("file"):
        sys.exit("capture.json has no raw frames")
    primary = load(os.path.join(args.folder, frames[0]["file"]), frames[0]["rotationDegrees"])
    report = {"frames": []}
    for i, f in enumerate(frames[1:]):
        if not f.get("file"):
            continue
        secondary = load(os.path.join(args.folder, f["file"]), f["rotationDegrees"])
        result = measure(primary, secondary, f"lens {f['lensId']}")
        if result is None:
            continue
        sim, stats = result
        describe(f"SIFT  lens {f['lensId']}", sim, stats, primary.shape[1], secondary.shape[1])
        entry = {"lensId": f["lensId"], "sift": similarity_to_dict(sim), "stats": stats}
        app = next((r for r in meta.get("alignment", []) if r["lensId"] == f["lensId"]), None)
        if app:
            t = app["global"] if app.get("global") else app["transform"]
            app_sim = (t["a"], t["b"], t["tx"], t["ty"])
            dev = corner_deviation(sim, app_sim, primary.shape[1], primary.shape[0])
            print(f"APP   lens {f['lensId']}: {app['summary']}")
            print(f"      deviation SIFT vs app: {dev:.2f} px ({dev * 4000 / primary.shape[1]:.2f} px @4000)")
            entry["appDeviationPx"] = dev
        blend_outputs(primary, secondary, sim, args.folder, f"analysis_lens_{f['lensId']}")
        report["frames"].append(entry)
    json.dump(report, open(os.path.join(args.folder, "analysis.json"), "w"), indent=2)
    return 0


def cmd_halprobe(args):
    meta = json.load(open(os.path.join(args.folder, "hal_probe.json")))
    main_id, uw_id = meta["mainId"], meta["ultraWideId"]
    rot_logical = meta["logicalSensorOrientation"]
    rot_phys = meta.get("physicalSensorOrientations", {})
    rows = []
    for shot in meta["shots"]:
        z = shot["requestedZoom"]
        tag = f"z{z:.2f}"
        paths = {
            "logical": os.path.join(args.folder, f"{tag}_logical.jpg"),
            "main": os.path.join(args.folder, f"{tag}_phys_{main_id}.jpg"),
            "uw": os.path.join(args.folder, f"{tag}_phys_{uw_id}.jpg"),
        }
        if not all(os.path.exists(p) for p in paths.values()):
            print(f"{tag}: logical stream only, skipping pair analysis")
            continue
        logical = load(paths["logical"], rot_logical)
        main = load(paths["main"], rot_phys.get(main_id, rot_logical))
        uw = load(paths["uw"], rot_phys.get(uw_id, rot_logical))
        ours = measure(main, uw, f"{tag} main->uw")
        if ours is None:
            continue
        zoom_ours = describe(f"{tag} main->uw   ", ours[0], ours[1], main.shape[1], uw.shape[1])
        if shot.get("ultraWideActive"):
            hal = measure(logical, uw, f"{tag} logical->uw")
            if hal is None:
                continue
            # Rescale the HAL transform to 1.0x and to the main grid (see HalProbeRunner).
            wm, hm = main.shape[1], main.shape[0]
            wl, hl = logical.shape[1], logical.shape[0]
            k = wl / wm * z
            pre = (k, 0.0, wl / 2 - k * wm / 2, hl / 2 - k * hm / 2)
            a, b, tx, ty = hal[0]
            pa, pb, ptx, pty = pre
            hal_in_main = (a * pa - b * pb, b * pa + a * pb, a * ptx - b * pty + tx, b * ptx + a * pty + ty)
            zoom_hal = uw.shape[1] / (wm * math.hypot(hal_in_main[0], hal_in_main[1]))
            dev = corner_deviation(hal_in_main, ours[0], wm, hm) * 4000 / wm
            print(f"{tag} HAL @1.0x     : zoom {zoom_hal:.4f}x  rot {math.degrees(math.atan2(hal_in_main[1], hal_in_main[0])):+.3f}°"
                  f"  deviation vs geometric {dev:.1f} px @4000")
            rows.append({"zoom": z, "zoomHal": zoom_hal, "zoomGeometric": zoom_ours, "deviationPx4k": dev,
                         "appDeviationPx4k": shot.get("deviationPx4k")})
    json.dump({"shots": rows}, open(os.path.join(args.folder, "analysis.json"), "w"), indent=2)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("pair")
    p.add_argument("primary")
    p.add_argument("secondary")
    p.add_argument("--rot-primary", type=int, default=0, help="clockwise rotation to apply (app raw frames)")
    p.add_argument("--rot-secondary", type=int, default=0)
    c = sub.add_parser("capture")
    c.add_argument("folder")
    h = sub.add_parser("halprobe")
    h.add_argument("folder")
    args = parser.parse_args()
    return {"pair": cmd_pair, "capture": cmd_capture, "halprobe": cmd_halprobe}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
