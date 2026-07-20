# app/src/main/python/analysis.py
#
# Bildanalyse für SmartWeedControl (v1.5)
#
# Ablauf: Die App analysiert jedes Bild einzeln (analyze_image) und fasst
# anschließend alle Vorher-/Nachher-Bilder zu einer Gruppen-Auswertung zusammen
# (build_group_summary). Vorher- und Nachher-Gruppe dürfen unterschiedlich
# viele Bilder enthalten — die Bedeckungsgrade werden je Gruppe gemittelt.
#
# Segmentierung (Pflanze vs. Boden), wählbar in den Experten-Einstellungen:
#   - "hsv": fester Grünton-Bereich im HSV-Farbraum (klassisch)
#   - "exg": Excess-Green-Index (2g−r−b) mit automatischem Otsu-Schwellwert —
#            robust gegen wechselnde Lichtverhältnisse (Woebbecke 1995,
#            Meyer & Neto 2008); entspricht der Zielmethodik im Businessplan.
#
# Modus C (Reihen-Erkennung): Grün AUF den Saatreihen zählt als Kulturpflanze,
# Grün ZWISCHEN den Reihen als Unkraut. Wird keine klare Reihenstruktur
# gefunden, fällt die Analyse ehrlich auf die Gesamtbedeckung zurück
# (rows_detected=false).
import os, json
import cv2
import numpy as np

# -------------------------------
# Konstanten / Parameter
# -------------------------------
H_LOW_DEFAULT  = 35
H_HIGH_DEFAULT = 85
SV_MIN         = 40    # Mindest-Sättigung/-Helligkeit für "grün" (nur HSV-Methode)
MIN_SIZE       = 50    # Komponenten kleiner als 50 px werden entfernt
EXG_MIN        = 0.05  # ExG-Untergrenze: Grün muss dominieren (verhindert
                       # Fehl-Detektionen auf reinem Boden)
EXG_UNIMODAL_FRACTION = 0.85  # ab diesem Grün-Anteil gilt das Bild als
                              # vollflächig bewachsen -> Otsu überspringen

METHOD_HSV = "hsv"
METHOD_EXG = "exg"

# Maximalgrößen für RAM-schonende Verarbeitung/Anzeige
MAX_SIDE_FOR_ANALYSIS = 1600
MAX_PANEL_HEIGHT      = 720
OVERVIEW_STRIP_HEIGHT = 240
MAX_OVERVIEW_WIDTH    = 2400

# Unkrautfilter-Parameter (Modus B)
# Formfaktor = P² / (4π × A)  — 1.0 = perfekter Kreis, >1 = unregelmäßiger
WEED_FORM_FACTOR_THRESHOLD = 2.0
WEED_SIZE_FRACTION = 0.25

# Reihen-Erkennung (Modus C) — Autokorrelations-Methode
# An echten Feldbildern kalibriert: reale Reihen sind oft eingewachsen und
# unregelmäßig, deshalb wird die Periodizität über die Autokorrelation des
# Spaltenprofils gemessen (robuster als reine Peak-Abstände).
ROW_MIN_COVERAGE   = 0.3    # Mindest-Bedeckung (%) — darunter keine Reihensuche
ROW_MIN_STRENGTH   = 0.35   # Mindest-Autokorrelation am Reihenraster (0..1)
ROW_MIN_PROMINENCE = 0.35   # Mindest-Prominenz des AC-Peaks: echte Reihenraster
                            # haben tiefe Täler zwischen den Peaks (gemessen:
                            # echte Reihen >= 0.68, Rausch-/Wolkenprofile <= 0.27)
ROW_MAX_ROWS       = 15     # höchstens so viele Reihen im Bild (setzt Mindestabstand)
ROW_MIN_LAG        = 40     # absoluter kleinster Reihenabstand (px)
ROW_BAND_FRACTION  = 0.3    # Bandbreite um jede Reihe (Anteil des Reihenabstands)

# Helligkeits-Warnung: mittlere Grauwert-Differenz Vorher vs. Nachher
BRIGHTNESS_WARN_DELTA = 40.0


def chaquopy_probe():
    print("[PY] chaquopy_probe ok")


# -------------------------------
# Basis-Helfer
# -------------------------------
def _resize_to_height(img, target_h):
    h, w = img.shape[:2]
    if h == target_h:
        return img
    scale = target_h / float(h)
    new_w = max(1, int(round(w * scale)))
    interp = cv2.INTER_AREA if target_h < h else cv2.INTER_LINEAR
    return cv2.resize(img, (new_w, target_h), interpolation=interp)


def _maybe_downscale_long_side(bgr, max_side):
    h, w = bgr.shape[:2]
    long_side = max(h, w)
    if long_side <= max_side:
        return bgr
    scale = max_side / float(long_side)
    return cv2.resize(bgr, (max(1, int(round(w * scale))), max(1, int(round(h * scale)))),
                      interpolation=cv2.INTER_AREA)


def _pad_to_width(img, target_w):
    h, w = img.shape[:2]
    if w >= target_w:
        return img
    canvas = np.ones((h, target_w, 3), dtype=np.uint8) * 255
    canvas[:, :w, :] = img
    return canvas


def _ensure_detail_dir(out_dir):
    """details/-Unterordner (.nomedia => Galerie ignoriert die Zwischenbilder)."""
    detail_dir = os.path.join(out_dir, "details")
    os.makedirs(detail_dir, exist_ok=True)
    nomedia = os.path.join(detail_dir, ".nomedia")
    if not os.path.exists(nomedia):
        open(nomedia, "w").close()
    return detail_dir


def _imwrite(path, img, params=None):
    """cv2.imwrite mit Fehlerprüfung: liefert bei vollem Speicher o. Ä. still
    False — dann stünden im Ergebnis-JSON Pfade auf nicht existierende Dateien,
    die die App später als Bilder laden will."""
    ok = cv2.imwrite(path, img, params if params is not None else [])
    if not ok:
        raise IOError(f"cannot write image: {path}")
    return path


# -------------------------------
# Segmentierung Pflanze vs. Boden
# -------------------------------
def _hsv_mask(bgr, h_low, h_high):
    # Direkt BGR->HSV (identisches Ergebnis wie der Umweg über RGB,
    # spart eine Vollbild-Zwischenkopie)
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    lower = np.array([h_low, SV_MIN, SV_MIN], dtype=np.uint8)
    upper = np.array([h_high, 255, 255], dtype=np.uint8)
    return cv2.inRange(hsv, lower, upper)


def _exg_otsu_mask(bgr):
    """Excess Green (auf normalisierten RGB-Werten) + Otsu-Schwellwert.
    Der Schwellwert passt sich jedem Bild automatisch an => robust gegen
    Sonne/Wolken/Tageszeit. Zwei Schutzmechanismen gegen Otsu-Fehltrennungen
    auf (nahezu) unimodalen Histogrammen:
      - vegetationsARM:  Untergrenze EXG_MIN verhindert, dass Bodenrauschen
        als Pflanze geteilt wird.
      - vegetationsREICH: Ist fast das ganze Bild grün-dominant, würde Otsu
        den Schwellwert MITTEN in die Vegetation legen und die Bedeckung
        halbieren — dann zählt direkt die EXG_MIN-Maske."""
    f = bgr.astype(np.float32)
    b, g, r = f[:, :, 0], f[:, :, 1], f[:, :, 2]
    s = b + g + r
    s[s == 0] = 1.0
    exg = 2.0 * g / s - r / s - b / s          # Wertebereich [-1..2]
    positive = ((exg > EXG_MIN).astype(np.uint8)) * 255

    # Nahezu vollflächige Vegetation: kein Boden-Modus vorhanden => Otsu wäre
    # bedeutungslos und würde die Vegetationsverteilung selbst zerteilen.
    if float((positive > 0).mean()) >= EXG_UNIMODAL_FRACTION:
        return positive

    # Vollen Wertebereich [-1..2] auf 0..255 abbilden (kein Clipping bei +1,
    # das das Otsu-Histogramm oben stauchen würde)
    exg8 = np.clip((exg + 1.0) / 3.0 * 255.0, 0, 255).astype(np.uint8)
    _, otsu = cv2.threshold(exg8, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    return cv2.bitwise_and(otsu, positive)


def _segment_plants(bgr, method, h_low, h_high):
    if method == METHOD_EXG:
        mask = _exg_otsu_mask(bgr)
    else:
        mask = _hsv_mask(bgr, h_low, h_high)
    # Morphologisches Aufräumen: Pixelrauschen entfernen, kleine Löcher schließen
    kernel = np.ones((3, 3), np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
    return mask


def _remove_small_components(mask, min_size):
    # Vektorisiert statt Schleife: die frühere Variante machte pro BEHALTENER
    # Komponente einen Vollbild-Vergleich (labels == i) — bei dichten Beständen
    # mit tausenden Pflanzen dauerte das auf dem Gerät zweistellige Sekunden.
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    keep = stats[:, cv2.CC_STAT_AREA] >= min_size
    keep[0] = False  # Hintergrund-Label nie übernehmen
    return keep[labels].astype(np.uint8) * 255


def _coverage_percent(mask):
    return round(float((mask == 255).sum()) / mask.size * 100.0, 2)


# -------------------------------
# Modus B: Unkrautfilter (Heuristik Größe + Form)
# -------------------------------
def _weed_filter_mask(filtered_mask):
    """Entfernt kleine, kompakte Objekte (typisches Unkraut) aus der Maske.

    Zwei bewusste Design-Punkte (beide waren früher fehlerhaft):
    - Die Originalmaske wird GEFILTERT statt behaltene Konturen neu zu zeichnen:
      cv2.drawContours(..., FILLED) hatte alle Boden-Löcher innerhalb einer
      Pflanze mit ausgemalt und die Bedeckung so massiv überschätzt
      (gemessen: Ringmaske 20 % -> 44 %). Jetzt werden nur die als Unkraut
      erkannten Komponenten aus der Maske gelöscht — Löcher bleiben Löcher.
    - Referenzfläche ist der FLÄCHENGEWICHTETE Median (die Komponentengröße,
      oberhalb derer die Hälfte der gesamten Pflanzenfläche liegt) statt des
      einfachen Median: Stellt Unkraut die zahlenmäßige Mehrheit der
      Komponenten, war der einfache Median selbst eine Unkrautgröße und der
      Filter entfernte gar nichts — ausgerechnet bei hohem Unkrautdruck."""
    contours, hierarchy = cv2.findContours(filtered_mask.copy(), cv2.RETR_CCOMP,
                                           cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return np.zeros_like(filtered_mask)

    # Nur Außenkonturen betrachten (RETR_CCOMP: Ebene 0 = Objektrand,
    # Ebene 1 = Lochrand). Anders als RETR_EXTERNAL erfasst das auch
    # Objekte, die innerhalb des Lochs eines anderen Objekts liegen.
    outer = [cnt for idx, cnt in enumerate(contours)
             if hierarchy is None or hierarchy[0][idx][3] < 0]
    if not outer:
        return np.zeros_like(filtered_mask)

    areas, form_factors = [], []
    for cnt in outer:
        a = cv2.contourArea(cnt)
        p = cv2.arcLength(cnt, True)
        ff = (p ** 2) / (4.0 * np.pi * a) if (a > 0 and p > 0) else float("inf")
        areas.append(a)
        form_factors.append(ff)

    # Flächengewichteter Median als Kultur-Referenzgröße
    order = np.argsort(areas)[::-1]
    sorted_areas = np.asarray(areas, dtype=np.float64)[order]
    cum = np.cumsum(sorted_areas)
    total = cum[-1]
    if total <= 0:
        return np.zeros_like(filtered_mask)
    ref_area = float(sorted_areas[int(np.searchsorted(cum, total / 2.0))])
    size_threshold = WEED_SIZE_FRACTION * ref_area

    # Unkraut-Komponenten aus der ORIGINALMASKE löschen (Löcher unangetastet)
    weed_paint = np.zeros_like(filtered_mask)
    for idx, cnt in enumerate(outer):
        if (areas[idx] < size_threshold) and (form_factors[idx] < WEED_FORM_FACTOR_THRESHOLD):
            cv2.drawContours(weed_paint, [cnt], -1, 255, cv2.FILLED)
    return cv2.bitwise_and(filtered_mask, cv2.bitwise_not(weed_paint))


# -------------------------------
# Modus C: Reihen-Erkennung
# -------------------------------
def _detect_rows(plant_mask):
    """Findet Saatreihen über die Autokorrelation des Spaltenprofils.

    Die Winkelsuche des inneren Detektors deckt ±45° ab — grob senkrechte
    Reihen. Schlägt sie fehl, wird zusätzlich die TRANSPONIERTE Maske geprüft
    (entspricht +90°): So werden auch quer im Bild liegende Reihen erkannt,
    zusammen deckt das alle Orientierungen ab.
    Rückgabe: (ok, band_mask, info)."""
    ok, band, info = _detect_rows_oriented(plant_mask)
    if ok:
        return ok, band, info
    ok_t, band_t, info_t = _detect_rows_oriented(np.ascontiguousarray(plant_mask.T))
    if ok_t:
        info_t["transposed"] = True
        if "angle" in info_t:
            info_t["angle"] = round(float(info_t["angle"]) + 90.0, 1)
        return True, np.ascontiguousarray(band_t.T), info_t
    return ok, band, info


def _detect_rows_oriented(plant_mask):
    """Innerer Reihen-Detektor (Winkelsuche ±45°).

    Ablauf: (1) beste Drehung suchen, sodass das Spaltenprofil maximal
    "gestreift" ist; (2) Autokorrelation des Profils bestimmen — eine klare
    Spitze bei Lag L bedeutet ein regelmäßiges Reihenraster mit Abstand L;
    (3) phasenrichtig einen Kamm aus Bändern um die Reihen legen. Rotation
    auf gepolstertem Canvas, damit keine Bildecken verloren gehen.
    Rückgabe: (ok, band_mask, info)."""
    h, w = plant_mask.shape

    # Schutz: extreme Streifenformate erzeugen bei der Rotation Treppen-
    # artefakte, die eine Scheinperiodizität vortäuschen können.
    if min(h, w) < 32:
        return False, None, {"reason": "image_too_small"}

    # Schutz: Auf (fast) leeren Masken kann keine Reihenstruktur bestehen.
    coverage = float((plant_mask > 0).mean()) * 100.0
    if coverage < ROW_MIN_COVERAGE:
        return False, None, {"reason": "too_little_vegetation", "coverage": round(coverage, 2)}

    pad = int((np.hypot(h, w) - min(h, w)) / 2) + 8
    padded = cv2.copyMakeBorder(plant_mask, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=0)
    ph, pw = padded.shape

    scale = 400.0 / max(ph, pw)
    small = cv2.resize(padded, (max(1, int(pw * scale)), max(1, int(ph * scale))),
                       interpolation=cv2.INTER_NEAREST)
    sh, sw = small.shape

    def profile_score(angle):
        M = cv2.getRotationMatrix2D((sw / 2, sh / 2), angle, 1.0)
        # INTER_LINEAR beim Scoring glättet Treppenartefakte der Binärmaske
        rot = cv2.warpAffine(small, M, (sw, sh), flags=cv2.INTER_LINEAR)
        prof = rot.sum(axis=0).astype(np.float32)
        if prof.sum() == 0:
            return 0.0
        return float(prof.std() / (prof.mean() + 1e-6))

    # Grobsuche in 1.5°-Schritten, danach Feinsuche in 0.25°-Schritten:
    # Der Restfehler der Grobsuche (bis 0.75°) kann bei engen Reihen das
    # Kamm-Band um etliche Pixel versetzen.
    best_angle, best_score = 0.0, -1.0
    for a in np.arange(-45, 45.1, 1.5):
        s = profile_score(a)
        if s > best_score:
            best_score, best_angle = s, a
    for a in np.arange(best_angle - 1.5, best_angle + 1.501, 0.25):
        s = profile_score(a)
        if s > best_score:
            best_score, best_angle = s, a

    M_full = cv2.getRotationMatrix2D((pw / 2, ph / 2), best_angle, 1.0)
    rot_full = cv2.warpAffine(padded, M_full, (pw, ph), flags=cv2.INTER_NEAREST)
    prof = rot_full.sum(axis=0).astype(np.float32)

    # Nur den vegetationsbedeckten Profilabschnitt betrachten
    nz = np.nonzero(prof)[0]
    if len(nz) < 100:
        return False, None, {"reason": "profile_too_narrow"}
    seg = prof[nz[0]:nz[-1] + 1].astype(np.float64)
    seg = seg - seg.mean()
    m = len(seg)

    # UNVERZERRTE (unbiased) Autokorrelation: np.correlate summiert bei Lag L
    # nur m-L Produkte — ohne Ausgleich wäre ac[L] implizit um (m-L)/m
    # gedämpft und Bilder mit wenigen, weiten Reihen fielen systematisch
    # durch die Mindeststärke. Deshalb erst auf die Überlapplänge normieren.
    ac = np.correlate(seg, seg, mode="full")[m - 1:]
    ac = ac / np.arange(m, 0, -1, dtype=np.float64)
    if ac[0] <= 0:
        return False, None, {"reason": "flat_profile"}
    ac = ac / ac[0]

    # Realistischer Reihenabstand: mindestens ROW_MIN_LAG px und so groß, dass
    # höchstens ROW_MAX_ROWS Reihen ins Bild passen (verhindert, dass feine
    # Textur als "Reihen alle paar Pixel" fehlinterpretiert wird).
    lo = max(ROW_MIN_LAG, m // ROW_MAX_ROWS)
    hi = max(lo + 2, m // 3)          # mind. ~3 Reihen im sichtbaren Bereich
    if hi <= lo + 2:
        return False, None, {"reason": "profile_too_narrow"}

    # Nur ECHTE lokale Maxima der Autokorrelation zulassen: Bei nicht-periodischen
    # Profilen (eine breite Vegetationswolke) fällt die Autokorrelation ab Lag 0
    # monoton — das globale argmax läge dann am Rand lo mit hohem Restwert und
    # würde ein Reihenraster erfinden. Ein Reihenraster erzeugt dagegen eine
    # echte Spitze: ac[lag] > ac[lag-1] und ac[lag] >= ac[lag+1].
    seg_ac = ac[lo:hi]
    local_max = np.where((seg_ac[1:-1] > seg_ac[:-2]) & (seg_ac[1:-1] >= seg_ac[2:]))[0] + 1
    if len(local_max) == 0:
        return False, None, {"angle": round(float(best_angle), 1),
                             "reason": "no_periodic_peak"}
    best_local = local_max[int(np.argmax(seg_ac[local_max]))]
    lag = lo + int(best_local)
    strength = float(ac[lag])

    # Prominenz des Peaks: Ein echtes Reihenraster erzeugt tiefe Täler zwischen
    # den AC-Peaks; Rauschwellen auf einer glatt abfallenden Kurve (breite
    # Vegetationswolke) haben nur minimale Einbuchtungen.
    half = max(5, lag // 2)
    left = ac[max(lo, lag - half):lag]
    right = ac[lag + 1:min(hi, lag + half + 1)]
    prominence = float(ac[lag] - max(left.min() if len(left) else ac[lag],
                                     right.min() if len(right) else ac[lag]))

    info = {"angle": round(float(best_angle), 1), "period": int(lag),
            "strength": round(strength, 3), "prominence": round(prominence, 3)}
    if strength < ROW_MIN_STRENGTH or prominence < ROW_MIN_PROMINENCE:
        return False, None, info

    # Phase: Verschiebung mit der höchsten aufsummierten Vegetation auf dem Raster
    origin = nz[0]
    best_phase = max(range(lag),
                     key=lambda p: float(prof[origin + p::lag].sum()) if origin + p < len(prof) else 0.0)

    band_hw = max(4, int(ROW_BAND_FRACTION * lag))
    band_rot = np.zeros((ph, pw), np.uint8)
    pos = origin + best_phase
    n_rows = 0
    while pos < nz[-1]:
        cv2.rectangle(band_rot, (max(0, pos - band_hw), 0),
                      (min(pw - 1, pos + band_hw), ph - 1), 255, -1)
        pos += lag
        n_rows += 1
    info["n_rows"] = n_rows

    M_inv = cv2.invertAffineTransform(M_full)
    band_padded = cv2.warpAffine(band_rot, M_inv, (pw, ph), flags=cv2.INTER_NEAREST)
    band = band_padded[pad:pad + h, pad:pad + w]
    return True, band, info


def _row_overlay(bgr, crop_mask, weed_mask):
    """Anschauliches Overlay: Kulturpflanze grün, Unkraut rot eingefärbt."""
    ov = bgr.copy().astype(np.float32)
    green = np.array([60, 220, 60], np.float32)   # BGR
    red   = np.array([50, 50, 230], np.float32)
    cm = crop_mask[..., None] > 0
    wm = weed_mask[..., None] > 0
    ov = np.where(cm, 0.45 * ov + 0.55 * green, ov)
    ov = np.where(wm, 0.45 * ov + 0.55 * red, ov)
    return np.clip(ov, 0, 255).astype(np.uint8)


# -------------------------------
# Panel (Original | Maske | Gefiltert [+ Unkrautgefiltert])
# -------------------------------
def _make_labeled_panel(bgr, plant_mask, filtered_mask, cov_bw, cov_f,
                        weed_filtered_mask=None, cov_wf=None):
    def to_bgr(x):
        return cv2.cvtColor(x, cv2.COLOR_GRAY2BGR)

    left = bgr.copy()
    mid = to_bgr(plant_mask)
    right = to_bgr(filtered_mask)

    cv2.putText(left,  "Original",                 (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 0, 0), 2)
    cv2.putText(mid,   f"SW: {cov_bw:.2f}%",       (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 255), 2)
    cv2.putText(right, f"Gefiltert: {cov_f:.2f}%", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 255), 2)

    gap = 10
    spacer = np.ones((bgr.shape[0], gap, 3), dtype=np.uint8) * 255
    panels = [left, spacer, mid, spacer, right]

    if weed_filtered_mask is not None and cov_wf is not None:
        wf_bgr = to_bgr(weed_filtered_mask)
        cv2.putText(wf_bgr, f"Unkrautgef.: {cov_wf:.2f}%", (20, 40),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 255), 2)
        panels.extend([spacer.copy(), wf_bgr])

    return np.concatenate(panels, axis=1)


# ==========================================================
# Einzelbild-Analyse (wird von der App pro Bild aufgerufen)
# ==========================================================
def analyze_image(path, out_dir, tag, index,
                  weed_filter=False, row_mode=False,
                  min_size=MIN_SIZE, h_low=H_LOW_DEFAULT, h_high=H_HIGH_DEFAULT,
                  method=METHOD_EXG):
    """
    Analysiert EIN Bild und legt die Detailbilder unter <out_dir>/details/ ab.
    - tag: "before" oder "after", index: Position innerhalb der Gruppe
    Rückgabe: JSON-String (ein Item für build_group_summary).
    """
    path = str(path)
    if not os.path.isfile(path):
        raise FileNotFoundError(path)
    os.makedirs(out_dir, exist_ok=True)
    detail_dir = _ensure_detail_dir(out_dir)

    bgr = cv2.imread(path)
    if bgr is None:
        raise ValueError(f"cannot read image: {path}")
    bgr = _maybe_downscale_long_side(bgr, MAX_SIDE_FOR_ANALYSIS)

    brightness = round(float(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).mean()), 1)

    plant_mask = _segment_plants(bgr, method, h_low, h_high)
    filtered = _remove_small_components(plant_mask, min_size)
    cov_bw = _coverage_percent(plant_mask)
    cov_f = _coverage_percent(filtered)

    weed_filtered_mask, cov_wf = None, None
    if weed_filter:
        weed_filtered_mask = _weed_filter_mask(filtered)
        cov_wf = _coverage_percent(weed_filtered_mask)

    rows_detected = None
    cov_crop = cov_weed = None
    overlay_path = None
    if row_mode:
        ok, band, info = _detect_rows(filtered)
        rows_detected = bool(ok)
        print(f"[PY] rows {tag}{index}: ok={ok} info={info}")
        if ok:
            crop_mask = cv2.bitwise_and(filtered, band)
            weed_mask = cv2.bitwise_and(filtered, cv2.bitwise_not(band))
            cov_crop = _coverage_percent(crop_mask)
            cov_weed = _coverage_percent(weed_mask)
            overlay = _row_overlay(bgr, crop_mask, weed_mask)
            overlay = _resize_to_height(overlay, min(MAX_PANEL_HEIGHT, overlay.shape[0]))
            overlay_path = os.path.join(detail_dir, f"{tag}_{index:02d}_reihen.jpg")
            _imwrite(overlay_path, overlay, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    # Dateien ablegen: Masken verlustfrei als PNG, Fotos/Panels als JPEG
    prefix = f"{tag}_{index:02d}"
    orig_path = os.path.join(detail_dir, f"{prefix}_original.jpg")
    bw_path = os.path.join(detail_dir, f"{prefix}_maske.png")
    fil_path = os.path.join(detail_dir, f"{prefix}_gefiltert_{min_size}px.png")
    panel_path = os.path.join(detail_dir, f"{prefix}_panel.jpg")

    _imwrite(orig_path, bgr, [int(cv2.IMWRITE_JPEG_QUALITY), 90])
    _imwrite(bw_path, plant_mask)
    _imwrite(fil_path, filtered)

    wf_path = None
    if weed_filtered_mask is not None:
        wf_path = os.path.join(detail_dir, f"{prefix}_unkrautgefiltert.png")
        _imwrite(wf_path, weed_filtered_mask)

    panel = _make_labeled_panel(bgr, plant_mask, filtered, cov_bw, cov_f,
                                weed_filtered_mask, cov_wf)
    panel = _resize_to_height(panel, min(MAX_PANEL_HEIGHT, panel.shape[0]))
    _imwrite(panel_path, panel, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    item = {
        "file": os.path.basename(path),
        "tag": tag,
        "index": int(index),
        "brightness": brightness,
        "coverage_bw_percent": cov_bw,
        "coverage_filtered_percent": cov_f,
        "outputs": {
            "original": orig_path,
            "mask_bw": bw_path,
            "filtered": fil_path,
            "panel": panel_path,
        },
    }
    if cov_wf is not None:
        item["coverage_weedfiltered_percent"] = cov_wf
        item["outputs"]["weed_filtered"] = wf_path
    if row_mode:
        item["rows_detected"] = rows_detected
        if rows_detected:
            item["coverage_crop_percent"] = cov_crop
            item["coverage_weed_percent"] = cov_weed
            item["outputs"]["row_overlay"] = overlay_path

    print(f"[PY] analyze_image {prefix}: bw={cov_bw}% f={cov_f}% "
          f"wf={cov_wf} crop={cov_crop} weed={cov_weed} bright={brightness}")
    # allow_nan=False als Wächter: NaN/Inf würde sonst zu ungültigem JSON,
    # das die Java-Seite erst beim Parsen crashen ließe
    return json.dumps(item, ensure_ascii=False, allow_nan=False)


# ==========================================================
# Gruppen-Auswertung (N Vorher- vs. M Nachher-Bilder)
# ==========================================================
def _mean_of(items, key):
    vals = [i[key] for i in items if key in i and i[key] is not None]
    return round(float(np.mean(vals)), 2) if vals else None


def _build_overview(before_items, after_items, out_dir):
    """Übersichtsbild: obere Zeile alle Vorher-Originale, untere Zeile Nachher."""
    def strip(items, label):
        imgs = []
        for it in items:
            p = it.get("outputs", {}).get("original")
            im = cv2.imread(p) if p else None
            if im is not None:
                imgs.append(_resize_to_height(im, OVERVIEW_STRIP_HEIGHT))
        if not imgs:
            return None
        # Einmal konkatenieren statt in der Schleife (vermeidet O(n²)-Kopien)
        gap = np.ones((OVERVIEW_STRIP_HEIGHT, 8, 3), np.uint8) * 255
        parts = [imgs[0]]
        for im in imgs[1:]:
            parts.extend([gap, im])
        row = np.concatenate(parts, axis=1)
        cv2.putText(row, label, (14, 34), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 5)
        cv2.putText(row, label, (14, 34), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)
        return row

    top = strip(before_items, f"Vorher ({len(before_items)})")
    bottom = strip(after_items, f"Nachher ({len(after_items)})")
    if top is None and bottom is None:
        return None
    rows = [r for r in (top, bottom) if r is not None]
    target_w = max(r.shape[1] for r in rows)
    rows = [_pad_to_width(r, target_w) for r in rows]
    gap = np.ones((10, target_w, 3), np.uint8) * 255
    parts = [rows[0]]
    for r in rows[1:]:
        parts.extend([gap, r])
    overview = np.concatenate(parts, axis=0)
    overview = _maybe_downscale_long_side(overview, MAX_OVERVIEW_WIDTH)
    path = os.path.join(out_dir, "uebersicht_vorher_nachher.jpg")
    _imwrite(path, overview, [int(cv2.IMWRITE_JPEG_QUALITY), 88])
    return path


def build_group_summary(items_json, out_dir):
    """
    Fasst die Einzelbild-Ergebnisse zu einer Gruppen-Auswertung zusammen.
    - items_json: JSON-Array-String der analyze_image-Ergebnisse
    Rückgabe: JSON-String mit Mittelwerten, Deltas, Warn-Flags und Übersichtsbild.
    """
    items = json.loads(str(items_json))
    before = [i for i in items if i.get("tag") == "before"]
    after = [i for i in items if i.get("tag") == "after"]

    def group_avg(group):
        return {
            "bw": _mean_of(group, "coverage_bw_percent"),
            "filtered": _mean_of(group, "coverage_filtered_percent"),
            "weedfiltered": _mean_of(group, "coverage_weedfiltered_percent"),
            "crop": _mean_of(group, "coverage_crop_percent"),
            "weed": _mean_of(group, "coverage_weed_percent"),
            "brightness": _mean_of(group, "brightness"),
        }

    avg_b = group_avg(before)
    avg_a = group_avg(after)

    def delta(key):
        if avg_b.get(key) is None or avg_a.get(key) is None:
            return None
        return round(avg_a[key] - avg_b[key], 2)

    row_mode = any("rows_detected" in i for i in items)
    rows_detected = None
    if row_mode:
        # Feinere Aggregation statt all(): Reihen gelten je GRUPPE als erkannt,
        # wenn MINDESTENS DIE HÄLFTE ihrer Bilder Reihen zeigt (bei 1 von 2
        # Bildern also ja). Ein einzelner Ausreißer kippt so nicht mehr die
        # gesamte Auswertung; die crop/weed-Mittelwerte stammen ohnehin nur aus
        # den Bildern MIT erkannten Reihen (_mean_of überspringt fehlende
        # Schlüssel).
        def group_rows_ok(group):
            flags = [bool(i.get("rows_detected")) for i in group if "rows_detected" in i]
            return bool(flags) and sum(flags) * 2 >= len(flags)
        rows_detected = group_rows_ok(before) and group_rows_ok(after)

    # Warnung 1: Die GRUPPEN-Mittelwerte der Helligkeit weichen stark ab
    # (systematischer Beleuchtungs-Bias vorher vs. nachher).
    brightness_warning = False
    if avg_b.get("brightness") is not None and avg_a.get("brightness") is not None:
        brightness_warning = abs(avg_b["brightness"] - avg_a["brightness"]) > BRIGHTNESS_WARN_DELTA

    # Warnung 2: EINZELBILDER streuen stark (z. B. ein sehr dunkles und ein
    # sehr helles Vorher-Bild) — im Gruppenmittel würde sich das aufheben,
    # obwohl beide Bilder einzeln segmentierungskritisch sind.
    def group_spread(group):
        vals = [i.get("brightness") for i in group if i.get("brightness") is not None]
        return (max(vals) - min(vals)) if len(vals) >= 2 else 0.0
    brightness_spread_warning = (group_spread(before) > BRIGHTNESS_WARN_DELTA
                                 or group_spread(after) > BRIGHTNESS_WARN_DELTA)

    overview_path = _build_overview(before, after, out_dir)

    result = {
        "mode": "group",
        "count_before": len(before),
        "count_after": len(after),
        "weed_filter": any("coverage_weedfiltered_percent" in i for i in items),
        "row_mode": row_mode,
        "rows_detected": rows_detected,
        "brightness_warning": brightness_warning,
        "brightness_spread_warning": brightness_spread_warning,
        "out_dir": out_dir,
        "avg_before": avg_b,
        "avg_after": avg_a,
        "delta": {
            "bw": delta("bw"),
            "filtered": delta("filtered"),
            "weedfiltered": delta("weedfiltered"),
            "crop": delta("crop"),
            "weed": delta("weed"),
        },
        # Schlüssel weglassen statt None: Androids JSONObject.optString würde
        # aus einem JSON-null den String "null" machen
        "combo": {"overview": overview_path} if overview_path else {},
        "items": items,
    }
    print(f"[PY] group summary: {len(before)} vorher / {len(after)} nachher, "
          f"rows={rows_detected}, bright_warn={brightness_warning}, "
          f"spread_warn={brightness_spread_warning}")
    return json.dumps(result, ensure_ascii=False, allow_nan=False)
