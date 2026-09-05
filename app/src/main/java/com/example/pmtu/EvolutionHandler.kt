package com.example.pmtu

class EvolutionHandler(
    private val viewModel: ResultViewModel,
    private val pokedexRepository: PokedexRepository
) {
    fun evolvePokemon(id: String, levelDiff: Int = 0, source: String = "lvl") {
        val old = viewModel.ownPokemon.value ?: return
        val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/$id.png"
        val artUrl = "https://www.serebii.net/pokemon/art/$id.png"
        
        pokedexRepository.findPokemonByNumber(id, spriteUrl, artUrl)?.let { next ->
            next.copyStateFrom(old)
            next.additionalLevel += levelDiff
            if (source == "mega") next.isBaseItemActivated = true
            if (source == "gmax") next.isGigaDynaActivated = true
            val teamIdx = viewModel.currentTeamIndex.value
            viewModel.setOwnPokemon(next, teamIdx)
            if (teamIdx != null) {
                viewModel.teamPokemon.value[teamIdx] = next
                viewModel.saveTeamData()
            }
            viewModel.setUpdateUI()
        }
    }
}
