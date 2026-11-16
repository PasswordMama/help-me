package com.canefarm;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Ownership credit for this plugin. The plugin checks it on startup and every few minutes;
 * if the credit or the author entry in plugin.yml was changed or removed, the plugin disables itself.
 */
final class Credit {
    private Credit() {
    }

    static final String OWNER = "AshieCloud045";
    static final String PLATFORM = "Discord";
    private static final String HASH = "0342aa4da78572fd2a664a370b9710107d1de4614aec49971671a9f5f2570925";

    static final String LINE = "Plugin by " + OWNER + " on " + PLATFORM;
    static final String CHAT = "&7[Cane] &dPlugin by &e" + OWNER + " &don &9" + PLATFORM;
    static final String LORE = "&8Plugin by " + OWNER + " (" + PLATFORM + ")";

    static boolean valid(JavaPlugin pl) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((OWNER + "@" + PLATFORM).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            if (!sb.toString().equals(HASH)) return false;
            List<String> authors = pl.getDescription().getAuthors();
            return authors.contains(OWNER);
        } catch (Exception e) {
            return false;
        }
    }
}
