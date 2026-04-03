package com.example.pmtu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import me.dm7.barcodescanner.zxing.ZXingScannerView

class SettingsActivity : AppCompatActivity(), ZXingScannerView.ResultHandler {

    private lateinit var languageGroup: RadioGroup
    private lateinit var masterButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var closeButton: Button
    private lateinit var statusTv: TextView
    private lateinit var qrImageView: ImageView
    private var scannerView: ZXingScannerView? = null
    private lateinit var rootLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleTv = TextView(this).apply {
            text = "Settings"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 48)
        }
        rootLayout.addView(titleTv)

        // Language Section
        val langLabel = TextView(this).apply {
            text = "Language / Sprache"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(langLabel)

        languageGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, 48)
        }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("language", "en")

        val enRadio = RadioButton(this).apply {
            text = "English"
            id = View.generateViewId()
            isChecked = currentLang == "en"
        }
        val gerRadio = RadioButton(this).apply {
            text = "Deutsch"
            id = View.generateViewId()
            isChecked = currentLang == "de"
        }

        languageGroup.addView(enRadio)
        languageGroup.addView(gerRadio)
        rootLayout.addView(languageGroup)

        languageGroup.setOnCheckedChangeListener { group, checkedId ->
            val lang = if (checkedId == enRadio.id) "en" else "de"
            prefs.edit().putString("language", lang).apply()
            Toast.makeText(this, "Language set to $lang", Toast.LENGTH_SHORT).show()
        }

        // Sync Section
        val btLabel = TextView(this).apply {
            text = "HTTP Sync"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(btLabel)

        statusTv = TextView(this).apply {
            text = "Status: ${HttpSyncService.connectionStatus}"
            textSize = 16f
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(statusTv)

        masterButton = Button(this).apply {
            text = "Start as Master (Show QR)"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            setOnClickListener { startMaster() }
        }
        rootLayout.addView(masterButton)

        disconnectButton = Button(this).apply {
            text = "Disconnect Sync"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            setOnClickListener {
                HttpSyncService.stopAll()
                qrImageView.visibility = View.GONE
                stopScanner()
                Toast.makeText(this@SettingsActivity, "Sync Disconnected", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(disconnectButton)

        qrImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(500, 500).apply {
                topMargin = 32
                bottomMargin = 32
            }
            visibility = View.GONE
        }
        rootLayout.addView(qrImageView)

        // Spacer to push Close button to bottom
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f)
        }
        rootLayout.addView(spacer)

        closeButton = Button(this).apply {
            text = "Close Settings"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { finish() }
        }
        rootLayout.addView(closeButton)

        setContentView(rootLayout)
        checkPermissions()

        HttpSyncService.onStatusChanged = { status, message ->
            runOnUiThread {
                statusTv.text = if (message != null) "Status: $status ($message)" else "Status: $status"
                if (status == HttpSyncService.Status.CONNECTED) {
                    qrImageView.visibility = View.GONE
                    stopScanner()
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.CAMERA)
        
        val needed = permissions.filter { 
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
        }
    }

    private fun startMaster() {
        val ip = HttpSyncService.getLocalIpAddress(this)
        if (ip == null) {
            Toast.makeText(this, "Connect to WiFi first", Toast.LENGTH_SHORT).show()
            return
        }
        
        HttpSyncService.startAsMaster()
        val bitmap = generateQRCode("pmtu_connect$ip")
        if (bitmap != null) {
            qrImageView.setImageBitmap(bitmap)
            qrImageView.visibility = View.VISIBLE
        }
        Toast.makeText(this, "Master mode started. Scan the QR code with the other device.", Toast.LENGTH_LONG).show()
    }

    private fun stopScanner() {
        scannerView?.stopCamera()
        if (scannerView != null) {
            rootLayout.removeView(scannerView)
            scannerView = null
        }
    }

    override fun handleResult(rawResult: com.google.zxing.Result?) {
        // Scanner is no longer used in SettingsActivity for connection
        stopScanner()
    }

    private fun generateQRCode(text: String): Bitmap? {
        val width = 500
        val height = 500
        val matrix: BitMatrix = try {
            MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
        } catch (e: Exception) {
            return null
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    override fun onPause() {
        super.onPause()
        scannerView?.stopCamera()
    }
}
