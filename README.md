# PMTUDex (Pokédex and Battle Assist for Pokemon Master Trainer Ultimate Edition)

PMTU is an Android application designed to help players manage and track Pokémon teams, view detailed Pokedex entries, and synchronize data with other Devices. The app features advanced state management for battle conditions (Tera, Dynamax, Status Effects, Items, Type Enhancers) and supports english and german.

## How To:
- print and cut the cards/token-backgounds in [QR_files](https://github.com/noHero123/PMTUDexCards)
- download the latest apk file in https://github.com/noHero123/PMTUDex/releases and install it ( [How to](https://www.lifewire.com/install-apk-on-android-4177185) )
- start the app
- profit

## How to use the App:
<img src="readme_pics/1.png" width="350"/><br>
1. Settings Menu: change language, manage teams, start server for connecting with other devices<br>
2. Your current team:<br>
  <img src="readme_pics/teamleiste.png" width="350"/><br>
    - current selected Pokémon is surrounded by a blue rectangle<br>
    - a green arrow on the left side of its sprite: it has an effective attack vs the enemy<br>
    - a red arrow on the left side of its sprite: it has only ineffective attacks vs the enemy<br>
    - a green arrow on the right side of its sprite: enemy has an effective attack vs this Pokémon<br>
    - a red arrow on the right side of its sprite: enemy has only ineffective attacks vs this Pokémon<br>
3. +/- button: add the current Pokémon to your team. After clicking the "+" button, select an place to add it. If the current Pokémon is part of the team, remove it with the "-" button.<br>
4. additional level dice: tap on it to select the current additional level of the current Pokémon<br>
5. Pokémon that envolves into your current Pokémon.<br>
6. the current selected/scanned Pokemon<br>
7. evolution(s) of your current Pokémon.<br>
8. Press on the Pokédex button to read out the Pokemon name and on of its Pokédex entries. (Because its cool to learn something about the Pokémon)<br>
9. Moves and attached Items of the current Pokémon:<br>
    <img src="readme_pics/moves.png" width="350"/><br>
    from left to right:<br>
     - Type of the Attack<br>
     - Name of the Attack<br>
     - Attack value of the Pokemon, includs: base level, additional level (given by the level dice above), effectiveness, attached items, status conditions, weather effects... A green/red number indicates, that the move is effective/ineffective against the selected enemy. (Tap moves with a * to show explanation) <br>
     - possible attack effects (tap to show an explanation of the effect)<br>
     - green/red arrow: additional indicator for type effectiveness (arrows for red/green weakness)<br>
     - trash sign allows you to remove Items, (z) Moves etc<br>
10. current enemy Pokémon (a press on the trash sign will remove the enemy Pokémon)<br>
11. Button to scan QR codes of the Cards (Pokemon, Items, ...) or the QR code to connect to other device. Items will automatically attacked to the current active Pokémon.<br>
12. Botton to switch the current Pokemon and the current Enemy<br>

## Settings:<br>
<img src="readme_pics/settings1.png" width="350"/><br>
After pressing the settings button, you are able to:<br>
- change the language<br>
- disable type immunities<br>
- add speaker symbols next to the moves, to read out the names of the moves. (My son is 5 and cant read ;) )<br>
- manage your team<br>
- start a Server: a QR code is displayed, that other devices with the app can scan. It will allow to synconize between your current end the others enemy and vice versa:<br>
  <img src="readme_pics/server_mode - Kopie.jpg" width="500"/><br>
  the Squirtle was only scanned with the upper device, the Chamander only scanned, with the lower device. but you can see the card that was not scanned as the enemy. (Note: I created Cards instead of Tokens, because i find them practical. But in the QR-files-folder you will find the backs of the token with QR codes)<br>

## Examples: 
Scanning a trainer card will show the Pokémon with its attacks.<br>
<img src="readme_pics/brock.png" height="350"/> &rarr; <img src="readme_pics/scan_brock.png" height="350"/><br><br>
Scanning a Dynaband will show the Gigamax (if the Pokémon can envolve into one) and/or a Dynamax ball. Pressing on the Gigamax symbol will automatically load the Gigamax version and (and copy stuff like additional levels, attached items, etc).<br>
<img src="readme_pics/Dynaband.png" height="350"/> &rarr; <img src="readme_pics/scan_dynaband.png" height="350"/> &rarr; <img src="readme_pics/giga_with_button.png" height="350"/><br><br>
Scanning status effects or weather conditions, will show the status + weather next to the "Pokédex" button. Both pictures are tapable to show its effect. <br>
<img src="readme_pics/para.png" height="350"/> + <img src="readme_pics/weather.png" height="350"/> &rarr; <img src="readme_pics/status_and_weather.png" height="350"/><br><br>
You can also tap on special moves or additional effects:<br>
<img src="readme_pics/move_clicked.png" height="350"/><img src="readme_pics/effect_clicked.png" height="350"/><br><br>
Scanning a TM will lead to an additional move:<br>
<img src="readme_pics/Tmcard.png" height="350"/> &rarr; <img src="readme_pics/scan_tm.png" height="350"/><br><br>
Items will also show on the slot. Sometimes they provide an permanent effect:<br>
<img src="readme_pics/Kingsrock.png" height="350"/> &rarr; <img src="readme_pics/scan_king.png" height="350"/><br><br>
Sometimes items needs to be activated after scanning:<br>
<img src="readme_pics/Megastone.png" height="350"/> &rarr; <img src="readme_pics/scan_mega.png" height="350"/><br><br>


 
 
 
 
