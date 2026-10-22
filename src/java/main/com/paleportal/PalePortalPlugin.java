package com.paleportal;

import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Главный класс плагина PalePortal.
 *
 * Собирает все менеджеры воедино, создаёт (при первом запуске) мир
 * "pale_forest" и регистрирует слушатели/команды.
 */
public final class PalePortalPlugin extends JavaPlugin implements Listener {

    private ConfigManager configManager;
    private DataManager dataManager;
    private PortalManager portalManager;
    private NoteListener noteListener;
    private CreakingManager creakingManager;

    private BukkitTask worldRulesTask;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        dataManager = new DataManager(this);

        configManager.load();
        dataManager.load();

        portalManager = new PortalManager(this);
        noteListener = new NoteListener(this);
        creakingManager = new CreakingManager(this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(portalManager, this);
        getServer().getPluginManager().registerEvents(noteListener, this);
        getServer().getPluginManager().registerEvents(creakingManager, this);

        setupPaleForestWorld();

        portalManager.reloadGeometry();
        portalManager.startParticleTask();

        creakingManager.start();
        startWorldRulesTask();

        PalePortalCommand commandExecutor = new PalePortalCommand(this);
        var cmd = getCommand("paleportal");
        if (cmd != null) {
            cmd.setExecutor(commandExecutor);
            cmd.setTabCompleter(commandExecutor);
        }

        getLogger().info("PalePortal включен.");
    }

    @Override
    public void onDisable() {
        if (worldRulesTask != null) worldRulesTask.cancel();
        if (portalManager != null) portalManager.stopParticleTask();
        if (creakingManager != null) creakingManager.stop();
        if (dataManager != null) dataManager.save();
        getLogger().info("PalePortal выключен.");
    }

    /** Переподключает/создаёт мир pale_forest. Мир пересоздаётся только если его ещё нет на диске. */
    private void setupPaleForestWorld() {
        String name = configManager.getPaleForestWorldName();
        World existing = getServer().getWorld(name);
        if (existing != null) {
            applyWorldRules(existing);
            return;
        }

        WorldCreator creator = new WorldCreator(name);
        creator.generator(new PaleWorldGenerator(configManager.getTreeChance(), configManager.getPondChance()));
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.FLAT);

        World world = creator.createWorld();
        if (world == null) {
            getLogger().severe("Не удалось создать мир '" + name + "'!");
            return;
        }

        Location cfgEntry = configManager.getEntryLocation();
        Location entry = new Location(world, cfgEntry.getX(), cfgEntry.getY(), cfgEntry.getZ(),
                cfgEntry.getYaw(), cfgEntry.getPitch());
        world.setSpawnLocation(entry);

        applyWorldRules(world);
        dataManager.setWorldGenerated(true);
        getLogger().info("Мир '" + name + "' создан.");
    }

    private void applyWorldRules(World world) {
        world.setTime(6000);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setStorm(false);
        world.setThundering(false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
    }

    /** Раз в 5 секунд поддерживает время/погоду и обновляет эффекты игрокам в pale_forest. */
    private void startWorldRulesTask() {
        worldRulesTask = getServer().getScheduler().runTaskTimer(this, () -> {
            World world = getServer().getWorld(configManager.getPaleForestWorldName());
            if (world == null) return;

            if (world.getTime() != 6000) world.setTime(6000);
            if (world.isThundering()) world.setThundering(false);
            if (world.hasStorm()) world.setStorm(false);

            for (Player p : world.getPlayers()) {
                if (configManager.isNightVisionEnabled()) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 220, 0, true, false, false));
                }
                if (configManager.isDarknessEnabled()) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 220,
                            Math.max(0, configManager.getDarknessAmplifier()), true, false, false));
                }
            }
        }, 20L, 100L);
    }

    // ------------------------------------------------------------------
    //  Правила мира pale_forest: нельзя спать, не работают порталы в Нижний мир/Энд
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (isPaleForest(event.getBed().getWorld())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§7Здесь невозможно уснуть...");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        World from = event.getFrom().getWorld();
        if (isPaleForest(from)) {
            event.setCancelled(true);
        }
    }

    private boolean isPaleForest(World world) {
        return world != null && configManager != null
                && world.getName().equals(configManager.getPaleForestWorldName());
    }

    // ------------------------------------------------------------------
    //  Геттеры
    // ------------------------------------------------------------------

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public PortalManager getPortalManager() {
        return portalManager;
    }

    public NoteListener getNoteListener() {
        return noteListener;
    }

    public CreakingManager getCreakingManager() {
        return creakingManager;
    }
}
