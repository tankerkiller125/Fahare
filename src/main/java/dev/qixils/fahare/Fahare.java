package dev.qixils.fahare;

import cloud.commandframework.Command;
import cloud.commandframework.bukkit.CloudBukkitCapabilities;
import cloud.commandframework.execution.CommandExecutionCoordinator;
import cloud.commandframework.minecraft.extras.MinecraftExceptionHandler;
import cloud.commandframework.paper.PaperCommandManager;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationRegistry;
import net.kyori.adventure.util.UTF8ResourceBundleControl;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;

public final class Fahare extends JavaPlugin implements Listener {

    private static final boolean isFolia = Bukkit.getVersion().contains("Folia");
    private static final NamespacedKey REAL_OVERWORLD_KEY = NamespacedKey.minecraft("overworld");
    private static final Random RANDOM = new Random();
    private final NamespacedKey fakeOverworldKey = new NamespacedKey(this, "overworld");
    private final NamespacedKey limboWorldKey = new NamespacedKey(this, "limbo");
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private World limboWorld;
    private Path worldContainer;
    private @Nullable Path backupContainer;
    private boolean resetting = false;
    // config
    private boolean backup = true;
    private boolean autoReset = true;
    private boolean anyDeath = false;
    private boolean spectateWhenDead = false;
    private int lives = 1;
    private Duration banTime = Duration.ZERO;
    private long lastReset = 0;
    
    private final File dataFile = new File(getDataFolder(), "data.yml");
    FileConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    private final Map<String, Integer> playerDeaths = new HashMap<>();
    private final Map<String, Long> playerLastLogin = new HashMap<>();
    

    private static @NotNull World overworld() {
        return Objects.requireNonNull(Bukkit.getWorld(REAL_OVERWORLD_KEY), "Overworld not found");
    }

    private @NotNull World fakeOverworld() {
        return Objects.requireNonNullElseGet(Bukkit.getWorld(fakeOverworldKey), this::createFakeOverworld);
    }

    private @NotNull World createFakeOverworld() {
        // Create fake overworld
        WorldCreator creator = new WorldCreator(fakeOverworldKey).copy(overworld()).seed(RANDOM.nextLong());
        World world = Objects.requireNonNull(creator.createWorld(), "Could not load fake overworld");
        world.setDifficulty(overworld().getDifficulty());
        return world;
    }

    @Override
    public void onEnable() {
        // Load config
        loadConfig();

        // Create backup folder
        worldContainer = Bukkit.getWorldContainer().toPath();
        backupContainer = worldContainer.resolve("fahare-backups");

        if (!Files.exists(backupContainer)) {
            try {
                Files.createDirectory(backupContainer);
            } catch (Exception e) {
                getComponentLogger().error(translatable("fhr.log.error.backup-folder"), e);
                backupContainer = null;
            }
        }

        // Register i18n
        TranslationRegistry registry = TranslationRegistry.create(new NamespacedKey(this, "translations"));
        registry.defaultLocale(Locale.US);
        for (Locale locale : List.of(Locale.US)) { // TODO: reflection
            ResourceBundle bundle = ResourceBundle.getBundle("Fahare", locale, UTF8ResourceBundleControl.get());
            registry.registerAll(locale, bundle, false);
        }
        GlobalTranslator.translator().addSource(registry);

        // Create limbo world
        WorldCreator creator = new WorldCreator(limboWorldKey)
                .type(WorldType.FLAT)
                .generateStructures(false)
                .hardcore(true)
                .generatorSettings("{\"biome\":\"minecraft:the_end\",\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}]}");
        limboWorld = creator.createWorld();

        // Create fake overworld
        World fakeOverworld = createFakeOverworld();

        // Register commands
        try {
            final PaperCommandManager<CommandSender> commandManager = PaperCommandManager.createNative(this, CommandExecutionCoordinator.simpleCoordinator());
            if (commandManager.hasCapability(CloudBukkitCapabilities.BRIGADIER)) {
                try {
                    commandManager.registerBrigadier();
                } catch (Exception ignored) {
                }
            }

            // Commands
            // TODO: help command
            // TODO: i18n descriptions
            Command.Builder<CommandSender> cmd = commandManager.commandBuilder("fahare");
            commandManager.command(cmd
                    .literal("reset")
                    .permission("fahare.reset")
                    .handler(c -> {
                        c.getSender().sendMessage(translatable("fhr.chat.resetting"));
                        reset();
                    }));
            
            commandManager.command(cmd
                    .literal("reload")
                    .permission("fahare.reload")
                    .handler(c -> {
                        loadConfig();
                        c.getSender().sendMessage(translatable("fhr.chat.reloaded"));
                    }));
            
            commandManager.command(cmd.literal("lives")
                    .permission("fahare.lives")
                    .handler(c -> {
                        var lives = playerDeaths.get(getServer().getPlayerUniqueId(c.getSender().getName()));
                        c.getSender().sendMessage(translatable("fhr.chat.lives", lives.toString()));
                    }));

            // Exception handler
            new MinecraftExceptionHandler<CommandSender>()
                    .withDefaultHandlers()
                    .withDecorator(component -> component.colorIfAbsent(NamedTextColor.RED))
                    .apply(commandManager, sender -> sender);
        } catch (Exception e) {
            getComponentLogger().error(translatable("fhr.log.error.commands"), e);
        }

        // Register events and tasks
        Bukkit.getPluginManager().registerEvents(this, this);
        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, (test) -> {
                // Teleport players from fake overworld
                Location destination = overworld().getSpawnLocation();
                for (Player player : fakeOverworld.getPlayers()) {
                    player.teleport(destination);
                }
            }, 1, 1);
        } else {
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                // Teleport players from real overworld
                Location destination = fakeOverworld.getSpawnLocation();
                for (Player player : overworld().getPlayers()) {
                    player.teleport(destination);
                }
            }, 1, 1);
        }
    }

    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        try {
            if (!dataFile.exists()) {
                dataFile.createNewFile();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var config = getConfig();
        backup = config.getBoolean("backup", backup);
        autoReset = config.getBoolean("auto-reset", autoReset);
        anyDeath = config.getBoolean("any-death", anyDeath);
        lives = Math.max(1, config.getInt("lives", lives));
        banTime = Duration.ofSeconds(config.getInt("ban-time", 0));
        lastReset = config.getLong("last-reset", lastReset);
        spectateWhenDead = config.getBoolean("spectate-when-dead", true);
    }

    public int getDeathsFor(UUID player) {
        if (dataConfig.isConfigurationSection("deaths")) {
            ConfigurationSection deaths = dataConfig.getConfigurationSection("deaths");
            if (deaths != null) {
                for (String key : deaths.getKeys(false)) {
                    playerDeaths.put(key, deaths.getInt(key));
                }
            }
        }
        return playerDeaths.get(player.toString()) == null ? 0 : playerDeaths.get(player.toString());
    }

    public void addDeathTo(UUID player) {
        playerDeaths.put(player.toString(), getDeathsFor(player) + 1);
        dataConfig.createSection("deaths", playerDeaths);
        try {
            dataConfig.save(dataFile);
        } catch (Exception ignored) {
        }
    }

    public boolean isDead(UUID player) {
        return getDeathsFor(player) >= lives;
    }

    public boolean isAlive(UUID player) {
        return !isDead(player);
    }

    private void deleteNextWorld(List<World> worlds, @Nullable Path backupDestination) {
        // check if all worlds are deleted
        if (worlds.isEmpty()) {
            World overworld = fakeOverworld();
            Location spawn = overworld.getSpawnLocation();
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.setGameMode(GameMode.SURVIVAL);
                player.teleport(spawn);
            }
            resetting = false;
            return;
        }

        // check if worlds are ticking
        if (Bukkit.isTickingWorlds()) {
            if (isFolia) {
                Bukkit.getGlobalRegionScheduler().runDelayed(this, test -> {deleteNextWorld(worlds, backupDestination);}, 1);
            } else {
                Bukkit.getScheduler().runTaskLater(this, () -> deleteNextWorld(worlds, backupDestination), 1);
            }
            return;
        }

        // get world data
        World world = worlds.removeFirst();
        String worldName = world.getName();
        Component worldKey = text(worldName);
        WorldCreator creator = new WorldCreator(worldName, world.getKey());
        creator.copy(world).seed(RANDOM.nextLong());

        // unload world
        if (Bukkit.unloadWorld(world, backup)) {
            try {
                Path worldFolder = worldContainer.resolve(worldName);
                Component arg = text(worldFolder.toString());
                if (backupDestination != null) {
                    // Backup world
                    getComponentLogger().info(translatable("fhr.log.info.backup", arg));
                    Files.move(worldFolder, backupDestination.resolve(worldName));
                } else {
                    // Delete world
                    getComponentLogger().info(translatable("fhr.log.info.delete", arg));
                    IOUtils.deleteDirectory(worldFolder);
                }

                // create new world
                creator.hardcore(true);
                creator.createWorld();
                Bukkit.getServer().sendMessage(translatable("fhr.chat.success", worldKey));
            } catch (Exception e) {
                Component error = translatable("fhr.chat.error", NamedTextColor.RED, worldKey);
                Audience.audience(Bukkit.getOnlinePlayers()).sendMessage(error);
                getComponentLogger().warn(error, e);
            }
        } else {
            Bukkit.getServer().sendMessage(translatable("fhr.chat.error", NamedTextColor.RED, worldKey));
        }

        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(this, test -> {deleteNextWorld(worlds, backupDestination);}, 1);
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> deleteNextWorld(worlds, backupDestination), 1);
        }
    }

    public synchronized void reset() {
        if (resetting)
            return;
        if (limboWorld == null)
            return;
        deaths.clear();
        dataConfig.set("deaths", null);
        playerDeaths.clear();
        playerLastLogin.clear();
        try {
            dataConfig.save(dataFile);
        } catch (Exception ignored) {
        }
        // teleport all players to limbo
        Location destination = new Location(limboWorld, 0, 100, 0);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SPECTATOR);
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.setLevel(0);
            player.setExp(0);
            player.teleport(destination);
            player.setHealth(20);
            player.setFoodLevel(20);
            player.setSaturation(5);
        }
        var config = getConfig();
        config.set("last-reset", System.currentTimeMillis());
        saveConfig();
        // check if worlds are ticking
        if (Bukkit.isTickingWorlds()) {
            if (isFolia) {
                Bukkit.getGlobalRegionScheduler().runDelayed(this, test -> {reset();}, 1);
            } else {
                Bukkit.getScheduler().runTaskLater(this, this::reset, 1);
            }
            return;
        }
        resetting = true;
        // calculate backup folder
        Path backupDestination = null;
        if (backup && backupContainer != null) {
            String baseName = ISO_LOCAL_DATE.format(LocalDate.now());
            int attempt = 1;
            do {
                String name = baseName + '-' + attempt++;
                backupDestination = backupContainer.resolve(name);
            } while (Files.exists(backupDestination));
            try {
                Files.createDirectory(backupDestination);
            } catch (Exception e) {
                getComponentLogger().error(translatable("fhr.log.error.backup-subfolder", text(backupDestination.toString())), e);
                backupDestination = null;
            }
        }
        // unload and delete worlds
        List<World> worlds = Bukkit.getWorlds().stream()
                .filter(w -> !w.getKey().equals(limboWorldKey) && !w.getKey().equals(REAL_OVERWORLD_KEY))
                .collect(Collectors.toList());
        deleteNextWorld(worlds, backupDestination);
    }

    public void resetCheck(boolean death) {
        if (!autoReset)
            return;
        if (anyDeath && death) {
            reset();
            return;
        }
        @NotNull OfflinePlayer[] offlinePlayers = Bukkit.getServer().getOfflinePlayers();
        for (@NotNull OfflinePlayer offlinePlayer : offlinePlayers) {
            if (isAlive(offlinePlayer.getUniqueId()) && offlinePlayer.getLastSeen() >= System.currentTimeMillis() - 1000 * 60 * 60 * 24 * 7)
                return;
        }
        reset();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResponse(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(this, test -> {
                if (isAlive(player.getUniqueId()) || !spectateWhenDead) {
                    player.setGameMode(GameMode.SURVIVAL);
                }
            }, 2);
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (isAlive(player.getUniqueId()) || !spectateWhenDead) {
                    player.setGameMode(GameMode.SURVIVAL);
                }
            }, 2);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        addDeathTo(player.getUniqueId());
        if (!banTime.isZero()) {
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.setLevel(0);
            player.setExp(0);
            player.setHealth(20);
            player.setFoodLevel(20);
            player.setSaturation(5);
        }
        if (isAlive(player.getUniqueId()))
            return;
        
        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(this, test -> {
                player.spigot().respawn();
                player.setGameMode(GameMode.SURVIVAL);
                resetCheck(true);
            }, 1);
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                player.spigot().respawn();
                player.setGameMode(GameMode.SURVIVAL);
                resetCheck(true);
            }, 1);
        }
        if (banTime.isPositive()) {
            player.ban("You have died. Come back later.", banTime, "Hardcore");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityPortal(EntityPortalEvent event) {
        Location to = event.getTo();
        if (to == null) return;
        World toWorld = to.getWorld();
        if (toWorld == null) return;
        if (!toWorld.getKey().equals(REAL_OVERWORLD_KEY)) return;

        // check if player is coming from the end, and if so just send them to spawn
        if (event.getPortalType() == PortalType.ENDER)
            event.setTo(fakeOverworld().getSpawnLocation());
            // else just update the world
        else
            to.setWorld(fakeOverworld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Location to = event.getTo();
        World toWorld = to.getWorld();
        if (toWorld == null) return;
        if (!toWorld.getKey().equals(REAL_OVERWORLD_KEY)) return;

        // check if player is coming from the end, and if so just send them to spawn
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL)
            event.setTo(fakeOverworld().getSpawnLocation());
            // else just update the world
        else
            to.setWorld(fakeOverworld());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (dataConfig.isConfigurationSection("logins")) {
            ConfigurationSection playerLogins = dataConfig.getConfigurationSection("logins");
            if (playerLogins != null) {
                for (String key : playerLogins.getKeys(false)) {
                    playerLastLogin.put(key, playerLogins.getLong(key));
                }
            }
        }
        
        
        if (!playerLastLogin.containsKey(player.getUniqueId().toString())) {
            playerLastLogin.put(player.getUniqueId().toString(), Bukkit.getOfflinePlayer(player.getUniqueId()).getLastLogin());
        }
        
        // If the player last logged in before the last reset, reset the player
        if(playerLastLogin.get(player.getUniqueId().toString()) < lastReset) {
            player.getInventory().clear();
            player.getEnderChest().clear();
            player.setLevel(0);
            player.setExp(0);
            player.setHealth(20);
            player.setFoodLevel(20);
            player.setSaturation(5);
            player.teleport(fakeOverworld().getSpawnLocation());
        }
        
        playerLastLogin.put(player.getUniqueId().toString(), Bukkit.getOfflinePlayer(player.getUniqueId()).getLastLogin());
        dataConfig.createSection("logins", playerLastLogin);
        try {
            dataConfig.save(dataFile);
        } catch (Exception ignored) {
        }
        
        if (player.getWorld().getKey().equals(REAL_OVERWORLD_KEY)) {
            player.teleport(fakeOverworld().getSpawnLocation());
        }
        
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Location destination = event.getRespawnLocation();
        if (destination.getWorld().getKey().equals(REAL_OVERWORLD_KEY))
            event.setRespawnLocation(fakeOverworld().getSpawnLocation());
    }
}
