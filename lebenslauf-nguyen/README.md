# Lebenslauf-Vorlage – Trang Phuong Nguyen

Moderne, zweispaltige Lebenslauf-Vorlage (hellblaue Sidebar, Petrol-Akzent
`#10657E`, Schrift „Blinker") – dasselbe Design wie die Vorlage für Alan Kader,
hier mit den Inhalten aus dem PDF-Lebenslauf von Trang Phuong Nguyen.

Es gibt **zwei Ausgabeformate**:

- **PDF** – exaktes Layout, fertig zum Versenden (`Lebenslauf_Trang_Nguyen.pdf`)
- **Word (.docx)** – zum selbst Bearbeiten (`Lebenslauf_Trang_Nguyen.docx`)

## Dateien

| Datei | Zweck |
|-------|-------|
| `lebenslauf.html` | Inhalt & Struktur der PDF-Version |
| `style.css` | Gestaltung der PDF-Version |
| `make_docx.py` | Erzeugt die Word-Version |
| `assets/img/foto.png` | Bewerbungsfoto |
| `assets/fonts/` | Schriftart Blinker (für die PDF eingebettet) |
| `build.sh` | Erzeugt die PDF-Datei |
| `Lebenslauf_Trang_Nguyen.pdf` | Fertige PDF |
| `Lebenslauf_Trang_Nguyen.docx` | Fertige Word-Datei |

## PDF neu erzeugen

```bash
pip install weasyprint
./build.sh                     # -> Lebenslauf_Trang_Nguyen.pdf
```

Alternativ `lebenslauf.html` im Browser öffnen und über „Drucken → Als PDF
speichern" (Ränder: keine) exportieren.

## Word-Datei neu erzeugen

```bash
pip install python-docx
python3 make_docx.py           # -> Lebenslauf_Trang_Nguyen.docx
```

## Anpassen

- **PDF-Texte:** in `lebenslauf.html` ändern.
- **Word-Texte:** direkt in der `.docx` in Word/LibreOffice bearbeiten
  (oder die Inhalte in `make_docx.py` anpassen und neu erzeugen).
- **Farben/Abstände der PDF:** Variablen in `style.css` (`:root { ... }`).
- **Foto austauschen:** `assets/img/foto.png` ersetzen.

> Schrift-Hinweis: Die PDF nutzt die eingebettete Schrift „Blinker". Für den
> exakt gleichen Look in der Word-Datei sollte Blinker installiert sein
> (kostenlos via Google Fonts). Ist sie nicht installiert, ersetzt Word sie
> automatisch durch eine ähnliche serifenlose Schrift – Inhalt und Layout
> bleiben gleich.
