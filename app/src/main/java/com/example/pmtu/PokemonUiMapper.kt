package com.example.pmtu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class PokemonUiMapper(private val context: Context) {

    fun updatePokemonImage(pokemon: PokemonInfo?, imageView: ImageView, defaultResId: Int) {
        if (pokemon?.spriteBitmap != null) {
            imageView.setImageBitmap(pokemon.spriteBitmap)
        } else {
            imageView.setImageResource(defaultResId)
        }
    }

    fun updateEnemyTypeIcons(pokemon: PokemonInfo?, container: LinearLayout) {
        container.removeAllViews()
        if (pokemon == null) return

        addTypeImage(pokemon.type1, container)
        if (pokemon.type2 != "None" && pokemon.type2.isNotBlank()) {
            addTypeImage(pokemon.type2, container)
        }
    }

    fun addTypeImage(type: String, container: LinearLayout) {
        val cleanType = type.replace("{", "").replace("}", "").trim()
        val iv = ImageView(context)
        val size = 60
        iv.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            bottomMargin = 4
        }
        try {
            val inputStream = context.assets.open("$cleanType.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            iv.setImageBitmap(bitmap)
        } catch (e: Exception) {
            val tv = TextView(context)
            tv.text = cleanType
            tv.textSize = 8f
            container.addView(tv)
            return
        }
        container.addView(iv)
    }

    fun formatMoveText(
        result: MoveRepository.PowerResult,
        textView: TextView,
        lang: String,
        enemyPokemon: PokemonInfo?
    ): CharSequence {
        val moveData = result.moveData
        val powerval = result.power
        val effectiveness = result.effectiveness
        val cleanType = result.cleanType

        val builder = SpannableStringBuilder()
        val typeImagePath = "$cleanType.png"
        try {
            val inputStream = context.assets.open(typeImagePath)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val drawable: Drawable = BitmapDrawable(context.resources, bitmap)
            val size = (textView.textSize * 1.5).toInt()
            drawable.setBounds(0, 0, (size * bitmap.width / bitmap.height), size)
            builder.append("  ")
            builder.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(" ")
        } catch (e: Exception) {
            builder.append(moveData.type ?: "").append(" ")
        }

        val finalMoveName = if (lang == "de") moveData.germanName else moveData.englishName
        val wurfel = moveData.wurfel ?: ""
        val cleanWurfel = wurfel.replace(Regex("\\{.*?d[48]\\}"), "").trim()
        builder.append(" ").append(finalMoveName ?: "").append(" ").append(cleanWurfel)

        val start = builder.length
        builder.append(powerval.toString())
        val end = builder.length

        if (enemyPokemon != null) {
            if (effectiveness < 0) {
                builder.setSpan(ForegroundColorSpan(Color.RED), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (effectiveness > 0) {
                builder.setSpan(ForegroundColorSpan(Color.GREEN), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        return builder
    }
}
