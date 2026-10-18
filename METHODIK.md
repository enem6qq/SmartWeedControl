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

### Vorverarbeitung: Zählrahmen (seit v1.7)

Liegt ein **roter Zählrahmen** im Bild, wertet die App nur sein Inneres aus
(Einstellung „Nur Zählrahmen auswerten“, Standard: an). Das Innere wird als
größtes Loch in der Rot-Maske gefunden, perspektivisch zu einem Quadrat
entzerrt (Originalauflösung, damit die Mindestgröße ihre Bedeutung behält)
und mit 6 % Sicherheitsrand beschnitten. Blätter über dem Rahmen werden durch
schrittweises Schließen der Lücken überbrückt; eine Ringprüfung (außen
überwiegend Rot, innen kaum Rot, Rot nicht großflächig) verhindert
Fehlalarme auf rötlichem Boden oder roten Geräten. Ohne erkannten Rahmen
wird das ganze Bild ausgewertet und das in der App angezeigt; ein
Kontrollbild mit eingezeichnetem Ausschnitt liegt im `details/`-Ordner.

Warum: Auf Vollbildern zählte die Segmentierung Schatten und Kleidung
(Hose) als Pflanze und lieferte so einen scheinbar plausiblen, aber falschen
CSC (gemessen: 34 % bzw. 43 % statt eines negativen Werts im Rahmen). Der
Rahmen ist zudem genau die Fläche, die auch bonitiert wird. Hinweis für die
Praxis: Graue Drahtrahmen mit rotem oder orangem Klebeband umwickeln.

### Verfahren

Drei Verfahren stehen zur Wahl (Analyse-Einstellungen → Segmentierung):

- **ExG + Otsu (Standard, empfohlen):** Excess-Green-Index
  `ExG = 2·g − r − b` auf normalisierten RGB-Kanälen, gefolgt von einem
  automatischen Otsu-Schwellwert, der sich jedem Bild anpasst. Bekannte
  Grenze (gemessen in v1.7): Diese bildabhängige Schwelle **wandert mit dem
  Licht** — bewölkt ~0,06–0,09, Sonne ~0,14, Sonne mit dichtem Bestand bis
  0,21. Bei Sonne werden dünne Blätter unterschätzt, bei Wolkenlicht fransen
  die Blattränder in den Boden aus. Ein Lichtwechsel zwischen Vorher und
  Nachher verschiebt den CSC deshalb in eine Richtung (siehe Licht-Warnung).
  Zwei Schutzmechanismen sichern die unimodalen Grenzfälle ab, in denen Otsu
  keine sinnvolle Trennung finden kann: Auf vegetationsfreien Bildern
  verhindert eine ExG-Untergrenze Geister-Detektionen auf Bodenrauschen, und
  auf (nahezu) vollflächig bewachsenen Bildern — wo Otsu den Schwellwert
  mitten in die Vegetationsverteilung legen und die Bedeckung halbieren
  würde — zählt direkt die ExG-Untergrenzen-Maske.
  Grundlage: Woebbecke et al. (1995), Meyer & Neto (2008) — der Standard-
  Vegetationsindex der Agrar-Bildanalyse und die im Businessplan genannte
  Zielmethodik.
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
- **Geplant:** Abgleich der gemessenen Bedeckungsgrade gegen eine
  Referenzmessung (z. B. die frei verfügbare App *Canopeo* der Oklahoma State
  University) sowie gegen manuelle Auszählung. Erst damit lässt sich
  entscheiden, ob ExGR statt ExG+Otsu Standard werden sollte.
- Die Licht-Warnung ist an 18 Fotos einer Kamera kalibriert; mit den eigenen
  Versuchsfotos (Sonne/Wolken) ist die Schwelle 2,1 zu überprüfen.

## Quellen

- Rasmussen, J. et al. – Arbeiten zur mechanischen Unkrautregulierung / CSC
- Rasmussen, J., Mathiasen, H. & Bibby, B. M. (2010): *Timing of
  post-emergence weed harrowing.* Weed Research 50, 436–446.
- Woebbecke, D. M. et al. (1995): *Color indices for weed identification.*
- Meyer, G. E. & Neto, J. C. (2008): *Verification of color vegetation indices.*
- Canopeo (Oklahoma State University) – App zur Messung der Grünbedeckung
