"""Golden-Image-Tests für die Bildanalyse (analysis.py).

Läuft ohne Android — pur mit numpy/opencv:
    PYTHONPATH=app/src/main/python pytest app/src/test/python/ -v

Die Testbilder sind synthetisch (Reihenfelder, Wolken, Chaos, Boden), damit
das Soll-Ergebnis exakt bekannt ist. Genau die hier abgedeckten Fälle haben
in v1.3 zwei echte Bugs enthalten (Otsu-Untersegmentierung bei Vollbewuchs,
Reihen-Falschpositive bei glatten Profilen) — diese Suite verhindert Rückfälle.
"""
import json

import cv2
import numpy as np
import pytest

import analysis


# ---------------------------------------------------------------------------
# Synthetische Testbilder
# ---------------------------------------------------------------------------
def make_field(w=1200, h=900, n_rows=7, angle_deg=8, weed_frac=0.5,
               brightness=1.0, gap_frac=0.35, seed=42):
    """Boden + Getreidereihen (gedreht) + zufällige Unkraut-Blobs."""
    rng = np.random.default_rng(seed)
    soil = np.zeros((h, w, 3), np.uint8)
    soil[:] = (60, 95, 130)  # BGR Braun
    noise = rng.normal(0, 12, (h, w, 3))
    img = np.clip(soil.astype(np.float32) + noise, 0, 255).astype(np.uint8)

    crop = np.zeros((h, w), np.uint8)
    weed = np.zeros((h, w), np.uint8)

    spacing = w / (n_rows + 1)
    theta = np.deg2rad(angle_deg)
    for r in range(1, n_rows + 1):
        x0 = r * spacing
        for t in np.linspace(-h, 2 * h, 400):
            x = int(x0 + t * np.sin(theta))
            y = int(t * np.cos(theta))
            if 0 <= x < w and 0 <= y < h and rng.random() > gap_frac:
                cv2.circle(crop, (x + int(rng.normal(0, 3)), y),
                           int(rng.integers(4, 11)), 255, -1)

    for _ in range(int(80 * weed_frac)):
        cv2.circle(weed, (int(rng.integers(0, w)), int(rng.integers(0, h))),
                   int(rng.integers(3, 9)), 255, -1)

    plant = cv2.bitwise_or(crop, weed)
    green = np.zeros_like(img)
    green[:] = (int(40 * brightness), int(150 * brightness), int(45 * brightness))
    img = np.where(plant[..., None] > 0, green, img)
    return (np.clip(img.astype(np.float32) * brightness, 0, 255).astype(np.uint8),
            crop, weed)


def make_cloud_mask(seed, w=1200, h=900):
    """Breite Vegetations-Wolke OHNE Reihenstruktur."""
    rng = np.random.default_rng(seed)
    mask = np.zeros((h, w), np.uint8)
    for _ in range(400):
        x = int(rng.normal(w / 2, 220))
        y = int(rng.normal(h / 2, 220))
        if 0 <= x < w and 0 <= y < h:
            cv2.circle(mask, (x, y), int(rng.integers(4, 10)), 255, -1)
    return mask


def make_chaos_image(seed, w=1200, h=900):
    """Zufällig verteilte Pflanzen-Blobs auf Boden, keine Reihen."""
    rng = np.random.default_rng(seed)
    img = np.zeros((h, w, 3), np.uint8)
    img[:] = (60, 95, 130)
    for _ in range(150):
        cv2.circle(img, (int(rng.integers(0, w)), int(rng.integers(0, h))),
                   int(rng.integers(3, 12)), (40, 150, 45), -1)
    return img


def iou(a, b):
    inter = np.logical_and(a > 0, b > 0).sum()
    union = np.logical_or(a > 0, b > 0).sum()
    return inter / union if union else 0.0


def seg(img, method):
    return analysis._segment_plants(img, method, 35, 85)


# ---------------------------------------------------------------------------
# Segmentierung
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("brightness", [0.45, 1.0, 1.4])
def test_exg_robust_gegen_helligkeit(brightness):
    img, crop, weed = make_field(brightness=brightness)
    truth = cv2.bitwise_or(crop, weed)
    assert iou(seg(img, "exg"), truth) > 0.9


def test_hsv_bei_normallicht_korrekt():
    img, crop, weed = make_field(brightness=1.0)
    truth = cv2.bitwise_or(crop, weed)
    assert iou(seg(img, "hsv"), truth) > 0.9


def test_hsv_schwaeche_bei_dunkelheit_dokumentiert():
    """Beleg für die dokumentierte HSV-Schwäche (Motivation für ExG als
    Standard): Bei dunklem Licht fällt HSV deutlich hinter ExG zurück,
    weil Sättigung/Helligkeit unter die festen SV_MIN-Grenzen rutschen."""
    img, crop, weed = make_field(brightness=0.45)
    truth = cv2.bitwise_or(crop, weed)
    iou_hsv = iou(seg(img, "hsv"), truth)
    iou_exg = iou(seg(img, "exg"), truth)
    assert iou_exg > 0.9
    assert iou_hsv < iou_exg


def test_exg_halluziniert_nicht_auf_boden():
    soil, _, _ = make_field(weed_frac=0, n_rows=0, gap_frac=1.0)
    assert (seg(soil, "exg") > 0).mean() < 0.01


def test_otsu_vollbewuchs_nicht_halbiert():
    """Regression v1.3-Bug: Otsu legte den Schwellwert bei unimodalem
    ExG-Histogramm mitten in die Vegetation (52% statt ~100%)."""
    rng = np.random.default_rng(3)
    h, w = 600, 800
    g = 120 + 60 * rng.random((h, w))
    r = 90 + 30 * rng.random((h, w))
    b = 70 + 30 * rng.random((h, w))
    img = np.stack([b, g, r], axis=2).astype(np.uint8)
    assert (analysis._exg_otsu_mask(img) > 0).mean() > 0.95


# ---------------------------------------------------------------------------
# Reihen-Erkennung (Modus C)
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("angle", [0, 12, 20])
def test_reihen_synthetisch_erkannt(angle):
    img, _, _ = make_field(angle_deg=angle)
    mask = analysis._remove_small_components(seg(img, "exg"), 50)
    ok, band, info = analysis._detect_rows(mask)
    assert ok, info
    assert band is not None and band.shape == mask.shape


@pytest.mark.parametrize("seed", [0, 3, 5])
def test_reihen_wolke_abgelehnt(seed):
    """Regression v1.3-Bug: glatt abfallende AC-Kurven erzeugten
    Falsch-Positive — die Prominenz-Prüfung fängt sie ab."""
    ok, _, info = analysis._detect_rows(make_cloud_mask(seed))
    assert not ok, info


@pytest.mark.parametrize("seed", [1, 42])
def test_reihen_chaos_abgelehnt(seed):
    mask = analysis._remove_small_components(seg(make_chaos_image(seed), "exg"), 50)
    ok, _, info = analysis._detect_rows(mask)
    assert not ok, info


def test_reihen_leere_maske_abgelehnt():
    ok, _, info = analysis._detect_rows(np.zeros((900, 1200), np.uint8))
    assert not ok


def test_reihen_quer_im_bild_erkannt():
    """Horizontale Reihen (quer zur Suchachse): der Transponier-Durchlauf
    deckt die Orientierungen ab, die die ±45°-Winkelsuche nicht erreicht."""
    img, _, _ = make_field(angle_deg=0)
    mask = analysis._remove_small_components(seg(img, "exg"), 50)
    mask_quer = np.ascontiguousarray(mask.T)
    ok, band, info = analysis._detect_rows(mask_quer)
    assert ok, info
    assert band is not None and band.shape == mask_quer.shape


def test_reihen_wenige_weite_reihen_erkannt():
    """Regression: Die frühere verzerrte (biased) Autokorrelation dämpfte
    große Reihenabstände — Felder mit wenigen, weiten Reihen fielen
    systematisch durch die Mindeststärke."""
    m = np.zeros((900, 1200), np.uint8)
    for i in range(1, 5):                      # 4 Reihen, Abstand 240 px
        x = i * 240
        cv2.rectangle(m, (x - 25, 0), (x + 25, 899), 255, -1)
    ok, _, info = analysis._detect_rows(m)
    assert ok, info


def test_reihen_extreme_streifenmaske_abgelehnt():
    """Regression: Extreme Streifenformate erzeugten bei der Rotation
    Treppenartefakte, die eine Scheinperiodizität vortäuschten."""
    ok, _, info = analysis._detect_rows(np.full((1, 2000), 255, np.uint8))
    assert not ok, info


def test_reihen_vollflaechige_maske_abgelehnt():
    ok, _, info = analysis._detect_rows(np.full((900, 1200), 255, np.uint8))
    assert not ok, info


# ---------------------------------------------------------------------------
# Unkrautfilter (Modus B)
# ---------------------------------------------------------------------------
def test_unkrautfilter_fuellt_keine_loecher():
    """Regression v1.4-Bug: drawContours(FILLED) malte Boden-Löcher innerhalb
    von Pflanzen aus und überschätzte die Bedeckung massiv (20% -> 44%)."""
    m = np.zeros((400, 400), np.uint8)
    cv2.circle(m, (200, 200), 150, 255, -1)
    cv2.circle(m, (200, 200), 110, 0, -1)      # Boden-Loch in der Pflanze
    wf = analysis._weed_filter_mask(m)
    cov_in = (m > 0).mean()
    cov_out = (wf > 0).mean()
    assert abs(cov_out - cov_in) < 0.005


def test_unkrautfilter_bei_unkraut_mehrheit():
    """Regression v1.4-Bug: Stellte Unkraut die Komponenten-Mehrheit, war der
    einfache Median selbst eine Unkrautgröße und der Filter entfernte nichts.
    Der flächengewichtete Median nimmt die Kultur-Streifen als Referenz."""
    rng = np.random.default_rng(7)
    m = np.zeros((900, 1200), np.uint8)
    for i in range(1, 16):                     # 15 Kultur-Streifen
        x = i * 75
        cv2.rectangle(m, (x - 14, 0), (x + 14, 899), 255, -1)
    cov_rows_only = (m > 0).mean() * 100
    for _ in range(60):                        # 60 kleine Unkraut-Blobs
        cv2.circle(m, (int(rng.integers(0, 1200)), int(rng.integers(0, 900))),
                   int(rng.integers(5, 12)), 255, -1)
    cov_with_blobs = (m > 0).mean() * 100
    cov_out = (analysis._weed_filter_mask(m) > 0).mean() * 100
    # Die freistehenden Blobs müssen (fast) vollständig verschwinden
    assert cov_out < cov_rows_only + 0.5
    assert cov_out < cov_with_blobs - 0.3


def test_unkrautfilter_leere_maske():
    wf = analysis._weed_filter_mask(np.zeros((100, 100), np.uint8))
    assert (wf > 0).sum() == 0


# ---------------------------------------------------------------------------
# Extrembilder / Robustheit
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("img", [
    np.zeros((100, 100, 3), np.uint8),                 # komplett schwarz
    np.full((100, 100, 3), 255, np.uint8),             # komplett weiß
    np.full((1, 1, 3), 128, np.uint8),                 # 1x1
    np.full((1, 500, 3), 128, np.uint8),               # extremes Seitenverhältnis
])
def test_segmentierung_extrembilder_crashen_nicht(img):
    for method in ("exg", "hsv"):
        mask = seg(img, method)
        assert mask.shape == img.shape[:2]


def test_reihen_trennen_kultur_und_unkraut():
    img, crop, weed = make_field(angle_deg=10, weed_frac=1.0)
    mask = analysis._remove_small_components(seg(img, "exg"), 50)
    ok, band, _ = analysis._detect_rows(mask)
    assert ok
    crop_det = cv2.bitwise_and(mask, band)
    weed_det = cv2.bitwise_and(mask, cv2.bitwise_not(band))
    crop_recall = np.logical_and(crop_det > 0, crop > 0).sum() / max(1, (crop > 0).sum())
    weed_purity = np.logical_and(weed_det > 0, weed > 0).sum() / max(1, (weed_det > 0).sum())
    assert crop_recall > 0.9
    assert weed_purity > 0.9


# ---------------------------------------------------------------------------
# Gruppen-Auswertung
# ---------------------------------------------------------------------------
def _item(tag, index, bw, flt, bright, rows=None, crop=None, weed=None):
    it = {"tag": tag, "index": index, "coverage_bw_percent": bw,
          "coverage_filtered_percent": flt, "brightness": bright, "outputs": {}}
    if rows is not None:
        it["rows_detected"] = rows
        if rows:
            it["coverage_crop_percent"] = crop
            it["coverage_weed_percent"] = weed
    return it


def test_gruppen_mittel_bei_ungleicher_anzahl(tmp_path):
    items = [_item("before", 0, 8.0, 7.8, 100),
             _item("before", 1, 10.0, 9.8, 100),
             _item("before", 2, 9.0, 8.8, 100),
             _item("after", 0, 6.0, 5.9, 100),
             _item("after", 1, 7.0, 6.9, 100)]
    s = json.loads(analysis.build_group_summary(json.dumps(items), str(tmp_path)))
    assert s["count_before"] == 3 and s["count_after"] == 2
    assert s["avg_before"]["bw"] == 9.0
    assert s["avg_after"]["bw"] == 6.5
    assert s["delta"]["bw"] == -2.5


def test_helligkeitswarnung(tmp_path):
    items = [_item("before", 0, 8.0, 7.8, 130),
             _item("after", 0, 6.0, 5.9, 60)]
    s = json.loads(analysis.build_group_summary(json.dumps(items), str(tmp_path)))
    assert s["brightness_warning"] is True


def test_helligkeitswarnung_negativfall(tmp_path):
    items = [_item("before", 0, 8.0, 7.8, 100),
             _item("after", 0, 6.0, 5.9, 110)]
    s = json.loads(analysis.build_group_summary(json.dumps(items), str(tmp_path)))
    assert s["brightness_warning"] is False
    assert s["brightness_spread_warning"] is False


def test_helligkeits_streuung_trotz_gleichem_mittel(tmp_path):
    """Ein sehr dunkles + sehr helles Vorher-Bild heben sich im Gruppenmittel
    auf — die Streuungs-Warnung erkennt die Ausreißer trotzdem."""
    items = [_item("before", 0, 8.0, 7.8, 40),
             _item("before", 1, 8.0, 7.8, 180),
             _item("after", 0, 6.0, 5.9, 110)]
    s = json.loads(analysis.build_group_summary(json.dumps(items), str(tmp_path)))
    assert s["brightness_warning"] is False        # Mittel 110 vs. 110
    assert s["brightness_spread_warning"] is True  # Streuung 140 > 40


def test_overview_key_fehlt_ohne_originale(tmp_path):
    """Regression: JSON-null würde in Android zum String 'null'."""
    items = [_item("before", 0, 1.0, 1.0, 100), _item("after", 0, 0.9, 0.9, 100)]
    s = json.loads(analysis.build_group_summary(json.dumps(items), str(tmp_path)))
    assert "overview" not in s["combo"]


def test_rows_mehrheits_aggregation(tmp_path):
    items = [_item("before", 0, 8.0, 7.9, 100, rows=True, crop=7.0, weed=0.5),
             _item("before", 1, 8.2, 8.0, 100, rows=True, crop=7.2, weed=0.4),
             _item("before", 2, 8.1, 8.0, 100, rows=False),
             _item("after", 0, 6.5, 6.4, 100, rows=True, crop=6.0, weed=0.2)]
    s = json.loads(analysis.build_group_summary(json.dumps(items), str(tmp_path)))
    assert s["rows_detected"] is True          # 2/3 + 1/1 = Mehrheit
    assert s["avg_before"]["crop"] == 7.1      # nur aus erkannten Bildern

    for it in items[:2]:
        it["rows_detected"] = False
        it.pop("coverage_crop_percent"); it.pop("coverage_weed_percent")
    s2 = json.loads(analysis.build_group_summary(json.dumps(items), str(tmp_path)))
    assert s2["rows_detected"] is False        # 0/3 vorher = keine Mehrheit


def test_gruppe_leer_und_einseitig(tmp_path):
    """Leere Item-Liste und nur-Vorher-Gruppe dürfen nicht crashen. Nicht
    berechenbare Mittelwerte werden gemäß JSON-Kontrakt WEGGELASSEN (kein
    null — Androids optString macht aus JSON-null den String "null")."""
    s = json.loads(analysis.build_group_summary("[]", str(tmp_path)))
    assert s["count_before"] == 0 and s["count_after"] == 0
    assert "bw" not in s["avg_before"]
    assert "bw" not in s["delta"]
    assert "overview" not in s["combo"]

    only_before = [_item("before", 0, 8.0, 7.8, 100)]
    s2 = json.loads(analysis.build_group_summary(json.dumps(only_before), str(tmp_path)))
    assert s2["count_before"] == 1 and s2["count_after"] == 0
    assert s2["avg_before"]["bw"] == 8.0
    assert "bw" not in s2["avg_after"]
    assert "bw" not in s2["delta"]


def test_gruppen_json_enthaelt_keine_nulls(tmp_path):
    """Der komplette Gruppen-JSON-Kontrakt: nirgendwo darf ein JSON-null
    stehen — optionale Schlüssel werden weggelassen (Regression zu v1.5,
    wo avg_*/delta/rows_detected echte nulls emittierten)."""
    def assert_no_none(obj, path="root"):
        if isinstance(obj, dict):
            for k, v in obj.items():
                assert v is not None, f"JSON-null bei {path}.{k}"
                assert_no_none(v, f"{path}.{k}")
        elif isinstance(obj, list):
            for i, v in enumerate(obj):
                assert_no_none(v, f"{path}[{i}]")

    # Ohne row_mode: rows_detected muss KOMPLETT fehlen, nicht null sein
    items = [_item("before", 0, 8.0, 7.8, 100), _item("after", 0, 6.0, 5.9, 104)]
    s = json.loads(analysis.build_group_summary(json.dumps(items), str(tmp_path)))
    assert "rows_detected" not in s
    assert_no_none(s)


# ---------------------------------------------------------------------------
# analyze_image End-to-End (JSON-Kontrakt + geschriebene Dateien)
# ---------------------------------------------------------------------------
def test_analyze_image_end_to_end(tmp_path):
    import os
    img, _, _ = make_field()
    src = str(tmp_path / "feld.jpg")
    cv2.imwrite(src, img)
    out_dir = str(tmp_path / "out")

    item = json.loads(analysis.analyze_image(src, out_dir, "before", 0,
                                             weed_filter=True, row_mode=True,
                                             method="exg"))
    # Pflicht-Schlüssel und Typen
    assert item["file"] == "feld.jpg"
    assert item["tag"] == "before" and item["index"] == 0
    for key in ("brightness", "coverage_bw_percent", "coverage_filtered_percent",
                "coverage_weedfiltered_percent"):
        assert isinstance(item[key], float), key
    assert isinstance(item["rows_detected"], bool)
    # Alle gemeldeten Ausgabedateien müssen wirklich existieren
    for name, p in item["outputs"].items():
        assert os.path.isfile(p), f"outputs[{name}] fehlt: {p}"
    # .nomedia schützt die Detailbilder vor der Galerie
    assert os.path.isfile(os.path.join(out_dir, "details", ".nomedia"))


def test_analyze_image_fehlerpfade(tmp_path):
    with pytest.raises(FileNotFoundError):
        analysis.analyze_image(str(tmp_path / "fehlt.jpg"), str(tmp_path), "before", 0)
    kaputt = str(tmp_path / "kaputt.jpg")
    with open(kaputt, "w") as f:
        f.write("kein bild")
    with pytest.raises(ValueError):
        analysis.analyze_image(kaputt, str(tmp_path), "before", 0)


# ---------------------------------------------------------------------------
# v1.6-Regressionen: Reihen-Erkennung (Harmonischen-Bug) und Unkrautfilter
# ---------------------------------------------------------------------------
def make_clean_rows(n_rows, spacing, row_w=18, h=900):
    """Ideales Reihenfeld OHNE Rauschen/Lücken — genau der Fall, in dem die
    unverzerrte Autokorrelation die Harmonischen über das Fundamental hebt."""
    w = n_rows * spacing + spacing // 2
    mask = np.zeros((h, w), np.uint8)
    for i in range(n_rows):
        c = spacing // 2 + i * spacing
        mask[:, c - row_w // 2:c + row_w // 2] = 255
    return mask


def test_reihen_sauberes_feld_fundamental_statt_harmonischer():
    """Regression v1.5-Bug (kritisch): Bei sauberen periodischen Mustern
    wählte das argmax die 2. Harmonische (Periode 2L) — nur 50 % der
    Kulturpixel lagen im Band, die halbe Kultur wurde als Unkraut gezählt.
    Der Detektor muss das FUNDAMENTAL und damit ALLE Reihen finden."""
    spacing = 150
    mask = make_clean_rows(8, spacing)
    ok, band, info = analysis._detect_rows(mask)
    assert ok
    assert abs(info["period"] - spacing) <= spacing * 0.1, info
    assert info["n_rows"] == 8, info
    in_band = (cv2.bitwise_and(mask, band) > 0).sum() / (mask > 0).sum()
    assert in_band > 0.95, f"nur {in_band:.0%} der Kultur im Band: {info}"


def test_reihen_zu_dicht_ehrlich_abgelehnt():
    """Regression v1.5-Bug (hoch): Lag der echte Reihenabstand unter dem
    Suchfenster (mehr als ROW_MAX_ROWS Reihen), akzeptierte der Detektor
    zwingend eine Harmonische mit hoher Konfidenz (45 % der Kultur als
    Unkraut). Jetzt: ehrlicher Rückfall auf die Gesamtbedeckung."""
    mask = make_clean_rows(20, 60, row_w=12)
    ok, band, info = analysis._detect_rows(mask)
    assert not ok, f"zu dichte Reihen wurden akzeptiert: {info}"


def test_reihen_wenige_weite_reihen_kein_falscher_subharmonik_veto():
    """Gegenprobe zum Subharmonik-Veto: Ein legitimes Raster mit wenigen,
    weiten Reihen hat bei L/2, L/3 AC-Täler (keine Peaks) und darf durch
    die Gegenprüfung NICHT abgelehnt werden."""
    spacing = 220
    mask = make_clean_rows(5, spacing, row_w=50)
    ok, band, info = analysis._detect_rows(mask)
    assert ok, info
    assert abs(info["period"] - spacing) <= spacing * 0.1, info


def test_unkrautfilter_verschont_objekt_im_loch():
    """Regression v1.5-Bug: drawContours(FILLED) auf der Außenkontur einer
    Unkraut-Komponente malte deren Löcher mit aus — ein separates
    Kultur-Objekt IM Loch wurde mitgelöscht. Jetzt wird über die
    Komponenten-Labels exakt pixelgenau gelöscht."""
    mask = np.zeros((400, 600), np.uint8)
    cv2.circle(mask, (150, 200), 60, 255, -1)                # große Kultur-Referenz
    cv2.circle(mask, (450, 200), 30, 255, -1)                # Unkraut-Ring ...
    cv2.circle(mask, (450, 200), 20, 0, -1)                  # ... mit Loch
    cv2.line(mask, (440, 190), (460, 210), 255, 2)           # dünnes Objekt IM Loch
    line_probe = np.zeros_like(mask)
    cv2.line(line_probe, (440, 190), (460, 210), 255, 2)

    out = analysis._weed_filter_mask(mask)
    ring_only = cv2.circle(np.zeros_like(mask), (450, 200), 30, 255, -1)
    ring_only = cv2.circle(ring_only, (450, 200), 20, 0, -1)
    assert (cv2.bitwise_and(out, ring_only) > 0).sum() == 0, "Ring (Unkraut) blieb stehen"
    survived = (cv2.bitwise_and(out, line_probe) > 0).sum() / (line_probe > 0).sum()
    assert survived > 0.9, f"Objekt im Loch wurde mitgelöscht ({survived:.0%} übrig)"


def test_unkrautfilter_pixelflaeche_statt_konturflaeche():
    """Regression v1.5-Bug: cv2.contourArea der Außenkontur zählte Löcher
    mit — löchrige Objekte wurden zu groß bewertet und fälschlich als
    Kultur behalten. Maß ist jetzt die echte Pixelfläche (CC_STAT_AREA)."""
    mask = np.zeros((500, 700), np.uint8)
    cv2.circle(mask, (170, 250), 76, 255, -1)                # Referenz: 18.1k px
    cv2.circle(mask, (500, 250), 40, 255, -1)                # kompakte Scheibe ...
    cv2.circle(mask, (500, 250), 15, 0, -1)                  # ... mit Loch: 4.3k px
    # Konturfläche der Scheibe (5.0k) läge ÜBER der Schwelle (0.25*18.1k=4.5k),
    # die echte Pixelfläche (4.3k) liegt DARUNTER -> muss als Unkraut entfernt werden
    out = analysis._weed_filter_mask(mask)
    disk_probe = cv2.circle(np.zeros_like(mask), (500, 250), 40, 255, -1)
    assert (cv2.bitwise_and(out, disk_probe) > 0).sum() == 0, \
        "löchrige Scheibe wurde über die Konturfläche fälschlich behalten"


def test_unkrautfilter_flaechen_mehrheit_bekannte_grenze():
    """DOKUMENTIERT eine bekannte Grenze (kein Soll-Verhalten im engeren
    Sinn): Stellt Unkraut >50 % der PflanzenFLÄCHE, wird der flächen-
    gewichtete Median selbst eine Unkrautgröße und der Filter faktisch
    wirkungslos — Modus B nähert sich Modus A. Schlägt dieser Test fehl,
    wurde die Heuristik geändert: dann Docstring + Methodik prüfen."""
    rng = np.random.default_rng(7)
    mask = np.zeros((600, 900), np.uint8)
    for x in (100, 350, 600, 850):                            # 4 schmale Kulturstreifen
        cv2.line(mask, (x, 0), (x, 599), 255, 6)
    for _ in range(120):                                      # dichte Unkraut-Blobs
        cv2.circle(mask, (int(rng.integers(0, 900)), int(rng.integers(0, 600))),
                   int(rng.integers(6, 12)), 255, -1)
    out = analysis._weed_filter_mask(mask)
    cov_in = (mask > 0).mean() * 100
    cov_out = (out > 0).mean() * 100
    assert cov_out > cov_in * 0.7, (
        "Flächen-Mehrheits-Verhalten hat sich geändert — Doku anpassen "
        f"(in {cov_in:.1f}% -> out {cov_out:.1f}%)")


# ---------------------------------------------------------------------------
# v1.6: HSV-Pfad End-to-End und Item-JSON-Kontrakt ohne erkannte Reihen
# ---------------------------------------------------------------------------
def test_analyze_image_hsv_end_to_end(tmp_path):
    import os
    img, _, _ = make_field()
    src = str(tmp_path / "feld.jpg")
    cv2.imwrite(src, img)
    item = json.loads(analysis.analyze_image(src, str(tmp_path / "out"), "before", 0,
                                             weed_filter=True, row_mode=True,
                                             method="hsv"))
    assert item["coverage_bw_percent"] > 0
    for name, p in item["outputs"].items():
        assert os.path.isfile(p), f"outputs[{name}] fehlt: {p}"


def test_hsv_invertierter_hue_bereich_crasht_nicht(tmp_path):
    """Experten-Einstellungen erlauben h_low/h_high frei — ein (durch die
    Java-Validierung eigentlich verhinderter) invertierter Bereich darf
    höchstens eine leere Maske ergeben, nie einen Crash."""
    img, _, _ = make_field()
    mask = analysis._segment_plants(img, "hsv", 85, 35)
    assert (mask > 0).mean() < 0.01
    src = str(tmp_path / "feld.jpg")
    cv2.imwrite(src, img)
    item = json.loads(analysis.analyze_image(src, str(tmp_path / "out"), "before", 0,
                                             h_low=85, h_high=35, method="hsv"))
    assert item["coverage_bw_percent"] == 0.0


def test_item_json_ohne_erkannte_reihen(tmp_path):
    """row_mode=True ohne erkennbare Reihen: rows_detected=False, und die
    Nur-bei-Reihen-Schlüssel müssen FEHLEN (nicht null sein).
    (Seed 1 wird sicher abgelehnt; Seed 5 wäre ein grenzwertiges
    Falsch-Positiv knapp über beiden Schwellen — auch schon in v1.5.)"""
    img = make_chaos_image(1)
    src = str(tmp_path / "chaos.jpg")
    cv2.imwrite(src, img)
    item = json.loads(analysis.analyze_image(src, str(tmp_path / "out"), "after", 1,
                                             row_mode=True, method="exg"))
    assert item["rows_detected"] is False
    assert "coverage_crop_percent" not in item
    assert "coverage_weed_percent" not in item
    assert "row_overlay" not in item["outputs"]
