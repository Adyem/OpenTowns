package xaos.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import xaos.cli.GameCommandShell;
import xaos.cli.GameCommandProtocol;
import xaos.main.Game;
import xaos.main.World;
import xaos.utils.AStarQueue;
import xaos.utils.Utils;
import xaos.tiles.entities.living.LivingEntity;

/** In-JVM coverage for the public command API used by TownsHeadless. */
class GameCommandShellTest {

    private static Path userFolder;
    private static GameCommandShell shell;

    @BeforeAll
    static void boot() throws Exception {
        userFolder = HeadlessRunner.newUserFolder();
        Game.initHeadless(userFolder.toString());
        AStarQueue.setSynchronousMode(true);
        Utils.setRandomSeed(42);
        Game.startGame("c1", "normal");
        shell = new GameCommandShell(Game.getWorld());
    }

    @AfterAll
    static void cleanup() {
        HeadlessRunner.deleteRecursively(userFolder);
    }

    @Test
    void helpAndValidationAreAvailable() {
        GameCommandShell.CommandResult help = shell.execute("help");
        assertTrue(help.isSuccessful());
        assertTrue(help.getMessage().contains("mine-area"));
        assertFalse(shell.execute("tick -1").isSuccessful());
        assertFalse(shell.execute("unknown-command").isSuccessful());
    }

    @Test
    void tickCommandAdvancesOnlyTheRequestedAmount() {
        long before = shell.getTicksAdvanced();
        assertTrue(shell.execute("tick 3").isSuccessful());
        assertEquals(before + 3, shell.getTicksAdvanced());
    }

    @Test
    void mineCommandUsesTheGameOrderPipeline() {
        LivingEntity startingCitizen = World.getLivingEntityByID(World.getCitizenIDs().get(0));
        int x = startingCitizen.getCoordinates().x;
        int y = startingCitizen.getCoordinates().y;
        int z = startingCitizen.getCoordinates().z + 1;
        GameCommandShell.CommandResult result = shell.execute("mine " + x + " " + y + " " + z);
        assertTrue(result.isSuccessful(), result.getMessage());
        assertTrue(World.getCell(x, y, z).isFlagOrders());
        assertTrue(shell.execute("tick 1").isSuccessful());
        assertTrue(shell.execute("status").getMessage().contains("citizens="));
    }

    @Test
    void villageSetupUsesStartingGroundAndConfiguresTheStarterChain() {
        GameCommandShell.CommandResult result = shell.execute("setup village");
        assertTrue(result.isSuccessful(), result.getMessage());
        assertTrue(result.getMessage().contains("orchard=40 tiles"));

        String zones = shell.execute("zones").getMessage();
        assertTrue(zones.contains("type=zdining"));
        assertTrue(zones.contains("type=zcarpentry"));
        assertTrue(zones.contains("type=zmasonry"));
        assertTrue(zones.contains("type=zbakery"));

        String stockpiles = shell.execute("stockpiles").getMessage();
        assertTrue(stockpiles.contains("type=prepfood"));
        assertTrue(stockpiles.contains("type=rawfood"));
        assertTrue(stockpiles.contains("type=rawmaterials"));

        String progress = shell.execute("progress").getMessage();
        assertTrue(progress.contains("storage=prepfood"));
        assertTrue(progress.contains("rawfood(wheat:0/10 flour:0/10)"));
        assertTrue(shell.execute("queues summary").isSuccessful());

        assertTrue(shell.execute("automation qharvestapple").getMessage().contains("minimum=20"));
        assertTrue(shell.execute("automation qharvestpear").getMessage().contains("minimum=20"));
        assertTrue(shell.execute("automation qharvestwildwheat").getMessage().contains("minimum=10"));
        assertTrue(shell.execute("automation qflour").getMessage().contains("minimum=10"));
        assertTrue(shell.execute("automation qbread").getMessage().contains("minimum=10"));
        assertFalse(shell.execute("food-ready").isSuccessful());
    }

    @Test
    void jsonProtocolProvidesTypedObservationAndAssertions() {
        GameCommandProtocol protocol = new GameCommandProtocol(shell);
        String capabilities = protocol.execute("{\"id\":\"c1\",\"op\":\"capabilities\"}");
        assertTrue(capabilities.contains("\"ok\":true"));
        assertTrue(capabilities.contains("\"observe\""));

        String observation = protocol.execute("{\"id\":\"o1\",\"op\":\"observe\"}");
        assertTrue(observation.contains("\"summary\""));
        String assertion = protocol.execute("{\"id\":\"a1\",\"op\":\"assert\",\"args\":{\"condition\":{\"path\":\"summary.citizens\",\"op\":\"gte\",\"value\":0}}}");
        assertTrue(assertion.contains("\"passed\":true"), assertion);
    }

    @Test
    void jsonProtocolKeepsFramingAfterMalformedInputAndSupportsRevisionGuards() {
        GameCommandProtocol protocol = new GameCommandProtocol(shell);
        String malformed = protocol.execute("{not-json");
        assertTrue(malformed.contains("\"ok\":false"));
        String next = protocol.execute("{\"id\":\"next\",\"op\":\"capabilities\"}");
        assertTrue(next.contains("\"id\":\"next\""));
        String stale = protocol.execute("{\"id\":\"stale\",\"op\":\"observe\",\"if_revision\":999}");
        assertTrue(stale.contains("STALE_REVISION"), stale);
    }

    @Test
    void jsonProtocolRecordsCanonicalTranscript() throws Exception {
        Path transcript = Files.createTempFile("opentowns-protocol-", ".ndjson");
        GameCommandProtocol protocol = new GameCommandProtocol(shell, transcript);
        protocol.execute("{\"id\":\"t1\",\"op\":\"capabilities\"}");
        protocol.closeTranscript();
        assertTrue(Files.readAllLines(transcript).size() >= 3);
        Files.deleteIfExists(transcript);
    }

    @Test
    void jsonObservationUsesTypedSpatialRecordsAndEventConditions() {
        GameCommandProtocol protocol = new GameCommandProtocol(shell);
        LivingEntity citizen = World.getLivingEntityByID(World.getCitizenIDs().get(0));
        int x=citizen.getCoordinates().x, y=citizen.getCoordinates().y, z=citizen.getCoordinates().z;
        String request = "{\"id\":\"spatial\",\"op\":\"observe\",\"args\":{\"include\":[\"citizens\"],\"near\":{\"x\":"+x+",\"y\":"+y+",\"z\":"+z+",\"radius\":0}}}";
        String observation=protocol.execute(request);
        assertTrue(observation.contains("\"position\""), observation);
        String action=protocol.execute("{\"id\":\"tagged\",\"op\":\"act\",\"args\":{\"action\":\"mine\",\"target\":{\"x\":"+x+",\"y\":"+y+",\"z\":"+z+"},\"client_tag\":\"probe\",\"dry_run\":true}}");
        assertTrue(action.contains("\"dry_run\":true"), action);
    }

    @Test
    void jsonObservationRetainsTypedRevisionDeltas() {
        GameCommandProtocol protocol = new GameCommandProtocol(shell);
        String first = protocol.execute("{\"id\":\"full\",\"op\":\"observe\",\"args\":{\"include\":[\"citizens\"]}}");
        Map<?,?> firstEnvelope=(Map<?,?>)GameCommandProtocol.parseJson(first);
        long revision=((Number)firstEnvelope.get("revision")).longValue();
        protocol.execute("{\"id\":\"advance\",\"op\":\"advance\",\"args\":{\"max_ticks\":1}}");
        String changed=protocol.execute("{\"id\":\"delta\",\"op\":\"observe\",\"args\":{\"include\":[\"citizens\"],\"since_revision\":"+revision+"}}");
        assertTrue(changed.contains("\"from_revision\":"+revision), changed);
        assertTrue(changed.contains("\"added\""), changed);
        assertTrue(changed.contains("\"updated\""), changed);
        assertTrue(changed.contains("\"removed\""), changed);
    }
}
