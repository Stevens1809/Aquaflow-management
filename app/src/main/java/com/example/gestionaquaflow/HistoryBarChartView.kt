package com.example.gestionaquaflow

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * HistoryBarChartView — Graphique en barres avec axe Y et ligne de seuil
 *
 * Conçu pour le grand public :
 *   • Axe Y avec valeurs et unité lisibles (L, m³)
 *   • Valeur affichée en gras AU-DESSUS de chaque barre
 *   • Ligne pointillée orange = seuil limite
 *   • Barre cyan = aujourd'hui (live)
 *   • Barre orange = proche du seuil (> 80%)
 *   • Barre rouge = dépassement
 *   • Badge "Aujourd'hui" sous la barre courante
 *   • Auto-scroll droite pour voir les données récentes
 */
class HistoryBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Barre(
        val etiquette: String,       // Ex: "Lun\n09 Jun" ou "Jun\n2026"
        val valeur: Float,           // Valeur brute pour hauteur proportionnelle
        val texteValeur: String,     // Ex: "47.3 L" — affiché au-dessus
        val estAujourdhui: Boolean = false,
        val estDepasse: Boolean = false,
        val estProche: Boolean = false   // > 80% du seuil
    )

    var seuilValeur: Float = 0f          // Valeur du seuil dans la même unité que barres
    var uniteLabelY: String = "L"        // Unité affichée sur l'axe Y

    private var barres: List<Barre> = emptyList()
    private var valeurMax = 1f

    private val Float.dp: Float get() = this * resources.displayMetrics.density
    private val Int.dp: Float   get() = this * resources.displayMetrics.density

    // Dimensions
    private val Y_AXIS_W  = 56f   // Largeur axe Y (pour les labels)
    private val PAD_R     = 16f
    private val PAD_TOP   = 28f   // Espace au-dessus pour la valeur max
    private val H_GRAPH   = 160f
    private val H_LABEL   = 48f   // Hauteur zone étiquette X (2 lignes de texte)
    private val LARG_B    = 42f
    private val ESP_B     = 12f

    // Paints
    private val pBarreNormal  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E88E5.toInt(); style = Paint.Style.FILL }
    private val pBarreAujourd = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00BCD4.toInt(); style = Paint.Style.FILL }
    private val pBarreProche  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFA726.toInt(); style = Paint.Style.FILL }
    private val pBarreDepasse = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt(); style = Paint.Style.FILL }
    private val pFondCol      = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x0AFFFFFF.toInt(); style = Paint.Style.FILL }
    private val pAxe          = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF546E7A.toInt(); style = Paint.Style.STROKE }
    private val pGrille       = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF.toInt(); style = Paint.Style.STROKE }
    private val pYLabel       = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF78909C.toInt(); textAlign = Paint.Align.RIGHT }
    private val pValeur       = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFECEFF1.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pEtiq1        = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF90A4AE.toInt(); textAlign = Paint.Align.CENTER }
    private val pEtiq2        = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF607D8B.toInt(); textAlign = Paint.Align.CENTER }
    private val pSeuil        = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFA726.toInt(); style = Paint.Style.STROKE }
    private val pSeuilLabel   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFA726.toInt(); textAlign = Paint.Align.LEFT }
    private val pBadgeFond    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00ACC1.toInt(); style = Paint.Style.FILL }
    private val pBadgeTexte   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val pBarreAujourdFond = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1500BCD4.toInt(); style = Paint.Style.FILL }

    private val dashEffect = DashPathEffect(floatArrayOf(8f, 5f), 0f)

    fun setDonnees(barres: List<Barre>) {
        this.barres = barres
        this.valeurMax = barres.maxOfOrNull { it.valeur }?.takeIf { it > 0f } ?: 1f
        // Assurer que la ligne de seuil est visible si proche du max
        if (seuilValeur > 0f && seuilValeur > valeurMax) valeurMax = seuilValeur * 1.1f
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = barres.size.coerceAtLeast(1)
        val w = (Y_AXIS_W + 8f + n * (LARG_B + ESP_B) + PAD_R).dp.toInt()
        val h = (PAD_TOP + H_GRAPH + H_LABEL).dp.toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (barres.isEmpty()) return

        val yAxisX = Y_AXIS_W.dp
        val startX = yAxisX + 8f.dp
        val padTop = PAD_TOP.dp
        val hG     = H_GRAPH.dp
        val baseY  = padTop + hG
        val largB  = LARG_B.dp
        val espB   = ESP_B.dp

        // Tailles de texte adaptées
        pYLabel.textSize     = 10f.dp
        pValeur.textSize     = 9.5f.dp
        pEtiq1.textSize      = 10f.dp
        pEtiq2.textSize      = 9f.dp
        pSeuilLabel.textSize = 9f.dp
        pBadgeTexte.textSize = 8.5f.dp
        pAxe.strokeWidth     = 1.5f.dp
        pGrille.strokeWidth  = 0.7f.dp
        pSeuil.strokeWidth   = 1.5f.dp
        pSeuil.pathEffect    = dashEffect

        // ── Axe Y : 4 niveaux lisibles ────────────────────────────────────
        listOf(0f, 0.33f, 0.66f, 1f).forEach { frac ->
            val y      = baseY - frac * hG
            val valRef = frac * valeurMax
            val label  = formatYLabel(valRef)

            // Ligne de grille
            canvas.drawLine(yAxisX, y, width.toFloat() - PAD_R.dp, y, pGrille)

            // Label axe Y
            canvas.drawText(label, yAxisX - 4f.dp, y + 4f.dp, pYLabel)
        }

        // Unité sur l'axe Y (en haut)
        canvas.drawText(uniteLabelY, yAxisX / 2, padTop - 4f.dp, pYLabel)

        // Axe Y vertical
        pAxe.style = Paint.Style.STROKE
        canvas.drawLine(yAxisX, padTop, yAxisX, baseY, pAxe)

        // Axe X
        canvas.drawLine(yAxisX, baseY, width.toFloat() - PAD_R.dp, baseY, pAxe)

        // ── Ligne de seuil ─────────────────────────────────────────────────
        if (seuilValeur > 0f && seuilValeur <= valeurMax) {
            val seuilY = baseY - (seuilValeur / valeurMax) * hG
            canvas.drawLine(startX, seuilY, width.toFloat() - PAD_R.dp, seuilY, pSeuil)
            canvas.drawText("⬦ Seuil max", startX + 4f.dp, seuilY - 4f.dp, pSeuilLabel)
        }

        // ── Barres ─────────────────────────────────────────────────────────
        barres.forEachIndexed { i, barre ->
            val cx   = startX + i * (largB + espB) + largB / 2f
            val hB   = if (valeurMax > 0f) (barre.valeur / valeurMax) * hG else 0f
            val topB = (baseY - hB).coerceAtMost(baseY)

            // Fond colonne (guide visuel)
            canvas.drawRoundRect(cx - largB/2, padTop, cx + largB/2, baseY, 4f.dp, 4f.dp, pFondCol)

            // Fond spécial pour aujourd'hui (colonne entière en surbrillance)
            if (barre.estAujourdhui) {
                canvas.drawRoundRect(cx - largB/2, padTop, cx + largB/2, baseY, 4f.dp, 4f.dp, pBarreAujourdFond)
            }

            // Barre avec dégradé
            if (hB > 2f.dp) {
                val basePaint = when {
                    barre.estDepasse    -> pBarreDepasse
                    barre.estProche     -> pBarreProche
                    barre.estAujourdhui -> pBarreAujourd
                    else                -> pBarreNormal
                }
                val pGrad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = LinearGradient(
                        cx, topB, cx, baseY,
                        intArrayOf(fadeColor(basePaint.color, 0.65f), basePaint.color),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(cx - largB/2 + 3f.dp, topB, cx + largB/2 - 3f.dp, baseY, 5f.dp, 5f.dp, pGrad)
            }

            // Valeur au sommet (toujours affichée si la barre existe)
            if (barre.valeur > 0f) {
                val valY = if (hB > 18f.dp) topB - 5f.dp else baseY - hB - 5f.dp
                canvas.drawText(barre.texteValeur, cx, valY.coerceAtLeast(padTop + pValeur.textSize), pValeur)
            }

            // Étiquette ligne 1 + ligne 2
            val parties = barre.etiquette.split("\n")
            val yBase   = baseY + H_LABEL.dp / 2f + pEtiq1.textSize / 2f - 6f.dp
            if (parties.size >= 2) {
                canvas.drawText(parties[0], cx, yBase - pEtiq1.textSize, pEtiq1)
                canvas.drawText(parties[1], cx, yBase, pEtiq2)
            } else {
                canvas.drawText(barre.etiquette, cx, yBase, pEtiq1)
            }

            // Badge "Aujourd'hui"
            if (barre.estAujourdhui) {
                val bW  = 64f.dp
                val bH  = 16f.dp
                val bY0 = baseY + 4f.dp
                canvas.drawRoundRect(cx - bW/2, bY0, cx + bW/2, bY0 + bH, 8f.dp, 8f.dp, pBadgeFond)
                canvas.drawText("Aujourd'hui", cx, bY0 + bH - 3.5f.dp, pBadgeTexte)
            }
        }
    }

    private fun formatYLabel(valeur: Float): String {
        return when {
            valeur >= 1000f -> String.format("%.1fk", valeur / 1000f)
            valeur >= 100f  -> String.format("%.0f", valeur)
            valeur > 0f     -> String.format("%.1f", valeur)
            else            -> "0"
        }
    }

    private fun fadeColor(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
