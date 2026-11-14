package tabstats.playerapi.api;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import tabstats.TabStats;
import tabstats.config.ModConfig;
import tabstats.playerapi.HPlayer;
import tabstats.playerapi.StatWorld;
import tabstats.playerapi.api.UrchinAPI.UrchinReport;
import tabstats.playerapi.api.UrchinAPI.UrchinReportType;
import tabstats.playerapi.exception.ApiRequestException;
import tabstats.playerapi.exception.BadJsonException;
import tabstats.util.ChatColor;
import tabstats.util.Handler;
import tabstats.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The Urchin tag of a player: looked up in batches, remembered for the session, and announced in
 * chat the first time a tagged player turns up.
 *
 * <p>A game class asks for a tag by the identity Urchin knows the player under and puts the answer
 * into its TAG column; nothing in here knows which game that is.
 */
public final class UrchinTags {
    private static final UrchinAPI URCHIN_API = new UrchinAPI();
    /** Shown for a player Urchin has nothing on. */
    private static final String NO_TAG = ChatColor.GRAY + "-";
    /** How each report type is written: short in the TAG column, spelled out in chat. */
    private static final Map<UrchinReportType, Label> LABELS = new EnumMap<>(UrchinReportType.class);
    /** With several reports on one player, the first of these is the one shown. */
    private static final UrchinReportType[] PRIORITY = new UrchinReportType[]{
            UrchinReportType.CONFIRMED_CHEATER,
            UrchinReportType.BLATANT_CHEATER,
            UrchinReportType.CLOSET_CHEATER,
            UrchinReportType.SNIPER,
            UrchinReportType.POSSIBLE_SNIPER,
            UrchinReportType.LEGIT_SNIPER,
            UrchinReportType.CAUTION,
            UrchinReportType.ACCOUNT,
            UrchinReportType.INFO
    };
    private static final ConcurrentMap<String, Tag> CACHE = new ConcurrentHashMap<>();
    private static final Dispatcher DISPATCHER = new Dispatcher();

    static {
        label(UrchinReportType.SNIPER, ChatColor.DARK_RED + "S", ChatColor.DARK_RED + "Sniper");
        label(UrchinReportType.POSSIBLE_SNIPER, ChatColor.RED + "PS", ChatColor.RED + "Possible Sniper");
        label(UrchinReportType.LEGIT_SNIPER, ChatColor.RED + "LS", ChatColor.RED + "Legit Sniper");
        label(UrchinReportType.CONFIRMED_CHEATER, ChatColor.DARK_PURPLE + "CC", ChatColor.DARK_PURPLE + "Confirmed Cheater");
        label(UrchinReportType.BLATANT_CHEATER, ChatColor.GOLD + "BC", ChatColor.GOLD + "Blatant Cheater");
        label(UrchinReportType.CLOSET_CHEATER, ChatColor.GOLD + "CC", ChatColor.GOLD + "Closet Cheater");
        label(UrchinReportType.CAUTION, ChatColor.YELLOW + "C", ChatColor.YELLOW + "Caution");
        label(UrchinReportType.ACCOUNT, ChatColor.DARK_AQUA + "A", ChatColor.DARK_AQUA + "Account");
        label(UrchinReportType.INFO, ChatColor.DARK_GRAY + "I", ChatColor.DARK_GRAY + "Info");
    }

    private UrchinTags() {
    }

    private static void label(UrchinReportType type, String column, String chat) {
        LABELS.put(type, new Label(applyBoldFormatting(column), applyBoldFormatting(chat)));
    }

    /** The Urchin key the mod is set to use, empty when there is none. */
    public static String activeApiKey() {
        String configured = ModConfig.getInstance().getUrchinApiKey();
        return configured == null ? "" : configured.trim();
    }

    /** The tag of a player Urchin already answered for this session, or null. */
    public static Tag cached(String identity) {
        String normalized = normalizeIdentity(identity);
        return normalized == null ? null : CACHE.get(normalized);
    }

    /**
     * Asks Urchin about one player. Requests made in the same moment share a single API call; the
     * callback runs on a worker thread and is handed a {@link Tag#isPending() pending} tag when
     * Urchin did not answer.
     */
    public static void lookUp(String identity, Consumer<Tag> callback) {
        if (identity == null || callback == null) {
            return;
        }
        DISPATCHER.enqueue(identity, callback);
    }

    /** Writes a tagged player into the chat, once per tag however often it is handed in. */
    public static void announce(Tag tag, String playerName) {
        if (tag == null || !tag.shouldAnnounce() || !tag.markAnnounced()) {
            return;
        }

        Handler.onClientThread(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null) {
                return;
            }

            String reason = tag.reason.isEmpty() ? "Unknown" : tag.reason;
            String addedOn = tag.addedOn.isEmpty() ? "Unknown" : tag.addedOn;

            String message =
                    playerLabel(playerName) + ChatColor.YELLOW + " is tagged on Urchin!" + " \n" +
                    ChatColor.GRAY + "- Tag: " + tag.chatLabel + " \n" +
                    ChatColor.GRAY + "- Reason: " + ChatColor.WHITE + reason + " \n" +
                    ChatColor.GRAY + "- Added on: " + ChatColor.WHITE + addedOn + ChatColor.RESET;

            mc.thePlayer.addChatMessage(new ChatComponentText(message));
        });
    }

    /** Rank and name as the tab list knows the player, the bare name otherwise. */
    private static String playerLabel(String playerName) {
        TabStats tabStats = TabStats.getTabStats();
        StatWorld statWorld = tabStats == null ? null : tabStats.getStatWorld();
        HPlayer player = statWorld == null ? null : statWorld.getPlayerByName(playerName);
        if (player != null) {
            return player.getRankedName("");
        }

        if (playerName == null || playerName.trim().isEmpty()) {
            return ChatColor.WHITE + "Unknown player";
        }

        return ChatColor.WHITE + playerName;
    }

    private static Map<String, Tag> resolveBatch(String apiKey, Collection<String> identities) {
        Map<String, Tag> results = new LinkedHashMap<>();
        if (identities == null || identities.isEmpty()) {
            return results;
        }

        String trimmedKey = apiKey == null ? "" : apiKey.trim();
        if (trimmedKey.isEmpty()) {
            for (String identity : identities) {
                results.put(identity, pendingTag());
            }
            return results;
        }

        try {
            Map<String, List<UrchinReport>> response = URCHIN_API.fetchPlayerReports(trimmedKey, identities);
            Map<String, List<UrchinReport>> normalizedResponse = new HashMap<>();
            if (response != null) {
                for (Map.Entry<String, List<UrchinReport>> entry : response.entrySet()) {
                    String normalizedKey = normalizeIdentity(entry.getKey());
                    if (normalizedKey != null) {
                        normalizedResponse.put(normalizedKey, entry.getValue());
                    }
                }
            }

            for (String identity : identities) {
                String normalizedIdentity = normalizeIdentity(identity);
                List<UrchinReport> reports = normalizedIdentity == null ? null : normalizedResponse.get(normalizedIdentity);
                results.put(identity, toTag(identity, reports));
            }
        } catch (ApiRequestException | BadJsonException ex) {
            Log.warn("Urchin lookup failed: " + ex.getMessage());
            for (String identity : identities) {
                results.put(identity, pendingTag());
            }
        }

        return results;
    }

    /** An answer from Urchin is cached, reports or none; no answer at all stays pending. */
    private static Tag toTag(String identity, List<UrchinReport> reports) {
        if (identity == null || reports == null) {
            return pendingTag();
        }

        UrchinReport report = priorityReport(reports);
        return cache(identity, report == null ? noHitTag() : tagFor(report));
    }

    private static UrchinReport priorityReport(List<UrchinReport> reports) {
        for (UrchinReportType priority : PRIORITY) {
            for (UrchinReport report : reports) {
                if (report != null && report.getType() == priority) {
                    return report;
                }
            }
        }

        return null;
    }

    private static Tag tagFor(UrchinReport report) {
        Label label = LABELS.get(report.getType());
        return new Tag(
                label == null ? NO_TAG : label.column,
                label == null ? NO_TAG : label.chat,
                report.getType(),
                report.getReason().trim(),
                formatDate(report.getAddedOn()),
                false
        );
    }

    private static Tag noHitTag() {
        return new Tag(NO_TAG, NO_TAG, null, "", "", false);
    }

    /** No answer from Urchin. Never cached, so the lookup can be tried again. */
    private static Tag pendingTag() {
        return new Tag("", "", null, "", "", true);
    }

    /** The date of an ISO timestamp, without the time of day. */
    private static String formatDate(String raw) {
        String trimmed = raw.trim();
        int tIndex = trimmed.indexOf('T');
        return tIndex > 0 ? trimmed.substring(0, tIndex) : trimmed;
    }

    private static Tag cache(String identity, Tag value) {
        String normalized = normalizeIdentity(identity);
        if (normalized == null) {
            return value;
        }

        /* An unchanged answer keeps the tag already there, and with it the note that it was announced. */
        return CACHE.compute(normalized, (key, existing) ->
                existing != null && existing.samePayload(value) ? existing : value);
    }

    private static String normalizeIdentity(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** Puts the bold code behind the leading colour codes, where it is not undone by them. */
    private static String applyBoldFormatting(String value) {
        if (value.isEmpty() || value.contains(ChatColor.BOLD.toString())) {
            return value;
        }

        int insertIndex = 0;
        while (insertIndex + 1 < value.length() && value.charAt(insertIndex) == ChatColor.COLOR_CHAR) {
            insertIndex += 2;
        }
        return value.substring(0, insertIndex) + ChatColor.BOLD + value.substring(insertIndex);
    }

    /**
     * Batches Urchin lookups so repeated requests share a single API call.
     */
    private static final class Dispatcher {
        // Small delay to accumulate simultaneous requests without feeling slow.
        private static final long BATCH_DEBOUNCE_MS = 125L;

        private final Object lock = new Object();
        private final LinkedHashMap<String, List<Consumer<Tag>>> pendingLookups = new LinkedHashMap<>();
        private boolean draining;

        void enqueue(String identity, Consumer<Tag> callback) {
            synchronized (lock) {
                pendingLookups.computeIfAbsent(identity, key -> new ArrayList<>()).add(callback);
                if (!draining) {
                    draining = true;
                    Handler.asExecutor(this::processLoop);
                }
            }
        }

        private void processLoop() {
            boolean finished = false;
            try {
                while (true) {
                    delay(BATCH_DEBOUNCE_MS);
                    List<LookupRequest> batch = pollBatch();
                    if (!batch.isEmpty()) {
                        dispatch(batch);
                    }
                    synchronized (lock) {
                        if (pendingLookups.isEmpty()) {
                            draining = false;
                            finished = true;
                            return;
                        }
                    }
                }
            } finally {
                if (!finished) {
                    /*
                     * The loop died on an exception. Left set, draining would make every later
                     * enqueue wait for a loop that no longer exists.
                     */
                    synchronized (lock) {
                        draining = !pendingLookups.isEmpty();
                        if (draining) {
                            Handler.asExecutor(this::processLoop);
                        }
                    }
                }
            }
        }

        private List<LookupRequest> pollBatch() {
            synchronized (lock) {
                if (pendingLookups.isEmpty()) {
                    return Collections.emptyList();
                }

                List<LookupRequest> batch = new ArrayList<>(pendingLookups.size());
                Iterator<Map.Entry<String, List<Consumer<Tag>>>> iterator = pendingLookups.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, List<Consumer<Tag>>> entry = iterator.next();
                    batch.add(new LookupRequest(entry.getKey(), new ArrayList<>(entry.getValue())));
                    iterator.remove();
                }
                return batch;
            }
        }

        private void dispatch(List<LookupRequest> batch) {
            List<String> identities = new ArrayList<>(batch.size());
            for (LookupRequest request : batch) {
                identities.add(request.identity);
            }

            Map<String, Tag> resolved;
            try {
                resolved = resolveBatch(activeApiKey(), identities);
            } catch (RuntimeException ex) {
                // Every callback still has to hear back, or its player never asks again
                Log.warn("Urchin lookup failed", ex);
                resolved = Collections.emptyMap();
            }
            for (LookupRequest request : batch) {
                Tag tag = resolved.get(request.identity);
                if (tag == null) {
                    tag = pendingTag();
                }
                for (Consumer<Tag> callback : request.callbacks) {
                    try {
                        callback.accept(tag);
                    } catch (RuntimeException ignored) {
                        // Avoid breaking the dispatcher if a callback misbehaves.
                    }
                }
            }
        }

        private void delay(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private static final class LookupRequest {
            private final String identity;
            private final List<Consumer<Tag>> callbacks;

            private LookupRequest(String identity, List<Consumer<Tag>> callbacks) {
                this.identity = identity;
                this.callbacks = callbacks;
            }
        }
    }

    private static final class Label {
        private final String column;
        private final String chat;

        private Label(String column, String chat) {
            this.column = column;
            this.chat = chat;
        }
    }

    /** What Urchin said about one player, in the form it is displayed. */
    public static final class Tag {
        private final String displayValue;
        private final String chatLabel;
        private final UrchinReportType type;
        private final String reason;
        private final String addedOn;
        private final boolean pending;
        private final AtomicBoolean announced = new AtomicBoolean(false);

        private Tag(String displayValue, String chatLabel, UrchinReportType type, String reason, String addedOn, boolean pending) {
            this.displayValue = displayValue;
            this.chatLabel = chatLabel;
            this.type = type;
            this.reason = reason;
            this.addedOn = addedOn;
            this.pending = pending;
        }

        /** True when Urchin gave no answer, as opposed to answering "no reports". */
        public boolean isPending() {
            return pending;
        }

        /** What goes into the TAG column, colour codes included. */
        public String getDisplayValue() {
            return displayValue;
        }

        private boolean shouldAnnounce() {
            return type != null && !NO_TAG.equals(displayValue);
        }

        private boolean markAnnounced() {
            return announced.compareAndSet(false, true);
        }

        private boolean samePayload(Tag other) {
            return this.type == other.type
                    && this.displayValue.equals(other.displayValue)
                    && this.chatLabel.equals(other.chatLabel)
                    && this.reason.equals(other.reason)
                    && this.addedOn.equals(other.addedOn);
        }
    }
}
