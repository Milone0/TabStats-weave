package tabstats.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyPartiesTest {
    private static final String SELF = "Milone0";
    /** Long enough after the last line for its group to be closed. */
    private static final long LATER = LobbyParties.BURST_GAP_MS + 1;

    private LobbyParties parties;

    @BeforeEach
    void freshLobby() {
        this.parties = new LobbyParties();
    }

    private void line(String text, long at) {
        this.parties.offer(text, SELF, at);
    }

    private int full(int teamSize, long at) {
        return this.parties.fullParties(teamSize, SELF, at);
    }

    /** A Doubles lobby as it was logged, own party first. */
    @Test
    void duoPartiesAreCountedAndTheOwnOneIsNot() {
        line("Aqualotial has joined (7/16)!", 0);
        line("Milone0 has joined (8/16)!", 100);
        line("XoiObjm5pujswd has joined (9/16)!", 9_000);
        line("0zPSTWyOjNmW has joined (10/16)!", 9_200);
        line("SmE has quit!", 11_000);
        line("PGNn91rfimoC has joined (10/16)!", 20_000);
        line("s8CwS6qBvA6 has joined (11/16)!", 20_300);
        line("DKRBQGevq has joined (12/16)!", 25_000);

        assertTrue(this.parties.isWaiting());
        assertEquals(2, full(2, 25_000 + LATER));
    }

    @Test
    void aSoloPlayerIsNoParty() {
        line("DKRBQGevq has joined (12/16)!", 0);
        line("he9SOoht has joined (14/16)!", 5_000);
        assertEquals(0, full(2, 5_000 + LATER));
    }

    @Test
    void twoStrangersNotQuiteTogetherAreNoParty() {
        line("aaa has joined (5/16)!", 0);
        line("bbb has joined (6/16)!", LobbyParties.BURST_GAP_MS + 200);
        assertEquals(0, full(2, 10_000));
    }

    @Test
    void aPartyStillJoiningIsNotCountedYet() {
        line("eLAX3i78W has joined (13/16)!", 0);
        line("FZrMXU has joined (14/16)!", 200);
        assertEquals(0, full(2, 300), "the group might still grow");
        assertEquals(1, full(2, 200 + LATER));
    }

    @Test
    void onlyFullPartiesCount() {
        line("aaa has joined (5/16)!", 0);
        line("bbb has joined (6/16)!", 100);
        assertEquals(0, full(4, LATER), "two together are no full party in 4v4v4v4");

        line("ccc has joined (7/16)!", 10_000);
        line("ddd has joined (8/16)!", 10_100);
        line("eee has joined (9/16)!", 10_200);
        line("fff has joined (10/16)!", 10_300);
        assertEquals(1, full(4, 10_300 + LATER));
    }

    @Test
    void twoPartiesArrivingTogetherCountTwice() {
        line("aaa has joined (5/16)!", 0);
        line("bbb has joined (6/16)!", 100);
        line("ccc has joined (7/16)!", 200);
        line("ddd has joined (8/16)!", 300);
        assertEquals(2, full(2, 300 + LATER));
    }

    @Test
    void aMemberLeavingBreaksTheParty_andComingBackRestoresIt() {
        line("eLAX3i78W has joined (13/16)!", 0);
        line("FZrMXU has joined (14/16)!", 200);
        assertEquals(1, full(2, 200 + LATER));

        line("FZrMXU has quit!", 5_000);
        assertEquals(0, full(2, 5_000));

        line("FZrMXU has joined (14/16)!", 9_000);
        assertEquals(1, full(2, 9_000 + LATER));
    }

    @Test
    void aQuitWhileStillJoiningShrinksTheGroup() {
        line("aaa has joined (5/16)!", 0);
        line("bbb has joined (6/16)!", 100);
        line("bbb has quit!", 300);
        assertEquals(0, full(2, 300 + LATER));
    }

    @Test
    void playersFromBeforeTheJoinAreIgnored() {
        line("Milone0 has joined (13/16)!", 0);
        line("bYlGu7 has quit!", 4_000);
        assertEquals(0, full(2, 4_000 + LATER));
    }

    @Test
    void soloModesHaveNoParties() {
        line("aaa has joined (5/16)!", 0);
        line("bbb has joined (6/16)!", 100);
        assertEquals(0, full(1, LATER));
        assertEquals(0, full(0, LATER));
    }

    @Test
    void aNewWorldStartsOver() {
        Object lobby = new Object();
        this.parties.enterWorld(lobby);
        line("aaa has joined (5/16)!", 0);
        line("bbb has joined (6/16)!", 100);
        assertEquals(1, full(2, 100 + LATER));

        this.parties.enterWorld(lobby);
        assertEquals(1, full(2, 100 + LATER), "the same world keeps its count");

        this.parties.enterWorld(new Object());
        assertEquals(0, full(2, 100 + LATER));
        assertFalse(this.parties.isWaiting());
    }

    @Test
    void otherChatIsIgnored() {
        line("The game starts in 20 seconds!", 0);
        line("Hans: has joined (1/2)!", 0);
        assertFalse(this.parties.isWaiting());
    }
}
