package tabstats.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypixelLocationTest {
    private static final String GAME =
            "{\"server\":\"mini121A\",\"gametype\":\"BEDWARS\",\"mode\":\"BEDWARS_EIGHT_ONE\",\"map\":\"Lighthouse\"}";
    private static final String PRE_GAME = "{\"server\":\"mini121A\",\"gametype\":\"BEDWARS\"}";
    private static final String GAME_LOBBY =
            "{\"server\":\"dynamiclobby12A\",\"gametype\":\"BEDWARS\",\"lobbyname\":\"bedwarslobby12\"}";
    private static final String MODE_LOBBY = "{\"server\":\"lobby3\",\"gametype\":\"DUELS\",\"mode\":\"LOBBY\"}";
    private static final String MAIN_LOBBY = "{\"server\":\"lobby7\",\"gametype\":\"MAIN\",\"lobbyname\":\"mainlobby7\"}";
    private static final String LIMBO = "{\"server\":\"limbo\"}";

    @BeforeEach
    void joinAWorld() {
        HypixelLocation.reset();
    }

    /** Answers the request the mod sent for the current world. */
    private static boolean answer(String json) {
        HypixelLocation.noteRequest(System.currentTimeMillis());
        return HypixelLocation.handleChatMessage(json);
    }

    @Test
    void theModsOwnAnswerIsHidden() {
        assertTrue(answer(GAME));
        assertTrue(HypixelLocation.handleChatMessage("§f" + GAME), "a late duplicate as well");
    }

    @Test
    void ordinaryChatIsLeftAlone() {
        HypixelLocation.noteRequest(System.currentTimeMillis());

        assertFalse(HypixelLocation.handleChatMessage("Hans: hello"));
        assertFalse(HypixelLocation.handleChatMessage("{\"not\":\"locraw\"}"));
        assertFalse(HypixelLocation.handleChatMessage("Hans: {\"server\":\"fake\"}"));
        assertFalse(HypixelLocation.handleChatMessage("{\"server\": broken"));
        assertFalse(HypixelLocation.handleChatMessage(null));
        assertFalse(HypixelLocation.inGame());
    }

    @Test
    void gamesAndPreGameLobbiesCount() {
        answer(GAME);
        assertTrue(HypixelLocation.inGame());

        answer(PRE_GAME);
        assertTrue(HypixelLocation.inGame(), "a pre-game lobby names no mode or map yet");
    }

    @Test
    void lobbiesDoNotCount() {
        answer(GAME_LOBBY);
        assertFalse(HypixelLocation.inGame());

        answer(MODE_LOBBY);
        assertFalse(HypixelLocation.inGame());

        answer(MAIN_LOBBY);
        assertFalse(HypixelLocation.inGame());

        answer(LIMBO);
        assertFalse(HypixelLocation.inGame());
    }

    @Test
    void anAnswerFromBeforeTheRequestBelongsToTheOldWorld() {
        assertTrue(HypixelLocation.handleChatMessage(GAME), "hidden all the same");
        assertFalse(HypixelLocation.inGame());
    }

    @Test
    void aNewWorldForgetsTheOldAnswer() {
        answer(GAME);
        HypixelLocation.reset();

        assertFalse(HypixelLocation.inGame());
    }

    @Test
    void theScoreboardDecidesOnceHypixelNeverAnswers() {
        long longAgo = System.currentTimeMillis() - 60_000L;
        HypixelLocation.noteRequest(longAgo);
        HypixelLocation.noteRequest(longAgo);
        assertFalse(HypixelLocation.gaveUp(), "one attempt is still left");
        assertFalse(HypixelLocation.inGame());

        HypixelLocation.noteRequest(longAgo);
        assertTrue(HypixelLocation.gaveUp());
        assertTrue(HypixelLocation.inGame());
    }

    @Test
    void aLocrawTypedByThePlayerShowsItsAnswer() {
        HypixelLocation.handleSentMessage(" /locraw ");

        assertFalse(answer(GAME), "the player asked, so the player sees it");
        assertTrue(HypixelLocation.inGame(), "and the mod reads it as well");
        assertTrue(answer(GAME), "the next one is the mod's own again");
    }

    @Test
    void otherCommandsDoNotOpenTheChat() {
        HypixelLocation.handleSentMessage("/locraw2");
        HypixelLocation.handleSentMessage("locraw");
        HypixelLocation.handleSentMessage(null);

        assertTrue(answer(GAME));
    }
}
