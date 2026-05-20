package preferred_difficulty.com

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.permissions.Permissions
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
    private val minimumFile: Path =
        FabricLoader.getInstance().configDir.resolve("preferred-difficulty-minimum.txt")

    private val preferences: MutableMap<UUID, Difficulty> = ConcurrentHashMap()

    @Volatile
    private var minimumDifficulty: Difficulty = Difficulty.PEACEFUL

    override fun onInitialize() {
        loadStore()
        loadMinimum()

        ServerPlayConnectionEvents.JOIN.register { _, _, server ->
            updateDifficulty(server)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            // The player is still in the player list when DISCONNECT fires — exclude them.
            updateDifficulty(server, excludeUuid = handler.player.uuid)
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

            val minNode = Commands.literal("min")
                .requires { it.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) }
                .executes { ctx ->
                    ctx.source.sendSuccess(
                        { Component.literal("Global minimum difficulty: ${minimumDifficulty.name}.") },
                        false,
                    )
                    1
                }
            for (choice in Difficulty.values()) {
                minNode.then(
                    Commands.literal(choice.name.lowercase()).executes { ctx ->
                        val source = ctx.source
                        minimumDifficulty = choice
                        saveMinimum()
                        updateDifficulty(source.server)
                        val effective = source.server.worldData.difficulty
                        source.sendSuccess(
                            {
                                Component.literal(
                                    "Global minimum difficulty is now ${choice.name}. " +
                                        "Server difficulty: ${effective.name}."
                                )
                            },
                            true,
                        )
                        1
                    }
                )
            }
            root.then(minNode)

            dispatcher.register(root)
        }
    }

    private fun updateDifficulty(server: MinecraftServer, excludeUuid: UUID? = null) {
        val onlinePrefs = server.playerList.players
            .filter { it.uuid != excludeUuid }
            .mapNotNull { preferences[it.uuid] }
        // Difficulty enum is declared PEACEFUL, EASY, NORMAL, HARD — ordinal == easiness rank.
        val floor = minimumDifficulty
        val playerTarget = onlinePrefs.minByOrNull { it.ordinal }
        val target = when {
            playerTarget != null ->
                if (playerTarget.ordinal < floor.ordinal) floor else playerTarget
            // No online player preferences and no floor above PEACEFUL — leave the server alone.
            floor.ordinal > server.worldData.difficulty.ordinal -> floor
            else -> return
        }
        if (server.worldData.difficulty != target) {
            logger.info(
                "Setting difficulty to $target (online prefs: ${onlinePrefs.map { it.name }}, min: ${floor.name})"
            )
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

    private fun loadMinimum() {
        if (!Files.exists(minimumFile)) return
        val line = Files.readAllLines(minimumFile).firstOrNull()?.trim().orEmpty()
        if (line.isEmpty()) return
        minimumDifficulty = Difficulty.valueOf(line.uppercase())
        logger.info("Loaded global minimum difficulty: ${minimumDifficulty.name}")
    }

    private fun saveMinimum() {
        Files.createDirectories(minimumFile.parent)
        Files.write(minimumFile, listOf(minimumDifficulty.name))
    }
}
