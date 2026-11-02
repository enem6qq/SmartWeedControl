# app/src/main/python/frame_u.py
#
# Erkennung des grauen Metall-U-Zaehlrahmens (v1.8).
#
# Der im Feld benutzte Zaehlrahmen ist KEIN roter Ring, sondern ein grauer
# Metallbuegel 50 x 50 cm aus drei Staeben (eine Seite offen) mit einem
# blauen Stiel an einer geschlossenen Ecke. Der rote Rahmen-Detektor in
# analysis.py konnte ihn nie finden. Dieser Detektor wurde an den Fotos des
# Feldversuchs 26.-31.10.2026 entwickelt: 254 von 255 Rahmen gefunden, in
# einer Stichprobe von 47 Bildern keine falsche Erkennung (~2.7 s/Bild auf
# einem Desktop-Kern). Nur numpy + OpenCV, Python-3.8-kompatibel (Chaquopy).
"""Detector for the grey metal U-shaped 50x50 cm counting frame (+ blue corner stick).

detect_frame(bgr) -> dict(ok, corners (4x2 float32, TL,TR,BR,BL in original px),
                          confidence 0..1, reason, kind, notes, ...)
crop_to_frame(bgr, margin) -> (square warp of the interior or None, result dict)

The corners are the intersections of the bar CENTRE lines (open side: where both
parallel bars end), so callers should crop with a small inward margin to exclude
the ~1 cm bars themselves.

Landscape photos are rotated to portrait first and the corners mapped back, so the
detector always runs on the validated geometry (portrait, work width WORK_W = 600 px;
the search is not exactly rotation-invariant, near-tie hypotheses can flip).

Pipeline:
 0. Early exit (cheap, ~0.1 s): if there is no plausible blue-stick blob AND a coarse
    line search finds no near-continuous strong bar, return ok=False before the
    expensive search. Calibrated on the field trial: all 254 reference frames pass
    the check (lowest bar score 0.057 vs. threshold QUICK_MIN_SCORE = 0.04); of 756
    frame-less photos 57 % exit early (~0.17 s instead of ~3 s): 79 % on normal
    soil, ~1 % on very dense low-saturation soil texture (30.10. after harrowing),
    which is still rejected by the full search (0 false detections).
 1. Ridge map R: the bars are thin structures that are LESS saturated than their
    surroundings (soil/leaves are saturated, grey/rusty metal is not):
    R = blackhat(HSV-S, 13px) * weight(low absolute S). A permissive variant (Rp)
    tolerates shadowed/rusty bar parts and is only used to locate bar ends.
 2. Bar candidates: max-subarray line search over all orientations (rotate R, per
    column the interval with max sum(v - bias)) -> robust to gaps where leaves
    cross the bar. Each candidate is refit by weighted least squares.
 3. Hypotheses: (a) two parallel candidates at frame distance -> the two parallel
    bars of the U, closed side at either end; (b) two perpendicular candidates
    meeting in a corner (L) -> the missing parallel bar is searched in a wide band.
    Every hypothesis re-searches the three bar lines (offset/angle scan, evidence =
    on-ridge fraction minus fraction on parallel lines beside it, i.e. texture is
    rejected) and intersects them -> two closed-side corners.
 4. Open side: both parallel bars end at the same distance (square). The cut x is
    the maximiser of the cumulative background-corrected support of both bars,
    restricted to 0.9..1.15 x closed side; fallbacks: one bar's measured extent,
    then the closed side length (pure square geometry, e.g. bars leave the image).
 5. Score: bar evidence (weakest parallel bar counts extra), contiguous run on the
    closed bar, blue stick touching a closed corner, square-ness, right angles;
    penalties for bars continuing past the corners (sub-squares of the true frame).
    confidence < MIN_CONF -> ok=False.

Work budget (v1.8 review): step 3 is capped at MAX_BUILDS hypothesis builds and
TIME_BUDGET_S seconds per image (see _hyps); without truncation the result is
identical to the validated version. crop_to_frame rejects frames that reach past
the photo (black warp fill would count as soil).

"""
import math
import time
import cv2
import numpy as np

WORK_W = 600
STR_REF = 30.0
MIN_CONF = 0.4
# Arbeitsbudget der Hypothesen-Suche (v1.8, siehe _hyps): hoechstens MAX_BUILDS
# U-Aufbauten bzw. TIME_BUDGET_S Sekunden je Bild, danach zaehlt das Beste bis
# dahin (ok=False "search budget exhausted", wenn noch keine Hypothese vorliegt).
# Gemessen an den 255 Rahmenfotos des Feldversuchs: bis zu 251 Aufbauten je
# Bild, der Sieger stand aber spaetestens nach 145 fest; mit 160 sind alle 254
# Erkennungen identisch. Rahmenlose Feldfotos brauchten hoechstens 101.
# Die Zeitgrenze ist die Notbremse fuer langsame Handys (Desktop-Kern: ~3 s
# typisch, ~10 s im ungedeckelten Extremfall); None = keine Zeitgrenze.
MAX_BUILDS = 160
TIME_BUDGET_S = 20.0
FINE = tuple(np.arange(-1.5, 1.51, 0.5))

# Fruehabbruch (Schritt 0). Kalibriert an 254 Rahmen- und 756 rahmenlosen
# Feldfotos: kleinster Balken-Score eines echten Rahmens 0.057, Schwelle mit
# Sicherheitsabstand darunter. Blauer Stiel: groesste Blau-Komponente in px
# (Arbeitsbild, Breite 600 px); echte Rahmen meist 50-800 px.
QUICK_SW = 200          # kurze Seite des Grob-Suchbilds (px)
QUICK_STEP_DEG = 3.0    # Winkelraster der Grobsuche
QUICK_BIAS = 0.8        # streng: nur nahezu durchgehende, kraeftige Balken zaehlen
QUICK_MIN_SCORE = 0.04  # Mindest-Score (Anteil der kurzen Seite)
QUICK_MIN_BLUE_PX = 20  # ab dieser Blob-Groesse gilt Blau als moeglicher Stiel


# ----------------------------------------------------------------- utilities
def _ridge_map(small, s_lim=70.0, s_soft=40.0):
    """Thin structures that are LESS saturated than their surroundings (black-hat of
    the HSV saturation channel), weighted towards absolutely low saturation.
    s_lim/s_soft: strict map (70/40) for finding bars; permissive (100/45) to follow
    shadowed / rusty bar parts when locating the bar ends."""
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    s = cv2.GaussianBlur(hsv[..., 1].astype(np.float32), (3, 3), 0)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (13, 13))
    bh = cv2.morphologyEx(s, cv2.MORPH_CLOSE, k) - s
    w = np.clip((s_lim - s) / s_soft, 0, 1)
    return bh * w


def _blue_mask(small):
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    m = cv2.inRange(hsv, (95, 110, 70), (128, 255, 255))
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    return m


def _sample(img, pts):
    h, w = img.shape[:2]
    x = np.clip(np.round(pts[:, 0]).astype(int), 0, w - 1)
    y = np.clip(np.round(pts[:, 1]).astype(int), 0, h - 1)
    return img[y, x]


def _runs(mask, max_gap):
    """Runs of True in a 1-D bool array after bridging gaps <= max_gap."""
    m = mask.copy()
    idx = np.flatnonzero(m)
    if idx.size == 0:
        return []
    runs = []
    start = prev = idx[0]
    for i in idx[1:]:
        if i - prev > max_gap + 1:
            runs.append((start, prev))
            start = i
        prev = i
    runs.append((start, prev))
    return runs


class Seg:
    __slots__ = ("p1", "p2", "fill", "len", "dir", "trunc1", "trunc2", "strength")

    def __init__(self, p1, p2, fill, strength, trunc1, trunc2):
        self.p1, self.p2 = np.asarray(p1, float), np.asarray(p2, float)
        v = self.p2 - self.p1
        self.len = float(np.hypot(*v))
        self.dir = v / max(self.len, 1e-9)
        self.fill, self.strength = fill, strength
        self.trunc1, self.trunc2 = trunc1, trunc2  # endpoint touches image border


def _fit_line_weighted(Rb, p1, p2, band=4):
    """Refit a line to ridge pixels within `band` px of segment p1-p2."""
    h, w = Rb.shape
    d = p2 - p1
    L = np.hypot(*d)
    if L < 5:
        return p1, p2
    d = d / L
    n = np.array([-d[1], d[0]])
    x0, x1 = int(max(0, min(p1[0], p2[0]) - band - 1)), int(min(w, max(p1[0], p2[0]) + band + 2))
    y0, y1 = int(max(0, min(p1[1], p2[1]) - band - 1)), int(min(h, max(p1[1], p2[1]) + band + 2))
    sub = Rb[y0:y1, x0:x1]
    ys, xs = np.nonzero(sub > 0)
    if xs.size < 10:
        return p1, p2
    P = np.stack([xs + x0, ys + y0], 1).astype(float)
    rel = P - p1
    t = rel @ d
    off = rel @ n
    sel = (np.abs(off) <= band) & (t >= -2) & (t <= L + 2)
    if sel.sum() < 10:
        return p1, p2
    P, wts = P[sel], sub[ys[sel], xs[sel]].astype(float)
    c = (P * wts[:, None]).sum(0) / wts.sum()
    Q = (P - c) * np.sqrt(wts[:, None])
    _, _, vt = np.linalg.svd(Q, full_matrices=False)
    dd = vt[0]
    if dd @ d < 0:
        dd = -dd
    # project original endpoints onto the new line
    q1 = c + ((p1 - c) @ dd) * dd
    q2 = c + ((p2 - c) @ dd) * dd
    return q1, q2


def _segments_msa(R, n_peaks=24, step_deg=1.0, sw=400, bias=0.55):
    """Max-subarray line search: for every orientation, rotate the ridge map and find
    per column the interval with maximum sum(v - bias). Robust to gaps (leaves)."""
    h, w = R.shape
    f = sw / w
    Rs = cv2.resize(R, (sw, int(round(h * f))), interpolation=cv2.INTER_AREA)
    Rs = cv2.dilate(Rs, np.ones((3, 3), np.uint8))
    v = np.clip(Rs / 30.0, 0, 1).astype(np.float32)
    hs, ws = v.shape
    D = int(math.ceil(math.hypot(hs, ws))) + 4
    cx, cy = ws / 2.0, hs / 2.0
    angles = np.arange(0, 180, step_deg)
    S = np.zeros((len(angles), D), np.float32)
    mats = []
    for k, a in enumerate(angles):
        M = cv2.getRotationMatrix2D((cx, cy), a, 1.0)
        M[0, 2] += D / 2.0 - cx
        M[1, 2] += D / 2.0 - cy
        mats.append(M)
        r = cv2.warpAffine(v, M, (D, D), flags=cv2.INTER_LINEAR, borderValue=0)
        msk = cv2.warpAffine(np.ones_like(v), M, (D, D), flags=cv2.INTER_NEAREST, borderValue=0)
        val = r - bias * msk  # outside image: 0 (neutral)
        C = np.cumsum(val, axis=0)
        C = np.vstack([np.zeros((1, D), np.float32), C])
        Mn = np.minimum.accumulate(C, axis=0)
        S[k] = (C - Mn).max(axis=0)
    segs = []
    Sw = S.copy()
    na = len(angles)
    sup_a = int(round(6 / step_deg))
    for _ in range(n_peaks):
        k, col = np.unravel_index(int(np.argmax(Sw)), Sw.shape)
        sc = Sw[k, col]
        if sc < 0.06 * ws:
            break
        # suppress neighbourhood (angles wrap around with column mirrored)
        for dk in range(-sup_a, sup_a + 1):
            kk = k + dk
            cc = col
            if kk < 0 or kk >= na:
                kk %= na
                cc = D - 1 - col
            Sw[kk, max(0, cc - 6):cc + 7] = -1
        M = mats[k]
        r = cv2.warpAffine(v, M, (D, D), flags=cv2.INTER_LINEAR, borderValue=0)
        msk = cv2.warpAffine(np.ones_like(v), M, (D, D), flags=cv2.INTER_NEAREST, borderValue=0)
        val = r[:, col] - bias * msk[:, col]
        C = np.concatenate([[0], np.cumsum(val)])
        Mn = np.minimum.accumulate(C)
        j = int(np.argmax(C - Mn))
        i = int(np.argmin(C[:j + 1]))
        Mi = cv2.invertAffineTransform(M)
        pts = np.array([[col, i, 1.0], [col, j, 1.0]])
        q = (Mi @ pts.T).T / f
        segs.append((q[0], q[1], float(sc / f)))
    return segs


def _segments(R, thr):
    h, w = R.shape
    B = (R > thr).astype(np.uint8)
    Rb = np.where(B > 0, R, 0).astype(np.float32)
    Rd = cv2.dilate(B, np.ones((5, 5), np.uint8))
    min_len = 0.15 * w
    segs = []
    for p1, p2, sc in _segments_msa(R):
        if np.hypot(*(p2 - p1)) < min_len:
            continue
        p1, p2 = _fit_line_weighted(Rb, p1, p2)
        fill = float(np.mean(_sample(Rd, np.linspace(p1, p2, 100)) > 0))
        strength = float(np.mean(_sample(R, np.linspace(p1, p2, 60))))
        m = 6
        t1 = p1[0] < m or p1[1] < m or p1[0] > w - 1 - m or p1[1] > h - 1 - m
        t2 = p2[0] < m or p2[1] < m or p2[0] > w - 1 - m or p2[1] > h - 1 - m
        segs.append(Seg(p1, p2, fill, strength, t1, t2))
    return segs, B


def _order_tl_tr_br_bl(pts):
    pts = np.asarray(pts, np.float32)
    c = pts.mean(0)
    ang = np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0])
    pts = pts[np.argsort(ang)]  # clockwise in image coords (y down)
    k = int(np.argmin(pts.sum(1)))
    return np.roll(pts, -k, axis=0)


# ------------------------------------------------------------- hypotheses
def _blue_near(blue_pts, P, r):
    """1 if a blue (stick) pixel touches corner P, decaying with distance (scale r/2.5)."""
    if blue_pts is None or len(blue_pts) == 0:
        return 0.0
    d = np.hypot(blue_pts[:, 0] - P[0], blue_pts[:, 1] - P[1])
    if (d < r).sum() < 8:  # need a real blob, not a few stray pixels
        return 0.0
    return float(math.exp(-max(0.0, d.min() - 3) / (0.4 * r)))


class Ctx:
    """Evidence maps at work resolution."""

    def __init__(self, R, thr=10.0, Rp=None):
        self.R = R
        self.Rp = cv2.dilate(Rp if Rp is not None else R, np.ones((3, 3), np.uint8))
        self.h, self.w = R.shape
        self.Rm = cv2.dilate(R, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (7, 7)))
        self.R3 = cv2.dilate(R, np.ones((3, 3), np.uint8))
        self.Rb = np.where(R > thr, R, 0).astype(np.float32)
        self.thr = thr

    def inside(self, pts, m=2):
        return ((pts[:, 0] >= m) & (pts[:, 0] <= self.w - 1 - m) &
                (pts[:, 1] >= m) & (pts[:, 1] <= self.h - 1 - m))

    def evidence(self, p, q, trim=0.04):
        """Support of a bar from p to q: fraction of (in-image) samples on a ridge,
        times strength factor. Samples outside the image are ignored
        (returned in `vis`)."""
        L = float(np.hypot(*(q - p)))
        n = max(int(L), 8)
        t = np.linspace(trim, 1 - trim, n)
        pts = p[None] + t[:, None] * (q - p)[None]
        ins = self.inside(pts)
        vis = ins.mean()
        if ins.sum() < 5:
            return 0.0, 0.0, vis
        v = _sample(self.R3, pts[ins])
        on = v > self.thr
        sup = on.mean()
        st = float(np.median(v[on])) if on.any() else 0.0
        # background: same test on parallel lines beside the bar (texture rejection)
        d = _unit(q - p)
        nrm = np.array([-d[1], d[0]])
        bg = []
        for off in (-15, -10, 10, 15):
            po = pts[ins] + off * nrm
            io = self.inside(po)
            if io.sum() >= 5:
                bg.append((_sample(self.R3, po[io]) > self.thr).mean())
        bgv = float(np.mean(bg)) if bg else 0.0
        eff = max(0.0, sup - bgv)
        return eff * min(1.0, st / STR_REF), sup, vis

    def longest_run(self, p, q, gap=4):
        """Longest contiguous on-ridge run along p-q (fraction of |pq|). A real bar gives
        long runs; a line through scattered leaf edges only short ones."""
        L = float(np.hypot(*(q - p)))
        n = max(int(L), 8)
        pts = p[None] + np.linspace(0, 1, n)[:, None] * (q - p)[None]
        on = (_sample(self.R3, pts) > self.thr) & self.inside(pts)
        rr = _runs(on, gap)
        if not rr:
            return 0.0
        return max(b - a + 1 for a, b in rr) / n

    def extent(self, P, u, Lmax, gap):
        """Distance from P along u until the ridge run ends (gaps <= gap bridged).
        Returns (dist, truncated_by_border)."""
        ts = np.arange(0, Lmax, 1.0)
        pts = P[None] + ts[:, None] * u[None]
        ins = self.inside(pts, 3)
        on = (_sample(self.Rm, pts) > self.thr) & ins
        last, miss = 0, 0
        for i in range(len(ts)):
            if not ins[i]:
                return float(last), True
            if on[i]:
                last, miss = i, 0
            else:
                miss += 1
                if miss > gap and i > 0.1 * Lmax:
                    break
        return float(last), False

    def refit(self, p, q, band=5):
        """Weighted LSQ refit; kept only if it does not lose evidence or rotate much."""
        p2, q2 = _fit_line_weighted(self.Rb, p, q, band)
        d0, d1 = _unit(q - p), _unit(q2 - p2)
        if abs(d0 @ d1) < math.cos(math.radians(4)):
            return p, q
        if self.evidence(p2, q2)[0] < 0.9 * self.evidence(p, q)[0]:
            return p, q
        return p2, q2

    def search_line(self, p, q, max_off, n_off=None, angs=(-2.5, 0, 2.5), trim=0.04):
        return self.search_lines(p, q, max_off, angs, trim, k=1)[0]

    def search_lines(self, p, q, max_off, angs=(-2.5, 0, 2.5), trim=0.04, k=3, nms=8):
        """Find the line near p-q (shifted up to max_off px along the normal, rotated by
        `angs` degrees about the midpoint) with the best background-corrected evidence.
        Vectorised: every 1-px offset row is sampled once; background rows are the
        rows +-10/15 px away."""
        d = q - p
        L = float(np.hypot(*d))
        d = d / max(L, 1e-9)
        c = (p + q) / 2
        mo = int(math.ceil(max_off))
        offs = np.arange(-mo - 15, mo + 16, 1.0)
        n = max(int(L), 8)
        t = (np.linspace(trim, 1 - trim, n) - 0.5) * L
        cands = []
        for a in angs:
            ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
            dd = np.array([ca * d[0] - sa * d[1], sa * d[0] + ca * d[1]])
            nrm = np.array([-dd[1], dd[0]])
            pts = (c[None, None] + offs[:, None, None] * nrm[None, None]
                   + t[None, :, None] * dd[None, None])
            flat = pts.reshape(-1, 2)
            ins = self.inside(flat).reshape(len(offs), n)
            v = _sample(self.R3, flat).reshape(len(offs), n)
            on = (v > self.thr) & ins
            cnt = np.maximum(ins.sum(1), 1)
            sup = on.sum(1) / cnt
            st = (v * on).sum(1) / np.maximum(on.sum(1), 1)
            k0 = 15
            core = np.arange(k0, len(offs) - k0)
            bg = 0.25 * (sup[core - 15] + sup[core - 10] + sup[core + 10] + sup[core + 15])
            eff = np.maximum(0, sup[core] - bg) * np.minimum(1.0, st[core] / STR_REF)
            eff[ins[core].sum(1) < 5] = 0
            for j in range(len(core)):
                cands.append((float(eff[j]), offs[core[j]], dd, nrm))
        cands.sort(key=lambda x: -x[0])
        out = []
        for e, o, dd, nrm in cands:
            if any(abs(o - o2) < nms for _, o2, _, _ in out):
                continue
            out.append((e, o, dd, nrm))
            if len(out) >= k:
                break
        res = []
        for e, o, dd, nrm in out:
            cc = c + o * nrm
            res.append((max(e, 0.0), cc - dd * L / 2, cc + dd * L / 2))
        if not res:
            res = [(0.0, p, q)]
        return res


def _intersect(p, d, q, e):
    A = np.array([d, -e]).T
    if abs(np.linalg.det(A)) < 1e-6:
        return None
    t, _ = np.linalg.solve(A, q - p)
    return p + t * d


def _unit(v):
    return v / max(np.hypot(*v), 1e-9)


def _build_u(ctx, P1, P2, u1, u2, blue_pts, tag, wide2=False):
    """Given approximate closed-side corners and the directions of the two parallel
    bars, refine the three bar lines and determine the open side. Returns hyp dict."""
    side0 = float(np.hypot(*(P2 - P1)))
    if side0 < 0.15 * ctx.w:
        return None
    # refine parallel bars (lines through P1 along u1 / P2 along u2)
    e1, a1, b1 = ctx.search_line(P1, P1 + u1 * side0, 8, None, FINE)
    if wide2:  # bar 2 is only predicted (L hypothesis): search a wider band
        e2, a2, b2 = ctx.search_line(P2, P2 + u2 * side0, 0.15 * side0, None,
                                     tuple(np.arange(-3, 3.01, 0.75)))
    else:
        e2, a2, b2 = ctx.search_line(P2, P2 + u2 * side0, 8, None, FINE)
    a1, b1 = ctx.refit(a1, b1)
    a2, b2 = ctx.refit(a2, b2)
    d1, d2 = _unit(b1 - a1), _unit(b2 - a2)
    if d1 @ u1 < 0:
        d1 = -d1
    if d2 @ u2 < 0:
        d2 = -d2
    # closed bar: search around P1-P2
    out = []
    # closed bar candidates: around the P1-P2 line AND around the line perpendicular
    # to the parallel bars (P1/P2 come from bar ends, which may be off when a bar
    # protrudes or is hidden by leaves -> P1-P2 can be tilted)
    um = _unit(d1 + d2)
    pm = np.array([um[1], -um[0]])
    if pm @ (P2 - P1) < 0:
        pm = -pm
    mid = (P1 + P2) / 2
    seeds = [(P1, P2), (mid - pm * side0 / 2, mid + pm * side0 / 2)]
    cands = []
    for S1, S2 in seeds:
        cands += ctx.search_lines(S1, S2, 0.25 * side0 + 4, tuple(np.arange(-4, 4.01, 0.75)), k=3)
    for ec, pc, qc in cands:
        pc, qc = ctx.refit(pc, qc)
        h = _finish_u(ctx, pc, qc, a1, d1, a2, d2, side0, blue_pts, tag)
        if h is not None:
            out.append(h)
    return out


def _joint_cut(ctx, C1, d1, C2, d2, side, lo=0.9, hi=1.15):
    """Run the cut on the strict and on the permissive ridge map and keep the later
    end: the typical error is stopping early (leaves over the bar end, shiny or
    shadowed bar tips), not overshooting."""
    xs = [x for x in (_joint_cut_map(ctx, ctx.R3, C1, d1, C2, d2, side, lo, hi),
                      _joint_cut_map(ctx, ctx.Rp, C1, d1, C2, d2, side, lo, hi))
          if x is not None]
    return max(xs) if xs else None


def _joint_cut_map(ctx, M, C1, d1, C2, d2, side, lo, hi):
    """Common end of both parallel bars (square: they end at the same distance).
    Per bar the support profile on the permissive ridge map is background-corrected
    with parallel lines beside the bar, then x maximises the cumulative
    sum of (on - bg - 0.2) over both bars."""
    ts = np.arange(0, hi * side, 1.0)
    acc = np.zeros(len(ts))
    used = 0
    win = np.ones(9) / 9
    for C, d in ((C1, d1), (C2, d2)):
        nrm = np.array([-d[1], d[0]])
        pts = C[None] + ts[:, None] * d[None]
        ins = ctx.inside(pts, 3)
        if ins[:int(1.15 * side)].mean() < 0.999:
            continue  # bar leaves the image -> its end is not observable
        on = (_sample(M, pts) > ctx.thr).astype(float)
        bg = np.mean([(_sample(M, pts + o * nrm) > ctx.thr) for o in (-12, -8, 8, 12)], 0)
        on = np.convolve(on, win, "same")
        bg = np.convolve(bg, win, "same")
        acc += on - bg - 0.2
        used += 1
    if used == 0:
        return None
    cs = np.cumsum(acc)
    i0, i1 = int(lo * side), len(ts) - 1
    k = i0 + int(np.argmax(cs[i0:i1 + 1]))
    if k >= i1 - 2:  # support continues to hi -> not a clean end
        return None
    return float(ts[k])


def _finish_u(ctx, pc, qc, a1, d1, a2, d2, side0, blue_pts, tag):
    dc = _unit(qc - pc)
    C1 = _intersect(pc, dc, a1, d1)
    C2 = _intersect(pc, dc, a2, d2)
    if C1 is None or C2 is None:
        return None
    side = float(np.hypot(*(C2 - C1)))
    if side < 0.15 * ctx.w or abs(side - side0) > 0.3 * side0:
        return None
    # open side extents
    gap = int(0.05 * side) + 3
    l1, tr1 = ctx.extent(C1, d1, 1.6 * side, gap)
    l2, tr2 = ctx.extent(C2, d2, 1.6 * side, gap)
    notes = [tag]

    def plaus(l, tr):
        return (not tr) and 0.85 * side <= l <= 1.12 * side

    ok1, ok2 = plaus(l1, tr1), plaus(l2, tr2)
    # joint open-side search: both parallel bars end at the same distance (square);
    # pick the cut x maximising sum_{t<x}(on1+on2-1) - i.e. where the combined bar
    # support stops. Uses only bars that are inside the image up to 1.15*side.
    x = _joint_cut(ctx, C1, d1, C2, d2, side)
    if x is not None:
        x1 = x2 = x
        notes.append("cut")
    elif ok1:
        x1 = x2 = l1
        notes.append("ext2-from-1")
    elif ok2:
        x1 = x2 = l2
        notes.append("ext1-from-2")
    else:
        x1 = x2 = side
        notes.append("ext-from-closed")
    Q1, Q2 = C1 + d1 * x1, C2 + d2 * x2
    evc, supc, visc = ctx.evidence(C1, C2)
    ev1, sup1, vis1 = ctx.evidence(C1, Q1)
    ev2, sup2, vis2 = ctx.evidence(C2, Q2)
    evo, supo, viso = ctx.evidence(Q1, Q2)
    # bars must END at the closed corners: continuation beyond them is evidence
    # against this hypothesis (typical for sub-squares of the true frame)
    ext = 0.3 * side
    # (parallel bars may protrude a little past the closed bar -> start further out,
    #  lower weight)
    over = (0.25 * ctx.evidence(C1 - dc * 0.08 * side, C1 - dc * ext)[0]
            + 0.25 * ctx.evidence(C2 + dc * 0.08 * side, C2 + dc * ext)[0]
            + 0.5 * ctx.evidence(C1 - d1 * 0.15 * side, C1 - d1 * 0.4 * side)[0]
            + 0.5 * ctx.evidence(C2 - d2 * 0.15 * side, C2 - d2 * 0.4 * side)[0])
    # parallel bars continuing well past the open corners -> sub-square of the frame
    past = 0.5 * (ctx.evidence(Q1 + d1 * 0.1 * side, Q1 + d1 * 0.5 * side)[0]
                  + ctx.evidence(Q2 + d2 * 0.1 * side, Q2 + d2 * 0.5 * side)[0])
    over += past
    longp = 0.0
    # weak size prior: frame side is ~0.45-0.65 image widths in this trial
    longp += max(0.0, 0.32 - side / ctx.w) * 5
    # geometry
    perp = 1 - 0.5 * (abs(dc @ d1) + abs(dc @ d2))
    par = d1 @ d2
    sides = [side, x1, x2, float(np.hypot(*(Q2 - Q1)))]
    sq = min(sides) / max(sides)
    blue = max(_blue_near(blue_pts, C1, 0.1 * side), _blue_near(blue_pts, C2, 0.1 * side))
    evs = np.array([evc, ev1, ev2])
    vis = np.array([visc, vis1, vis2])
    runc = ctx.longest_run(C1, C2) * side / max(side, 0.5 * ctx.w)
    score = (evs.sum() + 1.0 * min(ev1, ev2) + 0.6 * runc + 0.9 * blue + 0.5 * sq + 0.5 * perp + 0.3 * par
             + 0.1 * (ok1 + ok2) + 0.3 * side / ctx.w - 0.3 * max(0, evo - 0.5)
             - 1.0 * over - longp
             - 5.0 * max(0.0, (1 - perp) - 0.09))  # corners must be roughly 90 deg
    # confidence: weakest bar matters most
    conf = (0.35 * evs.min() + 0.25 * evs.mean() + 0.15 * blue + 0.15 * sq * perp
            + 0.05 * (ok1 + ok2))
    if vis.min() < 0.5:
        conf -= 0.1
    conf -= 0.25 * over + 0.2 * longp
    corners = _order_tl_tr_br_bl([C1, C2, Q2, Q1])
    return dict(kind="U", corners=corners, score=float(score), conf=float(np.clip(conf, 0, 1)),
                side=side, blue=blue, sq=sq, evs=evs, over=over, notes=",".join(notes),
                dbg=dict(l1=round(l1 / side, 2), l2=round(l2 / side, 2), tr=(tr1, tr2),
                         evo=round(evo, 2), runc=round(runc, 2), perp=round(perp, 3)))


class _BudgetExceeded(Exception):
    pass


def _hyps(ctx, segs, blue_pts, max_builds=MAX_BUILDS, deadline=None):
    """All frame hypotheses from pairs of bar candidates.

    Work cap (v1.8): every _build_u call is expensive (3 line searches + open-side
    search), and without a cap a frameless photo with many bar-like structures took
    >10 s on a desktop core. The pairs are visited in order of the WEAKER segment's
    rank (segs come sorted by line-search score), i.e. all pairs among the k
    strongest candidates before any pair with candidate k+1, and the search stops
    after `max_builds` builds or at `deadline` (time.monotonic()).

    Without truncation the result is IDENTICAL to the validated field-trial version
    (pairs visited i-outer/j-inner, first hypothesis per rounded corner key wins):
    every hypothesis carries its rank in that original visiting order, duplicates
    keep the lowest rank and the list is returned in rank order.
    Returns (hyps, truncated)."""
    w = ctx.w
    cos10 = math.cos(math.radians(10))
    cos70 = math.cos(math.radians(70))
    best = {}                  # corner key -> hypothesis with the lowest rank
    st = dict(nb=0, pair=(0, 0), k=0)

    def start_pair(i, j):
        st["pair"], st["k"] = (i, j), 0

    def add(h):
        for pos, hh in enumerate(h or []):
            key = tuple(np.round(hh["corners"].reshape(-1) / 6).astype(int))
            rank = st["pair"] + (st["k"], pos)
            if key not in best or rank < best[key]["rank"]:
                hh["rank"] = rank
                hh["build_no"] = st["nb"]
                best[key] = hh

    def build(*args):
        if st["nb"] >= max_builds or (deadline is not None and time.monotonic() >= deadline):
            raise _BudgetExceeded()
        st["nb"] += 1
        st["k"] += 1
        return _build_u(ctx, *args)

    truncated = False
    try:
        _pairs(ctx, segs, blue_pts, w, cos10, cos70, start_pair, add, build)
    except _BudgetExceeded:
        truncated = True
    return sorted(best.values(), key=lambda x: x["rank"]), truncated


def _pairs(ctx, segs, blue_pts, w, cos10, cos70, start_pair, add, build):
    n = len(segs)
    for j in range(1, n):
        B = segs[j]
        for i in range(j):
            A = segs[i]
            start_pair(i, j)
            c = A.dir @ B.dir
            if abs(c) > cos10:
                # G1: parallel pair -> the two bars of a U
                u = _unit(A.dir + (B.dir if c > 0 else -B.dir))
                nrm = np.array([-u[1], u[0]])
                dist = abs((B.p1 - A.p1) @ nrm)
                Lm = max(A.len, B.len)
                if dist < 0.18 * w or dist < 0.55 * Lm or dist > 1.7 * Lm:
                    continue
                ta = sorted([A.p1 @ u, A.p2 @ u])
                tb = sorted([B.p1 @ u, B.p2 @ u])
                ov = min(ta[1], tb[1]) - max(ta[0], tb[0])
                if ov < 0.4 * min(A.len, B.len):
                    continue
                oa = A.p1 - (A.p1 @ u) * u  # point of line A at t=0
                ob = B.p1 - (B.p1 @ u) * u
                # closed side at either end; the bar ends disagree when one bar is
                # hidden/extended (stick, shadow), so try both bars' ends
                for end in (0, 1):
                    if end == 0:
                        ts_ = sorted({round(ta[0]), round(tb[0])})
                        dirv = u
                    else:
                        ts_ = sorted({round(ta[1]), round(tb[1])})
                        dirv = -u
                    if len(ts_) == 2 and ts_[1] - ts_[0] < 0.2 * dist:
                        ts_ = [ts_[0]] if end == 0 else [ts_[1]]
                    for t in ts_:
                        P1, P2 = oa + t * u, ob + t * u
                        add(build(P1, P2, dirv, dirv, blue_pts, "par"))
            elif abs(c) < cos70:
                # G2: perpendicular pair meeting at a corner (L) -> predict the rest
                P = _intersect(A.p1, A.dir, B.p1, B.dir)
                if P is None:
                    continue
                dA = [np.hypot(*(A.p1 - P)), np.hypot(*(A.p2 - P))]
                dB = [np.hypot(*(B.p1 - P)), np.hypot(*(B.p2 - P))]
                tol = 0.15 * max(A.len, B.len) + 6
                if min(dA) > tol or min(dB) > tol:
                    continue
                fA = A.p2 if dA[1] > dA[0] else A.p1
                fB = B.p2 if dB[1] > dB[0] else B.p1
                uA, uB = _unit(fA - P), _unit(fB - P)
                LA, LB = max(dA), max(dB)
                # closed bar = A: parallel bars along uB from P and from far end of A
                for (Lc, uc, up) in ((LA, uA, uB), (LB, uB, uA)):
                    for side in {Lc, max(LA, LB)}:
                        if side < 0.18 * w:
                            continue
                        add(build(P, P + uc * side, up, up, blue_pts, "L", True))


# ------------------------------------------------------------- early exit
def _largest_blue_blob(bm):
    n, _, stats, _ = cv2.connectedComponentsWithStats(bm, connectivity=8)
    return int(stats[1:, cv2.CC_STAT_AREA].max()) if n > 1 else 0


def _quick_bar_score(R, sw=QUICK_SW, step_deg=QUICK_STEP_DEG, bias=QUICK_BIAS):
    """Cheap upper-level check: coarse max-subarray line search with a STRICT bias,
    i.e. only near-continuous strong ridges score (dense soil texture with a mean
    ridge value below the bias does not). Returns the best score as a fraction of the
    short side. The validity mask is warped with the SAME (linear) interpolation as
    the map, otherwise interpolated border pixels would score for free along the
    image border. (R is always a portrait work image here, i.e. w = short side.)"""
    h, w = R.shape
    f = sw / float(min(h, w))
    Rs = cv2.resize(R, (max(1, int(round(w * f))), max(1, int(round(h * f)))),
                    interpolation=cv2.INTER_AREA)
    Rs = cv2.dilate(Rs, np.ones((3, 3), np.uint8))
    v = np.clip(Rs / 30.0, 0, 1).astype(np.float32)
    hs, ws = v.shape
    D = int(math.ceil(math.hypot(hs, ws))) + 4
    cx, cy = ws / 2.0, hs / 2.0
    one = np.ones_like(v)
    best = 0.0
    for a in np.arange(0, 180, step_deg):
        M = cv2.getRotationMatrix2D((cx, cy), float(a), 1.0)
        M[0, 2] += D / 2.0 - cx
        M[1, 2] += D / 2.0 - cy
        r = cv2.warpAffine(v, M, (D, D), flags=cv2.INTER_LINEAR, borderValue=0)
        msk = cv2.warpAffine(one, M, (D, D), flags=cv2.INTER_LINEAR, borderValue=0)
        C = np.cumsum(r - bias * msk, axis=0)
        C = np.vstack([np.zeros((1, D), np.float32), C])
        best = max(best, float((C - np.minimum.accumulate(C, axis=0)).max()))
    return best / float(min(hs, ws))


# ------------------------------------------------------------------- public
def _empty_result(reason):
    return dict(ok=False, corners=None, confidence=0.0, reason=reason, kind=None)


def detect_frame(bgr, debug=False):
    """Find the grey U-frame. Returns dict(ok, corners, confidence, reason, kind, ...);
    corners (TL,TR,BR,BL, float32, original px) are set whenever a best hypothesis
    exists (also when ok=False because of low confidence). Landscape images are
    rotated to portrait (90 deg clockwise) and the result is mapped back."""
    if bgr is None or bgr.ndim != 3 or min(bgr.shape[:2]) < 32:
        return _empty_result("image too small")
    H0, W0 = bgr.shape[:2]
    if W0 <= H0:
        return _detect_portrait(bgr, debug)
    res = _detect_portrait(np.ascontiguousarray(np.rot90(bgr, -1)), debug)

    def back(pts):
        # rot90(-1): original (x, y) -> rotated (H0-1-y, x); inverse: (x', y') -> (y', H0-1-x')
        pts = np.asarray(pts, np.float32).reshape(-1, 2)
        return np.stack([pts[:, 1], (H0 - 1) - pts[:, 0]], axis=1).astype(np.float32)

    if res.get("corners") is not None:
        res["corners"] = _order_tl_tr_br_bl(back(res["corners"]))
    if "segs" in res:
        res["segs"] = [(back(p1)[0], back(p2)[0]) for p1, p2 in res["segs"]]
    res["rotated"] = True
    return res


def _detect_portrait(bgr, debug=False):
    H0, W0 = bgr.shape[:2]
    scale = WORK_W / float(W0)
    small = cv2.resize(bgr, (WORK_W, max(1, int(round(H0 * scale)))), interpolation=cv2.INTER_AREA)
    R = _ridge_map(small)
    bm = _blue_mask(small)
    blue_px = _largest_blue_blob(bm)
    if blue_px < QUICK_MIN_BLUE_PX:
        qs = _quick_bar_score(R)
        if qs < QUICK_MIN_SCORE:
            res = _empty_result("early exit: no blue stick, no strong bar (score %.3f)" % qs)
            res["early_exit"] = True
            return res
    ctx = Ctx(R, Rp=_ridge_map(small, 100.0, 45.0))
    ys, xs = np.nonzero(bm)
    blue_pts = np.stack([xs, ys], 1).astype(float) if xs.size else None
    res = _empty_result("")
    segs, _ = _segments(R, 10.0)
    deadline = None if TIME_BUDGET_S is None else time.monotonic() + TIME_BUDGET_S
    hyps, truncated = _hyps(ctx, segs, blue_pts, MAX_BUILDS, deadline)
    res["truncated"] = truncated
    if debug:
        res["segs"] = [(sg.p1 / scale, sg.p2 / scale) for sg in segs]
    if not hyps:
        res["reason"] = ("search budget exhausted, no frame hypothesis" if truncated
                         else "no frame hypothesis (%d bar candidates)" % len(segs))
        return res
    hyps.sort(key=lambda x: -x["score"])
    best = hyps[0]
    corners = best["corners"] / scale
    res.update(corners=corners.astype(np.float32), confidence=round(best["conf"], 3),
               kind=best["kind"], notes=best["notes"], blue=best["blue"],
               build_no=best["build_no"],
               evs=[round(float(e), 2) for e in best["evs"]])
    inside = np.all((corners[:, 0] >= -0.02 * W0) & (corners[:, 0] <= 1.02 * W0) &
                    (corners[:, 1] >= -0.02 * H0) & (corners[:, 1] <= 1.02 * H0))
    res["inside"] = bool(inside)
    ev = "ev=" + "/".join("%.2f" % e for e in best["evs"])
    if "dbg" in best:
        dd = best["dbg"]
        ev += " bars=%.2f%s/%.2f%s" % (dd["l1"], "T" if dd["tr"][0] else "",
                                       dd["l2"], "T" if dd["tr"][1] else "")
    if best["conf"] < MIN_CONF:
        res["reason"] = "low confidence %s %s" % (ev, best["notes"])
    else:
        res["ok"] = True
        res["reason"] = "%s %s%s" % (best["notes"], ev, "" if inside else " partly-outside")
    return res


# Anteil des Ausschnitts, der hoechstens ausserhalb des Fotos liegen darf
# (Interpolationssaum an der Bildkante). Mehr = Rahmen ragt aus dem Bild: die
# schwarze Fuellung wuerde sonst als Boden mitgezaehlt.
MAX_OUTSIDE_FRACTION = 0.002


def crop_to_frame(bgr, margin=0.0):
    """Square perspective warp of the frame interior (side = mean corner distance,
    i.e. original resolution), cropped by `margin` x side on every edge.
    Returns (crop or None, detect_frame result).

    A frame that reaches past the photo (e.g. open side cut off; detect_frame then
    still reports ok=True with inside=False) is REJECTED here: warpPerspective would
    fill the missing part with black, which counts as soil in the coverage
    denominator and shifts the soilref soil reference. The check warps a validity
    mask with the same homography, so it is exact for the cropped area."""
    r = detect_frame(bgr)
    if not r["ok"]:
        return None, r
    quad = r["corners"].astype(np.float32)
    side = int(round(np.mean([np.linalg.norm(quad[i] - quad[(i + 1) % 4]) for i in range(4)])))
    m = int(side * margin)
    if side - 2 * m < 16:
        r = dict(r, ok=False, reason="frame too small after margin")
        return None, r
    dst = np.array([[0, 0], [side, 0], [side, side], [0, side]], np.float32)
    H = cv2.getPerspectiveTransform(quad, dst)
    valid = cv2.warpPerspective(np.full(bgr.shape[:2], 255, np.uint8), H, (side, side),
                                flags=cv2.INTER_NEAREST, borderValue=0)
    outside = float(np.mean(valid[m:side - m, m:side - m] == 0))
    if outside > MAX_OUTSIDE_FRACTION:
        r = dict(r, ok=False, outside_fraction=round(outside, 4),
                 reason="frame partly outside image (%.1f %% of the interior missing)"
                        % (outside * 100))
        return None, r
    warp = cv2.warpPerspective(bgr, H, (side, side))
    return np.ascontiguousarray(warp[m:side - m, m:side - m]), r
