# app/src/main/python/analysis.py
#
# Bildanalyse für SmartWeedControl (v1.7)
#
# Ablauf: Die App analysiert jedes Bild einzeln (analyze_image) und fasst
# anschließend alle Vorher-/Nachher-Bilder zu einer Gruppen-Auswertung zusammen
# (build_group_summary). Vorher- und Nachher-Gruppe dürfen unterschiedlich
# viele Bilder enthalten — die Bedeckungsgrade werden je Gruppe gemittelt.
#
# Zählrahmen (optional): Liegt ein roter Zählrahmen im Bild, wird nur sein
# Inneres ausgewertet (entzerrt, Originalauflösung). Ohne gefundenen Rahmen
# fällt die Analyse auf das ganze Bild zurück (frame_found=false).
#
# Segmentierung (Pflanze vs. Boden), wählbar in den Experten-Einstellungen:
#   - "hsv":  fester Grünton-Bereich im HSV-Farbraum (klassisch)
#   - "exg":  Excess-Green-Index (2g−r−b) mit automatischem Otsu-Schwellwert
#             (Woebbecke 1995); Standard, an 24 Feldbildern validiert.
#   - "exgr": Excess Green minus Excess Red mit fester Schwelle 0
#             (Meyer & Neto 2008) — ohne bildabhängige Schwelle, daher ohne
#             deren Licht-Drift (siehe _exgr_mask).
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
METHOD_EXGR = "exgr"

# Zählrahmen-Erkennung (roter/rosa Rahmen um die Bonitur-Fläche)
FRAME_MIN_AREA_FRACTION = 0.08  # Rahmeninneres muss >= 8 % des Bildes einnehmen
FRAME_MAX_ASPECT        = 1.6   # Seitenverhältnis des Inneren (Quadrat ~1.0)
FRAME_MIN_SIDE          = 120   # Mindest-Kantenlänge des Inneren in px
FRAME_MARGIN            = 0.06  # Sicherheitsrand nach innen (kein Rot im Ausschnitt)
FRAME_CLOSE_KERNELS     = (15, 31, 51, 71, 91)  # Lücken durch Blätter schließen
FRAME_MIN_FRAGMENT_PX   = 100   # kleinere Rot-Flecken sind Rauschen
FRAME_MAX_RED_FRACTION  = 0.35  # mehr Rot im Bild = kein schmaler Rahmen
FRAME_MIN_RING_RED      = 0.5   # direkt außerhalb des Inneren überwiegend Rot
FRAME_MAX_INNER_RED     = 0.1   # im Inneren (fast) kein Rot

# Licht-Warnung: Hell-Dunkel-Spanne des BODENS (90./10. Perzentil der Grauwerte
# außerhalb der Pflanzen). Harte Sonnenschatten spreizen sie, diffuses
# Wolkenlicht nicht (gemessen an 18 Rahmen-Ausschnitten: Sonne 2.24–3.56,
# bewölkt 1.61–1.95). Ab LIGHT_SUN_SPAN gilt ein Bild als "Sonne"; gewarnt
# wird, wenn Vorher- und Nachher-Gruppe mehrheitlich verschieden eingestuft
# sind. VORLÄUFIG kalibriert (2 Bildserien, 1 Kamera, knappe Lücke).
LIGHT_SUN_SPAN          = 2.1
LIGHT_MIN_SOIL_FRACTION = 0.05   # ohne genug sichtbaren Boden kein Wert
LIGHT_MIN_SOIL_PIXELS   = 1000

# Maximalgrößen für RAM-schonende Verarbeitung/Anzeige
MAX_SIDE_FOR_ANALYSIS = 1600
MAX_PANEL_HEIGHT      = 720
OVERVIEW_TILE_HEIGHT  = 300    # Kachelhöhe der Bilder im Übersichtsbild
OVERVIEW_MIN_WIDTH    = 960    # Mindestbreite: Kopfzeilen nie abgeschnitten
MAX_OVERVIEW_WIDTH    = 2400

# Gestaltung der Ergebnisbilder (BGR). Beschriftung steht in eigenen Leisten
# ÜBER/UNTER den Bildern statt im Foto — bleibt auf jedem Untergrund lesbar
# und wird bei schmalen Hochformat-Bildern nicht abgeschnitten.
UI_FONT       = cv2.FONT_HERSHEY_DUPLEX
UI_BG         = (255, 255, 255)
UI_TILE_BG    = (242, 242, 240)
UI_ACCENT     = (60, 125, 46)     # #2E7D3C Grün (Titel, Vorher)
UI_ACCENT_2   = (40, 110, 200)    # #C86E28 Orange (Nachher)
UI_TEXT       = (38, 38, 38)
UI_TEXT_MUTED = (118, 118, 118)
UI_ON_ACCENT  = (255, 255, 255)

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
        halbieren — dann zählt direkt die EXG_MIN-Maske.

    Bekannte Grenze (bewusst NICHT "gefixt"): Bei 50-84 % Bewuchs mit sehr
    breiter ExG-Verteilung (starke Sonne/Schatten-Kontraste im Bestand) kann
    Otsu die Schwelle in die Vegetation legen und die Bedeckung unterschätzen.
    Ein Fallback auf die EXG_MIN-Maske ist hier KEINE Option: Auf den 24
    validierten Feldbildern schneidet Otsu regulär 30-70 % der ExG-positiven
    Pixel weg (grünstichiger Boden/Moos knapp über EXG_MIN) — auch bei
    Grün-Anteilen über 50 %. Der pathologische Fall ist metrisch nicht vom
    Feld-Normalfall unterscheidbar; ein Guard würde die validierte Messung
    auf echten Bildern massiv verfälschen (z. B. feld_08: 10 % -> 66 %)."""
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


def _chromatic(bgr):
    """Normalisierte Farbanteile r, g, b (Summe 1) — helligkeitsunabhängig."""
    f = bgr.astype(np.float32)
    s = f[:, :, 0] + f[:, :, 1] + f[:, :, 2]
    s[s == 0] = 1.0
    return f[:, :, 2] / s, f[:, :, 1] / s, f[:, :, 0] / s


def _exgr_mask(bgr):
    """ExGR = ExG − ExR mit ExR = 1.4r − g, Schwelle fest bei 0
    (Meyer & Neto 2008).

    Hintergrund: Bei ExG+Otsu wandert die bildabhängige Schwelle mit dem Licht
    (gemessen an 18 Rahmen-Ausschnitten: bewölkt ~0.06–0.09, Sonne ~0.14,
    Sonne mit dichtem Bestand bis 0.21) — Sonne unterschätzt dünne Blätter,
    Wolkenlicht lässt die Blattränder in den Boden ausfransen. ExGR braucht
    keine bildabhängige Schwelle. Grenze: Farbbasierte Indizes greifen an
    Blatträndern leicht in beschatteten Boden über (JPEG-Farbunterabtastung).
    Nicht Standard, solange keine Referenzmessung vorliegt: Auf den 24
    Feldbildern weicht ExGR je nach Boden in beide Richtungen ab."""
    r, g, b = _chromatic(bgr)
    exgr = (2.0 * g - r - b) - (1.4 * r - g)
    return ((exgr > 0.0).astype(np.uint8)) * 255


def _segment_plants(bgr, method, h_low, h_high):
    if method == METHOD_EXG:
        mask = _exg_otsu_mask(bgr)
    elif method == METHOD_EXGR:
        mask = _exgr_mask(bgr)
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


def _soil_light_span(bgr, plant_mask):
    """Hell-Dunkel-Spanne des Bodens: 90./10. Perzentil der Grauwerte auf allen
    Pixeln außerhalb der (leicht erweiterten) Pflanzenmaske.

    Misst, wie hart die Schatten sind (Sonne hoch, Wolken niedrig). Die
    Erweiterung hält Blattränder fern. Hinweis: Sonnenbeschienener, gelblicher
    Boden hat selbst einen leicht positiven ExG — Boden darum NICHT über
    "ExG <= 0" bestimmen (das erfasst bei Sonne fast nur Schatten).
    None, wenn zu wenig Boden sichtbar ist."""
    soil = cv2.dilate(plant_mask, np.ones((7, 7), np.uint8)) == 0
    n = int(soil.sum())
    if n < LIGHT_MIN_SOIL_PIXELS or n < LIGHT_MIN_SOIL_FRACTION * soil.size:
        return None
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)[soil].astype(np.float32)
    p10, p90 = np.percentile(gray, 10), np.percentile(gray, 90)
    return round(float(p90 / max(p10, 1.0)), 2)


# -------------------------------
# Zählrahmen-Erkennung
# -------------------------------
def _frame_red_mask(bgr, close):
    """Rote bzw. rosa Rahmenpixel. Kräftiges Rot (H<=8) nur mit hoher Sättigung,
    damit rötlicher Boden (H~16) nicht zählt; Rosa-Rot (H>=160) auch blasser:
    Ein verwaschener Rahmen hat bei Bewölkung nur S~65-95 (gemessen)."""
    hsv = cv2.cvtColor(bgr, cv2.COLOR_BGR2HSV)
    h, s, v = cv2.split(hsv)
    m = (((h <= 8) & (s >= 110) & (v >= 90)) |
         ((h >= 160) & (s >= 50) & (v >= 120))).astype(np.uint8) * 255
    # Erst verstreute Kleinstflecken entfernen: Auf rötlichem Boden fallen durch
    # Rauschen ~1 % Einzelpixel in den Rot-Bereich, die das große Closing sonst
    # zu einer vollflächigen "Rahmen"-Maske verschmelzen würde. Bewusst ein
    # Flächenfilter statt Opening: Von Blättern zerschnittene, schmale
    # Rahmenstücke bleiben so erhalten (ein Opening dünnte sie aus — im Feld
    # wurden dann 2 von 18 Rahmen nicht mehr gefunden). Bekannte Grenze: Ist
    # ein Schenkel großteils von Blättern verdeckt, bleiben nur Splitter, die
    # von Bodenrauschen nicht unterscheidbar sind -> kein Rahmen, Rückfall auf
    # das ganze Bild mit Hinweis (gemessen: 17 von 18 Feldfotos erkannt).
    m = _remove_small_components(m, FRAME_MIN_FRAGMENT_PX)
    return cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((close, close), np.uint8))


def _looks_like_frame_ring(red, quad):
    """Plausibilitätsprüfung: Ein Zählrahmen ist ein SCHMALER roter Ring.
    Direkt außerhalb des Inneren muss überwiegend Rot liegen, im Inneren fast
    keins, und Rot darf nicht große Teile des Bildes füllen (rötlicher Boden,
    rote Geräte)."""
    if float((red > 0).mean()) > FRAME_MAX_RED_FRACTION:
        return False
    inner = np.zeros(red.shape, np.uint8)
    cv2.fillConvexPoly(inner, quad.astype(np.int32), 255)
    side = float(np.mean([np.linalg.norm(quad[i] - quad[(i + 1) % 4]) for i in range(4)]))
    k = max(3, int(side * 0.04)) | 1
    ring = cv2.subtract(cv2.dilate(inner, np.ones((k, k), np.uint8)), inner)
    ring_red = float((red[ring > 0] > 0).mean()) if (ring > 0).any() else 0.0
    inner_red = float((red[inner > 0] > 0).mean())
    return ring_red >= FRAME_MIN_RING_RED and inner_red <= FRAME_MAX_INNER_RED


def _order_corners(pts):
    """Ecken sortieren: oben-links, oben-rechts, unten-rechts, unten-links."""
    pts = np.asarray(pts, dtype=np.float32)
    s = pts.sum(axis=1)
    d = np.diff(pts, axis=1).ravel()
    return np.array([pts[np.argmin(s)], pts[np.argmin(d)],
                     pts[np.argmax(s)], pts[np.argmax(d)]], dtype=np.float32)


def _frame_inner_quad(bgr):
    """Viereck des Rahmeninneren oder None.

    Das Innere ist das größte LOCH in der Rahmenmaske. Blätter, die über den
    Rahmen ragen, unterbrechen den Ring — deshalb werden Lücken mit wachsender
    Kernelgröße geschlossen, bis ein plausibles Inneres (Mindestfläche,
    annähernd quadratisch) entsteht."""
    h, w = bgr.shape[:2]
    min_area = FRAME_MIN_AREA_FRACTION * h * w
    for k in FRAME_CLOSE_KERNELS:
        red = _frame_red_mask(bgr, k)
        contours, hier = cv2.findContours(red, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_SIMPLE)
        if hier is None:
            continue
        holes = [c for c, hh in zip(contours, hier[0]) if hh[3] != -1]
        if not holes:
            continue
        hole = max(holes, key=cv2.contourArea)
        if cv2.contourArea(hole) < min_area:
            continue
        hull = cv2.convexHull(hole)
        approx = cv2.approxPolyDP(hull, 0.02 * cv2.arcLength(hull, True), True)
        if len(approx) == 4:
            quad = _order_corners(approx.reshape(4, 2))
        else:
            quad = _order_corners(cv2.boxPoints(cv2.minAreaRect(hull)))
        sides = [float(np.linalg.norm(quad[i] - quad[(i + 1) % 4])) for i in range(4)]
        if min(sides) < FRAME_MIN_SIDE or max(sides) / max(min(sides), 1.0) > FRAME_MAX_ASPECT:
            continue
        if not _looks_like_frame_ring(red, quad):
            continue
        return quad
    return None


def _crop_to_frame(bgr):
    """Entzerrt das Rahmeninnere auf ein Quadrat in Originalauflösung.

    Kantenlänge = mittlere gemessene Kante, damit min_size dieselbe Bedeutung
    behält wie am Vollbild. Rückgabe: (ausschnitt, quad) oder (None, None)."""
    quad = _frame_inner_quad(bgr)
    if quad is None:
        return None, None
    side = int(round(np.mean([np.linalg.norm(quad[i] - quad[(i + 1) % 4]) for i in range(4)])))
    dst = np.array([[0, 0], [side, 0], [side, side], [0, side]], dtype=np.float32)
    warp = cv2.warpPerspective(bgr, cv2.getPerspectiveTransform(quad, dst), (side, side))
    m = int(side * FRAME_MARGIN)
    return np.ascontiguousarray(warp[m:side - m, m:side - m]), quad


# -------------------------------
# Modus B: Unkrautfilter (Heuristik Größe + Form)
# -------------------------------
def _weed_filter_mask(filtered_mask):
    """Entfernt kleine, kompakte Objekte (typisches Unkraut) aus der Maske.

    Bewusste Design-Punkte (alle waren früher fehlerhaft):
    - Gelöscht wird über die KOMPONENTEN-LABELS, nicht über gefüllte Konturen:
      cv2.drawContours(..., FILLED) auf der Außenkontur malt auch die Löcher
      einer Unkraut-Komponente aus — ein separates Objekt IM Loch (das selbst
      als Kultur klassifiziert wurde) wurde dadurch mitgelöscht. Über die
      Labels werden exakt die Pixel der Unkraut-Komponenten entfernt; Löcher
      und darin liegende Fremdobjekte bleiben unangetastet.
    - Flächen sind echte PIXELflächen (CC_STAT_AREA), nicht cv2.contourArea
      der Außenkontur: Letztere zählt Löcher mit — ring-/löchrige Objekte
      wurden systematisch zu groß bewertet (Ring r=80/Loch r=65: Kontur
      ~19.9k vs. real ~6.8k px) und verzerrten auch die Referenzfläche.
    - Referenzfläche ist der FLÄCHENGEWICHTETE Median (die Komponentengröße,
      oberhalb derer die Hälfte der gesamten Pflanzenfläche liegt) statt des
      einfachen Median: Stellt Unkraut die zahlenmäßige Mehrheit der
      Komponenten, war der einfache Median selbst eine Unkrautgröße und der
      Filter entfernte gar nichts — ausgerechnet bei hohem Unkrautdruck.

    Bekannte Grenze: Der flächengewichtete Median schützt nur gegen die
    zahlenmäßige Unkraut-Mehrheit. Stellt Unkraut >50 % der PflanzenFLÄCHE,
    wird die Referenz selbst eine Unkrautgröße und der Filter faktisch
    wirkungslos (Ergebnis nähert sich Modus A). Für die Kernfrage des
    Projekts (Kultur-CSC) ist Modus C zuständig, nicht diese Heuristik."""
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(filtered_mask,
                                                                    connectivity=8)
    contours, hierarchy = cv2.findContours(filtered_mask, cv2.RETR_CCOMP,
                                           cv2.CHAIN_APPROX_SIMPLE)
    if not contours or num_labels <= 1:
        return np.zeros_like(filtered_mask)

    # Nur Außenkonturen betrachten (RETR_CCOMP: Ebene 0 = Objektrand,
    # Ebene 1 = Lochrand). Anders als RETR_EXTERNAL erfasst das auch
    # Objekte, die innerhalb des Lochs eines anderen Objekts liegen.
    # Konturpunkte liegen AUF der Komponente -> Label direkt ablesbar.
    outer = [cnt for idx, cnt in enumerate(contours)
             if hierarchy is None or hierarchy[0][idx][3] < 0]
    if not outer:
        return np.zeros_like(filtered_mask)

    comp_labels, areas, form_factors = [], [], []
    for cnt in outer:
        x, y = int(cnt[0][0][0]), int(cnt[0][0][1])
        lbl = int(labels[y, x])
        a = float(stats[lbl, cv2.CC_STAT_AREA]) if lbl > 0 else 0.0
        p = cv2.arcLength(cnt, True)
        ff = (p ** 2) / (4.0 * np.pi * a) if (a > 0 and p > 0) else float("inf")
        comp_labels.append(lbl)
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

    # Exakt die Pixel der Unkraut-Komponenten aus der Maske löschen
    weed_ids = [comp_labels[idx] for idx in range(len(outer))
                if comp_labels[idx] > 0
                and (areas[idx] < size_threshold)
                and (form_factors[idx] < WEED_FORM_FACTOR_THRESHOLD)]
    if not weed_ids:
        return filtered_mask.copy()
    weed_pixels = np.isin(labels, weed_ids)
    result = filtered_mask.copy()
    result[weed_pixels] = 0
    return result


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

    # INTER_AREA statt INTER_NEAREST: dichteerhaltend — dünne Reihen (<1-2 px
    # nach Skalierung) würden bei Nearest-Neighbor aliasen oder streckenweise
    # verschwinden und das Winkel-Scoring verfälschen.
    # Nie hochskalieren (kleine Rahmen-Ausschnitte): das kostete nur Zeit
    scale = min(1.0, 400.0 / max(ph, pw))
    small = cv2.resize(padded, (max(1, int(pw * scale)), max(1, int(ph * scale))),
                       interpolation=cv2.INTER_AREA)
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
    # Maßgeblich ist die SPANNWEITE des bewachsenen Abschnitts, nicht die
    # Zahl bewachsener Spalten: Für eine Periodizität braucht es mindestens
    # zwei Reihenabstände. Die frühere Grenze "100 Spalten mit Grün" lehnte
    # schmale Rahmen-Ausschnitte mit lückigen Reihen pauschal ab.
    nz = np.nonzero(prof)[0]
    if len(nz) == 0 or (nz[-1] - nz[0] + 1) < 2 * ROW_MIN_LAG:
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

    # Prominenz eines Peaks: Ein echtes Reihenraster erzeugt tiefe Täler
    # zwischen den AC-Peaks; Rauschwellen auf einer glatt abfallenden Kurve
    # (breite Vegetationswolke) haben nur minimale Einbuchtungen.
    def peak_prominence(lag):
        half = max(5, lag // 2)
        left = ac[max(lo, lag - half):lag]
        # Rechts über das Suchfenster hinaus schauen (bis zur halben
        # Profillänge): Bei wenigen Reihen (Zählrahmen-Ausschnitt, 3-4 Reihen)
        # liegt der Reihenabstand knapp unter hi — ein bei hi abgeschnittenes
        # Fenster verdeckte das Tal rechts vom Peak und drückte die Prominenz
        # echter Reihen unter die Schwelle (gemessen: 1 von 18 Ausschnitten
        # erkannt, mit Fix 11 von 18; 24 Feldbilder unverändert).
        right = ac[lag + 1:min(max(hi, m // 2), lag + half + 1)]
        return float(ac[lag] - max(left.min() if len(left) else ac[lag],
                                   right.min() if len(right) else ac[lag]))

    # Den KLEINSTEN Peak über den Schwellen wählen, NICHT das globale argmax:
    # Die unverzerrte Normierung (Division durch m-L) hebt bei sauberen
    # periodischen Mustern die Harmonischen (2L, 3L, ...) leicht ÜBER das
    # Fundamental — ein argmax rastete dort ein, halbierte/drittelte die
    # Reihenzahl und zählte die Kultur zwischen den zu weiten Bändern als
    # Unkraut (gemessen: ideales 8-Reihen-Feld -> Periode 2L, 50 % der
    # Kulturpixel außerhalb der Bänder). Harmonische bestehen dieselben
    # Schwellen wie das Fundamental; das Fundamental ist aber stets der
    # kleinste gültige Peak.
    candidates = [lo + int(i) for i in local_max]
    passing = [L for L in candidates
               if float(ac[L]) >= ROW_MIN_STRENGTH
               and peak_prominence(L) >= ROW_MIN_PROMINENCE]
    if not passing:
        best = candidates[int(np.argmax(ac[np.asarray(candidates)]))]
        return False, None, {"angle": round(float(best_angle), 1),
                             "period": int(best),
                             "strength": round(float(ac[best]), 3),
                             "prominence": round(peak_prominence(best), 3)}
    lag = int(min(passing))

    # Subharmonische Gegenprüfung: Liegt der ECHTE Reihenabstand UNTERHALB des
    # Suchfensters (mehr als ROW_MAX_ROWS Reihen im Bild bzw. Abstand unter
    # ROW_MIN_LAG), wäre jeder Peak im Fenster nur eine Harmonische. Früher
    # wurde die dann mit hoher Konfidenz akzeptiert (gemessen: 20 Reihen im
    # Abstand 60 px -> Periode 360, 45 % der Kultur als Unkraut) — jetzt wird
    # ehrlich abgelehnt (Rückfall auf Gesamtbedeckung). Ein AC-Peak bei L/k
    # entsteht nur durch echte Struktur im Abstand L/k, nie durch ein reines
    # L-Raster (dort liegen zwischen den Peaks Täler).
    for k in (2, 3, 4):
        cand = int(round(lag / float(k)))
        if cand < 8 or cand >= lo:
            continue
        win = max(2, int(0.08 * cand))
        w_lo, w_hi = max(1, cand - win), min(len(ac) - 1, cand + win + 1)
        c = w_lo + int(np.argmax(ac[w_lo:w_hi]))
        is_peak = 0 < c < len(ac) - 1 and ac[c] > ac[c - 1] and ac[c] >= ac[c + 1]
        if is_peak and float(ac[c]) >= 0.5 * float(ac[lag]):
            return False, None, {"angle": round(float(best_angle), 1),
                                 "period": int(c),
                                 "reason": "rows_too_dense"}

    strength = float(ac[lag])
    prominence = peak_prominence(lag)
    info = {"angle": round(float(best_angle), 1), "period": int(lag),
            "strength": round(strength, 3), "prominence": round(prominence, 3)}

    # Phase: Verschiebung mit der höchsten aufsummierten Vegetation auf dem Raster
    origin = int(nz[0])
    best_phase = max(range(lag),
                     key=lambda p: float(prof[origin + p::lag].sum()) if origin + p < len(prof) else 0.0)

    band_hw = max(4, int(ROW_BAND_FRACTION * lag))
    band_rot = np.zeros((ph, pw), np.uint8)
    pos = int(origin + best_phase)    # cv2.rectangle verlangt Python-int
    n_rows = 0
    # <= statt <: eine Reihe exakt am Profilende bekommt sonst kein Band
    while pos <= int(nz[-1]):
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
    """Anschauliches Overlay: Kulturpflanze grün, Unkraut rot eingefärbt.

    Bewusst in uint8 gerechnet (addWeighted + boolesche Zuweisung) statt über
    float32-Vollbilder: Bei 1600er-Kantenlänge sparte das ~70-90 MB transiente
    Zwischenbilder — relevant auf Low-RAM-Geräten."""
    ov = bgr.copy()
    for mask, color in ((crop_mask, (60, 220, 60)),    # BGR: grün
                        (weed_mask, (50, 50, 230))):   # BGR: rot
        layer = np.empty_like(bgr)
        layer[:] = color
        blended = cv2.addWeighted(bgr, 0.45, layer, 0.55, 0.0)
        sel = mask > 0
        ov[sel] = blended[sel]
    return ov


# -------------------------------
# Beschriftung der Ergebnisbilder
# -------------------------------
_ASCII_MAP = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "Ä": "Ae", "Ö": "Oe",
                            "Ü": "Ue", "ß": "ss", "Ø": "O", "≥": ">=", "–": "-",
                            "·": "|", "é": "e", "è": "e", "à": "a", "ç": "c"})


def _ascii(text):
    """OpenCV-Hershey-Schriften kennen nur ASCII — Umlaute würden als '??'
    erscheinen. Bekannte Zeichen werden umschrieben, der Rest entfernt."""
    t = str(text).translate(_ASCII_MAP)
    return t.encode("ascii", "ignore").decode("ascii")


def _fit_text(text, max_w, scale, thickness=1, min_scale=0.4):
    """Liefert (text, scale), sodass der Text in max_w Pixel passt: zuerst
    verkleinern, notfalls mit '..' kürzen. Nie über den Rand hinaus."""
    text = _ascii(text)
    max_w = max(10, int(max_w))
    w = cv2.getTextSize(text, UI_FONT, scale, thickness)[0][0]
    if w > max_w:
        scale = max(min_scale, scale * max_w / float(w))
    while text and cv2.getTextSize(text, UI_FONT, scale, thickness)[0][0] > max_w:
        text = text[:-3] + ".." if len(text) > 3 else text[:-1]
    return text, scale


def _put(img, text, x, y_center, max_w, scale, color, thickness=1, align="left"):
    """Zeichnet Text vertikal zentriert auf y_center, kantengeglättet."""
    text, scale = _fit_text(text, max_w, scale, thickness)
    if not text:
        return 0
    (tw, th), _ = cv2.getTextSize(text, UI_FONT, scale, thickness)
    if align == "center":
        x = int(x + (max_w - tw) / 2)
    elif align == "right":
        x = int(x + max_w - tw)
    cv2.putText(img, text, (int(x), int(y_center + th / 2)), UI_FONT, scale,
                color, thickness, cv2.LINE_AA)
    return tw


def _band(width, height, text, bg=UI_BG, fg=UI_TEXT, scale=0.7, thickness=1,
          align="left", pad=12):
    band = np.empty((int(height), int(width), 3), np.uint8)
    band[:] = bg
    _put(band, text, pad, height / 2.0, width - 2 * pad, scale, fg, thickness, align)
    return band


def _with_caption(img, title, value, bar_h, scale):
    """Bild mit zweizeiliger Titelleiste darüber: Titel (dunkel) und darunter
    der Messwert (grün, fett). Zwei Zeilen statt nebeneinander, damit auch
    schmale Hochformat-Teilbilder nichts abschneiden."""
    w = img.shape[1]
    bar = np.empty((bar_h, w, 3), np.uint8)
    bar[:] = UI_BG
    pad = max(6, int(bar_h * 0.12))
    if value:
        _put(bar, title, pad, bar_h * 0.30, w - 2 * pad, scale, UI_TEXT, 1)
        _put(bar, value, pad, bar_h * 0.70, w - 2 * pad, scale * 1.15, UI_ACCENT, 2)
    else:
        _put(bar, title, pad, bar_h * 0.30, w - 2 * pad, scale, UI_TEXT, 1)
    return np.concatenate([bar, img], axis=0)


def _common_scale(texts, max_w, scale, thickness=1):
    """Größte gemeinsame Schriftgröße, bei der ALLE Texte in max_w passen —
    einheitliches Schriftbild über alle Teilbilder."""
    for t in texts:
        _, sc = _fit_text(t, max_w, scale, thickness)
        scale = min(scale, sc)
    return scale


def _hstack(parts, gap, bg=UI_BG):
    h = max(p.shape[0] for p in parts)
    out = []
    for i, p in enumerate(parts):
        if p.shape[0] < h:
            pad = np.empty((h - p.shape[0], p.shape[1], 3), np.uint8)
            pad[:] = bg
            p = np.concatenate([p, pad], axis=0)
        if i:
            g = np.empty((h, gap, 3), np.uint8)
            g[:] = bg
            out.append(g)
        out.append(p)
    return np.concatenate(out, axis=1)


def _frame(img, margin, bg=UI_BG):
    return cv2.copyMakeBorder(img, margin, margin, margin, margin,
                              cv2.BORDER_CONSTANT, value=bg)


# -------------------------------
# Panel (Original | Maske | Gefiltert [+ Unkrautgefiltert])
# -------------------------------
def _make_labeled_panel(bgr, plant_mask, filtered_mask, cov_bw, cov_f,
                        weed_filtered_mask=None, cov_wf=None, min_size=None,
                        target_h=MAX_PANEL_HEIGHT):
    """Nebeneinander: Original und Masken, jeweils mit Titelleiste und
    Bedeckungswert. Die Teilbilder werden VOR dem Zusammensetzen auf die
    Zielhöhe gebracht (spart bei 4 Spalten ~20 MB Zwischenbild)."""
    h = min(int(target_h), bgr.shape[0])

    def prep(x):
        if x.ndim == 2:
            x = cv2.cvtColor(x, cv2.COLOR_GRAY2BGR)
        return _resize_to_height(x, h)

    filt_title = f"Gefiltert >= {int(min_size)} px" if min_size else "Gefiltert"
    cols = [
        ("Original", None, bgr),
        ("Pflanzenmaske", f"{cov_bw:.2f} %", plant_mask),
        (filt_title, f"{cov_f:.2f} %", filtered_mask),
    ]
    if weed_filtered_mask is not None and cov_wf is not None:
        cols.append(("Unkrautgefiltert", f"{cov_wf:.2f} %", weed_filtered_mask))
    imgs = [prep(img) for _, _, img in cols]
    col_w = min(im.shape[1] for im in imgs)
    bar_h = max(56, int(round(h * 0.13)))
    pad = max(6, int(bar_h * 0.12))
    scale = _common_scale([t for t, _, _ in cols], col_w - 2 * pad, bar_h / 80.0)
    gap = max(8, h // 60)
    parts = [_with_caption(im, title, value, bar_h, scale)
             for im, (title, value, _) in zip(imgs, cols)]
    return _frame(_hstack(parts, gap), gap)


def _legend_band(width, entries, height=None):
    """Legende als Leiste: farbiges Kästchen + Text je Eintrag."""
    height = height or max(30, width // 40)
    band = np.empty((height, width, 3), np.uint8)
    band[:] = UI_BG
    x = 12
    box = int(height * 0.45)
    scale = height / 48.0
    slot = (width - 12) // max(1, len(entries))
    for color, text in entries:
        y0 = (height - box) // 2
        cv2.rectangle(band, (x, y0), (x + box, y0 + box), color, -1)
        _put(band, text, x + box + 8, height / 2.0, slot - box - 20, scale, UI_TEXT)
        x += slot
    return band


# ==========================================================
# Einzelbild-Analyse (wird von der App pro Bild aufgerufen)
# ==========================================================
def analyze_image(path, out_dir, tag, index,
                  weed_filter=False, row_mode=False,
                  min_size=MIN_SIZE, h_low=H_LOW_DEFAULT, h_high=H_HIGH_DEFAULT,
                  method=METHOD_EXG, frame_crop=False):
    """
    Analysiert EIN Bild und legt die Detailbilder unter <out_dir>/details/ ab.
    - tag: "before" oder "after", index: Position innerhalb der Gruppe
    - frame_crop: nur das Innere eines roten Zählrahmens auswerten (Rückfall
      auf das ganze Bild, wenn keiner gefunden wird)
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
    prefix = f"{tag}_{index:02d}"

    # Zählrahmen: ab hier gilt nur noch das Rahmeninnere als Bild. Ein
    # Kontrollbild mit eingezeichnetem Ausschnitt macht die Erkennung prüfbar.
    frame_found = None
    frame_check_path = None
    if frame_crop:
        roi, quad = _crop_to_frame(bgr)
        frame_found = roi is not None
        print(f"[PY] frame {prefix}: found={frame_found}")
        if frame_found:
            check = bgr.copy()
            cv2.polylines(check, [quad.astype(np.int32)], True, (255, 255, 0),
                          max(2, int(round(max(check.shape[:2]) / 250.0))))
            check = _resize_to_height(check, min(MAX_PANEL_HEIGHT, check.shape[0]))
            check = np.concatenate([check, _legend_band(check.shape[1], [
                ((255, 255, 0), "Erkannter Zaehlrahmen - nur das Innere wird ausgewertet")])],
                axis=0)
            frame_check_path = os.path.join(detail_dir, f"{prefix}_rahmen.jpg")
            _imwrite(frame_check_path, check, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
            bgr = roi

    brightness = round(float(cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).mean()), 1)

    plant_mask = _segment_plants(bgr, method, h_low, h_high)
    soil_light_span = _soil_light_span(bgr, plant_mask)
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
            overlay = np.concatenate([overlay, _legend_band(overlay.shape[1], [
                ((60, 220, 60), f"Kultur (Reihe) {cov_crop:.2f} %"),
                ((50, 50, 230), f"Unkraut (zw. Reihen) {cov_weed:.2f} %")])], axis=0)
            overlay_path = os.path.join(detail_dir, f"{tag}_{index:02d}_reihen.jpg")
            _imwrite(overlay_path, overlay, [int(cv2.IMWRITE_JPEG_QUALITY), 90])

    # Dateien ablegen: Masken verlustfrei als PNG, Fotos/Panels als JPEG
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
                                weed_filtered_mask, cov_wf, min_size=min_size)
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
    if soil_light_span is not None:
        item["soil_light_span"] = soil_light_span
    if frame_found is not None:
        item["frame_found"] = frame_found
        if frame_check_path:
            item["outputs"]["frame_check"] = frame_check_path
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


def _item_value(item, key):
    v = item.get(key)
    return f"{v:.1f} %" if isinstance(v, (int, float)) else "-"


def _overview_tiles(items, key, tile_h):
    tiles = []
    max_w = int(tile_h * 1.6)          # Panorama-Bilder nicht endlos breit
    for n, it in enumerate(items, 1):
        p = it.get("outputs", {}).get("original")
        im = cv2.imread(p) if p else None
        if im is None:
            continue
        im = _resize_to_height(im, tile_h)
        if im.shape[1] > max_w:
            im = cv2.resize(im, (max_w, max(1, int(round(im.shape[0] * max_w / im.shape[1])))),
                            interpolation=cv2.INTER_AREA)
            pad = tile_h - im.shape[0]
            im = cv2.copyMakeBorder(im, pad // 2, pad - pad // 2, 0, 0,
                                    cv2.BORDER_CONSTANT, value=UI_TILE_BG)
        cap_h = 36
        cap = np.empty((cap_h, im.shape[1], 3), np.uint8)
        cap[:] = UI_TILE_BG
        vw = _put(cap, _item_value(it, key), 8, cap_h / 2.0, im.shape[1] - 16, 0.6,
                  UI_TEXT, 2, align="right")
        name = f"#{n}  {os.path.splitext(it.get('file', ''))[0]}" if it.get("file") else f"#{n}"
        _put(cap, name, 8, cap_h / 2.0, max(10, im.shape[1] - 16 - vw - 10), 0.5, UI_TEXT_MUTED)
        tiles.append(np.concatenate([im, cap], axis=0))
    return tiles


def _wrap_tiles(tiles, inner_w, gap):
    """Kacheln zeilenweise umbrechen (statt bei vielen Bildern winzig zu skalieren)."""
    lines, cur, cur_w = [], [], 0
    for t in tiles:
        w = t.shape[1] if not cur else cur_w + gap + t.shape[1]
        if cur and w > inner_w:
            lines.append(cur)
            cur, w = [], t.shape[1]
        cur.append(t)
        cur_w = w
    if cur:
        lines.append(cur)
    out = []
    for line in lines:
        row = _hstack(line, gap)
        out.append(_pad_to_width(row, inner_w))
    return out


def _build_overview(before_items, after_items, out_dir, key="coverage_bw_percent",
                    key_label="Bedeckung", csc=None, avg_before=None, avg_after=None):
    """Übersichtsbild: Titelleiste, je Gruppe eine Kopfzeile (Anzahl, Mittel)
    und die Bilder als Kacheln mit Nummer, Dateiname und Messwert."""
    tile_h = OVERVIEW_TILE_HEIGHT
    gap, margin = 12, 24
    groups = []
    for title, items, color, avg in (("VORHER", before_items, UI_ACCENT, avg_before),
                                     ("NACHHER", after_items, UI_ACCENT_2, avg_after)):
        tiles = _overview_tiles(items, key, tile_h)
        if tiles:
            groups.append((title, tiles, color, avg))
    if not groups:
        return None

    natural = max(sum(t.shape[1] for t in g[1]) + gap * (len(g[1]) - 1) for g in groups)
    inner_w = int(min(MAX_OVERVIEW_WIDTH - 2 * margin, max(OVERVIEW_MIN_WIDTH - 2 * margin, natural)))
    width = inner_w + 2 * margin

    blocks = []
    # Titelleiste
    head_h = 64
    head = np.empty((head_h, width, 3), np.uint8)
    head[:] = UI_ACCENT
    right = f"CSC {csc:.1f} %" if isinstance(csc, (int, float)) else ""
    rw = _put(head, right, margin, head_h / 2.0, inner_w, 0.95, UI_ON_ACCENT, 2,
              align="right") if right else 0
    _put(head, "SmartWeed  |  Vorher / Nachher", margin, head_h / 2.0,
         inner_w - rw - 20, 0.95, UI_ON_ACCENT, 2)
    blocks.append(head)

    for title, tiles, color, avg in groups:
        sec_h = 54
        sec = np.empty((sec_h, width, 3), np.uint8)
        sec[:] = UI_BG
        cv2.rectangle(sec, (margin, 14), (margin + 6, sec_h - 8), color, -1)
        tw = _put(sec, title, margin + 16, sec_h / 2.0 + 3, inner_w // 2, 0.8, color, 2)
        n = len(tiles)
        info = f"{n} {'Bild' if n == 1 else 'Bilder'}"
        if isinstance(avg, (int, float)):
            info += f"   |   Mittel {key_label}: {avg:.2f} %"
        _put(sec, info, margin + 16 + tw + 24, sec_h / 2.0 + 3,
             inner_w - tw - 40, 0.6, UI_TEXT_MUTED)
        blocks.append(sec)
        for line in _wrap_tiles(tiles, inner_w, gap):
            blocks.append(cv2.copyMakeBorder(line, 0, gap, margin, margin,
                                             cv2.BORDER_CONSTANT, value=UI_BG))

    blocks.append(_band(width, 40, f"Werte: {key_label} in % der ausgewerteten Bildflaeche",
                        fg=UI_TEXT_MUTED, scale=0.5, pad=margin))
    overview = np.concatenate(blocks, axis=0)
    overview = _maybe_downscale_long_side(overview, MAX_OVERVIEW_WIDTH)
    path = os.path.join(out_dir, "uebersicht_vorher_nachher.jpg")
    _imwrite(path, overview, [int(cv2.IMWRITE_JPEG_QUALITY), 90])
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
        # Nicht berechenbare Mittelwerte werden WEGGELASSEN statt None gesetzt
        # (JSON-Kontrakt: kein null — siehe Kommentar am result-Objekt)
        avg = {
            "bw": _mean_of(group, "coverage_bw_percent"),
            "filtered": _mean_of(group, "coverage_filtered_percent"),
            "weedfiltered": _mean_of(group, "coverage_weedfiltered_percent"),
            "crop": _mean_of(group, "coverage_crop_percent"),
            "weed": _mean_of(group, "coverage_weed_percent"),
            "brightness": _mean_of(group, "brightness"),
        }
        return {k: v for k, v in avg.items() if v is not None}

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

    # Warnung 3: Lichtart verschieden (Sonne vs. bewölkt). Die Helligkeit allein
    # erkennt das nicht (gemessen: Sonne 136 vs. Wolken 125 — unter der
    # Schwelle), wohl aber die Hell-Dunkel-Spanne des Bodens. Ein Lichtwechsel
    # verschiebt die Segmentierung in eine Richtung und damit den CSC.
    def group_sunny(group):
        spans = [i["soil_light_span"] for i in group if i.get("soil_light_span") is not None]
        if not spans:
            return None
        return sum(1 for s in spans if s >= LIGHT_SUN_SPAN) * 2 >= len(spans)
    sun_b, sun_a = group_sunny(before), group_sunny(after)
    light_mismatch_warning = sun_b is not None and sun_a is not None and sun_b != sun_a

    # Übersichtsbild mit derselben CSC-Basis wie die App-Ergebnisseite
    # (SummaryFragment): Modus C mit Reihen -> Kultur, Modus B -> unkraut-
    # gefiltert (Rückfall gefiltert), sonst Pflanzenmaske (SW).
    weed_filter = any("coverage_weedfiltered_percent" in i for i in items)
    if rows_detected and "crop" in avg_b and "crop" in avg_a:
        basis, key, key_label = "crop", "coverage_crop_percent", "Kulturbedeckung"
    elif weed_filter and "weedfiltered" in avg_b and "weedfiltered" in avg_a:
        basis, key, key_label = "weedfiltered", "coverage_weedfiltered_percent", "Bedeckung unkrautgefiltert"
    elif weed_filter:
        basis, key, key_label = "filtered", "coverage_filtered_percent", "Bedeckung gefiltert"
    else:
        basis, key, key_label = "bw", "coverage_bw_percent", "Pflanzenbedeckung"
    b_val, a_val = avg_b.get(basis), avg_a.get(basis)
    csc = None
    if b_val is not None and a_val is not None and b_val > 0 and a_val >= 0:
        csc = (b_val - a_val) / b_val * 100.0
    overview_path = _build_overview(before, after, out_dir, key, key_label, csc,
                                    b_val, a_val)

    # Kontrakt Python->Java: Optionale Schlüssel werden durchgängig WEGGELASSEN
    # statt auf None/null gesetzt — Androids JSONObject.optString würde aus
    # einem JSON-null den String "null" machen; optDouble/optBoolean verhalten
    # sich bei fehlendem Schlüssel identisch zu ihrem Default.
    deltas = {k: delta(k) for k in ("bw", "filtered", "weedfiltered", "crop", "weed")}
    result = {
        "mode": "group",
        "count_before": len(before),
        "count_after": len(after),
        "weed_filter": weed_filter,
        "row_mode": row_mode,
        "brightness_warning": brightness_warning,
        "brightness_spread_warning": brightness_spread_warning,
        "light_mismatch_warning": light_mismatch_warning,
        "out_dir": out_dir,
        "avg_before": avg_b,
        "avg_after": avg_a,
        "delta": {k: v for k, v in deltas.items() if v is not None},
        "combo": {"overview": overview_path} if overview_path else {},
        "items": items,
    }
    if rows_detected is not None:
        result["rows_detected"] = rows_detected
    # Zählrahmen: nur gesetzt, wenn der Rahmen-Zuschnitt angefordert war
    frame_items = [i for i in items if "frame_found" in i]
    if frame_items:
        result["frame_crop"] = True
        result["frame_missing_count"] = sum(1 for i in frame_items if not i["frame_found"])
    print(f"[PY] group summary: {len(before)} vorher / {len(after)} nachher, "
          f"rows={rows_detected}, bright_warn={brightness_warning}, "
          f"spread_warn={brightness_spread_warning}, light_warn={light_mismatch_warning}, "
          f"frame_missing={result.get('frame_missing_count')}")
    return json.dumps(result, ensure_ascii=False, allow_nan=False)
