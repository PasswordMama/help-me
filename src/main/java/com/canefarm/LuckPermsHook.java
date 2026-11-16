package com.canefarm;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.WeightNode;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Only loaded when LuckPerms is installed, so the plugin still runs without it.
 * The plugin only ever adds/removes the group membership. Prefix and weight are set ONCE when a group
 * is created (and only if it has none), so anything you change in LuckPerms is never overwritten.
 */
final class LuckPermsHook {
    private LuckPermsHook() {
    }

    static CompletableFuture<Group> ensureGroup(String name, String prefix, int weight, Logger log) {
        return ensureGroup(name, prefix, weight, log, false);
    }

    /** forcePrefix = overwrite the group's prefix with the given one (used once, when the rank style is updated). */
    static CompletableFuture<Group> ensureGroup(String name, String prefix, int weight, Logger log, boolean forcePrefix) {
        LuckPerms lp = LuckPermsProvider.get();
        // createAndLoadGroup returns the existing group if it is already there
        return lp.getGroupManager().createAndLoadGroup(name).thenApply((Group group) -> {
            boolean changed = false;
            if (!prefix.isEmpty() && (forcePrefix || group.getNodes(NodeType.PREFIX).isEmpty())) {
                group.data().clear(NodeType.PREFIX::matches);
                group.data().add(PrefixNode.builder(prefix, 100).build());
                changed = true;
            }
            if (weight > 0 && group.getNodes(NodeType.WEIGHT).isEmpty()) {
                group.data().add(WeightNode.builder(weight).build());
                changed = true;
            }
            if (changed) lp.getGroupManager().saveGroup(group);
            return group;
        });
    }

    /** The player's current LuckPerms prefix (highest priority one from their groups), or null if none. */
    static String prefixOf(Player p) {
        LuckPerms lp = LuckPermsProvider.get();
        return lp.getPlayerAdapter(Player.class).getMetaData(p).getPrefix();
    }

    static CompletableFuture<Void> grant(UUID uuid, List<String> allTierGroups, String target, String prefix, int weight, Logger log) {
        LuckPerms lp = LuckPermsProvider.get();
        return ensureGroup(target, prefix, weight, log).thenCompose(group ->
                lp.getUserManager().modifyUser(uuid, user -> {
                    for (String g : allTierGroups) {
                        if (!g.equals(target)) user.data().remove(InheritanceNode.builder(g).build());
                    }
                    user.data().add(InheritanceNode.builder(target).build());
                })
        ).thenRun(() -> log.info("LuckPerms: " + uuid + " is now in group '" + target + "'.")).exceptionally(t -> {
            log.warning("LuckPerms grant failed: " + t);
            return null;
        });
    }

    static void revokeAll(UUID uuid, List<String> allTierGroups, Logger log) {
        LuckPerms lp = LuckPermsProvider.get();
        lp.getUserManager().modifyUser(uuid, user -> {
            for (String g : allTierGroups) user.data().remove(InheritanceNode.builder(g).build());
        }).exceptionally(t -> {
            log.warning("LuckPerms revoke failed: " + t);
            return null;
        });
    }
}
