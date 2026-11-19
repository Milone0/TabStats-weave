package tabstats.shout;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import tabstats.TabStats;
import tabstats.config.ModConfig;
import tabstats.config.ShoutSettings;
import tabstats.playerapi.PlayerLookup;
import tabstats.util.ChatColor;
import tabstats.util.Gamemodes;
import tabstats.util.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes what the tab list shows into the chat, for the team: one message per player, the team
 * colour first and then the stat columns the tab shows - {@code Red: Hans STAR:412 FKDR:3.45}.
 *
 * <p>The lines go wherever the player is chatting right now - the team in a game, the party with
 * {@code /chat p}. Prefixed with {@code /shout} they go to everyone in the game instead; since
 * Hypixel allows one {@code /shout} a minute, that packs as much as fits into a single message.
 */
public final class Shouter {
    private static final String SHOUT_COMMAND = "/shout ";
    private static final String TEAM_SEPARATOR = " // ";
    private static final String PREFIX = ChatColor.GOLD + "[TabStats] " + ChatColor.RESET;

    private Shouter() {
    }

    /**
     * Call on the client thread.
     *
     * @param words      what followed "shout"
     * @param toEveryone sent through {@code /shout}, to the whole game
     * @param auto       started by the game itself rather than by a command
     */
    public static void run(String gamemode, String[] words, boolean toEveryone, boolean auto) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }

        ShoutRequest request = ShoutRequest.parse(words);
        if (request.getError() != null) {
            info(ChatColor.RED + request.getError() + ChatColor.GRAY + " Teams: "
                    + String.join(", ", ShoutText.teamWords()) + ", or all");
            return;
        }

        if (!TabStats.isActive()) {
            info(ChatColor.RED + "TabStats is switched off - turn it on with /tabstats.");
            return;
        }

        boolean inGame = Gamemodes.inSupportedGame() && gamemode.equals(Gamemodes.resolveCurrent());
        boolean namesOnly = !request.getNames().isEmpty() && !request.isEveryone() && request.getTeams().isEmpty();
        if (!inGame && !namesOnly) {
            info(ChatColor.RED + "You are not in a " + pretty(gamemode) + " game.");
            return;
        }

        TabSnapshot snapshot = inGame ? TabSnapshot.take(gamemode) : null;
        List<ShoutPlayer> picked = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        if (request.isGoodOnly()) {
            pickGood(snapshot, picked);
        } else {
            if (snapshot != null) {
                for (ShoutPlayer player : snapshot.getPlayers()) {
                    if (request.isEveryone() || request.getTeams().contains(player.getTeam())) {
                        picked.add(player);
                    }
                }
            }

            for (String name : request.getNames()) {
                ShoutPlayer found = snapshot == null ? null : snapshot.find(name);
                if (found == null) {
                    missing.add(name);
                } else if (!picked.contains(found)) {
                    picked.add(found);
                }
            }
        }

        if (missing.isEmpty()) {
            finish(picked, request, toEveryone, auto);
        } else {
            lookUp(missing, picked, request, gamemode, toEveryone);
        }
    }

    private static void pickGood(TabSnapshot snapshot, List<ShoutPlayer> picked) {
        ShoutSettings settings = ModConfig.getInstance().getShoutSettings();
        String ownTeam = snapshot.getOwnTeam();
        for (ShoutPlayer player : snapshot.getPlayers()) {
            if (player.isSelf() || !player.isLoaded()) {
                continue;
            }

            if (!settings.isIncludeOwnTeam() && ownTeam != null && ownTeam.equals(player.getTeam())) {
                continue;
            }

            if (settings.isGood(player.getNumbers(), player.isFlagged())) {
                picked.add(player);
            }
        }
    }

    /** Named players the tab does not show are asked for, then everything goes out together. */
    private static void lookUp(List<String> names, List<ShoutPlayer> picked, ShoutRequest request,
                               String gamemode, boolean toEveryone) {
        final PlayerLookup.Result[] results = new PlayerLookup.Result[names.size()];
        final AtomicInteger remaining = new AtomicInteger(names.size());

        for (int i = 0; i < names.size(); i++) {
            final int index = i;
            final String name = names.get(i);
            PlayerLookup.lookup(name, result -> {
                results[index] = result;
                if (remaining.decrementAndGet() != 0) {
                    return;
                }

                /* Reading the stats touches the column layout, which belongs to the client thread. */
                Handler.onClientThread(() -> {
                    for (int j = 0; j < results.length; j++) {
                        picked.add(fromLookup(names.get(j), results[j], gamemode));
                    }
                    finish(picked, request, toEveryone, false);
                });
            });
        }
    }

    private static ShoutPlayer fromLookup(String name, PlayerLookup.Result result, String gamemode) {
        if (result != null && result.isOk()) {
            return ShoutPlayer.of(result.getName(), null, false, result.getPlayer(), gamemode, false);
        }

        boolean nicked = result != null && result.getStatus() == PlayerLookup.Status.UNKNOWN_NAME;
        return ShoutPlayer.unresolved(result == null ? name : result.getName(), nicked);
    }

    private static void finish(List<ShoutPlayer> picked, ShoutRequest request, boolean toEveryone, boolean auto) {
        if (picked.isEmpty()) {
            if (request.isGoodOnly()) {
                info(ChatColor.GRAY + (auto ? "Auto shout: no" : "No") + " good players in this game."
                        + (auto ? "" : " The rules are under /tabstats -> Shout Settings."));
            } else {
                info(ChatColor.GRAY + "Nobody in this game matches that.");
            }
            return;
        }

        Map<String, List<String>> teams = byTeam(picked);
        if (!toEveryone) {
            /* One line per player, since a player cannot send a line break inside a message. */
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, List<String>> team : teams.entrySet()) {
                for (String player : team.getValue()) {
                    lines.add(ShoutText.line(team.getKey(), player, ShoutText.MAX_MESSAGE_LENGTH));
                }
            }
            ChatQueue.send(lines);
            return;
        }

        int limit = ShoutText.MAX_MESSAGE_LENGTH - SHOUT_COMMAND.length();
        List<String> messages = new ArrayList<>();
        for (Map.Entry<String, List<String>> team : teams.entrySet()) {
            messages.addAll(ShoutText.pack(team.getKey(), team.getValue(), limit));
        }

        List<String> merged = ShoutText.merge(messages, TEAM_SEPARATOR, limit);
        ChatQueue.send(Collections.singletonList(SHOUT_COMMAND + merged.get(0)));
        if (merged.size() > 1) {
            info(ChatColor.GRAY + "/shout has a cooldown, so only one message went out. Not sent:");
            for (String line : merged.subList(1, merged.size())) {
                info(ChatColor.WHITE + line);
            }
        }
    }

    /** Players grouped by team, the teams in the order they first appear - the order of the tab. */
    private static Map<String, List<String>> byTeam(List<ShoutPlayer> players) {
        Map<String, List<String>> teams = new LinkedHashMap<>();
        for (ShoutPlayer player : players) {
            List<String> entries = teams.get(player.getTeam());
            if (entries == null) {
                entries = new ArrayList<>();
                teams.put(player.getTeam(), entries);
            }
            entries.add(player.describe());
        }
        return teams;
    }

    private static void info(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(PREFIX + message));
        }
    }

    private static String pretty(String gamemode) {
        if ("BEDWARS".equals(gamemode)) {
            return "Bed Wars";
        }
        return gamemode.charAt(0) + gamemode.substring(1).toLowerCase(Locale.ROOT);
    }
}
