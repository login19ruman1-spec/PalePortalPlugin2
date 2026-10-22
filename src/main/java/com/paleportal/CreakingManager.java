package com.paleportal;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Управляет 4 видами скрипунов (Creaking): обычный, умный, добрый, древний.
 * Все виды - это EntityType.CREAKING с разными характеристиками и поведением,
 * которые хранятся в PersistentDataContainer самой сущности, чтобы переживать
 * чанк-анлоад/релоад.
 *
 * Полная переработка ванильного ИИ "замирает под взглядом игрока" потребовала бы
 * доступа к Brain/Goal-селектору моба через NMS. Чтобы остаться строго в рамках
 * Paper API, поведение видов "умный"/"добрый"/"древний" реализовано через лёгкий
 * повторяющийся таск, который напрямую управляет целью атаки (Mob#setTarget).
 */
public class CreakingManager implements Listener {

    public enum CreakingVariant {
        NORMAL("обычный"),
        SMART("умный"),
        KIND("добрый"),
        ANCIENT("древний");

        public final String ruName;

        CreakingVariant(String ruName) {
            this.ruName = ruName;
        }

        public static CreakingVariant parse(String s) {
            if (s == null) return null;
            String n = s.trim().toLowerCase();
            return switch (n) {
                case "normal", "обычный" -> NORMAL;
                case "smart", "умный" -> SMART;
                case "kind", "добрый" -> KIND;
                case "ancient", "древний" -> ANCIENT;
                default -> null;
            };
        }
    }

    private final PalePortalPlugin plugin;
    private final ConfigManager config;
    private final NamespacedKey variantKey;

    private BukkitTask behaviorTask;
    private BukkitTask particleTask;
    private BukkitTask autoSpawnTask;

    /** UUID доброго скрипуна -> время (мс), до которого ему разрешено атаковать обидчика. */
    private final Map<UUID, Long> kindAggroUntil = new HashMap<>();
    private final Map<UUID, UUID> kindAggroTarget = new HashMap<>();

    public CreakingManager(PalePortalPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.variantKey = new NamespacedKey(plugin, "creaking_variant");
    }

    public void start() {
        stop();
        behaviorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickBehavior, 20L, 10L);
        particleTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickParticles, 20L, 10L);
        if (config.isCreakingAutoSpawn()) {
            long period = Math.max(1, config.getCreakingSpawnPeriodMinutes()) * 60L * 20L;
            autoSpawnTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tryAutoSpawn, period, period);
        }
    }

    public void stop() {
        if (behaviorTask != null) { behaviorTask.cancel(); behaviorTask = null; }
        if (particleTask != null) { particleTask.cancel(); particleTask = null; }
        if (autoSpawnTask != null) { autoSpawnTask.cancel(); autoSpawnTask = null; }
    }

    // ------------------------------------------------------------------
    //  Спавн
    // ------------------------------------------------------------------

    public Creaking spawn(CreakingVariant variant, Location location) {
        World world = location.getWorld();
        if (world == null) return null;

        Entity entity = world.spawnEntity(location, EntityType.CREAKING);
        if (!(entity instanceof Creaking creaking)) {
            entity.remove();
            return null;
        }

        creaking.getPersistentDataContainer().set(variantKey, PersistentDataType.STRING, variant.name());
        applyStats(creaking, variant);
        creaking.setCustomNameVisible(false);
        return creaking;
    }

    private void applyStats(Creaking creaking, CreakingVariant variant) {
        double health;
        double speed = 0.25; // ванильная базовая скорость скрипуна

        switch (variant) {
            case NORMAL -> health = config.getNormalHealth();
            case SMART -> health = config.getSmartHealth();
            case KIND -> health = config.getKindHealth();
            case ANCIENT -> {
                health = config.getAncientHealth();
                speed = config.getAncientSpeed();
            }
            default -> health = 20.0;
        }

        var maxHealthAttr = creaking.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            maxHealthAttr.setBaseValue(health);
        }
        creaking.setHealth(health);

        var speedAttr = creaking.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speedAttr != null) {
            speedAttr.setBaseValue(speed);
        }
    }

    public CreakingVariant getVariant(Entity entity) {
        if (!(entity instanceof Creaking)) return null;
        String raw = entity.getPersistentDataContainer().get(variantKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return CreakingVariant.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  Урон
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        // Скрипун бьёт кого-то - применяем настроенный урон для его вида.
        CreakingVariant attackerVariant = getVariant(event.getDamager());
        if (attackerVariant != null) {
            double damage = switch (attackerVariant) {
                case NORMAL -> config.getNormalDamage();
                case SMART -> config.getSmartDamage();
                case KIND -> config.getKindDamage();
                case ANCIENT -> config.getAncientDamage();
            };
            if (damage <= 0.0) {
                event.setCancelled(true);
            } else {
                event.setDamage(damage);
            }
        }

        // Игрок бьёт доброго скрипуна - даём временное "право на ответку".
        CreakingVariant victimVariant = getVariant(event.getEntity());
        if (victimVariant == CreakingVariant.KIND && event.getDamager() instanceof Player player
                && event.getEntity() instanceof LivingEntity victim) {
            kindAggroUntil.put(victim.getUniqueId(), System.currentTimeMillis() + 10_000L);
            kindAggroTarget.put(victim.getUniqueId(), player.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamageGeneric(EntityDamageEvent event) {
        // Зарезервировано под дополнительную настройку урона от иных источников при необходимости.
    }

    // ------------------------------------------------------------------
    //  Поведение (цель атаки) - повторяющийся таск
    // ------------------------------------------------------------------

    private void tickBehavior() {
        for (World world : plugin.getServer().getWorlds()) {
            if (!world.getName().equals(config.getPaleForestWorldName())) continue;

            for (LivingEntity le : world.getLivingEntities()) {
                CreakingVariant variant = getVariant(le);
                if (variant == null) continue;
                if (!(le instanceof Creaking creaking)) continue;

                switch (variant) {
                    case NORMAL -> handleNormal(creaking);
                    case KIND -> handleKind(creaking);
                    case SMART -> handleSmart(creaking);
                    case ANCIENT -> handleAncient(creaking);
                }
            }
        }
    }

    /**
     * Обычный скрипун: активируется (как это предусмотрено официальным Paper API через
     * {@link Creaking#activate(Player)}) при появлении игрока рядом - дальше используется
     * штатная ванильная логика "замирает под взглядом/бьёт, когда на него не смотрят".
     */
    private void handleNormal(Creaking creaking) {
        Player nearest = findNearestPlayer(creaking.getLocation(), 16.0);
        if (nearest != null) {
            if (!creaking.isActive()) creaking.activate(nearest);
        } else if (creaking.isActive()) {
            creaking.deactivate();
        }
    }

    /**
     * Добрый скрипун: не активируется сам по себе, только в ответ на полученный урон
     * (см. {@link #onDamageByEntity}), и только на ограниченное время.
     */
    private void handleKind(Creaking creaking) {
        UUID id = creaking.getUniqueId();
        Long until = kindAggroUntil.get(id);
        if (until == null || System.currentTimeMillis() > until) {
            if (creaking.isActive()) creaking.deactivate();
            creaking.setTarget(null);
            kindAggroUntil.remove(id);
            kindAggroTarget.remove(id);
            return;
        }
        UUID targetId = kindAggroTarget.get(id);
        if (targetId != null) {
            Entity target = plugin.getServer().getPlayer(targetId);
            if (target instanceof Player player && isReachable(creaking, player)) {
                creaking.activate(player);
                creaking.setTarget(player);
            }
        }
    }

    /**
     * Умный скрипун: активируется на увеличенном радиусе и получает цель напрямую через
     * {@link Mob#setTarget}, чтобы атаковать агрессивнее обычного. Полностью убрать
     * ванильное "замирание под взглядом" без NMS (доступ к Brain/Goal-селектору сущности)
     * невозможно - см. пояснение в README.
     */
    private void handleSmart(Creaking creaking) {
        Player nearest = findNearestPlayer(creaking.getLocation(), 20.0);
        if (nearest != null) {
            creaking.activate(nearest);
            creaking.setTarget(nearest);
        }
    }

    /** Древний скрипун-босс: атакует всех в радиусе aggro-radius, независимо от взгляда. */
    private void handleAncient(Creaking creaking) {
        double radius = config.getAncientAggroRadius();
        Player nearest = findNearestPlayer(creaking.getLocation(), radius);
        if (nearest != null) {
            creaking.activate(nearest);
            creaking.setTarget(nearest);
        } else if (creaking.isActive()) {
            creaking.deactivate();
        }
    }

    private boolean isReachable(Entity a, Entity b) {
        return a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation()) < 400;
    }

    private Player findNearestPlayer(Location from, double radius) {
        Player nearest = null;
        double best = radius * radius;
        for (Player p : from.getWorld().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double distSq = p.getLocation().distanceSquared(from);
            if (distSq <= best) {
                best = distSq;
                nearest = p;
            }
        }
        return nearest;
    }

    // ------------------------------------------------------------------
    //  Частицы и звук
    // ------------------------------------------------------------------

    private void tickParticles() {
        for (World world : plugin.getServer().getWorlds()) {
            if (!world.getName().equals(config.getPaleForestWorldName())) continue;

            for (LivingEntity le : world.getLivingEntities()) {
                CreakingVariant variant = getVariant(le);
                if (variant == null) continue;

                Location loc = le.getLocation().add(0, 1.0, 0);
                world.spawnParticle(Particle.SOUL, loc, 3, 0.3, 0.4, 0.3, 0.01);

                switch (variant) {
                    case KIND -> world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 2, 0.3, 0.3, 0.3, 0.0);
                    case SMART -> world.spawnParticle(Particle.DUST, loc, 2, 0.3, 0.3, 0.3, 0.0,
                            new DustOptions(Color.fromRGB(220, 0, 0), 1.0f));
                    case ANCIENT -> world.spawnParticle(Particle.PORTAL, loc, 3, 0.3, 0.5, 0.3, 0.02);
                    case NORMAL -> { /* только частицы SOUL, как у всех */ }
                }

                Vector velocity = le.getVelocity();
                if (velocity.lengthSquared() > 0.001) {
                    world.playSound(loc, Sound.ENTITY_CREAKING_AMBIENT, 0.6f, 1.0f);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Автоспавн по таймеру
    // ------------------------------------------------------------------

    private void tryAutoSpawn() {
        World world = plugin.getServer().getWorld(config.getPaleForestWorldName());
        if (world == null) return;

        long alive = countAlive(world);
        if (alive >= config.getCreakingMaxAlive()) return;

        Location entry = config.getEntryLocation();
        if (entry.getWorld() == null) return;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            double dist = rnd.nextDouble() * config.getCreakingSpawnRadius();
            int x = entry.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = entry.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);
            int y = world.getHighestBlockYAt(x, z) + 1;
            Location candidate = new Location(world, x + 0.5, y, z + 0.5);

            boolean tooCloseToPlayer = false;
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distanceSquared(candidate) < (double) config.getCreakingMinDistanceToPlayer()
                        * config.getCreakingMinDistanceToPlayer()) {
                    tooCloseToPlayer = true;
                    break;
                }
            }
            if (tooCloseToPlayer) continue;

            CreakingVariant variant = randomVariant(rnd);
            spawn(variant, candidate);
            return;
        }
    }

    private long countAlive(World world) {
        return world.getLivingEntities().stream().filter(le -> getVariant(le) != null).count();
    }

    private CreakingVariant randomVariant(ThreadLocalRandom rnd) {
        // Обычный встречается чаще, древний (босс) - редкость.
        int roll = rnd.nextInt(100);
        if (roll < 55) return CreakingVariant.NORMAL;
        if (roll < 80) return CreakingVariant.SMART;
        if (roll < 95) return CreakingVariant.KIND;
        return CreakingVariant.ANCIENT;
    }

    public List<CreakingVariant> allVariants() {
        return List.of(CreakingVariant.values());
    }
}
