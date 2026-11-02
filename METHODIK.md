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

### Vorverarbeitung: Zählrahmen (seit v1.7, grauer U-Rahmen seit v1.8)

Liegt ein **Zählrahmen** im Bild, wertet die App nur sein Inneres aus
(Einstellung „Nur Zählrahmen auswerten“, Standard: an). Gesucht wird in
dieser Reihenfolge:

1. **Grauer Metall-U-Rahmen (seit v1.8, `frame_u.py`):** der im Feld benutzte
   Rahmen, 50 × 50 cm, drei Stäbe (eine Seite offen), blauer Stiel an einer
   geschlossenen Ecke. Die Stäbe sind dünne Strukturen, die **weniger
   gesättigt** sind als Boden und Blätter; eine Liniensuche über alle
   Richtungen findet Stab-Kandidaten auch dann, wenn Blätter sie
   unterbrechen. Aus Paaren paralleler bzw. rechtwinkliger Kandidaten werden
   U-Hypothesen gebildet und bewertet (Stab-Evidenz, rechte Winkel,
   Quadratform, blauer Stiel an einer Ecke). Die Ecken liegen auf den
   Stab-Mittellinien; ausgewertet wird das entzerrte Innere mit 4 %
   Sicherheitsrand (~2 cm). Feldversuch 26.–31.10.2026: 254 von 255 Rahmen
   gefunden, in einer Stichprobe von 47 Bildern keine falsche Erkennung.
   - **Frühabbruch:** Ohne blauen Fleck und ohne kräftigen, durchgehenden
     Stab bricht die Suche nach ~0,2 s ab (57 % der rahmenlosen Feldfotos).
   - **Arbeitsbudget:** Die Hypothesen-Suche ist gedeckelt (Anzahl
     U-Aufbauten und Zeit je Bild), weil rahmenlose Fotos mit stab-artigen
     Strukturen sonst auf dem Handy sehr lange dauern würden.
   - **Rahmen ragt aus dem Bild** (z. B. offene Seite abgeschnitten): wird
     **verworfen**, weil die fehlende Fläche sonst schwarz aufgefüllt und als
     Boden mitgezählt würde; das Bild wird dann ohne Rahmen ausgewertet.
2. **Roter/rosa Rahmen (v1.7, Rückfall):** Das Innere wird als größtes Loch in
   der Rot-Maske gefunden, perspektivisch zu einem Quadrat entzerrt
   (Originalauflösung, damit die Mindestgröße ihre Bedeutung behält) und mit
   6 % Sicherheitsrand beschnitten. Blätter über dem Rahmen werden durch
   schrittweises Schließen der Lücken überbrückt; eine Ringprüfung (außen
   überwiegend Rot, innen kaum Rot, Rot nicht großflächig) verhindert
   Fehlalarme auf rötlichem Boden oder roten Geräten.

Ohne erkannten Rahmen wird das ganze Bild ausgewertet und das in der App
angezeigt; ein Kontrollbild mit eingezeichnetem Ausschnitt liegt im
`details/`-Ordner. Die Ergebnis-Seite zeigt, welcher Rahmen-Typ gefunden
wurde. Fotos, die bewusst **ohne** Rahmen aufgenommen wurden, werden
unverändert als ganzes Bild ausgewertet — der Rahmen spielt für sie keine
Rolle (die Option kostet dann nur Rechenzeit und kann ausgeschaltet werden).

Warum: Auf Vollbildern zählte die Segmentierung Schatten und Kleidung
(Hose) als Pflanze und lieferte so einen scheinbar plausiblen, aber falschen
CSC (gemessen: 34 % bzw. 43 % statt eines negativen Werts im Rahmen). Der
Rahmen ist zudem genau die Fläche, die auch bonitiert wird. Bis v1.7 wurde
nur der rote Rahmen erkannt — der im Feld benutzte graue U-Rahmen nie.

### Verfahren

Vier Verfahren stehen zur Wahl (Analyse-Einstellungen → Segmentierung):

- **Boden-referenziert, Lab a\* (Standard seit v1.8, empfohlen):** Der
  Grün-Index ist `−a*` (Lab-Farbraum, unabhängig von der Helligkeit). Statt
  einer festen oder Otsu-Schwelle wird der Farbstich des Lichts am **Boden
  desselben Bildes** gemessen: Die weniger grüne Hälfte der Pixel gilt als
  Boden, ihr Median ist die Boden-Referenz; Pflanze = mehr als 10 a\*-Einheiten
  grüner als diese Referenz. Ein gelbgrüner Sonnen-Farbstich verschiebt Boden
  und Pflanzen gleichermaßen und fällt dadurch heraus.
  **Feldversuch 30./31.10.2026** (1235 Fotos; Referenz: 36 Bilder × 100
  Punkte, 2 Bonitierende, Kappa 0,87): Genauigkeit 93 %, Falsch-Positive
  ≤ 4 % in allen Lichtsituationen (tiefe Sonne, Bewölkung), Trefferquote
  0,76–0,92; Vorher/Nachher am selben Tag ohne Korrekturfaktor vergleichbar.
  **Grenze (dichter Bestand):** Ab ~50 % echter Bedeckung enthält auch die
  „weniger grüne Hälfte“ Pflanzen, die Boden-Referenz wird zu grün und die
  Bedeckung wird **stark unterschätzt**; bei fast geschlossenem, einheitlichem
  Bestand kann sie bis auf ~0 % einbrechen. Mehr als ~75 % kann das Verfahren
  grundsätzlich nicht melden (die Referenz liegt etwa beim 25. Perzentil).
  Deshalb setzt die App bei soilref eine eigene Warnung **„dichter Bestand“**
  (`dense_warning`), wenn die Boden-Referenz selbst deutlich grün ist (−a\* > 8;
  auf den Feldfotos lag sie höchstens bei 4) oder die gemessene Bedeckung über
  45 % liegt (Feldfotos: ~2,5 % der Bilder). Diese Schwellen sind eine
  Heuristik, nicht gegen die Bonitur validiert — betroffene Bilder per Auge
  prüfen.
- **ExG + Otsu (alt, bis v1.7 Standard):** Excess-Green-Index
  `ExG = 2·g − r − b` auf normalisierten RGB-Kanälen, gefolgt von einem
  automatischen Otsu-Schwellwert. **Versagte im Feldversuch 10/2026** bei
  tiefer, warmer Sonne: Der gelbgrüne Farbstich gibt dem Boden positives ExG;
  der frühere Vollbewuchs-Rückfall zählte dann in 34–37 % der Sonnenbilder
  das ganze Bild als Pflanze, sonst trennte Otsu helle von dunklen Blättern
  (~0–3 % Bedeckung). Trefferquote gegen die Referenz nur 0,30 (vorher) /
  0,08 (nachher); die im Feld angezeigten CSC-Werte von ~90 % bzw. −100 %
  waren Messfehler. Schon in v1.7 gemessen: Die Otsu-Schwelle **wandert mit
  dem Licht** (bewölkt ~0,06–0,09, Sonne ~0,14, Sonne mit dichtem Bestand bis
  0,21). Seit v1.8 gibt es keinen Vollbewuchs-Rückfall mehr; eine
  ExG-Untergrenze verhindert Geister-Detektionen auf Bodenrauschen.
  Grundlage: Woebbecke et al. (1995), Meyer & Neto (2008).
- **ExGR (seit v1.7, wählbar):** `ExGR = ExG − ExR` mit `ExR = 1,4·r − g`
  und **fester Schwelle 0** (Meyer & Neto 2008) — ohne bildabhängige Schwelle
  und damit ohne deren Licht-Drift; der im Exposé genannte Index. Auf den
  Zählrahmen-Fotos folgt ExGR den Blättern bei Wolkenlicht enger und erfasst
  dichte Bestände in der Sonne vollständiger. An Blatträndern greift es leicht
  in beschatteten Boden über (JPEG-Farbunterabtastung). Auf den 24 Feldbildern
  weicht es je nach Boden in **beide** Richtungen ab (z. B. feld_08 10 → 37 %,
  feld_19 13 → 4 %). Bewusst **nicht Standard**, solange keine Referenzmessung
  (Canopeo / manuelle Auszählung) entscheidet, welches Verfahren näher an der
  Wahrheit liegt.
- **HSV-Farbschwelle (klassisch):** feste Grünton-Grenzen (H 35–85). Einfach
  und schnell, aber empfindlich gegenüber Beleuchtungsschwankungen.

Nach der Segmentierung wird die Maske morphologisch bereinigt (Open/Close)
und Komponenten unterhalb einer Mindestgröße (Standard 50 px) werden entfernt.

### Plausibilitäts-Hinweise je Bild (seit v1.8)

- **Unter 3 % Bedeckung** („low“): meist Boden ohne Bestand oder eine
  Fehlsegmentierung (im Feld: ExG + Otsu mit 0–3 %).
- **Über 80 % Bedeckung** („high“): meist als Pflanze gezählter Boden (im
  Feld: ExG-Vollbewuchs-Rückfall). **Mit soilref kann dieser Hinweis
  praktisch nie auftreten** — er fängt dort keinen dichten Bestand ab.
- **Dichter Bestand** (nur soilref, siehe oben): Bedeckung wahrscheinlich
  unterschätzt. Ein fast geschlossener Bestand kann bei soilref zusätzlich
  „unter 3 %“ zeigen — dann gilt der Hinweis „dichter Bestand“.

Kein Hinweis heißt nicht „sicher richtig“: Die Hinweise fangen nur grobe
Ausreißer ab.

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
Drei Plausibilitäts-Warnungen sichern die Beleuchtung ab:

1. **Gruppen-Bias:** Die mittlere Helligkeit von Vorher- und Nachher-Gruppe
   unterscheidet sich stark (systematischer Beleuchtungs-Bias).
2. **Ausreißer-Streuung:** Einzelbilder *innerhalb* einer Gruppe streuen
   stark (z. B. ein sehr dunkles und ein sehr helles Vorher-Bild) — im
   Gruppenmittel würde sich das aufheben, obwohl beide Bilder einzeln
   segmentierungskritisch sind.
3. **Lichtart (seit v1.7):** Vorher- und Nachher-Gruppe wurden bei
   verschiedenem Licht aufgenommen (Sonne vs. bewölkt). Die Helligkeit allein
   erkennt das nicht (gemessen: Sonne 136 vs. Wolken 125). Gemessen wird die
   **Hell-Dunkel-Spanne des Bodens** (90./10. Perzentil der Grauwerte außerhalb
   der Pflanzen): Sonne 2,24–3,56, bewölkt 1,61–1,95. Ab 2,1 gilt ein Bild als
   „Sonne“; gewarnt wird, wenn die Gruppen mehrheitlich verschieden eingestuft
   sind. **Vorläufig** kalibriert (2 Bildserien, 1 Kamera, knappe Lücke).

### Kontrollfläche (optional, seit v1.8)

Eine **unbehandelte** Parzelle wird am selben Tag direkt vor und nach dem
Striegeln unter demselben Licht fotografiert („Kontrolle vorher/nachher“).
Ihre Bedeckungsänderung entsteht ohne Striegeln — v. a. durch Licht und
Tageszeit, aber auch Tau, Wind oder einen etwas anderen Bildausschnitt — und
wird herausgerechnet:

```
CSC_kontrollbezogen = (1 − (nachher / vorher) / (Kontrolle_nachher / Kontrolle_vorher)) × 100 %
Änderung Kontrolle  = (Kontrolle_nachher / Kontrolle_vorher − 1) × 100 %   (relativ)
```

Im Feldversuch zeigte die unbehandelte Fläche allein durch den Lichtwechsel
einen Schein-CSC von 8–11 %. Kontrollbilder fließen **nicht** in die
Mittelwerte der behandelten Gruppen ein. Die Kontrolle nutzt dieselbe Basis
wie der CSC; fehlt sie den Kontrollbildern (Modus C: auf der Kontrolle keine
Reihen erkannt), wird ihre relative Änderung an der gefilterten bzw.
Gesamt-Bedeckung gemessen (die App weist darauf hin). Ist keine Korrektur
möglich, nennt die App den Grund: roher CSC nicht berechenbar, kein
Kontrollwert für die Basis, oder 0 % Bedeckung auf der Kontrollfläche.

Wichtig fürs Aufnahmeprotokoll: Alle Bilder einer Auswertung sollten aus
ähnlicher Höhe und möglichst senkrecht (Nadir) aufgenommen werden — die
Gruppen-Mittelung setzt voraus, dass jedes Bild eine vergleichbare Fläche
repräsentiert (keine automatische Perspektiv-/Maßstabskorrektur).

### Ergebnisbilder (seit v1.7.1)

Das Übersichtsbild zeigt in der Titelleiste den **CSC derselben Basis wie die
Ergebnis-Seite** (Modus C mit Reihen: Kulturbedeckung; Modus B:
unkrautgefiltert, sonst gefiltert; Modus A: Pflanzenmaske), je Gruppe Anzahl
und Mittelwert sowie unter jeder Bildkachel Nummer, Dateiname und
Einzelwert. Beschriftungen stehen in eigenen Leisten statt im Foto, werden
auf die verfügbare Breite eingepasst (nie abgeschnitten) und viele Bilder
zeilenweise umbrochen. Die Auswertungs-Panels (Original | Pflanzenmaske |
Gefiltert | Unkrautgefiltert) tragen je Spalte Titel und Bedeckungswert,
Reihen-Overlay und Rahmen-Kontrollbild eine Legende.

## 5. Validierung an echten Feldbildern (Stand v1.5, historisch)

> **Hinweis v1.8:** Dieser Abschnitt beschreibt den Stand bis v1.7 (Standard
> ExG + Otsu). Die v1.8-Standardmethode (soilref) wurde **nicht** an diesen 24
> Fotos nachvalidiert, sondern an der Punkt-Bonitur des Feldversuchs
> 30./31.10.2026 (siehe §2). Auf den 24 Fotos weichen die Werte deutlich von
> ExG + Otsu ab (z. B. feld_01 40,4 → 13,0 %, feld_05 44,9 → 13,7 %, feld_08
> 0,2 → 27,6 %); für diese Fotos gibt es keine Referenzmessung, die
> entscheidet, welcher Wert näher an der Wahrheit liegt.

Erstkalibrierung (v1.3) an 14 realen Feldfotos; inzwischen liegen 24 Fotos
unter `testdaten/feldbilder/` (Sonnenlicht, verschiedene Bewuchsdichten),
gegen die bis v1.7 jede Algorithmus-Änderung nachvalidiert wurde —
zusätzlich zu den synthetischen Kontrollbildern der automatischen Testsuite:

- **Segmentierung (bis v1.7):** ExG+Otsu segmentierte die Pflanzen auf allen
  Realbildern sichtbar robuster als HSV (die feste HSV-Schwelle verfehlt
  Vegetation bei ungünstigem Licht deutlich). ExG+Otsu war daher bis v1.7
  Standard; im Feldversuch 10/2026 versagte es bei tiefer Sonne (siehe §2).
- **Reihen-Erkennung:** Auf den Realbildern springt Modus C nur bei echter,
  grober Reihenstruktur an (bei den vorliegenden, meist dicht bewachsenen
  Zufallsbildern ~4 von 14) und fällt sonst korrekt zurück — 0 Fehlalarme auf
  Kontrollbildern ohne Reihen. Bei klar erkennbaren Reihen trennt die Methode
  Kultur (auf der Reihe) und Unkraut (dazwischen) zutreffend.

## 6. Validierung an Zählrahmen-Fotos (v1.7)

Zwei Bildserien aus einem früheren Striegelversuch (Lfd. 1 und Lfd. 17, je 3
Fotos vorher / nachher / 14 Tage danach, roter Zählrahmen, Sonne und Wolken
gemischt). Die Fotos liegen nicht im Repository (fremde Versuchsdaten).

| Prüfung | Ergebnis |
|---|---|
| Zählrahmen erkannt | 17 von 18 Fotos (verfehlt: ein Schenkel zu ~85 % von Blättern verdeckt) |
| Fehlalarme Zählrahmen | 0 von 22 Feldbildern ohne Rahmen; beide Feldbilder mit Rahmen erkannt |
| Reihen-Erkennung im Rahmen | 11 von 18 statt 1 von 18 (Fix im Prominenz-Fenster, s. u.) |
| Licht-Warnung | 4 von 4 Vergleichen richtig (3× Sonne/Wolken gewarnt, 1× Sonne/Sonne nicht) |
| 24 Feldbilder ohne Rahmen-Option | identische Werte wie v1.6 (Regression geprüft) |

**Reihen-Erkennung in kleinen Ausschnitten:** Im 0,5-m-Rahmen liegen nur 3–4
Reihen; der Reihenabstand liegt dann knapp unter der Suchobergrenze (ein
Drittel der Profillänge). Die Prominenz-Berechnung schnitt ihr Fenster genau
dort ab und verdeckte das Tal rechts vom Peak — echte Reihen fielen durch.
Das Fenster reicht jetzt bis zur halben Profillänge. Grenze: Bei größeren
Pflanzen (14 Tage nach der Behandlung) ragen Blattspitzen zwischen die
Reihen und werden als „Unkraut“ gezählt.

**Befund zum CSC:** Beide Serien ergeben einen **negativen** CSC (Lfd. 1:
−94 %, Lfd. 17: −42 %, ExG+Otsu im Rahmen). Ursache sind die Aufnahmen selbst:
„Nachher“ entstand offenbar Tage später (Pflanzen sichtbar gewachsen) und bei
anderem Licht. Die App zeigt korrekt „Bildpaar prüfen“. Für den CSC müssen
Vorher und Nachher direkt nacheinander an derselben Stelle aufgenommen werden.

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
- **Erledigt (v1.8):** Abgleich gegen eine manuelle Punkt-Bonitur (36 Bilder
  × 100 Punkte, Feldversuch 10/2026) — Ergebnis: soilref wurde Standard.
  Offen: Verhalten bei dichtem Bestand (> 50 % Bedeckung) und bei anderem Licht
  als tiefer Sonne / Bewölkung (z. B. Dämmerung) ist nicht validiert.
- Die Licht-Warnung ist an 18 Fotos einer Kamera kalibriert; mit den eigenen
  Versuchsfotos (Sonne/Wolken) ist die Schwelle 2,1 zu überprüfen.

## Quellen

- Rasmussen, J. et al. – Arbeiten zur mechanischen Unkrautregulierung / CSC
- Rasmussen, J., Mathiasen, H. & Bibby, B. M. (2010): *Timing of
  post-emergence weed harrowing.* Weed Research 50, 436–446.
- Woebbecke, D. M. et al. (1995): *Color indices for weed identification.*
- Meyer, G. E. & Neto, J. C. (2008): *Verification of color vegetation indices.*
- Canopeo (Oklahoma State University) – App zur Messung der Grünbedeckung
