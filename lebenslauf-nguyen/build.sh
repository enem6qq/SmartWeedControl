#!/usr/bin/env bash
# Rendert die Lebenslauf-Vorlage in eine PDF-Datei.
# Voraussetzung: weasyprint (pip install weasyprint)
set -euo pipefail
cd "$(dirname "$0")"
weasyprint lebenslauf.html Lebenslauf_Trang_Nguyen.pdf
echo "Fertig -> Lebenslauf_Trang_Nguyen.pdf"
