package com.paleportal;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Обрабатывает команду /paleportal (алиас /paleporatl):
 *   reload                     - перезагрузка config.yml
 *   spawn <тип>                - заспавнить скрипуна (OP)
 *   setportal                  - установка углов рамки по позиции игрока (2 вызова подряд)
 *   setmusic                   - установка музыкальных блоков по позиции игрока (5 вызовов подряд)
 */
public class PalePortalCommand implements CommandExecutor, TabCompleter {

    private final PalePortalPlugin plugin;

    /** Игрок -> первый угол рамки, ожидающий второго вызова setportal. */
    private final Map<UUID, Location> pendingFrameCorner = new HashMap<>();
    /** Игрок -> следующий индекс (0-4) музыкального блока для setmusic. */
    private final Map<UUID, Integer> pendingMusicIndex = new HashMap<>();

    public PalePortalCommand(PalePortalPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eИспользование: /" + label + " <reload|spawn|setportal|setmusic>");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "spawn" -> handleSpawn(sender, args);
            case "setportal" -> handleSetPortal(sender);
            case "setmusic" -> handleSetMusic(sender);
            default -> sender.sendMessage("§cНеизвестная подкоманда. Используйте: reload, spawn, setportal, setmusic.");
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        plugin.getConfigManager().load();
        plugin.getPortalManager().reloadGeometry();
        plugin.getPortalManager().startParticleTask();
        plugin.getCreakingManager().start();
        sender.sendMessage("§aКонфигурация PalePortal перезагружена.");
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭту команду может выполнять только игрок.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§eИспользование: /paleportal spawn <normal|smart|kind|ancient>");
            return;
        }
        CreakingManager.CreakingVariant variant = CreakingManager.CreakingVariant.parse(args[1]);
        if (variant == null) {
            sender.sendMessage("§cНеизвестный тип. Доступно: normal, smart, kind, ancient.");
            return;
        }
        Creaking creaking = plugin.getCreakingManager().spawn(variant, player.getLocation());
        if (creaking != null) {
            sender.sendMessage("§aЗаспавнен скрипун: §f" + variant.ruName);
        } else {
            sender.sendMessage("§cНе удалось заспавнить скрипуна.");
        }
    }

    private void handleSetPortal(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭту команду может выполнять только игрок.");
            return;
        }
        UUID id = player.getUniqueId();
        Location corner1 = pendingFrameCorner.get(id);
        if (corner1 == null) {
            pendingFrameCorner.put(id, player.getLocation());
            sender.sendMessage("§aПервый угол рамки запомнен. Встаньте на противоположный угол и снова "
                    + "выполните /paleportal setportal.");
            return;
        }
        Location corner2 = player.getLocation();
        pendingFrameCorner.remove(id);

        plugin.getConfigManager().setFrameCorner1(corner1);
        plugin.getConfigManager().setFrameCorner2(corner2);
        plugin.getPortalManager().reloadGeometry();
        sender.sendMessage("§aРамка портала установлена и зона телепортации пересчитана.");
    }

    private void handleSetMusic(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cЭту команду может выполнять только игрок.");
            return;
        }
        UUID id = player.getUniqueId();
        int index = pendingMusicIndex.getOrDefault(id, 0);

        Location loc = player.getLocation();
        plugin.getConfigManager().setMusicBlock(index, loc);

        Location center = plugin.getConfigManager().getFrameCenter();
        double distance = center.getWorld() != null && center.getWorld().equals(loc.getWorld())
                ? center.distance(loc) : -1;
        double radius = plugin.getConfigManager().getMusicRadius();

        int nextIndex = (index + 1) % 5;
        pendingMusicIndex.put(id, nextIndex);

        sender.sendMessage("§aМузыкальный блок §f#" + (index + 1) + "/5 §aустановлен"
                + (distance >= 0 ? String.format(" (расстояние до центра рамки: %.1f, лимит: %.1f)", distance, radius) : "")
                + ".");
        if (distance >= 0 && distance > radius) {
            sender.sendMessage("§cВнимание: этот блок находится за пределами допустимого радиуса!");
        }
        if (nextIndex == 0) {
            sender.sendMessage("§aВсе 5 музыкальных блоков настроены.");
        } else {
            sender.sendMessage("§eВстаньте у следующего блока мелодии и повторите команду "
                    + "(" + (nextIndex + 1) + "/5).");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload", "spawn", "setportal", "setmusic"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return filter(List.of("normal", "smart", "kind", "ancient"), args[1]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(o -> o.startsWith(p)).collect(Collectors.toList());
    }
}
