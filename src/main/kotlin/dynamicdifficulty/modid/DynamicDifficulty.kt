package dynamicdifficulty.modid

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.world.Difficulty
import org.slf4j.LoggerFactory

object DynamicDifficulty : ModInitializer {
    private val logger = LoggerFactory.getLogger("dynamic-difficulty")

    private const val TARGET_PLAYER = "SeraphicSage"

    override fun onInitialize() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            if (handler.player.gameProfile.name.equals(TARGET_PLAYER, ignoreCase = true)) {
                logger.info("$TARGET_PLAYER joined — setting difficulty to EASY")
                server.setDifficulty(Difficulty.EASY, true)
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            if (handler.player.gameProfile.name.equals(TARGET_PLAYER, ignoreCase = true)) {
                logger.info("$TARGET_PLAYER left — setting difficulty to NORMAL")
                server.setDifficulty(Difficulty.NORMAL, true)
            }
        }
    }
}
