# Linsen-Matching: 0,5× als 1× einpassen

Dieses Dokument beschreibt, wie Wiggleshot die Ultraweitwinkel-Linse (0,5×) des Pixel 9 so
transformiert, dass sie exakt wie die Hauptlinse (1×) aussieht, und wie wir die
Abgleichsdaten der Pixel-Kamera-Pipeline experimentell auslesen und mit unserer Messung
vergleichen.

## Warum das alte Verfahren nur mittelmäßig war

| Problem im alten Code | Auswirkung |
|---|---|
| Kalibriert wurde auf 640×480-Vorschaubildern mit einem 48×48-Raster | Skalierung nur auf ±1–2 % genau → in 4K bis zu 40–80 px Versatz am Rand |
| Nur ein Zoomfaktor wurde gesucht; der gefundene Versatz wurde **verworfen** | Jede Aufnahme wurde mittig zugeschnitten, obwohl die optischen Achsen beider Module nicht mittig liegen → sichtbarer Sprung |
| Keine Rotation | Die Module sind leicht gegeneinander verdreht (Zehntelgrad) → Kanten „kippen“ beim Wackeln |
| Kalibrierung auf Vorschau, Anwendung auf Fotos | Vorschau- und Foto-Stream können unterschiedlich zugeschnitten/entzerrt sein |
| Ergebnis nicht gespeichert | Nach jedem App-Start neu kalibrieren |
| Kein Farbabgleich | Beide Linsen belichten und weißabgleichen getrennt → Flackern |

## Erkenntnisse aus dem ersten Test auf dem Pixel 9

- **Die Werkskalibrierung passt nicht zu den fertigen Bildern.** `LENS_INTRINSIC_CALIBRATION`
  beschreibt die *unentzerrte* Optik. Der HAL entzerrt die 0,5×-Linse und zoomt dabei
  hinein; der daraus berechnete Startwert lag ~16 % in der Skalierung und deutlich im
  optischen Mittelpunkt daneben (Mittelpunkt-Fehler auf der langen Sensorachse wird im
  Hochformat zum **Höhenversatz**). Die erste Version suchte nur eng um diesen Startwert,
  fand nichts („zu wenig Struktur“) und übernahm dann den falschen Startwert.
- **Referenz aus der Pixel-Kamera** (Screenshots 1× / 0,5× / 0,5× im Moment des
  Linsenwechsels): Vergrößerung der 0,5× ≈ **1,93×**, Rotation ≈ 0,05°. Beim Wechsel richtet
  die Pixel-Kamera den Hintergrund aus (Skalierung 1,006), nahe Objekte zeigen die volle
  Parallaxe. Wiggleshot misst auf denselben Screenshots 1,926× (0,8 px Abweichung zu SIFT) –
  auch mit absichtlich falschem Startwert.
- Daraus folgt die **Suchstrategie** (`AlignmentStrategy`): gespeicherte Kalibrierung nur als
  schneller erster Versuch, danach immer eine breite Suche (Skalierung ±40 % um einen
  groben Hinweis, Verschiebung frei, kein optischer Mittelpunkt angenommen), notfalls eine
  komplett freie Suche; der beste Treffer gewinnt. Kalibrierungen werden nur noch aus
  gleichzeitigen Aufnahmen gelernt, alte Kalibrierungen wurden verworfen.
- **Drehpunkt** wählbar: *Bildmitte* (klassischer Wiggle) oder *Hintergrund* (wie der
  Linsenwechsel der Pixel-Kamera).

## Neues Verfahren

Der Auslöser nimmt nur die JPEGs beider Linsen auf (im Simultan-Modus wenige hundert
Millisekunden) und ist sofort wieder frei. Jede Aufnahme kommt in eine Warteschlange und
wird im Hintergrund ausgerichtet und in der Galerie gespeichert; man kann währenddessen
weiter fotografieren. Pro Aufnahme läuft:

1. **Startwerte** – die gespeicherte Kalibrierung dieses Linsenpaares (nur aus
   gleichzeitigen Aufnahmen gelernt) für einen schnellen ersten Versuch, und ein grober
   Skalierungshinweis aus der Camera2-Werkskalibrierung. Beides wird immer per Bildanalyse
   bestätigt; ein Startwert allein entscheidet nie (siehe oben).
2. **Photometrische Normierung** – lokale Kontrastnormierung entfernt Unterschiede in
   Belichtung, Weißabgleich, Vignettierung und Tonemapping.
3. **Grobsuche** – erschöpfende ZNCC-Suche über Skalierung und Verschiebung auf ~128 px
   Breite rund um den Prior (ohne Prior zweistufig über den ganzen Bereich).
4. **Robuste Verfeinerung** – Gauss-Newton (Lucas-Kanade) auf einer Bildpyramide bis
   1024 px Arbeitsbreite, mit Cauchy-Gewichten: Bereiche mit Parallaxe, Bewegung oder
   ausgefressenen Lichtern werden automatisch heruntergewichtet. Geschätzt werden
   **Skalierung, Rotation und Verschiebung** (Ähnlichkeitstransformation).
5. **Pivot** – die Verschiebung wird noch einmal nur auf der Bildmitte verfeinert. Das ist
   der Punkt, der im Wiggle stillsteht; alles davor/dahinter bewegt sich (der 3D-Effekt).
6. **Resampling** – die 0,5×-Aufnahme wird mit dieser Transformation in die Geometrie der
   1×-Aufnahme gerechnet; beide werden auf den gemeinsam abgedeckten Bereich (und den
   Zoom der Hauptlinse) zugeschnitten.
7. **Farbabgleich** – Histogramm-Matching pro Farbkanal an die Hauptlinse.

Warum keine Homographie? Zwei Linsen ~1–2 cm nebeneinander unterscheiden sich durch eine
(winzige, tiefenunabhängige) Rotation und eine Translation, deren Wirkung von der Tiefe
abhängt – die Parallaxe. Genau die macht den Wiggle aus und darf nicht wegkorrigiert
werden. Zu entfernen sind Skalierung, Rollwinkel und Versatz am Pivot – genau die vier
Parameter der Ähnlichkeitstransformation.

**Genauigkeit (Unit-Tests auf synthetischen Szenen mit bekannter Geometrie,
inkl. Belichtungs-/Gamma-/Vignettierungsunterschied und Rauschen):** Eckenfehler
≈ 0,02 px bei 1024 px Arbeitsbreite (≈ 0,1 px in 4K), Rotation auf < 0,005° genau.
Der Pivot rastet auf ein nahes Motiv mit Parallaxe ein (< 0,4 px Fehler).

Code: `app/src/main/java/com/example/align/` (`LensAligner` = Algorithmus,
`WiggleAligner` = Android-Pipeline, `LensGeometry` = Camera2-Werkskalibrierung,
`ColorMatcher`, `CalibrationStore`). Tests: `app/src/test/java/com/example/align/`.

## Experiment: Abgleichsdaten der Pixel-Kamera auslesen

Die Pixel-Kamera schaltet bei 1,0× von der 0,5×- auf die 1×-Linse um. Damit das ohne
Sprung passiert, muss die Kamera-Pipeline das 0,5×-Bild bei 0,99× **exakt** wie das
1×-Bild aussehen lassen – das ist genau der Abgleich, den wir brauchen. Drittanbieter-Apps
bekommen dasselbe Verhalten über die logische Kamera: Wird `CONTROL_ZOOM_RATIO` unter 1,0
gesetzt, schneidet und verzerrt der Kamera-HAL das Ultraweitwinkelbild selbst.

### Schritt 1 – HAL-Zoom-Test in der App (≈ 15 s)

*Einstellungen → Linsen-Ausrichtung → HAL-Test.* Am besten mit Stativ (oder aufgelegt) auf
eine detailreiche Szene > 2 m Entfernung richten.

Die App nimmt den logischen Stream bei 0,6×, 0,8×, 0,9×, 0,95×, 0,99×, 1,0×, 1,01× und
1,2× auf – wenn das Gerät es erlaubt **in derselben Anfrage** zusammen mit den Rohbildern
beider physischer Linsen (also exakt derselbe Moment, kein Verwackeln dazwischen). Pro
Zoomstufe wird gemessen:

- `ours` – Hauptlinse → 0,5×-Rohbild, mit unserem Algorithmus,
- `hal` – logisches Bild → 0,5×-Rohbild, also wie der HAL das Ultraweitwinkelbild
  zugeschnitten/verzerrt hat, umgerechnet auf 1,0× und das Pixelraster der Hauptlinse,
- `deviationPx4k` – wie weit beide die Bildecken auseinander legen (in Pixeln bei 4000 px
  Breite). **Wenige Pixel = unser Abgleich entspricht dem der Pixel-Kamera.**

Zusätzlich werden `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (welche Linse aktiv war) und
ab Android 15 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION` (welcher
Sensorbereich gelesen wurde) protokolliert. Alles landet in
`Download/Wiggleshot/hal_probe_<zeit>/` (JPEGs + `hal_probe.json`).

Mögliche Ergebnisse und was wir daraus machen:

| Ergebnis | Bedeutung | Nächster Schritt |
|---|---|---|
| Abweichung < ~5 px | Unser Abgleich ist so gut wie der der Pixel-Kamera | nichts zu tun |
| HAL-Wert stabil über mehrere Szenen, unserer weicht ab | Der HAL kennt etwas, das wir nicht messen (z. B. Entzerrung) | HAL-Transformation als Prior/Fixwert übernehmen |
| Geräteseitig keine Roh-Streams parallel erlaubt | Nur logischer Stream | 0,99× gegen 1,0× vergleichen (Stativ nötig) oder Schritt 3 |
| Radiale Restabweichung im Offline-Check (Schritt 2) | Unterschiedliche Restverzeichnung der Linsen | radialen Term (k1) in den Aligner aufnehmen |

### Schritt 2 – Rohdaten normaler Aufnahmen sammeln

*Einstellungen → Diagnose-Modus* einschalten. Jede Aufnahme speichert dann zusätzlich die
unausgerichteten Original-JPEGs, alle Capture-Metadaten (Intrinsics, Verzeichnung,
Crop-Region, Fokus, Belichtung) und das Ausrichtungsergebnis nach
`Download/Wiggleshot/capture_<zeit>/`. *Metadaten* exportiert die Werkskalibrierung aller
Kameras (`camera_info.json`).

Unabhängige Gegenprobe am PC (SIFT + RANSAC, völlig anderes Verfahren):

```bash
pip install opencv-python-headless numpy
python3 tools/lens_match_analysis.py capture  <Ordner capture_…>
python3 tools/lens_match_analysis.py halprobe <Ordner hal_probe_…>
```

Ausgegeben werden Zoom, Rotation, Versatz, Restfehler, eine Schätzung der radialen
Restverzeichnung sowie Überblend- und Differenzbilder (`analysis_*_blend.jpg`,
`analysis_*_diff.jpg`).

### Schritt 3 – Ohne App: Fotos aus der Pixel-Kamera

Stativ, detailreiche Szene, in der Pixel-Kamera je ein Foto bei **1×** und **0,5×**
(optional zusätzlich **0,9×**) aufnehmen. Dann:

```bash
python3 tools/lens_match_analysis.py pair PXL_1x.jpg PXL_0.5x.jpg
python3 tools/lens_match_analysis.py pair PXL_1x.jpg PXL_0.9x.jpg   # wie gut passt Google selbst?
```

Achtung: Die Pixel-Kamera-App verarbeitet das 0,5×-Bild mit eigener Pipeline (HDR+,
eigene Entzerrung). Die Zahlen beschreiben daher Googles Geometrie, nicht zwingend die
der Camera2-Rohstreams – deshalb ist Schritt 1 die eigentliche Referenz.

### Was ich zurückbrauche

Den Ordner `hal_probe_…` (und gern 2–3 `capture_…`-Ordner aus dem Diagnose-Modus) –
damit lässt sich prüfen, ob der HAL eine bessere Transformation kennt und ob ein radialer
Verzeichnungsterm nötig ist.
