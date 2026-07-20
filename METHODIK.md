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
  Zwei Schutzmechanismen sichern die unimodalen Grenzfälle ab, in denen Otsu
  keine sinnvolle Trennung finden kann: Auf vegetationsfreien Bildern
  verhindert eine ExG-Untergrenze Geister-Detektionen auf Bodenrauschen, und
  auf (nahezu) vollflächig bewachsenen Bildern — wo Otsu den Schwellwert
  mitten in die Vegetationsverteilung legen und die Bedeckung halbieren
  würde — zählt direkt die ExG-Untergrenzen-Maske.
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
  kleine, kompakte Objekte (typisches Unkraut) vor der Berechnung. Als
  Referenzgröße für „typische Kulturpflanze" dient der **flächengewichtete**
  Median der Komponentenflächen — so filtert der Modus auch dann korrekt,
  wenn Unkraut die zahlenmäßige Mehrheit der Objekte stellt. Boden-Löcher
  innerhalb einer Pflanze bleiben bei der Filterung unangetastet (die
  Bedeckung wird nicht künstlich aufgefüllt). Grenze: versagt, wenn
  Gras-Unkräuter länglich wie Getreide sind.
- **Modus C – Reihen-Erkennung (empfohlen für das Projektziel):** Nutzt aus,
  dass Getreide in Reihen gesät wird. Zunächst wird die Bildrotation mit dem
  am stärksten „gestreiften" Spaltenprofil gesucht (Grobsuche in 1,5°- und
  Feinsuche in 0,25°-Schritten, zusätzlich ein zweiter Durchlauf auf dem
  transponierten Bild — damit werden auch **quer** liegende Reihen erkannt);
  anschließend misst die **unverzerrte Autokorrelation** dieses Profils den
  regelmäßigen Reihenabstand (die Normierung auf die Überlapplänge stellt
  sicher, dass auch wenige, weit auseinanderliegende Reihen erkannt werden).
  Grün **auf** den Reihen zählt als Kulturpflanze, Grün **zwischen** den
  Reihen als Unkraut. Ergebnis: zwei getrennte Kennzahlen —
  - **Kultur-CSC** (Schaden an der Kulturpflanze, Zielband 8–12 %)
  - **Unkraut-Wirkungsgrad** (relative Abnahme des Unkrauts, je höher desto besser)

  Gewählt wird der **kleinste** Autokorrelations-Peak, der alle Schwellen
  besteht — nicht der stärkste: Die unverzerrte Normierung hebt bei sauberen
  periodischen Mustern die Harmonischen (2L, 3L, …) leicht über das
  Fundamental; ein argmax rastete dort ein und halbierte die Reihenzahl
  (bis v1.5 nachweisbar auch auf echten Feldbildern: feld_19/feld_20 wurden
  mit doppeltem Reihenabstand erkannt). Eine **subharmonische Gegenprüfung**
  lehnt zusätzlich Bilder ehrlich ab, deren echter Reihenabstand unterhalb
  des Suchfensters liegt (mehr als ~15 Reihen im Bild) — sonst würde
  zwingend eine Harmonische mit hoher Konfidenz akzeptiert.

  Drei Schutzkriterien verhindern Fehl-Erkennungen: Der Reihenabstand muss
  realistisch sein (höchstens ~15 Reihen im Bild), der Autokorrelations-Peak
  muss eine Mindeststärke erreichen **und** eine Mindest-**Prominenz** haben —
  echte Reihenraster erzeugen tiefe Täler zwischen den Peaks, während
  Rauschwellen auf einer glatt abfallenden Kurve (z. B. eine breite
  Vegetationswolke ohne Reihen) nur minimale Einbuchtungen zeigen (gemessen:
  echte Reihen ≥ 0,68 Prominenz, reihenlose Profile ≤ 0,27; Schwelle 0,35).
  Werden keine klaren Reihen gefunden, fällt die Analyse transparent auf die
  Gesamtbedeckung zurück (mit Hinweis in der App). Bei der Gruppen-Auswertung
  gilt Mehrheitsentscheid je Gruppe (mindestens die Hälfte der Bilder): Ein
  einzelnes Bild ohne erkannte Reihen kippt nicht die gesamte Auswertung; die
  Kultur-/Unkraut-Mittelwerte stammen ausschließlich aus den Bildern mit
  erkannten Reihen.

## 4. Gruppen-Auswertung (mehrere Bilder)

Für höhere Genauigkeit können mehrere Vorher- und Nachher-Bilder erfasst
werden (Anzahl darf sich unterscheiden). Die Bedeckungsgrade werden **je
Gruppe gemittelt**, der CSC wird aus den Gruppenmittelwerten berechnet.
Zwei Plausibilitäts-Warnungen sichern die Beleuchtung ab:

1. **Gruppen-Bias:** Die mittlere Helligkeit von Vorher- und Nachher-Gruppe
   unterscheidet sich stark (systematischer Beleuchtungs-Bias).
2. **Ausreißer-Streuung:** Einzelbilder *innerhalb* einer Gruppe streuen
   stark (z. B. ein sehr dunkles und ein sehr helles Vorher-Bild) — im
   Gruppenmittel würde sich das aufheben, obwohl beide Bilder einzeln
   segmentierungskritisch sind.

Wichtig fürs Aufnahmeprotokoll: Alle Bilder einer Auswertung sollten aus
ähnlicher Höhe und möglichst senkrecht (Nadir) aufgenommen werden — die
Gruppen-Mittelung setzt voraus, dass jedes Bild eine vergleichbare Fläche
repräsentiert (keine automatische Perspektiv-/Maßstabskorrektur).

## 5. Validierung an echten Feldbildern (Stand v1.5)

Erstkalibrierung (v1.3) an 14 realen Feldfotos; inzwischen liegen 24 Fotos
unter `testdaten/feldbilder/` (Sonnenlicht, verschiedene Bewuchsdichten),
gegen die jede Algorithmus-Änderung nachvalidiert wird — zusätzlich zu den
synthetischen Kontrollbildern der automatischen Testsuite:

- **Segmentierung:** ExG+Otsu segmentiert die Pflanzen auf allen Realbildern
  sauber und ist sichtbar robuster als HSV (die feste HSV-Schwelle verfehlt
  Vegetation bei ungünstigem Licht deutlich). ExG+Otsu ist daher Standard.
- **Reihen-Erkennung:** Auf den Realbildern springt Modus C nur bei echter,
  grober Reihenstruktur an (bei den vorliegenden, meist dicht bewachsenen
  Zufallsbildern ~4 von 14) und fällt sonst korrekt zurück — 0 Fehlalarme auf
  Kontrollbildern ohne Reihen. Bei klar erkennbaren Reihen trennt die Methode
  Kultur (auf der Reihe) und Unkraut (dazwischen) zutreffend.

### Bekannte Grenzen / nächste Schritte

- Die Reihen-Erkennung setzt näherungsweise senkrechte Aufnahmen (Nadir) mit
  klar sichtbaren Reihen voraus; die Orientierung der Reihen im Bild ist seit
  v1.5 egal (längs oder quer). Bei jungem, dichtem Mischbewuchs ist keine
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
