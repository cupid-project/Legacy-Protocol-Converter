/*
 *  Copyright (c) 2023-2026 Sunesis and/or its affiliates
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
package si.sunesis.interoperability.lpc.transformations.observability;

import io.nats.client.Connection;
import lombok.extern.slf4j.Slf4j;
import si.sunesis.interoperability.common.interfaces.RequestHandler;
import si.sunesis.interoperability.lpc.transformations.connections.Connections;
import si.sunesis.interoperability.modbus.ModbusClient;
import si.sunesis.interoperability.mqtt.Mqtt3Client;
import si.sunesis.interoperability.mqtt.Mqtt5Client;
import si.sunesis.interoperability.nats.NatsRequestHandler;
import si.sunesis.interoperability.rabbitmq.RabbitMQClient;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Derives the connection state of all configured connections for health checks and metrics.
 *
 * @author Sunesis
 * @since 1.6.0
 */
@Slf4j
public final class ConnectionStatus {

    private ConnectionStatus() {
    }

    /**
     * State of a single connection.
     */
    public enum State {
        UP, DOWN, UNKNOWN
    }

    /**
     * Returns the state of every configured connection, keyed by connection name.
     *
     * @param connections Active connections, may be null
     * @return Map of connection name to state (empty if no connections are active)
     */
    public static Map<String, State> states(Connections connections) {
        if (connections == null) {
            return Collections.emptyMap();
        }
        Map<String, State> result = new LinkedHashMap<>();
        for (Map.Entry<String, RequestHandler> entry : connections.getConnectionsMap().entrySet()) {
            result.put(entry.getKey(), stateOf(entry.getValue()));
        }
        return result;
    }

    /**
     * Returns whether a connection is a polled field-bus connection (Modbus), whose state is reported
     * but does not affect readiness.
     *
     * @param connections Active connections
     * @param name        Connection name
     * @return true for Modbus connections
     */
    public static boolean isFieldBus(Connections connections, String name) {
        RequestHandler handler = connections.getConnectionsMap().get(name);
        return handler instanceof ModbusClient;
    }

    /**
     * Counts distinct connection handlers (a handler may be registered under several names).
     *
     * @param connections Active connections, may be null
     * @return Number of distinct handlers
     */
    public static long distinctHandlers(Connections connections) {
        if (connections == null) {
            return 0;
        }
        Set<RequestHandler> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(connections.getConnectionsMap().values());
        return distinct.size();
    }

    /**
     * Counts distinct connection handlers that are currently connected.
     *
     * @param connections Active connections, may be null
     * @return Number of distinct connected handlers
     */
    public static long distinctHandlersUp(Connections connections) {
        if (connections == null) {
            return 0;
        }
        Set<RequestHandler> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        distinct.addAll(connections.getConnectionsMap().values());
        return distinct.stream().filter(h -> stateOf(h) == State.UP).count();
    }

    private static State stateOf(RequestHandler handler) {
        try {
            if (handler instanceof NatsRequestHandler) {
                return ((NatsRequestHandler) handler).getClient().getConnection()
                        .map(c -> c.getStatus() == Connection.Status.CONNECTED ? State.UP : State.DOWN)
                        .orElse(State.DOWN);
            }
            if (handler instanceof Mqtt3Client) {
                return ((Mqtt3Client) handler).getClient().isConnected() ? State.UP : State.DOWN;
            }
            if (handler instanceof Mqtt5Client) {
                return ((Mqtt5Client) handler).getClient().isConnected() ? State.UP : State.DOWN;
            }
            if (handler instanceof ModbusClient) {
                return ((ModbusClient) handler).getClient().isConnected() ? State.UP : State.DOWN;
            }
            if (handler instanceof RabbitMQClient) {
                return ((RabbitMQClient) handler).getClient().getChannel().isOpen() ? State.UP : State.DOWN;
            }
        } catch (Exception e) {
            log.debug("Could not determine connection state: {}", e.getMessage());
            return State.DOWN;
        }
        return State.UNKNOWN;
    }
}
