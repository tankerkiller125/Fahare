# Fahare

Fahare (short for **Fa**st **Ha**rdcore **Re**set) is a Minecraft: Java Edition multiplayer server mod that
automatically resets your hardcore world when all online players die. It is currently available for Paper 1.19.3+.

## Configuration

| Setting              | Default | Description                                                                                                                                                                                             |
|----------------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `backup`             | `true`  | Whether to save and backup worlds before resetting.<br>When disabled, worlds will be permanently deleted upon reset (much faster).                                                                      |
| `auto-reset`         | `true`  | Whether resets should happen automatically upon the death of all players.<br>When disabled, you may instead run `/fahare reset` to manually reset the world.                                            |
| `any-death`          | `false` | Whether to trigger a reset upon *any* death.<br>When disabled, automatic resets will trigger only when all players are dead.<br>This setting is ignored if `auto-reset` is disabled.                    |
| `spectate-when-dead` | `true`  | This setting mimics the default MC hardcore mode after the player has lived all of the available lives. If this is set to false, the player will be set to survival after every death.                  |
| `ban-time`           | `0`     | If set to an actual value, the player will be banned temporarily after death for this many seconds. If set to zero, the player is left alone to continue playing.                                       |
| `last-reset`         | `0`     | This value should in general be left alone. The plugin will set this value automatically upon ever server reset. It is a unix timestamp to keep reset players who haven't been on since the last reset. |


## Commands

| Command          | Permission      | Description                        |
|------------------|-----------------|------------------------------------|
| `/fahare reset`  | `fahare.reset`  | Manually reset the world.          |
| `/fahare reload` | `fahare.reload` | Reloads the config and data files. |
