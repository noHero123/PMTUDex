package com.example.pmtu

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
import java.net.URL

class ResultActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView

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
        val textView = TextView(this)
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

        // Example: Download image if the scanned text is a URL, or use a hardcoded one

        if (scannedText.startsWith("#")) {
            val poke_url = "https://www.serebii.net/scarletviolet/pokemon/new/" + scannedText.drop(1).split(",")[0] + ".png"

            downloadImage(poke_url)
        }
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
}
