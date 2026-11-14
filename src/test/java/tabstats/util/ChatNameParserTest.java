package tabstats.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatNameParserTest {
    private static final String BAR = "▬▬▬▬▬▬▬▬▬▬▬▬";

    private static void assertReveal(ChatNameParser.Kind kind, String name, String line) {
        ChatNameParser.Reveal reveal = ChatNameParser.parse(line);
        assertNotNull(reveal, line);
        assertEquals(kind, reveal.getKind(), line);
        assertEquals(name, reveal.getName(), line);
    }

    @Test
    void aChatLineRevealsItsSender() {
        assertReveal(ChatNameParser.Kind.REVEAL, "Hans", "§7Hans§7: hello");
        assertReveal(ChatNameParser.Kind.REVEAL, "Klaus_99", "§b[MVP§c+§b] Klaus_99§f: gg");
    }

    @Test
    void levelAndGuildTagsAreNotTheName() {
        assertReveal(ChatNameParser.Kind.REVEAL, "Hans", "[123✫] [VIP] Hans [GUILD]: hi");
    }

    @Test
    void onlyTheFirstColonSplitsSenderFromMessage() {
        assertReveal(ChatNameParser.Kind.REVEAL, "Hans", "Hans: look: a colon");
    }

    @Test
    void sendersOutsideTheLobbyAreIgnored() {
        assertNull(ChatNameParser.parse("§9Party §8> §b[MVP+] Hans§f: hello"));
        assertNull(ChatNameParser.parse("Guild > Hans [Officer]: hello"));
        assertNull(ChatNameParser.parse("From [VIP] Hans: hello"));
        assertNull(ChatNameParser.parse("To Hans: hello"));
    }

    @Test
    void serverLabelsAndNpcsAreNotPlayers() {
        assertNull(ChatNameParser.parse("Store: store.hypixel.net"));
        assertNull(ChatNameParser.parse("[NPC] Dave: Welcome!"));
        assertNull(ChatNameParser.parse("Bed Wars: something happened"));
    }

    @Test
    void whatIsNotShapedLikeANameIsNotOne() {
        assertNull(ChatNameParser.parse("ab: too short"));
        assertNull(ChatNameParser.parse("ThisNameIsFarTooLong: hello"));
        assertNull(ChatNameParser.parse("no colon here"));
        assertNull(ChatNameParser.parse(": starts with the colon"));
        assertNull(ChatNameParser.parse("Hans has joined (3/16)!"));
    }

    @Test
    void emptyLinesSayNothing() {
        assertNull(ChatNameParser.parse(null));
        assertNull(ChatNameParser.parse(""));
        assertNull(ChatNameParser.parse("§r   §r"));
    }

    @Test
    void aQuitLineHidesThePlayerAgain() {
        assertReveal(ChatNameParser.Kind.HIDE, "Hans", "§7Hans §ehas quit!");
        assertReveal(ChatNameParser.Kind.HIDE, "Hans", "[MVP+] Hans has quit!");
        assertNull(ChatNameParser.parse("Two Words has quit!"));
    }

    @Test
    void theSummaryBarClearsEveryone() {
        assertReveal(ChatNameParser.Kind.CLEAR, null, "§a§l" + BAR);
        assertReveal(ChatNameParser.Kind.CLEAR, null, "  " + BAR + "  ");
        assertNull(ChatNameParser.parse("▬▬▬"));
    }
}
