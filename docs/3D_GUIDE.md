# Anleitung: starke 3D-Wiggles mit dem Pixel 9

Kurzfassung: **Motiv nah (0,4–1 m), Hintergrund weit weg (mindestens 3× so weit), Motiv in
die Bildmitte, Hochformat.** Warum das so ist und wie stark der Effekt wird, steht unten –
mit Zahlen aus der Kalibrierung des Pixel 9 und aus echten Testaufnahmen.

Schon beim Ausrichten misst die App etwa einmal pro Sekunde die **3D-Stärke** auf den
Vorschaubildern beider Linsen und blendet oben im Sucher Untertitel-Tipps ein (✅ stark,
👍 mittel, ❌ schwach, dazu z. B. 👣 „Näher ran“ oder 🏞️ „Hintergrund zu nah“). Bei schneller
Bewegung und im Energiesparmodus pausiert die Messung. Nach jeder Aufnahme wird die Stärke
außerdem am fertigen Foto gemessen. Diese Anleitung in Kurzform: Knopf **3D-Tipps** neben dem
Auslöser.

## Warum der kleine Linsenabstand alles bestimmt

Laut `LENS_POSE_TRANSLATION` sitzt die 0,5×-Linse **16 mm** neben der Hauptlinse (im
Hochformat waagerecht daneben). Ein Punkt in der Entfernung *Z* erscheint in den beiden
Bildern um

```
Parallaxe = f · b / Z
```

verschoben (*f* = Brennweite in Pixeln, *b* = Basis). Beim Wiggle steht der Drehpunkt (die
Bildmitte) still; sichtbar ist der **Unterschied** zwischen nahen und fernen Teilen:

```
Spanne = f · b · (1/Z_nah − 1/Z_fern)
```

Beim Pixel 9 ist f = 2950 px bei 3072 px Bildbreite (4K, Hochformat), also

```
Spanne ≈ 1,54 % der Bildbreite × (1/Z_nah − 1/Z_fern)      (Z in Metern)
```

Daraus folgen drei Regeln:

1. **Es zählt der Kehrwert der Entfernung.** Den Hintergrund von 3 m auf „unendlich“ zu
   schieben bringt 0,33 m⁻¹; das Motiv von 1 m auf 0,5 m zu holen bringt 1 m⁻¹ – dreimal so
   viel. **Näher ran ist der stärkste Hebel.**
2. **Der Hintergrund muss deutlich weiter weg sein.** Bei 3× der Motiventfernung hat man
   schon zwei Drittel des möglichen Effekts; direkt vor einer Wand ist er nahe null.
3. **Der Abstand der Linsen ist klein.** 16 mm sind ein Viertel des Augenabstands (~63 mm):
   bei gleichen Entfernungen ist die Tiefe viermal schwächer als beim Sehen. Klassische
   Wiggle-Kameras mit vier Objektiven (Nimslo, Nishika) haben zwischen benachbarten
   Objektiven einen ähnlichen Abstand, verteilen den Wiggle aber auf vier Ansichten über
   eine größere Gesamtbreite – deshalb wirken deren Wiggles bei gleicher Szene stärker.
   Mit zwei Linsen gleicht man das durch nähere Motive aus.

## Was man erwarten kann

| Motiv | Hintergrund | Spanne (% Bildbreite) | Wirkung |
|---|---|---|---|
| 0,3 m | 1,5 m | 4,1 % | sehr stark – obere Grenze |
| 0,5 m | 2 m | 2,3 % | stark |
| 0,5 m | weit weg | 3,1 % | stark |
| 0,75 m | 3 m | 1,5 % | mittel–stark |
| 1 m | weit weg | 1,5 % | mittel–stark |
| 1 m | 3 m | 1,0 % | mittel |
| 1,5 m | 3 m | 0,5 % | schwach |
| 2 m | 4 m | 0,4 % | schwach |
| 3 m | weit weg | 0,5 % | schwach |

**Gemessen** (Spanne der Parallaxe vom 10. bis 90. Perzentil von ~110 Bildfeldern, so wie
die App sie anzeigt):

| Testaufnahme | Szene | gemessen | Bewertung |
|---|---|---|---|
| Decke ganz vorne, Regal hinten | ~0,5 m vor dem Raum | 2,2 % | stark |
| Wäscheständer vor TV-Wand | ~0,8 m vor ~2 m | 1,0 % | mittel |
| dasselbe, Nachtmodus | ~0,8 m vor ~2 m | 0,8 % | mittel |

Bewertung in der App: unter 0,6 % schwach, 0,6–1,6 % mittel, ab 1,6 % stark.

**Obergrenze:** Ab etwa 4 % (Objekte näher als ~0,3 m) springt der Wiggle unruhig, und an
den Kanten naher Objekte sieht die 0,5×-Linse „um das Objekt herum“ – dort entstehen
Ränder, die in nur einem Bild existieren. Die klassische Stereo-Faustregel (nächstes Objekt
≈ 30 × Basis) ergibt 0,5 m als angenehmen Nahbereich.

## Checkliste

**Für einen starken Effekt**

- Motiv **0,4–1 m** vor der Kamera.
- Hintergrund **mindestens 3× so weit** – offener Raum, Fenster, Straße, Landschaft.
- Motiv **in die Bildmitte**: Mit dem Drehpunkt „Bildmitte“ (Einstellungen) steht es still,
  Vorder- und Hintergrund bewegen sich gegenläufig. Mit „Hintergrund“ steht der Hintergrund
  still und das Motiv springt – so wie beim Linsenwechsel der Pixel-Kamera.
- **Schichten**: etwas vorne, das Motiv, dahinter Tiefe. Linien, die in die Tiefe laufen
  (Tisch, Weg, Zaun, Geländer), wirken besonders plastisch.
- **Hochformat**: Dann liegen die Linsen nebeneinander und der Wiggle geht seitlich, wie
  beim Sehen.

**Vermeiden**

- Motiv direkt vor einer Wand oder einem Schrank.
- Nur ferne Dinge (alles > 5 m): unter 0,3 % Spanne, kaum sichtbar.
- Nahe Objekte am Bildrand: werden angeschnitten und flackern.
- Strukturlose Bildmitte (weiße Wand, Himmel): der Drehpunkt findet keinen Halt.
- Spiegel, Glas, Bildschirme: zeigen eine andere Tiefe als ihre Oberfläche.

**Licht und Bewegung**

- Viel Licht ist am besten: Die 0,5×-Linse hat ~1/3 der Sensorfläche und wird 2,3×
  vergrößert; bei wenig Licht rauscht sie sichtbar (der Rauschfilter hilft, ersetzt aber kein
  Licht).
- Dunkel: **Nachtmodus** (Mond-Symbol). Etwa 1 s ruhig halten – der Ring um den Auslöser
  zeigt, wie lange noch. Kleine Wackler gleicht die App aus (Testserie: 1,7–7 px Bewegung,
  5/5 Bilder genutzt). Bewegte Teile werden aus der Mittelung genommen.
- Bewegte Motive (Menschen, Tiere): **normaler Modus** – beide Linsen lösen im selben
  Moment aus, Bewegung wird eingefroren.
- Abspielen: **120–200 ms pro Bild** im Ping-Pong wirkt am natürlichsten.
