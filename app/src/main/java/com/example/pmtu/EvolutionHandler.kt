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
            if (source == "mega") {
                next.isBaseItemActivated = true
                next.originalFormId = old.originalFormId ?: old.id
            }
            if (source == "gmax") {
                next.isGigaDynaActivated = true
                next.originalFormId = old.originalFormId ?: old.id
            }
            val teamIdx = viewModel.currentTeamIndex.value
            viewModel.setOwnPokemon(next, teamIdx)
            if (teamIdx != null) {
                viewModel.teamPokemon.value[teamIdx] = next
                viewModel.saveTeamData()
            }
            viewModel.setUpdateUI()
        }
    }

    fun devolvePokemon(source: String = "lvl") {
        val old = viewModel.ownPokemon.value ?: return
        val baseId = old.originalFormId ?: pokedexRepository.getBaseForm(old.id) ?: return
        val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/$baseId.png"
        val artUrl = "https://www.serebii.net/pokemon/art/$baseId.png"

        pokedexRepository.findPokemonByNumber(baseId, spriteUrl, artUrl)?.let { next ->
            next.copyStateFrom(old)
            if (source == "mega") {
                next.isBaseItemActivated = false
            }
            if (source == "gmax") {
                next.isGigaDynaActivated = false
            }
            next.originalFormId = null
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
