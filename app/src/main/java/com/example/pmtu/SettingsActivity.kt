package com.example.pmtu

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
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

    private val SAVED_TEAMS_FILE = "saved_teams.json"
    private val TEAM_DATA_FILE = "team_data.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            isFillViewport = true
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(rootLayout)
        mainLayout.addView(scroll)

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

        // Gameplay Options
        val optionsLabel = TextView(this).apply {
            text = "Gameplay Options"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(optionsLabel)

        val immunityCheckbox = CheckBox(this).apply {
            text = "Disable Type Immunities (0x -> -2)"
            isChecked = prefs.getBoolean("disable_immunities", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("disable_immunities", isChecked).apply()
            }
        }
        rootLayout.addView(immunityCheckbox)

        val speakerCheckbox = CheckBox(this).apply {
            text = "Show Speaker Symbols at Attacks"
            isChecked = prefs.getBoolean("show_speakers", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("show_speakers", isChecked).apply()
            }
        }
        rootLayout.addView(speakerCheckbox)

        val space = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 48)
        }
        rootLayout.addView(space)

        // Team Management Section
        val teamLabel = TextView(this).apply {
            text = "Team Management"
            textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(teamLabel)

        val saveTeamButton = Button(this).apply {
            text = "Save Current Team"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            setOnClickListener {
                val intent = Intent(this@SettingsActivity, TeamBrowserActivity::class.java)
                intent.putExtra("extra_save_mode", true)
                startActivity(intent)
            }
        }
        rootLayout.addView(saveTeamButton)

        val browseTeamsButton = Button(this).apply {
            text = "Browse Saved Teams"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 48 }
            setOnClickListener {
                onOpenBrowserClicked()
                //startActivity(Intent(this@SettingsActivity, TeamBrowserActivity::class.java))
            }
        }
        rootLayout.addView(browseTeamsButton)

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
            text = "Start Server (scan QR code with other devices)"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            setOnClickListener { startServer() }
        }
        rootLayout.addView(masterButton)

        disconnectButton = Button(this).apply {
            text = "Disconnect Sync"
            visibility = if (HttpSyncService.connectionStatus == HttpSyncService.Status.DISCONNECTED) View.GONE else View.VISIBLE
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

        closeButton = Button(this).apply {
            text = "Close Settings"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(48, 16, 48, 48)
            }
            setOnClickListener { finish() }
        }
        mainLayout.addView(closeButton)

        setContentView(mainLayout)
        checkPermissions()

        HttpSyncService.onStatusChanged = { status, message ->
            runOnUiThread {
                statusTv.text = if (message != null) "Status: $status ($message)" else "Status: $status"
                disconnectButton.visibility = if (status == HttpSyncService.Status.DISCONNECTED) View.GONE else View.VISIBLE
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

    private fun startServer() {
        val ip = HttpSyncService.getLocalIpAddress(this)
        if (ip == null) {
            Toast.makeText(this, "Connect to WiFi first", Toast.LENGTH_SHORT).show()
            return
        }
        
        HttpSyncService.startServer()
        val bitmap = generateQRCode("pmtu_connect$ip")
        if (bitmap != null) {
            qrImageView.setImageBitmap(bitmap)
            qrImageView.visibility = View.VISIBLE
        }
        val toast = Toast.makeText(this, "Server started. Scan the QR code with the other device.", Toast.LENGTH_LONG)
        toast.setGravity(Gravity.TOP, 0, 0)
        toast.show()

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

    private val browserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val selectedTeam = result.data?.getParcelableExtra<SavedTeam>("SELECTED_TEAM")

            val returnIntent = Intent()
            returnIntent.putExtra("SELECTED_TEAM", selectedTeam)

            setResult(RESULT_OK, returnIntent)
            finish()
        }
    }

    fun onOpenBrowserClicked() {
        val intent = Intent(this, TeamBrowserActivity::class.java)
        browserLauncher.launch(intent)
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
