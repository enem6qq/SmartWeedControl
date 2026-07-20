# SmartWeedControl

Android-App zur digitalen Entscheidungsunterstützung bei der mechanischen
Unkrautbekämpfung durch Striegeln.

Die App analysiert Vorher-/Nachher-Fotos eines Feldes mittels Bildverarbeitung
(OpenCV), berechnet den **Crop Soil Cover (CSC)** und leitet daraus eine klare
Handlungsempfehlung zur Striegelintensität ab. Empirisch gilt ein CSC von
ca. 10 % als optimal: Liegt der gemessene Wert darunter, kann aggressiver
gestriegelt werden, liegt er darüber, sollte die Intensität reduziert werden.

> Hochschule Reutlingen – Jahresprojekt 2025/2026
> Abdic, Amel · Aktepe, Tarik · Frank, Manuel · Kader, Alan · Koca, Cem

## Funktionen

- **Kamera-Seite:** Vorher-/Nachher-Fotos aufnehmen oder Bilder importieren
- **Analyse-Seite:** Beliebig viele Vorher- und Nachher-Bilder auswählen
  (Anzahl darf sich unterscheiden — die Bedeckungsgrade werden je Gruppe
  gemittelt). Drei Modi: A ohne Filter, B mit Unkrautfilter, **C mit
  Reihen-Erkennung** (trennt Kulturpflanze von Unkraut und liefert einen
  Kultur-CSC plus Unkraut-Wirkungsgrad). Zwei Segmentierungsverfahren wählbar:
  **ExG + Otsu** (robust gegen wechselndes Licht, Standard) oder HSV-Farbschwelle.
  Analyse läuft lokal und offline via Python/OpenCV. Details siehe
  [METHODIK.md](METHODIK.md).
- **Ergebnis-Seite:** Großer CSC-Wert mit farbcodierter Handlungsempfehlung
  (Zielband standardmäßig 8–12 %: darunter „aggressiver striegeln", im Band
  „optimal", darüber „weniger aggressiv"; negativer CSC → Hinweis, das
  Bildpaar zu prüfen; kein Grünanteil → Hinweis „Kein Pflanzenbewuchs
  erkannt"), darunter Bedeckungsgrade, Differenzen und Bilder pro Paar;
  bei mehreren Paaren mit Fortschrittsanzeige; Analysen können in den
  Papierkorb verworfen werden
- **Meine Aufnahmen:** Übersicht aller Sessions mit Vorschaubild und
  Bildanzahl — Antippen öffnet die Session direkt in der Analyse, das
  Papierkorb-Symbol verschiebt sie (wiederherstellbar) in den Papierkorb
- **Analyse-Einstellungen (Experten-Modus):** Grünton-Schwellen, minimale
  Objektgröße, Segmentierungsverfahren und CSC-Zielband sind über das Menü →
  „Einstellungen" anpassbar (z. B. für Feldtests mit anderen Kulturen).
  Änderungen erfordern die **Admin-Anmeldung** (siehe unten) — Ansehen ist
  für alle möglich, die App selbst läuft komplett ohne Anmeldung
- **Papierkorb:** Gelöschte Bilder werden 30 Tage aufbewahrt und können
  wiederhergestellt werden
- **Mehrsprachig:** Deutsch, Englisch und Französisch (umschaltbar über das Menü)

## Ablage der Bilder

Eine „Session" entspricht einem Feldeinsatz. Alle Arbeitsdaten liegen im
**app-eigenen Speicher** (Scoped-Storage-konform, ohne Spezial-Berechtigungen):

```
Android/data/de.smartweedcontrol.app/files/SmartWeed/
└── 2026-07-07_18-58/          ← Session (lesbarer Zeitstempel)
    ├── vorher/                ← eigene Aufnahmen
    ├── nachher/
    └── Analyse_18-59-30/      ← Analyse-Ergebnis zur Session
        ├── uebersicht_vorher_nachher.jpg   (Vergleichsbild)
        └── details/           ← Masken (PNG), Panels & Reihen-Overlays
```

Damit Fotos und Ergebnisse wie gewohnt sichtbar sind, exportiert die App
eigene Aufnahmen und das Analyse-Übersichtsbild **zusätzlich in die Galerie**
(`Pictures/SmartWeed/…`) — auf Android 10+ ohne jede Berechtigung über die
MediaStore-API, auf Android 9 über die klassische Speicher-Berechtigung.
Hinweis: Beim Deinstallieren der App werden die Session-Daten im App-Speicher
entfernt; die Galerie-Kopien bleiben erhalten.

## Admin-Bereich

Die Experten-Einstellungen sind durch eine Offline-Admin-Anmeldung geschützt,
damit die Analyse-Parameter nicht versehentlich verstellt werden. Im APK
liegt **kein Klartext-Passwort** — nur ein gesalzener SHA-256-Hash
(`AdminAuth.java`). Passwort ändern:

```bash
printf 'SmartWeedControl.v14.salt:NEUES_PASSWORT' | sha256sum
```

und den Hex-Wert in `AdminAuth.ADMIN_HASH` eintragen.

## Technik

| Komponente   | Details                                          |
|--------------|--------------------------------------------------|
| Sprache      | Java (UI) + Python 3.8 (Bildanalyse)             |
| Python-Bridge| [Chaquopy](https://chaquo.com/chaquopy/) 15.0.1  |
| Bildanalyse  | OpenCV (`opencv-python-headless 4.5.1.48`)       |
| Min. Android | API 28 (Android 9)                               |
| Build        | AGP 8.10, Gradle 8.11.1 (Wrapper)                |

## Build-Voraussetzungen

1. **Android Studio** (aktuelle Version) mit Android SDK 35
2. **Python 3.8** auf dem Build-Rechner (Chaquopy braucht es zum Paketieren
   der Python-Abhängigkeiten; die OpenCV-Wheels im Chaquopy-Repo existieren
   nur für cp38)
3. Optional: Pfad zur Python-3.8-Installation in der Datei `local.properties`
   (liegt im Projekt-Root, wird nicht eingecheckt) eintragen, falls Chaquopy
   sie nicht selbst findet:

   ```properties
   chaquopy.buildPython=C:/Users/<name>/AppData/Local/Programs/Python/Python38/python.exe
   ```

Danach normal in Android Studio öffnen und bauen, oder per CLI:

```bash
./gradlew :app:assembleDebug
```

## Tests & CI

- **Java:** CSC-Berechnung und Empfehlungslogik (`CscCalculator`) mit JUnit —
  `./gradlew testDebugUnitTest`
- **Python:** Golden-Image-Tests für Segmentierung, Reihen-Erkennung und
  Gruppen-Auswertung (`app/src/test/python/`) —
  `PYTHONPATH=app/src/main/python pytest app/src/test/python/`

Bei jedem Push auf `main` und jedem Pull Request laufen über GitHub Actions
(`.github/workflows/ci.yml`): Python-Tests, Java-Unit-Tests, Android Lint und
ein kompletter **Debug-APK-Build** inkl. Chaquopy/OpenCV-Packaging. Das
fertige APK hängt an jedem CI-Lauf als Artefakt `smartweedcontrol-debug-apk`
zum Download.

## Testdaten

`testdaten/feldbilder/` enthält 24 echte Feldfotos (Wintergetreide, frühes
Stadium, Sonnenlicht). Die Erstkalibrierung (v1.3) erfolgte an 14 dieser
Fotos; inzwischen wird jede Algorithmus-Änderung gegen alle 24 nachvalidiert
(siehe [METHODIK.md](METHODIK.md) §5).

## Hinweise / bekannte Einschränkungen

- Die Beschriftungen in den generierten Ergebnisbildern (z. B. „Vorher“,
  „Gefiltert“) sind fest auf Deutsch, da sie in `analysis.py` ins Bild
  gerendert werden.
- **Methodik:** Standard-Segmentierung ist **ExG + Otsu** (an echten
  Feldbildern validiert, robust gegen wechselndes Licht); die klassische
  HSV-Grünton-Schwelle bleibt in den Einstellungen als Alternative wählbar.
  Details und Validierungsstand in [METHODIK.md](METHODIK.md).
- Die früheren Platzhalter-Screens „Monitoring“ und „Manuell“ (Traktor-/
  Striegelsteuerung) wurden entfernt — sie hatten keine Funktion. Die
  Git-Historie enthält sie weiterhin, falls die Idee wieder aufgegriffen wird.
- **Python 3.8 / OpenCV 4.5.1 sind veraltet** (Python 3.8 erhält seit
  Oktober 2024 keine Sicherheitsupdates mehr): Die Versionen sind durch das
  Chaquopy-Paket-Repository vorgegeben — nur für Python 3.8 (cp38) existieren
  dort passende OpenCV-Wheels. Da die App komplett offline arbeitet und nur
  selbst aufgenommene bzw. bewusst importierte Bilder verarbeitet, ist das
  Risiko begrenzt. Vor einer Play-Store-Veröffentlichung prüfen, ob neuere
  Chaquopy-Versionen aktuellere Python/OpenCV-Kombinationen anbieten.
- **Reihen-Orientierung:** Seit v1.5 erkennt Modus C Saatreihen in jeder
  Orientierung (längs oder quer im Bild). Wichtig bleibt: möglichst senkrecht
  von oben fotografieren und innerhalb einer Auswertung eine ähnliche
  Aufnahmehöhe einhalten.
