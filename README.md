# Preferred Difficulty

A Fabric mod that lets each player set the difficulty they want to play at. The server's difficulty automatically tracks the easiest preference among online players, so anyone who picks `peaceful` will pull the whole server to `peaceful` while they're online. Players who haven't set a preference don't affect the server at all.

Operators can set a global minimum floor that player preferences cannot go below — useful for keeping the server above `peaceful` even when a player would otherwise drag it down.

## Commands

| Command | Who | What it does |
| --- | --- | --- |
| `/preferreddifficulty` | anyone | Shows your preference and the current server difficulty. |
| `/preferreddifficulty <peaceful\|easy\|normal\|hard>` | anyone | Sets your preference and recomputes server difficulty. |
| `/minimumpreferreddifficulty` | op (level 2+) | Shows the current global minimum. |
| `/minimumpreferreddifficulty <peaceful\|easy\|normal\|hard>` | op (level 2+) | Sets the global minimum. Server difficulty is clamped up to this floor even when no player has a preference. |

Setting the minimum to `peaceful` is equivalent to no floor.

## Behavior

- Player preferences are recomputed on every player join and disconnect.
- A player's preference only counts while they are online.
- Preferences are persisted to `config/preferred-difficulty-preferences.txt`; the global minimum is persisted to `config/preferred-difficulty-minimum.txt`.

## Building

Standard Fabric/Loom workflow:

```
./gradlew build
```

The built jar lands in `build/libs/`. Minecraft and Fabric versions are pinned in `gradle.properties`.

## License

CC0.
