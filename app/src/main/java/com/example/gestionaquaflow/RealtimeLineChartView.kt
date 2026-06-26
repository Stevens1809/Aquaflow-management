package com.example.gestionaquaflow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class RealtimeLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val valeurs = mutableListOf<Float>()

    private val lignePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val grillePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 30f
    }

    fun setValues(nouvellesValeurs: List<Float>) {
        valeurs.clear()
        valeurs.addAll(nouvellesValeurs.takeLast(MAX_POINTS))
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val largeur = width.toFloat()
        val hauteur = height.toFloat()
        if (largeur <= 0f || hauteur <= 0f) return

        lignePaint.color = ContextCompat.getColor(context, R.color.accent_bleu)
        pointPaint.color = ContextCompat.getColor(context, R.color.accent_vert)
        textePaint.color = ContextCompat.getColor(context, R.color.texte_secondaire)
        grillePaint.color = ContextCompat.getColor(context, R.color.contour_carte_bleu)

        dessinerGrille(canvas, largeur, hauteur)

        if (valeurs.isEmpty()) {
            canvas.drawText("En attente de donnees", largeur / 2f, hauteur / 2f + 10f, textePaint)
            return
        }

        val gauche = paddingLeft + 8f
        val droite = largeur - paddingRight - 8f
        val haut = paddingTop + 12f
        val bas = hauteur - paddingBottom - 12f
        val zoneLargeur = max(1f, droite - gauche)
        val zoneHauteur = max(1f, bas - haut)
        val maxDebit = max(1f, valeurs.maxOrNull() ?: 1f)
        val pasX = if (valeurs.size > 1) zoneLargeur / (valeurs.size - 1) else 0f

        fun yPour(valeur: Float): Float {
            val ratio = (valeur / maxDebit).coerceIn(0f, 1f)
            return bas - (ratio * zoneHauteur)
        }

        if (valeurs.size == 1) {
            val x = gauche + zoneLargeur / 2f
            val y = yPour(valeurs.first())
            canvas.drawCircle(x, y, 8f, pointPaint)
            return
        }

        val chemin = Path()
        valeurs.forEachIndexed { index, valeur ->
            val x = gauche + (index * pasX)
            val y = yPour(valeur)
            if (index == 0) chemin.moveTo(x, y) else chemin.lineTo(x, y)
        }

        canvas.drawPath(chemin, lignePaint)

        val dernierX = gauche + ((valeurs.size - 1) * pasX)
        val dernierY = yPour(valeurs.last())
        canvas.drawCircle(dernierX, dernierY, 7f, pointPaint)
    }

    private fun dessinerGrille(canvas: Canvas, largeur: Float, hauteur: Float) {
        val gauche = paddingLeft + 8f
        val droite = largeur - paddingRight - 8f
        repeat(3) { index ->
            val y = paddingTop + 12f + ((hauteur - paddingTop - paddingBottom - 24f) * index / 2f)
            canvas.drawLine(gauche, y, droite, y, grillePaint)
        }
    }

    companion object {
        private const val MAX_POINTS = 24
    }
}
