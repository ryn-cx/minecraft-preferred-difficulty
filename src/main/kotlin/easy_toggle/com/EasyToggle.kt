package easy_toggle.com

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
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
            // The player is still in the player list when DISCONNECT fires — exclude them.
            updateDifficulty(server, excludeUuid = handler.player.uuid)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("easydifficulty")
                    .then(Commands.literal("join").executes { ctx ->
                        val player = ctx.source.playerOrException
                        if (easyPlayers.add(player.uuid)) {
                            saveStore()
                            updateDifficulty(ctx.source.server)
                            ctx.source.sendSuccess(
                                { Component.literal("You joined the easy-difficulty group. Difficulty will be EASY while you're online.") },
                                false,
                            )
                        } else {
                            ctx.source.sendSuccess(
                                { Component.literal("You're already in the easy-difficulty group.") },
                                false,
                            )
                        }
                        1
                    })
                    .then(Commands.literal("leave").executes { ctx ->
                        val player = ctx.source.playerOrException
                        if (easyPlayers.remove(player.uuid)) {
                            saveStore()
                            updateDifficulty(ctx.source.server)
                            ctx.source.sendSuccess(
                                { Component.literal("You left the easy-difficulty group.") },
                                false,
                            )
                        } else {
                            ctx.source.sendSuccess(
                                { Component.literal("You weren't in the easy-difficulty group.") },
                                false,
                            )
                        }
                        1
                    })
                    .then(Commands.literal("list").executes { ctx ->
                        val server = ctx.source.server
                        val onlineNames = easyPlayers.mapNotNull { uuid ->
                            server.playerList.getPlayer(uuid)?.gameProfile?.name
                        }
                        val offlineCount = easyPlayers.size - onlineNames.size
                        val text = when {
                            easyPlayers.isEmpty() -> "No players are in the easy-difficulty group."
                            onlineNames.isEmpty() -> "Easy-difficulty group: ${easyPlayers.size} member(s), none online."
                            offlineCount == 0 -> "Easy-difficulty group (${onlineNames.size}): ${onlineNames.joinToString(", ")}"
                            else -> "Easy-difficulty group (${easyPlayers.size}): ${onlineNames.joinToString(", ")} + $offlineCount offline"
                        }
                        ctx.source.sendSuccess({ Component.literal(text) }, false)
                        1
                    })
            )
        }
    }

    private fun updateDifficulty(server: MinecraftServer, excludeUuid: UUID? = null) {
        val anyEasyOnline = server.playerList.players.any { player ->
            player.uuid != excludeUuid && easyPlayers.contains(player.uuid)
        }
        val target = if (anyEasyOnline) Difficulty.EASY else Difficulty.NORMAL
        if (server.worldData.difficulty != target) {
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
