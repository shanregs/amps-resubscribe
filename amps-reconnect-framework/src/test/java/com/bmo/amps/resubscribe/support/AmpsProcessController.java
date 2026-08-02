package com.bmo.amps.resubscribe.support;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts/stops the two local AMPS instances described in
 * {@code docs/HA_CLIENT_TEST_MATRIX_STEP_BY_STEP.md} (ports/paths taken from the "Environment"
 * sheet of {@code docs/HA_CLIENT_TEST_MATRIX_STEP_BY_STEP.xlsx}), so
 * {@code HAClientTestMatrixTests} can automate "Stop Primary" / "Start Secondary" style steps
 * instead of requiring a human to run them by hand for every test run.
 *
 * <p>All hosts/ports/folders/commands are overridable via system properties
 * ({@code -Damps.test.primary.port=9007 ...}) — the defaults match this project's dev environment
 * (WSL2 on Windows, AMPS unpacked under {@code /home/shan/AMPS_PRIMARY} and
 * {@code /home/shan/AMPS_SECONDARY}, each with {@code bin/ampServer} and {@code config.xml} per
 * {@code docs/HA_AMPS_LAB_SETUP_GUIDE.md}). <b>Verify/override the defaults for your actual setup
 * before enabling {@code HAClientTestMatrixTests}</b> — this class runs real OS commands that stop
 * and start real processes.
 */
public final class AmpsProcessController {

    private static final Logger log = LoggerFactory.getLogger(AmpsProcessController.class);

    private final String primaryHost = System.getProperty("amps.test.primary.host", "localhost");
    private final int primaryPort = Integer.getInteger("amps.test.primary.port", 9007);
    private final String secondaryHost = System.getProperty("amps.test.secondary.host", "localhost");
    private final int secondaryPort = Integer.getInteger("amps.test.secondary.port", 9017);

    private final String primaryFolder = System.getProperty("amps.test.primary.folder", "/home/shan/AMPS_PRIMARY");
    private final String secondaryFolder =
            System.getProperty("amps.test.secondary.folder", "/home/shan/AMPS_SECONDARY");

    private final String startPrimaryCmd = System.getProperty("amps.test.primary.startCmd", defaultStartCmd(primaryFolder));
    private final String stopPrimaryCmd = System.getProperty("amps.test.primary.stopCmd", defaultStopCmd(primaryFolder));
    private final String startSecondaryCmd =
            System.getProperty("amps.test.secondary.startCmd", defaultStartCmd(secondaryFolder));
    private final String stopSecondaryCmd = System.getProperty("amps.test.secondary.stopCmd", defaultStopCmd(secondaryFolder));

    // Set -Damps.test.useWsl=false if these tests are run from inside WSL directly (mvn invoked
    // from a WSL shell) rather than from Windows reaching into WSL.
    private final boolean useWsl = Boolean.parseBoolean(System.getProperty("amps.test.useWsl", "true"));

    private static String defaultStartCmd(String folder) {
        return "cd " + folder + " && nohup ./bin/ampServer ./config.xml > logs/stdout.log 2>&1 & disown";
    }

    private static String defaultStopCmd(String folder) {
        return "pkill -f '" + folder + "/config.xml'";
    }

    public void startPrimary() throws IOException, InterruptedException {
        run(startPrimaryCmd);
        waitForPortOpen(primaryHost, primaryPort);
    }

    public void stopPrimary() throws IOException, InterruptedException {
        run(stopPrimaryCmd);
        waitForPortClosed(primaryHost, primaryPort);
    }

    public void startSecondary() throws IOException, InterruptedException {
        run(startSecondaryCmd);
        waitForPortOpen(secondaryHost, secondaryPort);
    }

    public void stopSecondary() throws IOException, InterruptedException {
        run(stopSecondaryCmd);
        waitForPortClosed(secondaryHost, secondaryPort);
    }

    public boolean isPrimaryUp() {
        return isPortOpen(primaryHost, primaryPort);
    }

    public boolean isSecondaryUp() {
        return isPortOpen(secondaryHost, secondaryPort);
    }

    private void run(String bashCommand) throws IOException, InterruptedException {
        ProcessBuilder builder = useWsl
                ? new ProcessBuilder("wsl.exe", "bash", "-lc", bashCommand)
                : new ProcessBuilder("bash", "-lc", bashCommand);
        log.info("Running: {}", String.join(" ", builder.command()));
        Process process = builder.redirectErrorStream(true).start();
        process.getInputStream().transferTo(System.out);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // pkill legitimately exits non-zero when there was nothing to kill — don't fail on that
            // alone, the port-state assertions that follow are the real check.
            log.warn("Command exited with status {}: {}", exitCode, bashCommand);
        }
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void waitForPortOpen(String host, int port) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> isPortOpen(host, port));
    }

    private static void waitForPortClosed(String host, int port) {
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> !isPortOpen(host, port));
    }
}
