# Methodik der Bildanalyse

Dieses Dokument beschreibt, wie SmartWeedControl aus Vorher-/Nachher-Fotos
den **Crop Soil Cover (CSC)** ableitet und woher die Kennzahlen stammen.

## 1. Zielgröße: Crop Soil Cover (CSC)

Der CSC gibt an, welcher Anteil der Kulturpflanzen-Bedeckung durch das
Striegeln verschwunden ist (verschüttet/entwurzelt):

```
CSC = (Bedeckung_vorher − Bedeckung_nachher) / Bedeckung_vorher × 100 %
```

Empirisch gilt ein CSC von **ca. 10 %** als optimal: Bei diesem Wert ist die
Unkrautwirkung hoch, während die Kulturpflanze den Eingriff innerhalb von
~14 Tagen kompensiert (Rasmussen et al.). Die App verwendet ein Zielband
(Standard 8–12 %, in den Einstellungen anpassbar):

| CSC | Empfehlung |
|-----|------------|
| < unteres Band | aggressiver striegeln |
| im Band | optimal – Einstellung beibehalten |
| > oberes Band | weniger aggressiv striegeln |
| < 0 % | Bildpaar prüfen (nachher mehr Grün als vorher) |
| ~0 % Vorher-Bedeckung | kein Bewuchs erkannt |

## 2. Segmentierung Pflanze vs. Boden

Zwei Verfahren stehen zur Wahl (Analyse-Einstellungen → Segmentierung):

- **ExG + Otsu (Standard, empfohlen):** Excess-Green-Index
  `ExG = 2·g − r − b` auf normalisierten RGB-Kanälen, gefolgt von einem
  automatischen Otsu-Schwellwert. Der Schwellwert passt sich jedem Bild an
  und ist dadurch robust gegen wechselnde Beleuchtung (Sonne/Wolken/Tageszeit).
  Grundlage: Woebbecke et al. (1995), Meyer & Neto (2008) — der Standard-
  Vegetationsindex der Agrar-Bildanalyse und die im Businessplan genannte
  Zielmethodik.
- **HSV-Farbschwelle (klassisch):** feste Grünton-Grenzen (H 35–85). Einfach
  und schnell, aber empfindlich gegenüber Beleuchtungsschwankungen.

Nach der Segmentierung wird die Maske morphologisch bereinigt (Open/Close)
und Komponenten unterhalb einer Mindestgröße (Standard 50 px) werden entfernt.

## 3. Analyse-Modi

- **Modus A – ohne Unkrautfilter:** Bedeckungsgrad = Anteil aller grünen Pixel.
  Einfachster Modus; unterscheidet nicht zwischen Kultur und Unkraut.
- **Modus B – Unkrautfilter:** Heuristik über Größe und Formfaktor entfernt
  kleine, kompakte Objekte (typisches Unkraut) vor der Berechnung. Grenzen:
  versagt, wenn Unkraut die Mehrheit stellt oder Gras-Unkräuter länglich wie
  Getreide sind.
- **Modus C – Reihen-Erkennung (empfohlen für das Projektziel):** Nutzt aus,
  dass Getreide in Reihen gesät wird. Zunächst wird die Bildrotation mit dem
  am stärksten „gestreiften" Spaltenprofil gesucht; anschließend misst die
  **Autokorrelation** dieses Profils den regelmäßigen Reihenabstand. Grün
  **auf** den Reihen zählt als Kulturpflanze, Grün **zwischen** den Reihen als
  Unkraut. Ergebnis: zwei getrennte Kennzahlen —
  - **Kultur-CSC** (Schaden an der Kulturpflanze, Zielband 8–12 %)
  - **Unkraut-Wirkungsgrad** (relative Abnahme des Unkrauts, je höher desto besser)

  Damit keine feine Bodentextur als „Reihen alle paar Pixel" fehlinterpretiert
  wird, muss der Reihenabstand realistisch sein (höchstens ~15 Reihen im Bild)
  und die Autokorrelation eine Mindeststärke erreichen. Werden keine klaren
  Reihen gefunden, fällt die Analyse transparent auf die Gesamtbedeckung
  zurück (mit Hinweis in der App).

## 4. Gruppen-Auswertung (mehrere Bilder)

Für höhere Genauigkeit können mehrere Vorher- und Nachher-Bilder erfasst
werden (Anzahl darf sich unterscheiden). Die Bedeckungsgrade werden **je
Gruppe gemittelt**, der CSC wird aus den Gruppenmittelwerten berechnet.
Zusätzlich prüft die App, ob sich die mittlere Helligkeit von Vorher- und
Nachher-Gruppe stark unterscheidet, und warnt dann vor einem möglichen
Beleuchtungs-Bias.

## 5. Validierung an echten Feldbildern (Stand v1.3)

Erste Kalibrierung an 14 realen Feldfotos (Sonnenlicht, verschiedene
Bewuchsdichten) plus synthetischen Kontrollbildern:

- **Segmentierung:** ExG+Otsu segmentiert die Pflanzen auf allen Realbildern
  sauber und ist sichtbar robuster als HSV (die feste HSV-Schwelle verfehlt
  Vegetation bei ungünstigem Licht deutlich). ExG+Otsu ist daher Standard.
- **Reihen-Erkennung:** Auf den Realbildern springt Modus C nur bei echter,
  grober Reihenstruktur an (bei den vorliegenden, meist dicht bewachsenen
  Zufallsbildern ~4 von 14) und fällt sonst korrekt zurück — 0 Fehlalarme auf
  Kontrollbildern ohne Reihen. Bei klar erkennbaren Reihen trennt die Methode
  Kultur (auf der Reihe) und Unkraut (dazwischen) zutreffend.

### Bekannte Grenzen / nächste Schritte

- Die Reihen-Erkennung setzt näherungsweise senkrechte Aufnahmen mit klar
  sichtbaren Reihen voraus; bei jungem, dichtem Mischbewuchs ist keine
  zuverlässige Reihentrennung möglich (dann greift der Rückfall auf die
  Gesamtbedeckung).
- Die bisherige Kalibrierung erfolgte an *einzelnen* Feldbildern. Für die
  eigentliche CSC-Messung werden **echte Vorher-/Nachher-Paare** derselben
  Stelle benötigt — die Feinjustierung der Reihen-Parameter erfolgt an
  diesen Paaren.
- **Geplant:** Abgleich der gemessenen Bedeckungsgrade gegen eine
  Referenzmessung (z. B. die frei verfügbare App *Canopeo* der Oklahoma State
  University) sowie gegen manuelle Auszählung.

## Quellen

- Rasmussen, J. et al. – Arbeiten zur mechanischen Unkrautregulierung / CSC
- Woebbecke, D. M. et al. (1995): *Color indices for weed identification.*
- Meyer, G. E. & Neto, J. C. (2008): *Verification of color vegetation indices.*
- Canopeo (Oklahoma State University) – App zur Messung der Grünbedeckung
