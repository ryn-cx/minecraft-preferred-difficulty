package preferred_difficulty.com

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
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

object PreferredDifficulty : ModInitializer {
    private val logger = LoggerFactory.getLogger("preferred-difficulty")

    private val storeFile: Path =
        FabricLoader.getInstance().configDir.resolve("preferred-difficulty-preferences.txt")

    private val preferences: MutableMap<UUID, Difficulty> = ConcurrentHashMap()

    private const val AFK_THRESHOLD_MS = 5L * 60L * 1000L
    private const val CHECK_INTERVAL_TICKS = 20

    private data class Activity(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val lastActiveMs: Long,
    )

    private val playerActivity: MutableMap<UUID, Activity> = ConcurrentHashMap()
    private var tickCounter = 0

    override fun onInitialize() {
        loadStore()

        ServerPlayConnectionEvents.JOIN.register { _, _, server ->
            updateDifficulty(server)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            // The player is still in the player list when DISCONNECT fires — exclude them.
            playerActivity.remove(handler.player.uuid)
            updateDifficulty(server, excludeUuid = handler.player.uuid)
        }

        ServerTickEvents.END_SERVER_TICK.register { server ->
            tickCounter++
            if (tickCounter % CHECK_INTERVAL_TICKS != 0) return@register
            val now = System.currentTimeMillis()
            for (player in server.playerList.players) {
                val prev = playerActivity[player.uuid]
                val moved = prev != null && (
                    player.x != prev.x ||
                        player.y != prev.y ||
                        player.z != prev.z ||
                        player.yRot != prev.yaw ||
                        player.xRot != prev.pitch
                    )
                val newLastActive = if (prev == null || moved) now else prev.lastActiveMs
                playerActivity[player.uuid] = Activity(
                    player.x, player.y, player.z, player.yRot, player.xRot, newLastActive,
                )
            }
            updateDifficulty(server)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            val root = Commands.literal("preferreddifficulty")
                .executes { ctx ->
                    val source = ctx.source
                    val player = source.playerOrException
                    val pref = preferences[player.uuid]
                    val effective = source.server.worldData.difficulty
                    source.sendSuccess(
                        {
                            val prefText = if (pref != null) {
                                "Your preferred difficulty: ${pref.name}."
                            } else {
                                "You haven't set a preferred difficulty (you don't affect server difficulty)."
                            }
                            Component.literal("$prefText Server difficulty: ${effective.name}.")
                        },
                        false,
                    )
                    1
                }
            for (choice in Difficulty.values()) {
                root.then(
                    Commands.literal(choice.name.lowercase()).executes { ctx ->
                        val source = ctx.source
                        val player = source.playerOrException
                        preferences[player.uuid] = choice
                        saveStore()
                        updateDifficulty(source.server)
                        val effective = source.server.worldData.difficulty
                        source.sendSuccess(
                            {
                                Component.literal(
                                    "Your preferred difficulty is now ${choice.name}. " +
                                        "Server difficulty: ${effective.name}."
                                )
                            },
                            false,
                        )
                        1
                    }
                )
            }
            root.then(
                Commands.literal("none").executes { ctx ->
                    val source = ctx.source
                    val player = source.playerOrException
                    val hadPreference = preferences.remove(player.uuid) != null
                    if (hadPreference) {
                        saveStore()
                        updateDifficulty(source.server)
                    }
                    val effective = source.server.worldData.difficulty
                    source.sendSuccess(
                        {
                            val msg = if (hadPreference) {
                                "Your difficulty preference has been cleared. You no longer affect server difficulty."
                            } else {
                                "You didn't have a difficulty preference set."
                            }
                            Component.literal("$msg Server difficulty: ${effective.name}.")
                        },
                        false,
                    )
                    1
                }
            )
            dispatcher.register(root)
        }
    }

    private fun updateDifficulty(server: MinecraftServer, excludeUuid: UUID? = null) {
        val now = System.currentTimeMillis()
        val onlinePrefs = server.playerList.players
            .filter { it.uuid != excludeUuid }
            .filter { !isAfk(it.uuid, now) }
            .mapNotNull { preferences[it.uuid] }
        // No online player has expressed a preference — leave the server alone.
        // Difficulty enum is declared PEACEFUL, EASY, NORMAL, HARD — ordinal == easiness rank.
        val target = onlinePrefs.minByOrNull { it.ordinal } ?: return
        if (server.worldData.difficulty != target) {
            logger.info("Setting difficulty to $target (online prefs: ${onlinePrefs.map { it.name }})")
            server.setDifficulty(target, true)
        }
    }

    private fun loadStore() {
        if (!Files.exists(storeFile)) return
        Files.readAllLines(storeFile).forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val parts = trimmed.split(" ", limit = 2)
            val uuid = UUID.fromString(parts[0])
            val diff = Difficulty.valueOf(parts[1].uppercase())
            preferences[uuid] = diff
        }
        logger.info("Loaded ${preferences.size} difficulty preference(s)")
    }

    private fun saveStore() {
        Files.createDirectories(storeFile.parent)
        Files.write(storeFile, preferences.map { (uuid, diff) -> "$uuid ${diff.name}" })
    }

    private fun isAfk(uuid: UUID, now: Long): Boolean {
        val activity = playerActivity[uuid] ?: return false
        return (now - activity.lastActiveMs) >= AFK_THRESHOLD_MS
    }
}
