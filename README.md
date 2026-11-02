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

## Neu in v1.8

Grundlage ist der Feldversuch vom 30./31.10.2026 (1235 Fotos; Referenz:
36 Bilder × 100 Punkte, von 2 Personen bonitiert, Kappa 0,87).

- **Neue Standard-Segmentierung „Boden-referenziert" (Lab a\*):** misst die
  Bodenfarbe im Bild selbst (Median der weniger grünen Bildhälfte) und zählt
  nur Pixel als Pflanze, die deutlich grüner sind. Warum: Bei tiefer,
  warmer Sonne gibt der gelbgrüne Farbstich dem Boden positives ExG — das
  alte **ExG + Otsu** zählte dann in 34–37 % der Sonnenbilder das ganze Bild
  als Pflanze oder trennte nur helle von dunklen Blättern (Trefferquote
  gegen die Referenz nur 0,30 vorher / 0,08 nachher). Die im Feld angezeigten
  CSC-Werte von ~90 % bzw. −100 % waren Messfehler. Das neue Verfahren:
  93 % Genauigkeit, ≤ 4 % Fehlalarme in allen Lichtsituationen,
  Trefferquote 0,76–0,92; Vorher/Nachher am selben Tag ohne
  Korrekturfaktor vergleichbar. Bestehende Installationen mit gespeichertem
  „exg" werden einmalig umgestellt; ExG bleibt als „alt" wählbar.
  **Grenze:** Bei mehr als ~50 % echter Bedeckung enthält auch die „weniger
  grüne" Bildhälfte Pflanzen — die Bedeckung wird dann stark unterschätzt
  (bei fast geschlossenem Bestand bis auf ~0 %). Solche Bilder markiert die
  App als „dichter Bestand“; bitte per Auge prüfen.
- **Kontrollfläche (optional):** Auf der Analyse-Seite (eingeklappt) lassen
  sich Bilder einer unbehandelten Fläche „Kontrolle vorher/nachher" wählen —
  gleichzeitig und unter gleichem Licht wie die gestriegelte Fläche
  fotografiert. Die App rechnet damit Licht- und Tageszeit-Effekte heraus:
  `CSC_kontrollbezogen = (1 − (nachher/vorher) / (Kontrolle_nachher/Kontrolle_vorher)) × 100`.
  Im Versuch zeigte die Kontrolle allein durch den Lichtwechsel einen „CSC"
  von 8–11 %. Die Ergebnis-Seite zeigt dann den kontrollbezogenen CSC groß
  (Empfehlung danach), den rohen CSC darunter und die Veränderung der
  Kontrollfläche. Kontrollbilder fließen nicht in die Mittelwerte der
  behandelten Fläche ein. Ohne Kontrollbilder läuft alles wie bisher.
- **Grauer U-Zählrahmen:** Der im Feld genutzte graue Metall-Rahmen
  (50 × 50 cm, drei Stäbe, eine Seite offen, blauer Stab an einer Ecke)
  wird jetzt erkannt; danach wird wie bisher ein roter Rahmen gesucht. Die
  Ergebnis-Seite zeigt, welcher Rahmen gefunden wurde. Fotos **ohne** Rahmen
  werden unverändert als ganzes Bild ausgewertet. Ragt der Rahmen aus dem
  Foto (z. B. offene Seite abgeschnitten), wird er verworfen und das ganze
  Bild ausgewertet; die Rahmensuche ist zeitlich gedeckelt.
- **Plausibilitäts-Warnung:** Bilder mit Bedeckung unter 3 % oder über
  80 % werden markiert („bitte Bilder prüfen"). Mit der Standardmethode
  (boden-referenziert) kann „über 80 %" praktisch nie auftreten — dichte
  Bestände erkennt dort der eigene Hinweis „dichter Bestand“ (Bedeckung
  wahrscheinlich unterschätzt). Kein Hinweis heißt nicht „sicher richtig“.
- **Fehlerbehebungen:** Galerie-Ordner Vorher/Nachher waren nach einer
  Analyse teils leer (Export lief erst nach der Ergebnis-Meldung im
  Hintergrund und wurde abgebrochen, sobald die App in den Hintergrund ging) —
  jetzt wird jedes Bild direkt nach seiner Auswertung exportiert, Fehlschläge
  werden angezeigt. Buttons in den Einstellungen („Als Admin anmelden",
  „Zurücksetzen") waren grüne Flächen ohne lesbare Schrift.
- Das CSC-Zielband (8–12 %) ist unverändert.

## Funktionen

- **Kamera-Seite:** Vorher-/Nachher-Fotos aufnehmen oder Bilder importieren
- **Analyse-Seite:** Beliebig viele Vorher- und Nachher-Bilder auswählen
  (Anzahl darf sich unterscheiden — die Bedeckungsgrade werden je Gruppe
  gemittelt). Die Auswahl erscheint als Vorschau-Kacheln: Antippen zeigt das
  Bild groß, das ✕ entfernt ein versehentlich gewähltes Bild, „Alle
  entfernen" leert die Gruppe; weitere Bilder werden angehängt. Drei Modi: A ohne Filter, B mit Unkrautfilter, **C mit
  Reihen-Erkennung** (trennt Kulturpflanze von Unkraut und liefert einen
  Kultur-CSC plus Unkraut-Wirkungsgrad). Vier Segmentierungsverfahren wählbar:
  **Boden-referenziert** (Standard seit v1.8), ExG + Otsu (alt), **ExGR** mit
  fester Schwelle oder HSV-Farbschwelle. Liegt ein **Zählrahmen** im Bild
  (grauer U-Rahmen oder roter Rahmen), wird nur sein Inneres ausgewertet
  (ohne erkannten Rahmen das ganze Bild, mit Hinweis). Optional:
  **Kontrollfläche** für einen licht-korrigierten CSC (siehe „Neu in v1.8"). Analyse läuft lokal und
  offline via Python/OpenCV. Details siehe [METHODIK.md](METHODIK.md).
- **Ergebnis-Seite:** Großer CSC-Wert mit farbcodierter Handlungsempfehlung
  (Zielband standardmäßig 8–12 %: darunter „aggressiver striegeln", im Band
  „optimal", darüber „weniger aggressiv"; negativer CSC → Hinweis, das
  Bildpaar zu prüfen; kein Grünanteil → Hinweis „Kein Pflanzenbewuchs
  erkannt"), darunter Bedeckungsgrade, Differenzen und Bilder pro Paar;
  bei mehreren Paaren mit Fortschrittsanzeige; Hinweise bei Lichtwechsel
  (Sonne vs. bewölkt) und nicht erkanntem Zählrahmen; Analysen können in den
  Papierkorb verworfen werden
- **Meine Aufnahmen:** Übersicht aller Sessions mit Vorschaubild und
  Bildanzahl — Antippen öffnet die Session direkt in der Analyse, das
  Papierkorb-Symbol verschiebt sie (wiederherstellbar) in den Papierkorb
- **Analyse-Einstellungen (Experten-Modus):** Grünton-Schwellen, minimale
  Objektgröße, Segmentierungsverfahren, Zählrahmen-Zuschnitt und CSC-Zielband sind über das Menü →
  „Einstellungen" anpassbar (z. B. für Feldtests mit anderen Kulturen).
  Änderungen erfordern die **Admin-Anmeldung** (siehe unten) — Ansehen ist
  für alle möglich, die App selbst läuft komplett ohne Anmeldung
- **Papierkorb:** Gelöschte Bilder werden 30 Tage aufbewahrt und können
  wiederhergestellt werden
- **Mehrsprachig:** Deutsch, Englisch und Französisch (umschaltbar über das Menü)

## Ablage der Bilder

Eine „Session" entspricht einem Feldeinsatz. **Für Nutzer maßgeblich ist die
Galerie** — dort liegt alles geordnet nach Session (seit v1.7.1, Hinweis
auch in der App unter „Meine Aufnahmen" und auf der Ergebnis-Seite):

```
Pictures/SmartWeed/
└── 2026-10-18_10-30/                 ← Session (Datum_Uhrzeit)
    ├── vorher/                       ← Aufnahmen + importierte Bilder
    │   └── vorher_20261018_103012_123.jpg
    ├── nachher/
    │   └── nachher_20261018_111502_456.jpg
    └── Analyse_11-20-05_A/           ← eine Analyse (Uhrzeit_Modus A/B/C)
        ├── Vorher/                              (analysierte Vorher-Bilder)
        ├── Nachher/                             (analysierte Nachher-Bilder)
        ├── Kontrolle_Vorher/                    (nur mit Kontrollfläche, v1.8)
        ├── Kontrolle_Nachher/
        ├── 00_Uebersicht_2026-10-18_10-30.jpg   (Vergleichsbild mit CSC)
        ├── Vorher_01_Auswertung.jpg             (Original | Masken)
        └── Nachher_01_Auswertung.jpg
```

Jeder Analyse-Lauf bekommt einen eigenen Ordner — auch wenn dieselben Bilder
mit einer anderen Variante (A/B/C) erneut ausgewertet werden (seit v1.7.2).
Sessions lassen sich unter „Meine Aufnahmen" über das Stift-Symbol
umbenennen; der Galerie-Ordner wird mit umbenannt. Beim Import können
mehrere Bilder auf einmal gewählt werden.

Die Arbeitsdaten der App liegen zusätzlich im **app-eigenen Speicher**
(`Android/data/<app>/files/SmartWeed/<Session>/…`, inkl. `details/` mit
Masken als PNG und Reihen-Overlays). Dieser Ordner ist ab Android 11 im
Dateimanager meist nicht sichtbar — deshalb der Galerie-Export. Auf
Android 10+ braucht der Export keine Berechtigung (MediaStore), auf Android 9
die klassische Speicher-Berechtigung. Beim Deinstallieren der App wird der
App-Speicher entfernt; die Galerie-Kopien bleiben erhalten.

HEIC-Bilder (z. B. iPhone) kann die Analyse nicht lesen — die App weist beim
Import darauf hin; bitte als JPG/PNG speichern.

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
Fotos; bis v1.7 wurde jede Algorithmus-Änderung gegen alle 24 nachvalidiert
(siehe [METHODIK.md](METHODIK.md) §5). Die v1.8-Standardmethode wurde
stattdessen an der Punkt-Bonitur des Feldversuchs vom Oktober 2026 validiert,
nicht an diesen 24 Fotos (für sie gibt es keine Referenzmessung).

## Hinweise / bekannte Einschränkungen

- Die Beschriftungen in den generierten Ergebnisbildern (z. B. „Vorher“,
  „Gefiltert“) sind fest auf Deutsch, da sie in `analysis.py` ins Bild
  gerendert werden.
- **Methodik:** Standard-Segmentierung ist seit v1.8 **Boden-referenziert
  (Lab a\*)**, validiert gegen eine Punkt-Bonitur aus dem Feldversuch
  30./31.10.2026. Bei mehr als ~50 % echter Bedeckung unterschätzt sie die
  Bedeckung (die Bodenreferenz enthält dann Pflanzen). Vorher- und
  Nachher-Fotos trotzdem möglichst bei gleichem Licht aufnehmen; am
  zuverlässigsten mit Kontrollfläche. ExG + Otsu (alt), **ExGR** und die
  HSV-Schwelle bleiben als Alternativen wählbar. Details in
  [METHODIK.md](METHODIK.md).
- **Zählrahmen:** Erkannt werden der graue U-Rahmen mit blauem Stab und rote
  bzw. rosa Rahmen. Blätter nach Möglichkeit vom Rahmen fernhalten.
- **Datenschutz:** Die Fotos des Feldversuchs vom Oktober 2026 gehören nicht ins Repository; die neuen
  Tests arbeiten mit synthetischen Bildern.
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
