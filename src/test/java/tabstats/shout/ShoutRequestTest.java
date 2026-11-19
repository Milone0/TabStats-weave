package tabstats.shout;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoutRequestTest {

    @Test
    void nothingNamedMeansTheGoodPlayers() {
        ShoutRequest request = ShoutRequest.parse(new String[0]);
        assertTrue(request.isGoodOnly());
        assertFalse(request.isEveryone());
        assertNull(request.getError());
    }

    @Test
    void allAndDotMeanEveryone() {
        assertTrue(ShoutRequest.parse(new String[]{"all"}).isEveryone());
        assertTrue(ShoutRequest.parse(new String[]{"."}).isEveryone());
        assertFalse(ShoutRequest.parse(new String[]{"ALL"}).isGoodOnly());
    }

    @Test
    void teamsAndNamesCanBeMixed() {
        ShoutRequest request = ShoutRequest.parse(new String[]{"red", "Hans", "Blue", "hans"});
        assertEquals(Arrays.asList("Red", "Blue"), Arrays.asList(request.getTeams().toArray()));
        assertEquals(Collections.singletonList("Hans"), request.getNames());
        assertFalse(request.isGoodOnly());
    }

    @Test
    void aWordThatIsNeitherTeamNorNameIsAnError() {
        assertNotNull(ShoutRequest.parse(new String[]{"rot!"}).getError());
    }

    @Test
    void theShoutFormIsRecognised() {
        assertArrayEquals(new String[0], ShoutRequest.fromShoutCommand("/shout /bw shout"));
        assertArrayEquals(new String[]{"red"}, ShoutRequest.fromShoutCommand("  /SHOUT /bw Shout red "));
        assertNull(ShoutRequest.fromShoutCommand("/shout hello everyone"));
        assertNull(ShoutRequest.fromShoutCommand("/bw shout"));
        assertNull(ShoutRequest.fromShoutCommand("/shout /bw"));
    }
}
