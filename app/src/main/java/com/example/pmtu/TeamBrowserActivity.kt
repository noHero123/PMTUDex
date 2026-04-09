package com.example.pmtu

import android.content.Intent
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
import androidx.appcompat.app.AlertDialog
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
    private val TEAM_DATA_FILE = "team_data.json"
    private var isSaveMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isSaveMode = intent.getBooleanExtra("extra_save_mode", false)

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
            text = if (isSaveMode) "Select Team to Overwrite" else "Saved Teams"
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

        if (isSaveMode) {
            val createNewBtn = Button(this).apply {
                text = "Create New Team"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 16; bottomMargin = 16 }
                setOnClickListener { showCreateTeamDialog() }
            }
            rootLayout.addView(createNewBtn)
        }

        val closeButton = Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }
        rootLayout.addView(closeButton)

        setContentView(rootLayout)
        loadSavedTeams()
    }

    private fun loadSavedTeams() {
        teamListContainer.removeAllViews()
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
        val type = object : TypeToken<MutableList<SavedTeam>>() {}.type
        val savedTeams: MutableList<SavedTeam> = Gson().fromJson(json, type)

        for ((index, team) in savedTeams.withIndex()) {
            val outerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)
                setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 16) }
            }

            val teamInfoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (isSaveMode) {
                        confirmOverwrite(index, team.name)
                    } else {
                        loadTeam(team)
                    }
                }
            }

            val nameTv = TextView(this).apply {
                text = team.name
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            teamInfoLayout.addView(nameTv)

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
            teamInfoLayout.addView(spritesLayout)

            val deleteBtn = ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    confirmDelete(index, team.name)
                }
            }

            outerRow.addView(teamInfoLayout)
            outerRow.addView(deleteBtn)
            teamListContainer.addView(outerRow)
        }
    }

    private fun loadTeam(team: SavedTeam) {
        /*val teamJson = Gson().toJson(team.pokemon)
        File(filesDir, TEAM_DATA_FILE).writeText(teamJson)*/
        val resultIntent = Intent()
        resultIntent.putExtra("SELECTED_TEAM", team)
        setResult(RESULT_OK, resultIntent)
        Toast.makeText(this, "Team '${team.name}' loaded", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmOverwrite(index: Int, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Overwrite Team")
            .setMessage("Do you want to overwrite '$name' with your current team?")
            .setPositiveButton("Overwrite") { _, _ ->
                saveCurrentTeam(name, index)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(index: Int, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Team")
            .setMessage("Are you sure you want to delete '$name'?")
            .setPositiveButton("Delete") { _, _ ->
                deleteTeam(index)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteTeam(index: Int) {
        try {
            val file = File(filesDir, SAVED_TEAMS_FILE)
            if (!file.exists()) return

            val json = file.readText()
            val type = object : TypeToken<MutableList<SavedTeam>>() {}.type
            val savedTeams: MutableList<SavedTeam> = Gson().fromJson(json, type)

            if (index in savedTeams.indices) {
                val removed = savedTeams.removeAt(index)
                file.writeText(Gson().toJson(savedTeams))
                Toast.makeText(this, "Team '${removed.name}' deleted", Toast.LENGTH_SHORT).show()
                loadSavedTeams() // Refresh the list
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error deleting team: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCreateTeamDialog() {
        val input = EditText(this)
        input.hint = "Team Name"
        
        AlertDialog.Builder(this)
            .setTitle("Create New Team")
            .setMessage("Enter a name for your current team:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    saveCurrentTeam(name, null)
                } else {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveCurrentTeam(name: String, overwriteIndex: Int?) {
        try {
            val teamFile = File(filesDir, TEAM_DATA_FILE)
            if (!teamFile.exists()) {
                Toast.makeText(this, "No team data to save", Toast.LENGTH_SHORT).show()
                return
            }

            val teamJson = teamFile.readText()
            val typeTeam = object : TypeToken<Array<PokemonInfo?>>() {}.type
            val currentTeam: Array<PokemonInfo?> = Gson().fromJson(teamJson, typeTeam)

            val savedTeamsFile = File(filesDir, SAVED_TEAMS_FILE)
            val savedTeams: MutableList<SavedTeam> = if (savedTeamsFile.exists()) {
                val json = savedTeamsFile.readText()
                val typeList = object : TypeToken<MutableList<SavedTeam>>() {}.type
                Gson().fromJson(json, typeList)
            } else {
                mutableListOf()
            }

            val newSavedTeam = SavedTeam(name = name, pokemon = currentTeam)
            if (overwriteIndex != null && overwriteIndex in savedTeams.indices) {
                savedTeams[overwriteIndex] = newSavedTeam
                Toast.makeText(this, "Team '$name' overwritten!", Toast.LENGTH_SHORT).show()
            } else {
                savedTeams.add(newSavedTeam)
                Toast.makeText(this, "Team '$name' saved!", Toast.LENGTH_SHORT).show()
            }

            savedTeamsFile.writeText(Gson().toJson(savedTeams))
            loadSavedTeams() // Refresh the list
            if (overwriteIndex != null || !isSaveMode) {
                // If we overwritten or were not in create mode, maybe we want to close?
                // User didn't specify, but usually saving finishes the action.
                // Let's stay for now to see the updated list, or maybe close if overwrite.
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving team: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun base64ToBitmap(base64: String): Bitmap {
        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    }
}
