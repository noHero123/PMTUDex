package com.example.pmtu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class TeamBrowserActivity : AppCompatActivity() {

    private lateinit var teamListContainer: LinearLayout
    private val SAVED_TEAMS_FILE = "saved_teams.json"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleTv = TextView(this).apply {
            text = "Saved Teams"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }
        rootLayout.addView(titleTv)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }
        teamListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(teamListContainer)
        rootLayout.addView(scroll)

        val closeButton = Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }
        rootLayout.addView(closeButton)

        setContentView(rootLayout)
        loadSavedTeams()
    }

    private fun loadSavedTeams() {
        val file = File(filesDir, SAVED_TEAMS_FILE)
        if (!file.exists()) {
            val emptyTv = TextView(this).apply {
                text = "No saved teams found."
                gravity = Gravity.CENTER
                setPadding(0, 64, 0, 0)
            }
            teamListContainer.addView(emptyTv)
            return
        }

        val json = file.readText()
        val type = object : TypeToken<List<SavedTeam>>() {}.type
        val savedTeams: List<SavedTeam> = Gson().fromJson(json, type)

        for (team in savedTeams) {
            val teamRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    loadTeam(team)
                }
            }

            val nameTv = TextView(this).apply {
                text = team.name
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            teamRow.addView(nameTv)

            val spritesLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            for (pokemon in team.pokemon) {
                val iv = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(100, 100)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                if (pokemon?.spriteBase64 != null) {
                    iv.setImageBitmap(base64ToBitmap(pokemon.spriteBase64!!))
                } else {
                    iv.setBackgroundColor(Color.LTGRAY)
                }
                spritesLayout.addView(iv)
            }
            teamRow.addView(spritesLayout)

            teamListContainer.addView(teamRow)
        }
    }

    private fun loadTeam(team: SavedTeam) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        // We can't easily access the ResultViewModel here, so we save to team_data.json and let ResultActivity reload it
        val teamJson = Gson().toJson(team.pokemon)
        File(filesDir, "team_data.json").writeText(teamJson)
        setResult(RESULT_OK)
        Toast.makeText(this, "Team '${team.name}' loaded", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun base64ToBitmap(base64: String): Bitmap {
        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    }
}
