# Lebenslauf-Vorlage – Alan Kader

Moderne, zweispaltige Lebenslauf-Vorlage auf Basis von HTML & CSS.
Inhalt aus dem Word-Dokument übernommen, Layout am bevorzugten Design
orientiert (hellblaue Sidebar, Petrol-Akzent `#10657E`, Schrift „Blinker").

## Dateien

| Datei | Zweck |
|-------|-------|
| `lebenslauf.html` | Inhalt & Struktur – **hier wird der Text bearbeitet** |
| `style.css` | Gestaltung (Farben, Schrift, Layout) |
| `assets/img/foto.jpeg` | Bewerbungsfoto |
| `assets/fonts/` | Schriftart Blinker (eingebettet, keine Installation nötig) |
| `build.sh` | Erzeugt die PDF-Datei |
| `Lebenslauf_Alan_Kader.pdf` | Fertige PDF |

## PDF erzeugen

```bash
pip install weasyprint        # einmalig
./build.sh                     # -> Lebenslauf_Alan_Kader.pdf
```

Alternativ lässt sich `lebenslauf.html` einfach im Browser öffnen und über
„Drucken → Als PDF speichern" (Ränder: keine) exportieren.

## Anpassen

- **Texte / Stationen:** in `lebenslauf.html` ändern.
- **Farben / Abstände:** Variablen am Anfang von `style.css` (`:root { ... }`).
- **Foto austauschen:** Datei unter `assets/img/foto.jpeg` ersetzen.
- **Sidebar-Breite:** Variable `--sidebar-w` in `style.css`.

> Hinweis: Im Vergleich zum Original wurden einige Tippfehler korrigiert
> (z. B. „Flashen", „Steuergeräten", „SQL Server", „Verstellsysteme") und
> ein Untertitel/Berufsbezeichnung sowie eine Orts-/Datumszeile ergänzt.
