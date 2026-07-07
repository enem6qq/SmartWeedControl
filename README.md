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

- **Kamera-Seite:** Vorher-/Nachher-Fotos aufnehmen (werden in
  `Pictures/SmartWeed/<Session-Timestamp>/vorher|nachher` gespeichert) oder
  Bilder importieren
- **Analyse-Seite:** Mehrere Vorher-/Nachher-Paare auswählen, wahlweise mit
  Unkrautfilter (Modus B), Analyse läuft lokal und offline via Python/OpenCV
- **Ergebnis-Seite:** Großer CSC-Wert mit farbcodierter Handlungsempfehlung
  (Zielband standardmäßig 8–12 %: darunter „aggressiver striegeln", im Band
  „optimal", darüber „weniger aggressiv"; negativer CSC → Hinweis, das
  Bildpaar zu prüfen), darunter Bedeckungsgrade, Differenzen und Bilder pro
  Paar; bei mehreren Paaren mit Fortschrittsanzeige
- **Analyse-Einstellungen (Experten-Modus):** Grünton-Schwellen, minimale
  Objektgröße und CSC-Zielband sind über das Menü → „Einstellungen"
  anpassbar (z. B. für Feldtests mit anderen Kulturen)
- **Papierkorb:** Gelöschte Bilder werden 30 Tage aufbewahrt und können
  wiederhergestellt werden
- **Mehrsprachig:** Deutsch, Englisch und Französisch (umschaltbar über das Menü)

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

Die CSC-Berechnung und die Empfehlungslogik stecken in der eigenständigen
Klasse `CscCalculator` und sind mit JUnit getestet:

```bash
./gradlew testDebugUnitTest
```

Bei jedem Push auf `main` und bei jedem Pull Request laufen die Unit-Tests
und Android Lint automatisch über GitHub Actions (`.github/workflows/ci.yml`).

## Hinweise / bekannte Einschränkungen

- Die App fordert auf Android 11+ `MANAGE_EXTERNAL_STORAGE` an, damit die
  Analyse-Ergebnisse als normale Dateien unter `Pictures/SmartWeed/` liegen.
  Für eine Veröffentlichung im Play Store müsste die Speicherung auf die
  MediaStore-API umgestellt werden (Google lehnt diese Berechtigung für
  normale Apps ab). Für Sideloading/Demos ist das unkritisch.
- Der Login-Screen ist ein Demo-Feature: Die Zugangsdaten liegen unverschlüsselt
  in `app/src/main/assets/pw.json` und der Login wird beim App-Start übersprungen
  (`MainActivity` ist die Launcher-Activity).
- Die Beschriftungen in den generierten Ergebnisbildern (z. B. „Vorher“,
  „Gefiltert“) sind fest auf Deutsch, da sie in `analysis.py` ins Bild
  gerendert werden.
- **Methodik:** Die Pflanzensegmentierung nutzt aktuell eine HSV-Grünton-Schwelle
  (Standard: H 35–85, per Einstellungen anpassbar). Der Businessplan nennt
  ExG-/ExGR-Farbindizes als Zielmethodik — eine Umstellung sollte mit echten
  Feldbildern validiert werden, bevor sie die HSV-Schwelle ersetzt.
- Die Screens „Monitoring“ und „Manuell“ sind Vorschauen für geplante
  Funktionen (entsprechend gekennzeichnet, Buttons deaktiviert).
