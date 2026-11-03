package com.soscreate.starlitpaper;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class StarlitPaperPlugin extends JavaPlugin implements CommandExecutor {
    private SkyRenderer renderer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        renderer = new SkyRenderer(this);
        renderer.start();
        if (getCommand("starlit") != null) getCommand("starlit").setExecutor(this);
        getLogger().info("StarlitPaper enabled. Particle-based sky effects are active.");
        getLogger().warning("This plugin recreates sky visuals with particles; it does not load Fabric or replace Minecraft sky shaders.");
    }

    @Override
    public void onDisable() {
        if (renderer != null) renderer.stop();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("help")) {
            sender.sendMessage(ChatColor.GOLD + "StarlitPaper — команды:");
            sender.sendMessage(ChatColor.YELLOW + "/starlit status" + ChatColor.GRAY + " — состояние эффектов");
            sender.sendMessage(ChatColor.YELLOW + "/starlit toggle" + ChatColor.GRAY + " — включить/выключить небо");
            sender.sendMessage(ChatColor.YELLOW + "/starlit reload" + ChatColor.GRAY + " — перечитать config.yml");
            sender.sendMessage(ChatColor.YELLOW + "/starlit meteor" + ChatColor.GRAY + " — вызвать метеор для себя");
            sender.sendMessage(ChatColor.YELLOW + "/starlit wish" + ChatColor.GRAY + " — загадать желание под падающей звездой");
            return true;
        }

        if (sub.equals("meteor") || sub.equals("wish")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Эта команда доступна только игроку.");
                return true;
            }
            if (sub.equals("wish") && !getConfig().getBoolean("meteors.wishes", true)) {
                player.sendMessage(ChatColor.RED + "Функция желаний отключена в config.yml.");
                return true;
            }
            if (!getConfig().getBoolean("enabled", true)) {
                player.sendMessage(ChatColor.RED + "Эффекты StarlitPaper сейчас отключены.");
                return true;
            }
            renderer.forceMeteor(player, sub.equals("wish"));
            return true;
        }

        if (!sender.hasPermission("starlit.admin")) {
            sender.sendMessage(ChatColor.RED + "Недостаточно прав.");
            return true;
        }

        switch (sub) {
            case "status" -> {
                sender.sendMessage(ChatColor.GOLD + "StarlitPaper: "
                        + (getConfig().getBoolean("enabled", true)
                        ? ChatColor.GREEN + "включён" : ChatColor.RED + "выключен"));
                sender.sendMessage(ChatColor.GRAY + "Игроков онлайн: " + getServer().getOnlinePlayers().size());
                sender.sendMessage(ChatColor.GRAY + "Интервал: "
                        + getConfig().getInt("render-interval-ticks", 10) + " тиков");
            }
            case "toggle" -> {
                boolean enabled = !getConfig().getBoolean("enabled", true);
                getConfig().set("enabled", enabled);
                saveConfig();
                sender.sendMessage(ChatColor.GOLD + "StarlitPaper "
                        + (enabled ? ChatColor.GREEN + "включён" : ChatColor.RED + "выключен")
                        + ChatColor.GOLD + ".");
            }
            case "reload" -> {
                reloadConfig();
                renderer.reload();
                sender.sendMessage(ChatColor.GREEN + "Настройки StarlitPaper перечитаны.");
            }
            default -> sender.sendMessage(ChatColor.RED + "Неизвестная команда. Используй /starlit help.");
        }
        return true;
    }
}
