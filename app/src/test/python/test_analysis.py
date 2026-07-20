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


def test_hsv_bei_normallicht_korrekt(_=None):
    img, crop, weed = make_field(brightness=1.0)
    truth = cv2.bitwise_or(crop, weed)
    assert iou(seg(img, "hsv"), truth) > 0.9


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
