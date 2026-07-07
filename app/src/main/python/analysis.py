# app/src/main/python/analysis.py
import os, json
import cv2
import numpy as np

# -------------------------------
# Konstanten / Parameter
# -------------------------------
# Hinweis zur Methodik: Die Pflanzensegmentierung nutzt eine HSV-Grünton-Schwelle
# (H zwischen H_LOW und H_HIGH). Der Businessplan nennt ExG-/ExGR-Farbindizes als
# Zielmethodik — eine Umstellung sollte mit echten Feldbildern validiert werden.
H_LOW_DEFAULT  = 35
H_HIGH_DEFAULT = 85
SV_MIN         = 40   # Mindest-Sättigung/-Helligkeit für "grün"
MIN_SIZE       = 50   # Standard: Komponenten kleiner als 50 px werden entfernt
IMG_EXTS       = (".jpg", ".jpeg", ".png", ".bmp", ".webp")

# NEU: globale Maximalgrößen für RAM-schonende Verarbeitung/Anzeige
MAX_SIDE_FOR_ANALYSIS = 1600  # längste Bildkante für die Analyse
MAX_PANEL_HEIGHT      = 720   # Zielhöhe für Panels und Kombis (UI-freundlich)

# Unkrautfilter-Parameter
# Formfaktor = P² / (4π × A)  — 1.0 = perfekter Kreis, >1 = unregelmäßiger
WEED_FORM_FACTOR_THRESHOLD = 2.0
# Objekte kleiner als dieser Anteil der Median-Fläche gelten als "deutlich kleiner"
WEED_SIZE_FRACTION = 0.25

# -------------------------------
# Hilfsfunktionen
# -------------------------------
def chaquopy_probe():
    print("[PY] chaquopy_probe ok")

def _is_image(fn: str) -> bool:
    return fn.lower().endswith(IMG_EXTS)

def _ensure_outdir(base_file_or_dir: str) -> str:
    # nimmt Datei- oder Verzeichnispfad und baut 'ausgabe_pflanzen' darunter
    base_dir = base_file_or_dir
    if os.path.isfile(base_file_or_dir):
        base_dir = os.path.dirname(base_file_or_dir)
    if not base_dir:
        base_dir = os.getcwd()
    out_dir = os.path.join(base_dir, "ausgabe_pflanzen")
    os.makedirs(out_dir, exist_ok=True)
    return out_dir

def _to3c(img: np.ndarray) -> np.ndarray:
    """Sicherstellen, dass Bild 3 Kanäle (BGR) hat."""
    if img.ndim == 2:
        return cv2.cvtColor(img, cv2.COLOR_GRAY2BGR)
    return img

def _resize_to_height(img: np.ndarray, target_h: int) -> np.ndarray:
    """Proportional auf Zielhöhe skalieren."""
    h, w = img.shape[:2]
    if h == target_h:
        return img
    scale = target_h / float(h)
    new_w = max(1, int(round(w * scale)))
    interp = cv2.INTER_AREA if target_h < h else cv2.INTER_LINEAR
    return cv2.resize(img, (new_w, target_h), interpolation=interp)

def _pad_to_width(img: np.ndarray, target_w: int) -> np.ndarray:
    """Links-bündig mit Weiß auffüllen, damit Breite = target_w ist (für vertikales Stapeln)."""
    h, w = img.shape[:2]
    if w == target_w:
        return img
    canvas = np.ones((h, target_w, 3), dtype=np.uint8) * 255
    canvas[:, :w, :] = img
    return canvas

def _maybe_downscale_long_side(bgr: np.ndarray, max_side: int) -> np.ndarray:
    """Skaliert das Bild so, dass die längste Kante <= max_side ist (proportional)."""
    h, w = bgr.shape[:2]
    long_side = max(h, w)
    if long_side <= max_side:
        return bgr
    scale = max_side / float(long_side)
    new_w = max(1, int(round(w * scale)))
    new_h = max(1, int(round(h * scale)))
    return cv2.resize(bgr, (new_w, new_h), interpolation=cv2.INTER_AREA)

def _mask_and_stats(bgr: np.ndarray, weed_filter: bool = False,
                    min_size: int = MIN_SIZE,
                    h_low: int = H_LOW_DEFAULT, h_high: int = H_HIGH_DEFAULT):
    """
    Erzeugt:
      - plant_mask  (Binärmaske grün in HSV)
      - filtered    (Komponenten < min_size entfernt)
      - cov_bw      (Bedeckung plant_mask in %)
      - cov_f       (Bedeckung filtered   in %)
      - weed_filtered (optional: zusätzlich Unkraut entfernt)
      - cov_wf      (optional: Bedeckung weed_filtered in %)
    """
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)

    lower_green = np.array([h_low, SV_MIN, SV_MIN], dtype=np.uint8)
    upper_green = np.array([h_high, 255, 255], dtype=np.uint8)
    plant_mask = cv2.inRange(hsv, lower_green, upper_green)

    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(plant_mask, connectivity=8)
    filtered = np.zeros_like(plant_mask)
    for i in range(1, num_labels):
        if stats[i, cv2.CC_STAT_AREA] >= min_size:
            filtered[labels == i] = 255

    cov_bw = float((plant_mask == 255).sum()) / plant_mask.size * 100.0
    cov_f  = float((filtered   == 255).sum()) / filtered.size   * 100.0

    weed_filtered = None
    cov_wf = None

    if weed_filter:
        contours, _ = cv2.findContours(filtered.copy(), cv2.RETR_EXTERNAL,
                                        cv2.CHAIN_APPROX_SIMPLE)
        if contours:
            # Fläche und Formfaktor pro Kontur berechnen
            areas = []
            form_factors = []
            for cnt in contours:
                a = cv2.contourArea(cnt)
                p = cv2.arcLength(cnt, True)
                if a > 0 and p > 0:
                    ff = (p ** 2) / (4.0 * np.pi * a)
                else:
                    ff = float('inf')
                areas.append(a)
                form_factors.append(ff)

            # Mediangröße als Referenz für Getreidepflanzen
            median_area = float(np.median(areas)) if areas else 0.0
            size_threshold = WEED_SIZE_FRACTION * median_area

            weed_filtered = np.zeros_like(plant_mask)
            for idx, cnt in enumerate(contours):
                area = areas[idx]
                ff   = form_factors[idx]
                # Entferne: deutlich kleiner als Getreide UND kompakte Form
                is_weed = (area < size_threshold) and (ff < WEED_FORM_FACTOR_THRESHOLD)
                if not is_weed:
                    cv2.drawContours(weed_filtered, [cnt], -1, 255, cv2.FILLED)
        else:
            weed_filtered = np.zeros_like(plant_mask)

        cov_wf = float((weed_filtered == 255).sum()) / weed_filtered.size * 100.0
        cov_wf = round(cov_wf, 2)

    return plant_mask, filtered, round(cov_bw, 2), round(cov_f, 2), weed_filtered, cov_wf

def _make_labeled_panel(bgr, plant_mask, filtered_mask, cov_bw, cov_f,
                        weed_filtered_mask=None, cov_wf=None) -> np.ndarray:
    """
    Baut ein 3er-Panel (Original | SW | Gefiltert) mit Text oben links.
    Bei aktivem Unkrautfilter wird ein 4. Panel (Unkrautgefiltert) angehängt.
    """
    def to_bgr(x):
        return cv2.cvtColor(x, cv2.COLOR_GRAY2BGR)

    left = bgr.copy()
    mid  = to_bgr(plant_mask)
    right= to_bgr(filtered_mask)

    # Labels (oben links, identisch zu altem Skript)
    cv2.putText(left,  "Original",                 (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 0, 0), 2)
    cv2.putText(mid,   f"SW: {cov_bw:.2f}%",       (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0,   0, 255), 2)
    cv2.putText(right, f"Gefiltert: {cov_f:.2f}%", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0,   0, 255), 2)

    gap = 10
    spacer = np.ones((bgr.shape[0], gap, 3), dtype=np.uint8) * 255
    panels = [left, spacer, mid, spacer, right]

    if weed_filtered_mask is not None and cov_wf is not None:
        wf_bgr = to_bgr(weed_filtered_mask)
        cv2.putText(wf_bgr, f"Unkrautgef.: {cov_wf:.2f}%", (20, 40),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 255), 2)
        panels.extend([spacer.copy(), wf_bgr])

    panel = np.concatenate(panels, axis=1)
    return panel

def _ensure_detail_dir(out_dir: str) -> str:
    """
    Unterordner 'details' für Masken/Einzelbilder anlegen. Eine .nomedia-Datei
    sorgt dafür, dass die Galerie diese Zwischenergebnisse nicht anzeigt —
    im Datei-Manager und in der App bleiben sie zugänglich.
    """
    detail_dir = os.path.join(out_dir, "details")
    os.makedirs(detail_dir, exist_ok=True)
    nomedia = os.path.join(detail_dir, ".nomedia")
    if not os.path.exists(nomedia):
        open(nomedia, "w").close()
    return detail_dir


def _process_single_image(path: str, out_dir: str, tag: str = None,
                          weed_filter: bool = False,
                          min_size: int = MIN_SIZE,
                          h_low: int = H_LOW_DEFAULT, h_high: int = H_HIGH_DEFAULT,
                          detail_dir: str = None):
    """
    Analysiert ein Bild und speichert (in detail_dir, sonst out_dir):
      - *_original.jpg
      - *_schwarz_weiss.jpg
      - *_gefiltert_<min_size>px.jpg
      - *_unkrautgefiltert.jpg (nur bei weed_filter=True)
      - *_panel.jpg (mit SW/Gefiltert-Werten oben links, auf MAX_PANEL_HEIGHT skaliert)
    tag: None  -> Dateinamen wie im alten Skript (kein Tag)
         'before'/'after' -> Prefix um <tag> ergänzt
    Rückgabe: (result_dict, plant_mask, filtered_mask, bgr, panel_img)
    """
    if detail_dir is None:
        detail_dir = out_dir
    bgr = cv2.imread(path)
    if bgr is None:
        raise FileNotFoundError(f"cannot read image: {path}")

    # NEU: vor Analyse verkleinern (beeinflusst Prozente nicht!)
    bgr = _maybe_downscale_long_side(bgr, MAX_SIDE_FOR_ANALYSIS)

    plant_mask, filtered_mask, cov_bw, cov_f, weed_filtered_mask, cov_wf = \
        _mask_and_stats(bgr, weed_filter=weed_filter,
                        min_size=min_size, h_low=h_low, h_high=h_high)

    base = os.path.splitext(os.path.basename(path))[0]
    prefix = base if not tag else f"{base}_{tag}"

    orig_path  = os.path.join(detail_dir, f"{prefix}_original.jpg")
    bw_path    = os.path.join(detail_dir, f"{prefix}_schwarz_weiss.jpg")
    fil_path   = os.path.join(detail_dir, f"{prefix}_gefiltert_{min_size}px.jpg")
    panel_path = os.path.join(detail_dir, f"{prefix}_panel.jpg")

    # JPEG mit moderater Qualität (kleinere Dateien, weniger RAM beim Decoding)
    cv2.imwrite(orig_path,  bgr,           [int(cv2.IMWRITE_JPEG_QUALITY), 90])
    cv2.imwrite(bw_path,    plant_mask,    [int(cv2.IMWRITE_JPEG_QUALITY), 90])
    cv2.imwrite(fil_path,   filtered_mask, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    wf_path = None
    if weed_filter and weed_filtered_mask is not None:
        wf_path = os.path.join(detail_dir, f"{prefix}_unkrautgefiltert.jpg")
        cv2.imwrite(wf_path, weed_filtered_mask, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    panel_img = _make_labeled_panel(bgr, plant_mask, filtered_mask, cov_bw, cov_f,
                                    weed_filtered_mask, cov_wf)
    # NEU: Panel auf sinnvolle UI-Höhe limitieren
    panel_img = _resize_to_height(panel_img, MAX_PANEL_HEIGHT)
    cv2.imwrite(panel_path, panel_img, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    result = {
        "file": os.path.basename(path),
        "file_path": path,
        "coverage_bw_percent": cov_bw,
        "coverage_filtered_percent": cov_f,
        "outputs": {
            "original": orig_path,
            "mask_bw": bw_path,
            "filtered": fil_path,
            "panel": panel_path
        }
    }

    if weed_filter and cov_wf is not None:
        result["coverage_weedfiltered_percent"] = cov_wf
        if wf_path:
            result["outputs"]["weed_filtered"] = wf_path

    return result, plant_mask, filtered_mask, bgr, panel_img

# ==========================================================
# Vorher/Nachher-Analyse (nutzt exakt die gleiche Pipeline)
# ==========================================================
def analyze_pair(before_path: str, after_path: str, out_dir: str = None,
                 weed_filter: bool = False,
                 min_size: int = MIN_SIZE,
                 h_low: int = H_LOW_DEFAULT, h_high: int = H_HIGH_DEFAULT) -> str:
    """
    Analysiere genau zwei Bilder (Vorher/Nachher).
    - before_path, after_path: absolute Dateipfade (keine content:// URIs)
    - out_dir: optionaler Zielordner (z. B. /DCIM/SmartWeed/ausgabe_pflanzen)
    - weed_filter: True = Unkrautfilter aktivieren
    - min_size, h_low, h_high: Analyse-Parameter (Experten-Einstellungen der App)
    Rückgabe: JSON-String.
    """
    print("[PY] analyze_pair:", before_path, after_path, "weed_filter=", weed_filter,
          "min_size=", min_size, "h=", h_low, "-", h_high)

    if not os.path.isfile(before_path):
        raise FileNotFoundError(before_path)
    if not os.path.isfile(after_path):
        raise FileNotFoundError(after_path)

    # Zielordner
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    else:
        out_dir = _ensure_outdir(before_path)

    # Masken/Einzelbilder wandern in den versteckten details/-Unterordner,
    # nur die fertigen Kombibilder bleiben direkt im Ausgabe-Ordner.
    detail_dir = _ensure_detail_dir(out_dir)

    # Einzelanalysen (mit Panel + Werten wie im alten Skript)
    before_res, before_bw, before_filt, before_bgr, before_panel = \
        _process_single_image(before_path, out_dir, tag="before", weed_filter=weed_filter,
                              min_size=min_size, h_low=h_low, h_high=h_high,
                              detail_dir=detail_dir)
    after_res, after_bw, after_filt, after_bgr, after_panel = \
        _process_single_image(after_path, out_dir, tag="after", weed_filter=weed_filter,
                              min_size=min_size, h_low=h_low, h_high=h_high,
                              detail_dir=detail_dir)

    # Deltas (Prozentpunkte)
    try:
        delta_bw  = round(float(after_res["coverage_bw_percent"]) - float(before_res["coverage_bw_percent"]), 2)
        delta_flt = round(float(after_res["coverage_filtered_percent"]) - float(before_res["coverage_filtered_percent"]), 2)
    except Exception:
        delta_bw = delta_flt = 0.0

    delta_wf = None
    if weed_filter:
        bwf = before_res.get("coverage_weedfiltered_percent")
        awf = after_res.get("coverage_weedfiltered_percent")
        if bwf is not None and awf is not None:
            delta_wf = round(float(awf) - float(bwf), 2)

    # --- EINDEUTIGE Dateinamen für Kombibilder pro Paar ---
    # Basisnamen der Eingaben nehmen, Sonderzeichen entschärfen
    def _base(x: str) -> str:
        b = os.path.splitext(os.path.basename(x))[0]
        # sehr einfache Normalisierung, damit Dateinamen safe sind
        return "".join(c if c.isalnum() or c in ("-", "_") else "_" for c in b)

    bname = _base(before_path)
    aname = _base(after_path)
    pair_id = f"{bname}__{aname}"  # Eindeutige Paar-ID

    gap_h = 10
    before_bgr3 = _to3c(before_bgr)
    after_bgr3  = _to3c(after_bgr)
    target_h = min(before_bgr3.shape[0], after_bgr3.shape[0])
    before_bgr_r = _resize_to_height(before_bgr3, target_h)
    after_bgr_r  = _resize_to_height(after_bgr3,  target_h)

    cv2.putText(before_bgr_r, "Vorher",  (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255,0,0), 2)
    cv2.putText(after_bgr_r,  "Nachher", (20, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255,0,0), 2)

    spacer_v = np.ones((target_h, gap_h, 3), dtype=np.uint8) * 255
    both_orig = np.concatenate([before_bgr_r, spacer_v, after_bgr_r], axis=1)
    both_orig  = _resize_to_height(both_orig,  MAX_PANEL_HEIGHT)

    # >>> statt immer before_after_original.jpg jetzt pro Paar eindeutig:
    both_orig_path  = os.path.join(out_dir, f"{pair_id}_original_both.jpg")
    cv2.imwrite(both_orig_path,  both_orig,  [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    # 2) Vorher/Nachher-Tripanels (mit Prozentwerten) untereinander kombinieren
    before_panel3 = _to3c(before_panel)
    after_panel3  = _to3c(after_panel)
    target_w = max(before_panel3.shape[1], after_panel3.shape[1])
    before_panel_pad = _pad_to_width(before_panel3, target_w)
    after_panel_pad  = _pad_to_width(after_panel3,  target_w)
    spacer_h = np.ones((gap_h, target_w, 3), dtype=np.uint8) * 255
    both_panel = np.concatenate([before_panel_pad, spacer_h, after_panel_pad], axis=0)
    both_panel = _resize_to_height(both_panel, MAX_PANEL_HEIGHT * 2)

    # >>> statt immer before_after_panel.jpg jetzt pro Paar eindeutig:
    both_panel_path = os.path.join(out_dir, f"{pair_id}_panel_both.jpg")
    cv2.imwrite(both_panel_path, both_panel, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    delta_dict = {
        "coverage_bw_percent_points": delta_bw,
        "coverage_filtered_percent_points": delta_flt
    }
    if delta_wf is not None:
        delta_dict["coverage_weedfiltered_percent_points"] = delta_wf

    result = {
        "mode": "pair",
        "weed_filter": weed_filter,
        "out_dir": out_dir,
        "items": [before_res, after_res],
        "delta": delta_dict,
        "combo": {
            "original_panel": both_orig_path,
            "labeled_tripanel_stacked": both_panel_path
        }
    }
    print("[PY] DONE analyze_pair -> out_dir:", out_dir)
    return json.dumps(result, ensure_ascii=False)


# ==========================================================
# Batch-Analyse: mehrere Vorher/Nachher-Paare in einem Aufruf
# ==========================================================
def analyze_batch(before_paths, after_paths, out_dir=None, weed_filter=False,
                  min_size: int = MIN_SIZE,
                  h_low: int = H_LOW_DEFAULT, h_high: int = H_HIGH_DEFAULT) -> str:
    """
    Analysiert mehrere Vorher/Nachher-Paare (nutzt analyze_pair pro Paar).
    - before_paths, after_paths: Listen absoluter Dateipfade gleicher Länge
    - out_dir: gemeinsamer Zielordner für alle Paare
    - weed_filter: True = Unkrautfilter aktivieren
    - min_size, h_low, h_high: Analyse-Parameter (Experten-Einstellungen der App)
    Rückgabe: JSON-Array-String (ein Objekt pro Paar, Format wie analyze_pair).
    """
    befores = [str(p) for p in before_paths]
    afters = [str(p) for p in after_paths]
    print("[PY] analyze_batch:", len(befores), "pair(s), weed_filter=", weed_filter)

    if len(befores) != len(afters):
        raise ValueError(f"before/after count mismatch: {len(befores)} vs {len(afters)}")

    results = []
    for b, a in zip(befores, afters):
        results.append(json.loads(analyze_pair(b, a, out_dir, weed_filter,
                                               min_size=min_size, h_low=h_low, h_high=h_high)))

    print(f"[PY] DONE analyze_batch -> {len(results)} pair(s)")
    return json.dumps(results, ensure_ascii=False)


# ---------------------------------------------
# Ordneranalyse (mit Panels & Werten wie zuvor)
# ---------------------------------------------
def analyze_folder(folder_path: str) -> str:
    print("[PY] analyze_folder:", folder_path)
    if not os.path.isdir(folder_path):
        raise FileNotFoundError(f"Folder not found: {folder_path}")

    out_dir = _ensure_outdir(folder_path)
    files = [f for f in sorted(os.listdir(folder_path)) if _is_image(f)]
    print(f"[PY] found {len(files)} image(s)")

    if not files:
        return json.dumps({"count": 0, "out_dir": out_dir, "items": []}, ensure_ascii=False)

    results = []
    for name in files:
        in_path = os.path.join(folder_path, name)
        try:
            res, *_ = _process_single_image(in_path, out_dir, tag=None)
            results.append(res)
            print(f"[PY] processed {name} cov_bw={res['coverage_bw_percent']:.2f}% "
                  f"cov_f={res['coverage_filtered_percent']:.2f}%")
        except Exception as ex:
            print("[PY] ERROR processing", name, str(ex))

    summary_path = os.path.join(out_dir, "summary.json")
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print(f"[PY] DONE -> {len(results)} file(s), out_dir={out_dir}")
    return json.dumps({"count": len(results), "out_dir": out_dir, "items": results}, ensure_ascii=False)
