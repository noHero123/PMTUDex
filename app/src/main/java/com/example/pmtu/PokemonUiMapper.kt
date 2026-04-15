package com.example.pmtu

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.io.InputStream


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

        addTypeImage(pokemon.getType1(), container)
        if (pokemon.getType2() != "None" && pokemon.getType2().isNotBlank()) {
            addTypeImage(pokemon.getType2(), container)
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
        ownPokemon: PokemonInfo?,
        enemyPokemon: PokemonInfo?,
        ownWeather: String?,
        enemyWeather: String?,
        pokedexRepository: PokedexRepository,
        onEffectClicked: (String, View, String?) -> Unit
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
        var finalMoveName = if (lang == "de") moveData.germanName else moveData.englishName
        val isSpecialMove = moveData.powerStr!!.contains("*")
        if(isSpecialMove)
        {
            finalMoveName+="*"
        }
        var detailName = moveData.englishName!!
        if(moveData.wurfel != null )
        {
            if(moveData.wurfel != null && moveData.wurfel.contains("G-Max"))
            {
                detailName = "{G-MAX} " + detailName
            }
            else
            {
                if(moveData.wurfel != null && moveData.wurfel.contains("Max"))
                {
                    detailName = "{MAX} " + detailName
                }
            }
        }

        val startText = builder.length
        builder.append(finalMoveName ?: "").append(" ")//.append(cleanWurfel)
        val endText = builder.length
        if (onEffectClicked != null && isSpecialMove) {
            val clickableSpan = object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) {
                    onEffectClicked(detailName, widget, null)
                }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.isUnderlineText = false // Remove the default link underline
                }
            }
            builder.setSpan(clickableSpan, startText, endText, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }


        val start = builder.length
        builder.append(powerval.toString())
        val end = builder.length
        builder.append(" ")

        if (enemyPokemon != null) {
            if (effectiveness < -4) {
                 // Immunity or extremely ineffective
                 builder.setSpan(ForegroundColorSpan(Color.RED), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (effectiveness < 0) {
                builder.setSpan(ForegroundColorSpan(Color.RED), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (effectiveness > 0) {
                builder.setSpan(ForegroundColorSpan(Color.GREEN), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        var usedKing = false
        var usedZoom = false
        var meff1 = moveData.effect1?.replace("{", "")?.replace("}", "")?.trim() ?: ""
        var meff2 = moveData.effect2?.replace("{", "")?.replace("}", "")?.trim() ?: ""
        val effs = mutableListOf(meff1, meff2) // Initialization
        if (ownPokemon!!.isDynaActivated)
        {
            //dynamex always erases old effects
            effs.clear()
            effs.add(getDynamaxEffect(moveData.englishName))
        }
        if (ownPokemon!!.isGigaDynaActivated || ownPokemon.name.contains("Gigantamax"))
        {
            effs.clear()
            val geff = getDynamaxEffect(moveData.englishName)
            val geff2 = getGigaDynamaxEffect(moveData.englishName)
            if(!geff2.isEmpty())
            {
                //gmax effects
                for(e in geff2)
                {
                    effs.add(e)
                }
            }else {
                if (geff != "") {
                    effs.add(geff)
                }
            }

        }

        effs.add("")
        for (effi in effs)
        {
            if(effi == "")
            {continue}
            var eff = effi
            // Kings stone
            if(ownPokemon?.baseItem == "King" && !usedKing && eff.contains("B Dis"))
            {
                val counter = (eff.split(" ").last()).toIntOrNull()
                if (counter != null && counter > 1)
                {
                    eff = eff.replace(counter.toString(), (counter - 1).toString())
                    usedKing = true
                }
            }
            // Zoom Lense
            if(ownPokemon?.baseItem == "Zoom" && !usedZoom && eff.contains("W Adv"))
            {
                val counter = (eff.split(" ").last()).toIntOrNull()
                if (counter != null && counter > 1)
                {
                    eff = eff.replace(counter.toString(), (counter - 1).toString())
                    usedZoom = true
                }
            }
            //Wide lense
            if(ownPokemon?.baseItem == "Wide" && !eff.contains("KO") && ownPokemon.isBaseItemActivated)
            {
                val counter = (eff.split(" ").last()).toIntOrNull()
                if (counter != null && counter > 1)
                {
                    eff = eff.replace(counter.toString(), (counter - 1).toString())
                }
            }

            // Add effects
            if(enemyWeather == "Mist" && eff.contains("Dis"))
            {
                //ignore diss advantage if enemy has mist
            }
            else
            {
                addEffectIcon(builder, eff, textView,finalMoveName?:"", onEffectClicked)
            }
        }

        //additional effects:
        if(ownWeather == "Renewal")
        {
            addEffectIcon(builder, "W Life", textView,"", onEffectClicked)
        }
        if(enemyWeather != "Mist" && ownPokemon?.baseItem == "Evio" && ownPokemon.isBaseItemActivated && !pokedexRepository.isFullyEvolved(ownPokemon.id))
        {
            //evio adds a dis to your attacks:
            addEffectIcon(builder, "B Dis 1", textView,"", onEffectClicked)
        }
        if(enemyWeather != "Mist" && ownPokemon?.baseItem == "King" && !usedKing){
            addEffectIcon(builder, "B Dis 5", textView,"", onEffectClicked)
        }
        if(ownPokemon?.baseItem == "Zoom" && !usedZoom){
            addEffectIcon(builder, "W Adv 5", textView,"", onEffectClicked)
        }
        if(ownPokemon?.baseItem == "Quic" && ownPokemon.isBaseItemActivated){
            addEffectIcon(builder, "W Priority", textView,"", onEffectClicked)
        }
        if(ownPokemon?.baseItem == "Razo"){
            addEffectIcon(builder, "W Extra 6", textView, "Razo", onEffectClicked)
        }


        return builder
    }

    private fun getDynamaxEffect(moveName:String):String
    {
        if (moveName == "Strike")
            return "W Priority"
        if (moveName == "Knuckle")
            return "W Adv 1"
        if (moveName == "Airstream")
            return "W Priority"
        if (moveName == "Ooze")
            return "W Adv 1"
        if (moveName == "Rockfall")
            return "Sandstorm"
        if (moveName == "Flutterby")
            return "B Dis 1"
        if (moveName == "Phantasm")
            return "W Adv 1"
        if (moveName == "Steelspike")
            return "B Dis 1"
        if (moveName == "Flare")
            return "Sunny"
        if (moveName == "Geyser")
            return "Rain"
        if (moveName == "Overgrowth")
            return "Grassy Terrain"
        if (moveName == "Lightning")
            return "Electric Terrain"
        if (moveName == "Mindstorm")
            return "Psychic Terrain"
        if (moveName == "Hailstorm")
            return "Hail"
        if (moveName == "Wyrmwind")
            return "B Dis 1"
        if (moveName == "Darkness")
            return "W Adv 1"
        if (moveName == "Starfall")
            return "Misty Terrain"
        if (moveName == "Guard")
            return "W Prot 1"
        return ""
    }

    private fun getGigaDynamaxEffect(moveName:String):List<String>
    {
        var effects = mutableListOf<String>()
        if (moveName.equals("BEFUDDLE", ignoreCase = true)){
            effects.add("B Pois 2")
            effects.add("B Para 4")
            effects.add("B Sleep 6")
        }
        if (moveName.equals("DEPLETION", ignoreCase = true)){
            effects.add("W Recharge")
        }
        if (moveName.equals("STUN SHOCK", ignoreCase = true)){
            effects.add("B Pois 3")
            effects.add("B Para 6")
        }
        if (moveName.equals("WIND RAGE", ignoreCase = true)){
            effects.add("Clear")
        }

        return effects
    }


    private fun isAssetExists(pathInAssetsDir: String): Boolean {

        return try {
             context.assets.open(pathInAssetsDir).use{true}
        } catch (e: IOException) {
            false
        }
    }

    fun get_add_effect_icon_path(effect: String?):String?
    {
        if (effect.isNullOrBlank()) return null

        val cleanEffect = effect.replace("{", "").replace("}", "").trim()
        if (cleanEffect.isEmpty()) return null

        var folder: String? = null
        var imageName: String = cleanEffect

        if (cleanEffect.startsWith("B ")) {
            folder = "Black"
            imageName = cleanEffect.substring(2).trim()
        } else if (cleanEffect.startsWith("W ")) {
            folder = "White"
            imageName = cleanEffect.substring(2).trim()
        } else if (cleanEffect.startsWith("B")) {
            folder = "Black"
            imageName = cleanEffect.substring(1).trim()
        } else if (cleanEffect.startsWith("W")) {
            folder = "White"
            imageName = cleanEffect.substring(1).trim()
        }

        // Common mappings for abbreviations in CSV to actual filenames
        var mappedName = imageName
        mappedName = mappedName.replace("DoubAdv", "3Dice")
        mappedName = mappedName.replace("DoubDis", "3Dice")
        mappedName = mappedName.replace("Adv", "2Dice")
        mappedName = mappedName.replace("Dis", "2Dice")
        mappedName = mappedName.replace("Add", "AddDice")
        mappedName = mappedName.replace("Extra", "Additional")

        mappedName = mappedName.replace("Switch", "Switch")
        mappedName = mappedName.replace("KO", "KO")
        mappedName = mappedName.replace("Status", "StatusHeal")
        mappedName = mappedName.replace("Life", "Life Drain")
        mappedName = mappedName.replace("You", "You Icon")
        mappedName = mappedName.replace("Opp", "Opponent Icon")
        mappedName = mappedName.replace("Block", "Block Item")
        mappedName = mappedName.replace("Ignore", "Ignore Type")
        mappedName = mappedName.replace("Prot", "Protection")
        mappedName = mappedName.replace("Burn", "Burned")
        mappedName = mappedName.replace("Conf", "Confused")
        mappedName = mappedName.replace("Pois", "Poison")
        mappedName = mappedName.replace("Para", "Paralyse")
        mappedName = mappedName.replace("Freeze", "Frozen")
        mappedName = mappedName.replace("Lock", "Esc Prevention")
        mappedName = mappedName.replace("StatDown", "StatDown")
        mappedName = mappedName.replace("Rage", "Rage")
        mappedName = mappedName.replace("Priority", "Priority")
        mappedName = mappedName.replace("Sleep", "Sleep")
        mappedName = mappedName.replace("Boost", "Condition Boost")

        mappedName = mappedName.replace("Clear", "Field Clear")

        if (mappedName.contains( "AdvDis"))
        {
            mappedName = "AdvDis 6"
        }

        val pathsToTry = mutableListOf<String>()
        if (folder != null) {
            pathsToTry.add("move_symbols/$folder/$mappedName.png")
            // Try with space mapping too if it looks like "Word Number"
            if (mappedName.contains(" ")) {
                // already handled
            } else if (imageName != mappedName) {
                pathsToTry.add("move_symbols/$folder/$imageName.png")
            }
        } else {
            pathsToTry.add("move_symbols/$cleanEffect.png")
        }
        pathsToTry.add("Field/$cleanEffect.png")
        pathsToTry.add("Field/$mappedName.png")
        val path = context.filesDir.path

        for (path in pathsToTry) {
            if (isAssetExists(path)) {
                return path
            }
        }
        return null
    }
    private fun addEffectIcon(builder: SpannableStringBuilder, effect: String?, textView: TextView, altEffectName:String = "", onEffectClicked: ((String, View, String?) -> Unit)? = null) {
        if (effect.isNullOrBlank()) return
        val cleanEffect = effect.replace("{", "").replace("}", "").trim()
        if (cleanEffect.isEmpty()) return

        val image_path = get_add_effect_icon_path(effect)
        if (image_path == null) {
            builder.append(" ").append(effect)
            return
        }
        try {
            val inputStream = context.assets.open(image_path)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val drawable: Drawable = BitmapDrawable(context.resources, bitmap)
            val size = (textView.textSize * 1.5).toInt()
            drawable.setBounds(0, 0, (size * bitmap.width / bitmap.height), size)
            val start = builder.length
            builder.append("  ")
            val end = builder.length - 1
            builder.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // 2. The ClickableSpan (The logic)
            if (onEffectClicked != null) {
                val clickableSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        var effectName = cleanEffect
                        if(cleanEffect.contains(" Extra "))
                        {
                            effectName = altEffectName
                        }
                        onEffectClicked(effectName, widget, image_path)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false // Remove the default link underline
                    }
                }
                builder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } catch (e: Exception) {
        }
    }

}
