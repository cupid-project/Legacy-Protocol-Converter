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
package si.sunesis.interoperability.lpc.transformations.connections;

import lombok.extern.slf4j.Slf4j;
import si.sunesis.interoperability.lpc.transformations.configuration.models.ConnectionModel;
import si.sunesis.interoperability.lpc.transformations.configuration.models.MqttDeviceStatusModel;
import si.sunesis.interoperability.lpc.transformations.exceptions.LPCException;

import javax.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Owns the lifetime of all per-device MQTT status clients (Last Will and Testament + retained
 * online status) across configuration reloads.
 * <p>
 * Unlike {@link Connections}, which is rebuilt wholesale on every config reload, this bean is
 * {@code @ApplicationScoped} and survives reloads: {@link #sync} diffs the desired set of status
 * clients against the currently active ones and only creates or closes what actually changed.
 * Rebuilding status clients on every reload would open a window with no will registered at the
 * broker, and blanket-disconnect them the way {@code TransformationHandler.destroy()} does with
 * data connections.
 * <p>
 * Deliberately has no {@code @PreDestroy}: on JVM shutdown we want no graceful disconnect, so
 * every will fires at the broker and every device flips to offline on its own.
 *
 * @author David Trafela, Sunesis
 * @since 1.0.0
 */
@Slf4j
@ApplicationScoped
public class DeviceStatusManager {

    private final Map<String, Entry> active = new HashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "device-status-retry");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Reconciles the running status clients with the given connections' {@code device-status}
     * entries. Safe to call on every startup and every configuration reload.
     *
     * @param connections All connection models from the current configuration (any type; only
     *                    MQTT connections with a non-empty {@code device-status} are considered).
     * @param newConf     Mirrors {@link Connections} semantics: on first boot ({@code false}) a
     *                    client build failure is fatal; on reload ({@code true}) it is only logged.
     */
    public synchronized void sync(List<ConnectionModel> connections, boolean newConf) throws LPCException {
        Map<String, Entry> desired = new HashMap<>();

        for (ConnectionModel connection : connections) {
            if (!"MQTT".equalsIgnoreCase(connection.getType())) {
                continue;
            }

            if (connection.getDeviceStatus() == null || connection.getDeviceStatus().isEmpty()) {
                continue;
            }

            for (MqttDeviceStatusModel spec : connection.getDeviceStatus()) {
                String key = connection.getName() + "|" + spec.getDeviceId();
                desired.put(key, new Entry(connection, spec));
            }
        }

        Iterator<Map.Entry<String, Entry>> activeIterator = active.entrySet().iterator();
        while (activeIterator.hasNext()) {
            Map.Entry<String, Entry> current = activeIterator.next();
            Entry desiredEntry = desired.get(current.getKey());

            if (desiredEntry == null) {
                log.info("Device status entry {} removed from configuration, publishing offline", current.getKey());
                current.getValue().client.close(true);
                activeIterator.remove();
            } else if (!current.getValue().matchesSpec(desiredEntry)) {
                log.info("Device status entry {} configuration changed, reconnecting", current.getKey());
                current.getValue().client.close(false);
                activeIterator.remove();
            }
        }

        for (Map.Entry<String, Entry> desiredEntry : desired.entrySet()) {
            if (active.containsKey(desiredEntry.getKey())) {
                continue;
            }

            String connectionName = desiredEntry.getValue().connection.getName();
            Entry entry = desiredEntry.getValue();

            try {
                entry.client = new DeviceStatusClient(connectionName, entry.connection, entry.spec, scheduler);
                active.put(desiredEntry.getKey(), entry);
            } catch (LPCException e) {
                log.error("Error creating device status client for {}", desiredEntry.getKey(), e);
                if (!newConf) {
                    throw e;
                }
            }
        }
    }

    private static class Entry {

        private final ConnectionModel connection;
        private final MqttDeviceStatusModel spec;
        private DeviceStatusClient client;

        private Entry(ConnectionModel connection, MqttDeviceStatusModel spec) {
            this.connection = connection;
            this.spec = spec;
        }

        private boolean matchesSpec(Entry other) {
            return Objects.equals(connection, other.connection) && Objects.equals(spec, other.spec);
        }
    }
}
