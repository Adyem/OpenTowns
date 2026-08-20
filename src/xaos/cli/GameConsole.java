package xaos.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Reads commands from the process console without touching the game model.
 * Game.run() drains the queue on the window/game thread.
 */
public final class GameConsole implements AutoCloseable {

    private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true;
    private final Thread readerThread;
    private final Path commandFile;
    private int commandFilePosition;

    public GameConsole() {
        String configuredFile = System.getProperty("towns.cli.file");
        commandFile = configuredFile == null || configuredFile.trim().isEmpty()
                ? null : Paths.get(configuredFile).toAbsolutePath();
        readerThread = new Thread(this::readLoop, "opentowns-cli-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public String poll() {
        return commands.poll();
    }

    @Override
    public void close() {
        running = false;
        readerThread.interrupt();
    }

    private void readLoop() {
        if (commandFile != null) {
            readFileLoop();
            return;
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                commands.offer(line);
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("[TownsCLI] console input stopped: " + e.getMessage());
            }
        }
    }

    private void readFileLoop() {
        while (running) {
            try {
                if (Files.exists(commandFile)) {
                    List<String> lines = Files.readAllLines(commandFile, StandardCharsets.UTF_8);
                    while (commandFilePosition < lines.size()) {
                        commands.offer(lines.get(commandFilePosition++));
                    }
                }
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                if (running) {
                    System.err.println("[TownsCLI] file input stopped: " + e.getMessage());
                }
                return;
            }
        }
    }
}
