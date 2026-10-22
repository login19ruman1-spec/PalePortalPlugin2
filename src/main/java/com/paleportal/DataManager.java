package com.paleportal;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * Хранит состояние плагина между перезапусками сервера в data.yml:
 *  - активна ли сейчас рамка портала (блоки портала на месте, рамка цела);
 *  - разблокирована ли телепортация (мелодия уже была сыграна верно хотя бы раз);
 *  - создан ли уже мир pale_forest (чтобы не пересоздавать его).
 */
public class DataManager {

    private final PalePortalPlugin plugin;
    private final File file;
    private YamlConfiguration data;

    private boolean portalActive;
    private boolean teleportUnlocked;
    private boolean worldGenerated;

    public DataManager(PalePortalPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        if (!file.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось создать data.yml", e);
            }
        }
        data = YamlConfiguration.loadConfiguration(file);
        portalActive = data.getBoolean("portal-active", false);
        teleportUnlocked = data.getBoolean("teleport-unlocked", false);
        worldGenerated = data.getBoolean("world-generated", false);
    }

    public void save() {
        data.set("portal-active", portalActive);
        data.set("teleport-unlocked", teleportUnlocked);
        data.set("world-generated", worldGenerated);
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить data.yml", e);
        }
    }

    public boolean isPortalActive() {
        return portalActive;
    }

    public void setPortalActive(boolean portalActive) {
        this.portalActive = portalActive;
        save();
    }

    public boolean isTeleportUnlocked() {
        return teleportUnlocked;
    }

    public void setTeleportUnlocked(boolean teleportUnlocked) {
        this.teleportUnlocked = teleportUnlocked;
        save();
    }

    public boolean isWorldGenerated() {
        return worldGenerated;
    }

    public void setWorldGenerated(boolean worldGenerated) {
        this.worldGenerated = worldGenerated;
        save();
    }
}
