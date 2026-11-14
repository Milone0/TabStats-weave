package tabstats.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyTrackerTest {
    private static final String DIVIDER = "§9§m-----------------------------------------------------";

    /** Remembers what a request was answered with. */
    private static final class Answer implements PartyTracker.Callback {
        private int calls;
        private List<String> members;
        private String error;

        @Override
        public void onParty(List<String> members, String error) {
            this.calls++;
            this.members = new ArrayList<>(members);
            this.error = error;
        }
    }

    @BeforeEach
    void finishWhateverAnEarlierTestLeftOpen() {
        PartyTracker.handleChatMessage("You are not currently in a party.");
    }

    @Test
    void readsLeaderModeratorsAndMembers() {
        Answer answer = new Answer();
        assertTrue(PartyTracker.expect(answer));

        assertTrue(PartyTracker.handleChatMessage(DIVIDER));
        assertTrue(PartyTracker.handleChatMessage("§6Party Members (4)"));
        assertTrue(PartyTracker.handleChatMessage(""));
        assertTrue(PartyTracker.handleChatMessage("§eParty Leader: §b[MVP§c+§b] Hans §a●"));
        assertTrue(PartyTracker.handleChatMessage("Party Moderators: Klaus ●"));
        assertTrue(PartyTracker.handleChatMessage("Party Members: [VIP] Fritz ● Otto_2 ●"));
        assertEquals(0, answer.calls, "the closing divider is what ends the list");

        assertTrue(PartyTracker.handleChatMessage(DIVIDER));
        assertEquals(1, answer.calls);
        assertNull(answer.error);
        assertEquals(Arrays.asList("Hans", "Klaus", "Fritz", "Otto_2"), answer.members);
    }

    @Test
    void nothingIsSwallowedWithoutARequest() {
        assertFalse(PartyTracker.handleChatMessage(DIVIDER));
        assertFalse(PartyTracker.handleChatMessage("Party Leader: Hans ●"));
    }

    @Test
    void theAnswerIsOnlySwallowedOnce() {
        Answer answer = new Answer();
        PartyTracker.expect(answer);
        PartyTracker.handleChatMessage(DIVIDER);
        PartyTracker.handleChatMessage("Party Leader: Hans ●");
        PartyTracker.handleChatMessage(DIVIDER);

        assertFalse(PartyTracker.handleChatMessage(DIVIDER));
        assertEquals(1, answer.calls);
    }

    @Test
    void otherChatInBetweenStaysVisible() {
        Answer answer = new Answer();
        PartyTracker.expect(answer);

        assertTrue(PartyTracker.handleChatMessage(DIVIDER));
        assertFalse(PartyTracker.handleChatMessage("§7Hans§7: hello"));
        assertTrue(PartyTracker.handleChatMessage("Party Leader: Klaus ●"));
        assertTrue(PartyTracker.handleChatMessage(DIVIDER));

        assertEquals(Collections.singletonList("Klaus"), answer.members);
    }

    @Test
    void notBeingInAPartyIsAnError() {
        Answer answer = new Answer();
        PartyTracker.expect(answer);

        assertTrue(PartyTracker.handleChatMessage("§cYou are not currently in a party."));
        assertEquals(1, answer.calls);
        assertEquals("You are not in a party.", answer.error);
        assertTrue(answer.members.isEmpty());
    }

    @Test
    void aSecondRequestSharesTheFirst() {
        Answer first = new Answer();
        Answer second = new Answer();
        assertTrue(PartyTracker.expect(first));
        assertFalse(PartyTracker.expect(second), "no second /party list while one is on its way");

        PartyTracker.handleChatMessage(DIVIDER);
        PartyTracker.handleChatMessage("Party Leader: Hans ●");
        PartyTracker.handleChatMessage(DIVIDER);

        assertEquals(Collections.singletonList("Hans"), first.members);
        assertEquals(Collections.singletonList("Hans"), second.members);
    }

    @Test
    void aNameIsListedOnceWhateverItsCase() {
        Answer answer = new Answer();
        PartyTracker.expect(answer);

        PartyTracker.handleChatMessage(DIVIDER);
        PartyTracker.handleChatMessage("Party Leader: Hans ●");
        PartyTracker.handleChatMessage("Party Members: hans ● Klaus ●");
        PartyTracker.handleChatMessage(DIVIDER);

        assertEquals(Arrays.asList("Hans", "Klaus"), answer.members);
    }
}
