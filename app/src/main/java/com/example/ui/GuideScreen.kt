package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.WiggleColors

/**
 * How to get a strong 3D effect from two lenses that are only 16 mm apart. The numbers come
 * from the Pixel 9's calibration (baseline 16 mm, focal length 2950 px at 3072 px width) and
 * were checked against real captures; see docs/3D_GUIDE.md.
 */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WiggleColors.Background)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.background(WiggleColors.SurfaceElevated, RoundedCornerShape(12.dp)).size(44.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "3D-TIPPS",
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 1.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Card("Warum es auf den Abstand ankommt") {
            Body(
                "Die beiden Linsen liegen nur 16 mm auseinander – ein Viertel des Augenabstands. " +
                    "Der Wiggle zeigt deshalb nur dort Tiefe, wo sich Entfernungen stark unterscheiden. " +
                    "Entscheidend ist der Kehrwert der Entfernung: Ein Motiv von 2 m auf 0,5 m näher " +
                    "holen bringt viel mehr als den Hintergrund von 3 m auf 30 m wegzuschieben."
            )
            Spacer(modifier = Modifier.height(8.dp))
            Body("Stärke des Effekts ≈ 1,5 % der Bildbreite × (1/nah − 1/fern), Entfernungen in Metern.")
        }

        Card("Was du erwarten kannst") {
            TableRow("Motiv", "Hintergrund", "Wirkung", header = true)
            TableRow("0,3 m", "1,5 m", "sehr stark (Grenze)")
            TableRow("0,5 m", "2 m", "stark · 2,3 %")
            TableRow("0,5 m", "weit weg", "stark · 3,1 %")
            TableRow("1 m", "weit weg", "mittel–stark · 1,5 %")
            TableRow("1 m", "3 m", "mittel · 1,0 %")
            TableRow("1,5 m", "3 m", "schwach · 0,5 %")
            TableRow("3 m", "weit weg", "schwach · 0,5 %")
            Spacer(modifier = Modifier.height(8.dp))
            Body(
                "Gemessen: Decke vorne (~0,5 m) vor dem Raum 2,2 % – stark. Wäscheständer (~0,8 m) " +
                    "vor der TV-Wand (~2 m) 0,8–1,0 % – mittel. Nach jeder Aufnahme zeigt die App die " +
                    "gemessene 3D-Stärke an."
            )
        }

        Card("So wird es stark") {
            Tip("1", "Motiv nah: 0,4–1 m vor der Kamera. Unter 0,3 m wird es zu viel (Kanten reißen auf).")
            Tip("2", "Hintergrund weit weg: mindestens 3× so weit wie das Motiv, am besten offener Raum, Fenster, Straße.")
            Tip("3", "Motiv in die Bildmitte: dort stellt die Kamera scharf, und was scharf ist, bleibt im Wiggle stehen – die Szene dreht sich darum.")
            Tip("4", "Schichten: ein Gegenstand vorne, das Motiv, dahinter Raum – Diagonalen (Tisch, Weg, Zaun) wirken besonders.")
            Tip("5", "Hochformat halten: Die Linsen liegen dann nebeneinander und der Wiggle geht seitlich.")
        }

        Card("Das funktioniert schlecht") {
            Tip("–", "Motiv direkt vor einer Wand – kein Tiefenunterschied.")
            Tip("–", "Nur ferne Dinge (Landschaft, Gebäude > 5 m): fast kein Effekt.")
            Tip("–", "Nahe Objekte am Bildrand: sie werden abgeschnitten und flackern.")
            Tip("–", "Strukturlose Bildmitte (weiße Wand): der Drehpunkt findet keinen Halt.")
            Tip("–", "Spiegel, Glas, Bildschirme: zeigen eine andere Tiefe als ihre Oberfläche.")
        }

        Card("Licht und Bewegung") {
            Tip("☀", "Viel Licht ist am besten – die 0,5×-Linse hat einen kleinen Sensor und wird 2,3× vergrößert.")
            Tip("☾", "Bei wenig Licht den Nachtmodus nutzen und ~1 s ruhig halten; kleine Wackler gleicht die App aus.")
            Tip("⚡", "Bewegte Motive (Menschen, Tiere) im normalen Modus fotografieren: beide Linsen lösen gleichzeitig aus.")
            Tip("▶", "Abspielen: 120–200 ms pro Bild im Ping-Pong wirkt am natürlichsten.")
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .background(WiggleColors.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, WiggleColors.Outline, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WiggleColors.Accent)
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text = text, fontSize = 13.sp, color = WiggleColors.TextSecondary, lineHeight = 18.sp)
}

@Composable
private fun Tip(marker: String, text: String) {
    Row {
        Text(text = marker, fontSize = 13.sp, fontWeight = FontWeight.Black, color = WiggleColors.Accent, modifier = Modifier.width(22.dp))
        Text(text = text, fontSize = 13.sp, color = WiggleColors.TextSecondary, lineHeight = 18.sp)
    }
}

@Composable
private fun TableRow(a: String, b: String, c: String, header: Boolean = false) {
    val color = if (header) WiggleColors.TextTertiary else WiggleColors.TextPrimary
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(a, fontSize = 12.sp, color = color, fontWeight = weight, modifier = Modifier.weight(0.8f))
        Text(b, fontSize = 12.sp, color = color, fontWeight = weight, modifier = Modifier.weight(1f))
        Text(c, fontSize = 12.sp, color = color, fontWeight = weight, modifier = Modifier.weight(1.6f))
    }
}
