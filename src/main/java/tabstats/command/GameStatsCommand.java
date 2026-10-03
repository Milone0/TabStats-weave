package tabstats.command;

import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.weavemc.api.command.Command;
import tabstats.config.ModConfig;
import tabstats.config.StatColumnLayout;
import tabstats.playerapi.HPlayer;
import tabstats.playerapi.PlayerLookup;
import tabstats.playerapi.api.stats.Stat;
import tabstats.playerapi.api.stats.StatString;
import tabstats.util.ChatColor;
import tabstats.util.PartyTracker;
import tabstats.util.StatFormatting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes the stats of named players into the chat, for one gamemode.
 *
 * <p>One instance stands for one command: {@code /bw} is this class pointed at BEDWARS, and a
 * {@code /sk} for SKYWARS is the same class pointed at the other gamemode. Which stats are shown,
 * and in which order, is whatever the mod is set to show in the tab list for that gamemode -
 * unless the command names stats of its own, which then win and appear in the order they were
 * typed, even if the tab list has them switched off.
 *
 * <pre>
 *   /bw Hans              every configured stat of Hans
 *   /bw Hans Klaus        the same for two players
 *   /bw Hans Klaus fkdr   only the FKDR of both
 *   /bw p fkdr            only the FKDR, for everyone in the party
 * </pre>
 */
public class GameStatsCommand extends Command {
    /** A party can hold far more players than is worth spending API calls on in one go. */
    private static final int MAX_TARGETS = 20;

    private final String gamemode;
    private final String tag;

    /**
     * @param name the command name, without the slash
     * @param gamemode one of {@link StatColumnLayout#GAMEMODES}
     * @param tag the short label the chat output is prefixed with
     */
    public GameStatsCommand(String name, String gamemode, String tag, String... aliases) {
        super(name, aliases);
        this.gamemode = gamemode;
        this.tag = tag;
    }

    /**
     * Weave passes the command name itself as args[0], so a bare "/bw" is length 1.
     */
    @Override
    public void execute(String[] args) {
        List<String> names = new ArrayList<>();
        List<String> statKeys = new ArrayList<>();
        Set<String> knownStats = knownStats();

        for (int i = 1; i < args.length; i++) {
            String token = args[i] == null ? "" : args[i].trim();
            if (token.isEmpty()) {
                continue;
            }

            String key = StatFormatting.key(token);
            /*
             * Names come first, stats after them: a token is read as a stat once at least one
             * name stands in front of it, and from the first stat on nothing but stats follows.
             */
            boolean readAsStat = !statKeys.isEmpty() || (!names.isEmpty() && knownStats.contains(key));
            if (!readAsStat) {
                names.add(token);
                continue;
            }

            if (!knownStats.contains(key)) {
                sendLines(singleLine(ChatColor.RED + "Unknown stat " + ChatColor.WHITE + token
                        + ChatColor.RED + ". Available: " + ChatColor.GRAY + join(knownStats)));
                return;
            }

            if (!statKeys.contains(key)) {
                statKeys.add(key);
            }
        }

        if (names.isEmpty()) {
            sendUsage();
            return;
        }

        if (!wantsParty(names)) {
            lookUp(dedupe(names), statKeys);
            return;
        }

        final List<String> requested = names;
        PartyTracker.request((members, error) -> {
            if (error != null) {
                sendLines(singleLine(ChatColor.RED + error));
                return;
            }

            List<String> expanded = new ArrayList<>();
            for (String name : requested) {
                if (isPartyToken(name)) {
                    expanded.addAll(members);
                } else {
                    expanded.add(name);
                }
            }

            List<String> targets = dedupe(expanded);
            if (targets.isEmpty()) {
                sendLines(singleLine(ChatColor.RED + "You are not in a party."));
                return;
            }

            lookUp(targets, statKeys);
        });
    }

    /** Offers the party shorthand and the stat names, so the stats do not have to be memorised. */
    @Override
    public String[] getSuggestions(String[] args, BlockPos targetPos) {
        if (args == null || args.length == 0) {
            return null;
        }

        String current = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            if ("p".startsWith(current)) {
                suggestions.add("p");
            }
        } else {
            for (String stat : knownStats()) {
                String lower = stat.toLowerCase(Locale.ROOT);
                if (lower.startsWith(current)) {
                    suggestions.add(lower);
                }
            }
        }

        return suggestions.isEmpty() ? null : suggestions.toArray(new String[0]);
    }

    /**
     * Resolves every target at once and prints them in the order they were asked for, rather than
     * in the order the answers happen to come back.
     */
    private void lookUp(List<String> targets, List<String> statKeys) {
        List<String> limited = targets;
        if (targets.size() > MAX_TARGETS) {
            limited = new ArrayList<>(targets.subList(0, MAX_TARGETS));
            sendLines(singleLine(ChatColor.GRAY + "Only the first " + MAX_TARGETS + " players are looked up."));
        }

        final PlayerLookup.Result[] results = new PlayerLookup.Result[limited.size()];
        final AtomicInteger remaining = new AtomicInteger(limited.size());

        for (int i = 0; i < limited.size(); i++) {
            final int index = i;
            PlayerLookup.lookup(limited.get(i), result -> {
                results[index] = result;
                if (remaining.decrementAndGet() == 0) {
                    print(results, statKeys);
                }
            });
        }
    }

    /**
     * Builds and sends the lines on the client thread - reading the stats touches the column
     * layout, which the tab list is rendering from at the same time.
     */
    private void print(PlayerLookup.Result[] results, List<String> statKeys) {
        sendOnClientThread(() -> {
            List<String> lines = new ArrayList<>(results.length);
            for (PlayerLookup.Result result : results) {
                lines.add(line(result, statKeys));
            }
            return lines;
        });
    }

    private String line(PlayerLookup.Result result, List<String> statKeys) {
        String prefix = ChatColor.GOLD + "[" + this.tag + "] " + ChatColor.RESET;
        if (result == null) {
            return prefix + ChatColor.RED + "Lookup failed.";
        }

        if (!result.isOk()) {
            return prefix + ChatColor.WHITE + result.getName() + ChatColor.DARK_GRAY + " - "
                    + ChatColor.RED + reason(result.getStatus());
        }

        HPlayer player = result.getPlayer();
        List<Stat> stats = statKeys.isEmpty()
                ? StatFormatting.configuredStats(player, this.gamemode)
                : pick(player, statKeys);

        StringBuilder values = new StringBuilder();
        for (Stat stat : stats) {
            String value = StatFormatting.valueOf(stat);
            if (value == null || value.trim().isEmpty()) {
                /* A column the player has nothing for, such as an untagged player under TAG. */
                continue;
            }

            if (values.length() > 0) {
                values.append(ChatColor.DARK_GRAY).append(" | ");
            }

            values.append(ChatColor.GRAY).append(StatFormatting.key(stat.getStatName()))
                    .append(' ').append(value);
        }

        String name = nameOf(player, result.getName());
        if (values.length() == 0) {
            return prefix + name + ChatColor.DARK_GRAY + " - " + ChatColor.RED + "no " + this.tag + " stats";
        }

        return prefix + name + ChatColor.DARK_GRAY + " » " + values;
    }

    /** The stats the command named, in that order, whether or not the tab list shows them. */
    private List<Stat> pick(HPlayer player, List<String> statKeys) {
        List<Stat> available = StatFormatting.rawStats(player, this.gamemode);
        List<Stat> picked = new ArrayList<>(statKeys.size());

        for (String key : statKeys) {
            Stat stat = StatFormatting.find(available, key);
            String value = StatFormatting.valueOf(stat);
            /* A stat that was asked for by name is shown either way, empty or not. */
            picked.add(value.trim().isEmpty() ? new StatString(key, ChatColor.GRAY + "-") : stat);
        }

        return picked;
    }

    private String nameOf(HPlayer player, String fallback) {
        if (player == null) {
            return ChatColor.WHITE + fallback;
        }

        String rank = player.getPlayerRank();
        String name = player.getPlayerName();
        return (rank == null ? "" : rank) + (name == null ? fallback : name);
    }

    private String reason(PlayerLookup.Status status) {
        switch (status) {
            case UNKNOWN_NAME:
                return "no such player (nicked?)";
            case NO_HYPIXEL_DATA:
                return "never played on Hypixel";
            case NO_API_KEY:
                return "no Hypixel API key set - use /tabstats";
            default:
                return "lookup failed, try again";
        }
    }

    /** Every stat of this gamemode, in the configured order, hidden ones included. */
    private Set<String> knownStats() {
        Set<String> names = new LinkedHashSet<>();
        for (StatColumnLayout.Column column : ModConfig.getInstance().getStatColumns().getColumns(this.gamemode)) {
            names.add(column.getKey());
        }
        return names;
    }

    private boolean wantsParty(List<String> names) {
        for (String name : names) {
            if (isPartyToken(name)) {
                return true;
            }
        }
        return false;
    }

    /** Shorter than the shortest Minecraft name, so no player can be meant by it. */
    private boolean isPartyToken(String name) {
        return "p".equalsIgnoreCase(name) || "party".equalsIgnoreCase(name);
    }

    private List<String> dedupe(List<String> names) {
        List<String> unique = new ArrayList<>(names.size());
        for (String name : names) {
            boolean seen = false;
            for (String candidate : unique) {
                if (candidate.equalsIgnoreCase(name)) {
                    seen = true;
                    break;
                }
            }

            if (!seen) {
                unique.add(name);
            }
        }

        return unique;
    }

    private void sendUsage() {
        List<String> lines = new ArrayList<>();
        String name = "/" + getName();
        lines.add(ChatColor.GOLD + "[" + this.tag + "] " + ChatColor.GRAY + "Usage:");
        lines.add(ChatColor.GRAY + "  " + name + " <player> [player ...] [stat ...]");
        lines.add(ChatColor.GRAY + "  " + name + " p" + ChatColor.DARK_GRAY + " - everyone in your party");
        lines.add(ChatColor.GRAY + "  Stats: " + ChatColor.DARK_GRAY + join(knownStats()));
        sendLines(lines);
    }

    private List<String> singleLine(String line) {
        List<String> lines = new ArrayList<>(1);
        lines.add(ChatColor.GOLD + "[" + this.tag + "] " + ChatColor.RESET + line);
        return lines;
    }

    private static String join(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value.toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private void sendLines(List<String> lines) {
        sendOnClientThread(() -> lines);
    }

    private void sendOnClientThread(LineSupplier supplier) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }

        Runnable task = () -> {
            if (mc.thePlayer == null) {
                return;
            }

            for (String line : supplier.get()) {
                mc.thePlayer.addChatMessage(new ChatComponentText(line));
            }
        };

        if (mc.isCallingFromMinecraftThread()) {
            task.run();
        } else {
            mc.addScheduledTask(task);
        }
    }

    private interface LineSupplier {
        List<String> get();
    }
}
