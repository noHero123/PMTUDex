package com.example.pmtu

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.Locale

class ResultActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var imageView: ImageView
    private lateinit var textView: TextView
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingTTS: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Center Container for Image and Text
        val centerContainer = LinearLayout(this)
        centerContainer.orientation = LinearLayout.VERTICAL
        centerContainer.gravity = Gravity.CENTER
        val centerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        centerParams.gravity = Gravity.CENTER
        centerContainer.layoutParams = centerParams
        centerContainer.setPadding(32, 32, 32, 32)

        // Scanned Text
        val scannedText = intent.getStringExtra("SCANNED_TEXT") ?: "No data"
        
        // Image View
        imageView = ImageView(this)
        imageView.setImageResource(android.R.drawable.ic_menu_camera)
        val imageParams = LinearLayout.LayoutParams(600, 600)
        imageParams.bottomMargin = 64
        imageView.layoutParams = imageParams
        centerContainer.addView(imageView)

        // Text View for scanned result
        textView = TextView(this)
        textView.text = "Scanned Result:\n$scannedText"
        textView.textSize = 20f
        textView.gravity = Gravity.CENTER
        centerContainer.addView(textView)

        rootLayout.addView(centerContainer)

        // New Scan Button at the bottom
        val newScanButton = Button(this)
        newScanButton.text = "New Scan"
        val buttonParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        buttonParams.gravity = Gravity.BOTTOM
        buttonParams.setMargins(64, 0, 64, 64)
        newScanButton.layoutParams = buttonParams

        // Adjust button position to be above navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(newScanButton) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = insets.bottom + 64
            }
            windowInsets
        }

        newScanButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
        rootLayout.addView(newScanButton)

        setContentView(rootLayout)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        if (scannedText.startsWith("#")) {
            val number = scannedText.drop(1)
            val poke_url = "https://www.serebii.net/scarletviolet/pokemon/new/" + number + ".png"

            downloadImage(poke_url)
            get_pokedex(scannedText)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.GERMAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language specified is not supported!")
            } else {
                isTtsReady = true
                pendingTTS?.let {
                    speakOut(it)
                    pendingTTS = null
                }
            }
        } else {
            Log.e("TTS", "Initialization Failed!")
        }
    }

    private fun speakOut(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
        } else {
            pendingTTS = text
        }
    }
    
    private fun get_pokedex(number: String) {
        try {
            val inputStream = assets.open("pokedex.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                val lin2 = line?.drop(1)?.dropLast(1)
                val rawColumns = lin2?.split("\",\"") ?: continue
                // Map columns to remove surrounding quotes and trim whitespace
                val columns = rawColumns.map { it.trim().removeSurrounding("\"") }
                
                if (columns.isNotEmpty() && columns[0] == number) {
                    // Display the cleaned data
                    val name = columns[1].replace("{G-Max}", "Gmax").replace("{MEGA}", "Mega")
                    val default_level = columns[2]
                    val type1 = columns[3]
                    val type2 = columns[4]
                    val move1 = columns[5].split("/").last()
                    val move2 = columns[6].split("/").last()
                    val pokedex = columns.last()
                    val TTStext = name + ". " + pokedex
                    val m1 = search_moves(move1)
                    val m2 = search_moves(move2)
                    textView.text = TTStext + "\n"+m1 + "\n"+m2
                    speakOut(TTStext)
                    break
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
            textView.text = "Error reading Pokédex"
        }
    }

    private fun search_moves(moveName: String):String {
        try {
            val moveFiles = assets.list("")?.filter { it.startsWith("PMTU Moves") } ?: return ""
            for (fileName in moveFiles) {
                val inputStream = assets.open(fileName)
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lin2 = line?.trim()?.removeSurrounding("\"")
                    val columns = lin2?.split(",") ?: continue

                    val filename = columns[10]
                    
                    if ( filename.equals(moveName, ignoreCase = true)) {
                        var wurfel = columns[1]
                        if (wurfel == ""){
                            wurfel = "d6"
                        }
                        var power = columns[3]
                        val is_stab = moveName.endsWith("(S)")
                        if (is_stab)
                        {
                            power = columns[4]
                        }
                        val type = columns[0]
                        val resultText = type + " " + power + " " + columns[16] + " " + wurfel

                        return resultText
                    }
                }
                reader.close()
            }
        } catch (e: Exception) {
            Log.e("Moves", "Error searching moves", e)
        }
        return ""
    }

    private fun downloadImage(url: String) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val inputStream = URL(url).openStream()
                    BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    override fun onDestroy() {
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
        }
        super.onDestroy()
    }
}
