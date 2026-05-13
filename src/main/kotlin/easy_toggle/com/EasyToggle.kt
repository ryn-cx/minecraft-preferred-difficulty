package easy_toggle.com

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.text.Text
import net.minecraft.world.Difficulty
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object EasyToggle : ModInitializer {
    private val logger = LoggerFactory.getLogger("easy-toggle")

    private val storeFile: Path =
        FabricLoader.getInstance().configDir.resolve("easy-toggle-players.txt")

    private val easyPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override fun onInitialize() {
        loadStore()

        ServerPlayConnectionEvents.JOIN.register { _, _, server ->
            updateDifficulty(server)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            // The player is still in playerList when DISCONNECT fires — exclude them.
            updateDifficulty(server, excludeUuid = handler.player.uuid)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("easydifficulty")
                    .then(CommandManager.literal("join").executes { ctx ->
                        val player = ctx.source.playerOrThrow
                        if (easyPlayers.add(player.uuid)) {
                            saveStore()
                            updateDifficulty(ctx.source.server)
                            ctx.source.sendFeedback(
                                { Text.literal("You joined the easy-difficulty group. Difficulty will be EASY while you're online.") },
                                false,
                            )
                        } else {
                            ctx.source.sendFeedback(
                                { Text.literal("You're already in the easy-difficulty group.") },
                                false,
                            )
                        }
                        1
                    })
                    .then(CommandManager.literal("leave").executes { ctx ->
                        val player = ctx.source.playerOrThrow
                        if (easyPlayers.remove(player.uuid)) {
                            saveStore()
                            updateDifficulty(ctx.source.server)
                            ctx.source.sendFeedback(
                                { Text.literal("You left the easy-difficulty group.") },
                                false,
                            )
                        } else {
                            ctx.source.sendFeedback(
                                { Text.literal("You weren't in the easy-difficulty group.") },
                                false,
                            )
                        }
                        1
                    })
                    .then(CommandManager.literal("list").executes { ctx ->
                        val server = ctx.source.server
                        val names = easyPlayers.mapNotNull { uuid ->
                            server.userCache?.getByUuid(uuid)?.orElse(null)?.name
                                ?: server.playerManager.getPlayer(uuid)?.gameProfile?.name
                        }
                        val text = if (names.isEmpty()) {
                            "No players are in the easy-difficulty group."
                        } else {
                            "Easy-difficulty group (${names.size}): ${names.joinToString(", ")}"
                        }
                        ctx.source.sendFeedback({ Text.literal(text) }, false)
                        1
                    })
            )
        }
    }

    private fun updateDifficulty(server: MinecraftServer, excludeUuid: UUID? = null) {
        val anyEasyOnline = server.playerManager.playerList.any { player ->
            player.uuid != excludeUuid && easyPlayers.contains(player.uuid)
        }
        val target = if (anyEasyOnline) Difficulty.EASY else Difficulty.NORMAL
        if (server.saveProperties.difficulty != target) {
            logger.info("Setting difficulty to $target (easy players online: $anyEasyOnline)")
            server.setDifficulty(target, true)
        }
    }

    private fun loadStore() {
        if (!Files.exists(storeFile)) return
        Files.readAllLines(storeFile).forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            try {
                easyPlayers.add(UUID.fromString(trimmed))
            } catch (e: IllegalArgumentException) {
                logger.warn("Ignoring invalid UUID in store: $trimmed")
            }
        }
        logger.info("Loaded ${easyPlayers.size} easy-difficulty player(s)")
    }

    private fun saveStore() {
        Files.createDirectories(storeFile.parent)
        Files.write(storeFile, easyPlayers.map { it.toString() })
    }
}
