package tabstats.shout;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoutTextTest {

    @Test
    void plainDropsColoursAndEverythingOutsideAscii() {
        assertEquals("412", ShoutText.plain("§7§l4§61§a2§d✫"));
        assertEquals("3.45", ShoutText.plain("§f3.45"));
        assertEquals("", ShoutText.plain(null));
        assertEquals("a b", ShoutText.plain("  a §  b "));
    }

    @Test
    void numberReadsTheFirstNumber() {
        assertEquals(412.0, ShoutText.number("412"));
        assertEquals(3.45, ShoutText.number("3.45"));
        assertNull(ShoutText.number("-"));
        assertNull(ShoutText.number(""));
    }

    @Test
    void aTeamPrefixIsABoldLetterInTheTeamColour() {
        assertEquals("Red", ShoutText.teamFromPrefix("§c§lR §c"));
        assertEquals("Blue", ShoutText.teamFromPrefix("§9§lB §9"));
        assertEquals("Gray", ShoutText.teamFromPrefix("§8§lS §8"));
    }

    @Test
    void aRankPrefixIsNoTeam() {
        assertNull(ShoutText.teamFromPrefix("§b[MVP§c+§b] "));
        assertNull(ShoutText.teamFromPrefix("§7"));
        assertNull(ShoutText.teamFromPrefix(""));
        assertNull(ShoutText.teamFromPrefix(null));
    }

    @Test
    void teamWordsIgnoreCase() {
        assertEquals("Red", ShoutText.teamFromWord("RED"));
        assertEquals("Gray", ShoutText.teamFromWord("grey"));
        assertNull(ShoutText.teamFromWord("Hans"));
    }

    @Test
    void aLineIsTheTeamThenThePlayer() {
        assertEquals("Red: Hans STAR:412", ShoutText.line("Red", "Hans STAR:412", 100));
        assertEquals("Hans STAR:412", ShoutText.line(null, "Hans STAR:412", 100));
        assertEquals("Red: Hans", ShoutText.line("Red", "Hans STAR:412", 9));
    }

    @Test
    void oneTeamFitsIntoOneMessageWhileItCan() {
        List<String> messages = ShoutText.pack("Red", Arrays.asList("Hans STAR:412", "Klaus STAR:12"), 100);
        assertEquals(Arrays.asList("Red: Hans STAR:412 | Klaus STAR:12"), messages);
    }

    @Test
    void aTeamTooLongForOneMessageContinuesUnderItsLabel() {
        List<String> messages = ShoutText.pack("Red", Arrays.asList("aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc"), 30);
        assertEquals(Arrays.asList("Red: aaaaaaaaaa | bbbbbbbbbb", "Red: cccccccccc"), messages);
        for (String message : messages) {
            assertTrue(message.length() <= 30, message);
        }
    }

    @Test
    void aSinglePlayerLongerThanAMessageIsCut() {
        List<String> messages = ShoutText.pack("Red", Arrays.asList("x123456789012345678901234567890"), 20);
        assertEquals(1, messages.size());
        assertEquals(20, messages.get(0).length());
    }

    @Test
    void playersWithoutATeamGetNoLabel() {
        assertEquals(Arrays.asList("Hans | Klaus"), ShoutText.pack(null, Arrays.asList("Hans", "Klaus"), 100));
    }

    @Test
    void mergeJoinsMessagesWhileTheyFit() {
        List<String> merged = ShoutText.merge(Arrays.asList("Red: a", "Blue: b", "Green: cccccccccc"), " // ", 20);
        assertEquals(Arrays.asList("Red: a // Blue: b", "Green: cccccccccc"), merged);
    }
}
