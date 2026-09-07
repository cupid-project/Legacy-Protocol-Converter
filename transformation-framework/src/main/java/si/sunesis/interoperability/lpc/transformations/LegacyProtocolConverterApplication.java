/*
 *  Copyright (c) 2023-2024 Sunesis and/or its affiliates
 *  and other contributors as indicated by the @author tags and
 *  the contributor list.
 *
 *  Licensed under the MIT License (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://opensource.org/licenses/MIT
 *
 *  The software is provided "AS IS", WITHOUT WARRANTY OF ANY KIND, express or
 *  implied, including but not limited to the warranties of merchantability,
 *  fitness for a particular purpose and noninfringement. in no event shall the
 *  authors or copyright holders be liable for any claim, damages or other
 *  liability, whether in an action of contract, tort or otherwise, arising from,
 *  out of or in connection with the software or the use or other dealings in the
 *  software. See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package si.sunesis.interoperability.lpc.transformations;

import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;
import si.sunesis.interoperability.lpc.transformations.exceptions.LPCException;
import si.sunesis.interoperability.lpc.transformations.transformation.TransformationsHandler;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.ws.rs.ApplicationPath;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Main application class for the Legacy Protocol Converter.
 * Initializes REST API endpoints, manages Python process for Modbus operations,
 * and starts the transformation handling service.
 *
 * @author David Trafela, Sunesis
 * @since 1.0.1
 */
@Slf4j
@ApplicationPath("/")
public class LegacyProtocolConverterApplication extends ResourceConfig {

    /** Delay before relaunching the Python API process after it exits or fails to start. */
    private static final long PYTHON_RESTART_DELAY_MS = 5000L;
    /** Grace period for the Python process to stop after SIGTERM before it is killed forcibly. */
    private static final long PYTHON_TERMINATION_TIMEOUT_S = 5L;
    /** How long the JVM shutdown hook waits for the supervisor thread to unwind. */
    private static final long SHUTDOWN_JOIN_TIMEOUT_MS = 10000L;

    private Process pythonProcess = null;

    @Inject
    private TransformationsHandler handler;

    /**
     * Constructor that initializes the REST API.
     * Configures Jersey settings, sets up resource packages, and registers features.
     */
    public LegacyProtocolConverterApplication() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("jersey.config.server.wadl.disableWadl", "true");
        setProperties(properties);
        packages("si.sunesis.interoperability.lpc.transformations");
        register(MultiPartFeature.class);
    }

    /**
     * Initialization method that runs after dependency injection is complete.
     * Starts the Python Modbus service and initializes transformation handling.
     */
    @PostConstruct
    public void init() {
        pythonHandler();

        try {
            this.handler.startHandling();
        } catch (LPCException e) {
            log.error("Failed to start handling transformations", e);
            System.exit(1);
        }
    }

    /**
     * Manages the Python process used for Modbus operations.
     * Launches a Python script in a separate process, drains its output streams,
     * and relaunches it if it exits while the JVM is still running. A JVM shutdown
     * hook interrupts the supervisor, which is the normal way it stops.
     */
    private void pythonHandler() {
        Thread thread = new Thread(() -> {
            String port = System.getenv("PYTHON_PORT") != null ? System.getenv("PYTHON_PORT") : "9093";
            String[] cmd = {"python3", "pymodbus_script.py", "--api", "--api_port", port};

            ProcessBuilder processBuilder = new ProcessBuilder(cmd);

            // One daemon pool reused for every (re)start. The previous code created two non-daemon
            // single-thread executors per loop iteration and never shut them down, leaking threads
            // whenever the Python process was relaunched.
            ExecutorService gobblerPool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "python-api-stream-gobbler");
                t.setDaemon(true);
                return t;
            });

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        log.info("Starting Python API server...");
                        pythonProcess = processBuilder.start();

                        gobblerPool.submit(new StreamGobbler(pythonProcess.getInputStream(),
                                s -> log.debug("Python API InputStream output: {}", s)));
                        gobblerPool.submit(new StreamGobbler(pythonProcess.getErrorStream(),
                                s -> log.debug("Python API ErrorStream output: {}", s)));

                        int exitCode = pythonProcess.waitFor();

                        if (Thread.currentThread().isInterrupted()) {
                            break;
                        }

                        if (exitCode == 0) {
                            log.info("Python API process exited (code 0), restarting in {} ms", PYTHON_RESTART_DELAY_MS);
                        } else {
                            log.warn("Python API process exited with code {}, restarting in {} ms", exitCode, PYTHON_RESTART_DELAY_MS);
                        }

                        Thread.sleep(PYTHON_RESTART_DELAY_MS);
                    } catch (InterruptedException e) {
                        log.info("Python API supervisor interrupted, shutting down.");
                        Thread.currentThread().interrupt(); // Restore the flag; the while condition ends the loop
                    } catch (Exception e) {
                        log.error("Error starting Python API, retrying in {} ms", PYTHON_RESTART_DELAY_MS, e);
                        try {
                            Thread.sleep(PYTHON_RESTART_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            } finally {
                if (pythonProcess != null && pythonProcess.isAlive()) {
                    pythonProcess.destroy();
                    try {
                        if (!pythonProcess.waitFor(PYTHON_TERMINATION_TIMEOUT_S, TimeUnit.SECONDS)) {
                            pythonProcess.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        pythonProcess.destroyForcibly();
                        Thread.currentThread().interrupt();
                    }
                }
                gobblerPool.shutdownNow();
                log.info("Python API supervisor thread exited.");
            }
        }, "python-api-supervisor");

        thread.setDaemon(false); // Non-daemon: the finally block must run to reap the Python process
        thread.start();

        // Register JVM shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Stopping Python supervisor thread...");
            thread.interrupt();
            try {
                thread.join(SHUTDOWN_JOIN_TIMEOUT_MS);
                if (thread.isAlive()) {
                    log.warn("Python supervisor thread did not exit within {} ms", SHUTDOWN_JOIN_TIMEOUT_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "python-supervisor-shutdown"));
    }

    /**
     * Utility class for consuming output streams from subprocess.
     * Captures and processes output from Python process streams.
     * <p>
     * A plain class rather than a record: the CDI/Weld version bundled with this
     * KumuluzEE release fails to scan a JAX-RS Application class that contains a
     * nested record (NoClassDefFoundError: record), which took down the entire
     * web layer.
     */
    private static final class StreamGobbler implements Runnable {
        private final InputStream inputStream;
        private final Consumer<String> consumer;

        private StreamGobbler(InputStream inputStream, Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer = consumer;
        }

        /**
         * Reads input streamline by line and processes each line with the consumer.
         */
        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines()
                    .forEach(consumer);
        }
    }
}
