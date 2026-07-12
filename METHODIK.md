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
  dass Getreide in Reihen gesät wird. Über die Rotation mit der stärksten
  periodischen Spalten-Struktur werden die Reihen gefunden; Grün **auf** den
  Reihen zählt als Kulturpflanze, Grün **zwischen** den Reihen als Unkraut.
  Ergebnis: zwei getrennte Kennzahlen —
  - **Kultur-CSC** (Schaden an der Kulturpflanze, Zielband 8–12 %)
  - **Unkraut-Wirkungsgrad** (relative Abnahme des Unkrauts, je höher desto besser)

  Werden keine klaren Reihen gefunden, fällt die Analyse transparent auf die
  Gesamtbedeckung zurück (mit Hinweis in der App).

## 4. Gruppen-Auswertung (mehrere Bilder)

Für höhere Genauigkeit können mehrere Vorher- und Nachher-Bilder erfasst
werden (Anzahl darf sich unterscheiden). Die Bedeckungsgrade werden **je
Gruppe gemittelt**, der CSC wird aus den Gruppenmittelwerten berechnet.
Zusätzlich prüft die App, ob sich die mittlere Helligkeit von Vorher- und
Nachher-Gruppe stark unterscheidet, und warnt dann vor einem möglichen
Beleuchtungs-Bias.

## 5. Bekannte Grenzen / geplante Validierung

- Die HSV-Methode ist beleuchtungsabhängig; ExG+Otsu ist deshalb Standard.
- Die Reihen-Erkennung setzt näherungsweise senkrechte Aufnahmen mit
  sichtbaren Reihen voraus.
- **Geplant:** Validierung an echten Feldbildern gegen eine Referenzmessung
  (z. B. die frei verfügbare App *Canopeo* der Oklahoma State University,
  die die Grünbedeckung eines Einzelbildes misst) sowie gegen manuelle
  Auszählung.

## Quellen

- Rasmussen, J. et al. – Arbeiten zur mechanischen Unkrautregulierung / CSC
- Woebbecke, D. M. et al. (1995): *Color indices for weed identification.*
- Meyer, G. E. & Neto, J. C. (2008): *Verification of color vegetation indices.*
- Canopeo (Oklahoma State University) – App zur Messung der Grünbedeckung
