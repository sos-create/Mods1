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
    private StarlitModAdapter.Report adapterReport;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateLegacyConfig();
        adapterReport = new StarlitModAdapter(this).inspectConfiguredArchive();
        if (adapterReport.valid()) {
            getLogger().info("Starlit adapter: " + adapterReport.message()
                    + " Найдено shader-ресурсов: " + adapterReport.shaderResources()
                    + "; семейств переведено: " + adapterReport.translatedFamilies().size()
                    + ". Paper-реализации: " + String.join(", ", adapterReport.translatedFamilies()));
        } else {
            getLogger().info("Starlit adapter: " + adapterReport.message());
        }
        renderer = new SkyRenderer(this);
        renderer.start();
        if (getCommand("starlit") != null) getCommand("starlit").setExecutor(this);
        getLogger().info("StarlitPaper 1.2.0 enabled. Procedural Paper sky adapter is active.");
        getLogger().warning("Fabric classes and GPU shaders are not executed: their visual systems are translated to Paper particles for vanilla clients.");
    }

    /** Upgrades the previous particle prototype config without discarding user settings. */
    private void migrateLegacyConfig() {
        boolean changed = false;
        if (getConfig().isConfigurationSection("milky-way")) {
            getConfig().set("milky-way", 100);
            changed = true;
        }
        for (String key : new String[]{"nebulae", "galaxies", "planets", "moon-glow"}) {
            if (getConfig().isConfigurationSection(key)) {
                getConfig().set(key, true);
                changed = true;
            }
        }
        int stars = getConfig().getInt("stars.count", 5000);
        if (stars > 0 && stars < 500) {
            getConfig().set("stars.count", 5000);
            changed = true;
        }
        if (getConfig().contains("aurora.enabled")) {
            if (!getConfig().getBoolean("aurora.enabled", true))
                getConfig().set("aurora.mode", "off");
            getConfig().set("aurora.enabled", null);
            changed = true;
        }
        if (changed) {
            saveConfig();
            getLogger().info("Migrated old configuration to StarlitPaper 1.2.0 format.");
        }
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
            sender.sendMessage(ChatColor.YELLOW + "/starlit status" + ChatColor.GRAY + " — состояние эффектов и адаптера");
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
                sender.sendMessage(ChatColor.GRAY + "Адаптер Starlit: "
                        + (adapterReport != null && adapterReport.valid()
                        ? ChatColor.GREEN + "архив распознан, shader-ресурсов: " + adapterReport.shaderResources()
                        : ChatColor.YELLOW + "встроенный переводчик активен; оригинальный JAR не найден/не прошёл проверку"));
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
                adapterReport = new StarlitModAdapter(this).inspectConfiguredArchive();
                renderer.reload();
                sender.sendMessage(ChatColor.GREEN + "Настройки и адаптер Starlit перечитаны.");
            }
            default -> sender.sendMessage(ChatColor.RED + "Неизвестная команда. Используй /starlit help.");
        }
        return true;
    }
}
