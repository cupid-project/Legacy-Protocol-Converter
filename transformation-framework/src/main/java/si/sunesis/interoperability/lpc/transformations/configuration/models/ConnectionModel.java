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
package si.sunesis.interoperability.lpc.transformations.configuration.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a connection configuration for various protocol types.
 * Supports multiple protocols including NATS, MQTT, RabbitMQ, and Modbus.
 * Contains all settings needed to establish connections to external systems.
 *
 * @author David Trafela, Sunesis
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode
public class ConnectionModel {

    // Common connection parameters
    /**
     * Unique name identifying this connection
     */
    @EqualsAndHashCode.Exclude
    private String name;

    /**
     * Connection protocol type (e.g., NATS, MQTT, Modbus, RabbitMQ)
     */
    private String type;

    /**
     * Hostname or IP address of the remote server
     */
    private String host;

    /**
     * Port number for the connection
     */
    private Integer port;

    /**
     * Username for authentication
     */
    private String username;

    /**
     * Password for authentication
     */
    private String password;

    /**
     * SSL/TLS configuration for secure connections
     */
    private SslModel ssl = new SslModel();

    /**
     * Whether to automatically reconnect if connection is lost
     */
    @EqualsAndHashCode.Exclude
    private Boolean reconnect = false;

    // MQTT specific parameters
    /**
     * MQTT protocol version (3 or 5)
     */
    private Integer version = 5;

    /**
     * MQTT session persistence, mapping to MQTT v5 Clean Start and MQTT v3 Clean Session.
     * <p>
     * When {@code false}, the broker keeps this client's session - and therefore its subscriptions - across
     * reconnects, so message-triggered transformations keep working after a connection blip without an application
     * restart. When {@code true}, every (re)connect starts a fresh, empty session (Paho's own default), which
     * silently drops all subscriptions on the first disconnect even though {@code reconnect} restores the socket.
     * <p>
     * If not set: defaults to {@code false} when {@link #reconnect} is enabled, {@code true} otherwise.
     */
    @JsonProperty("clean-start")
    private Boolean cleanStart;

    /**
     * MQTT v5 only. Seconds the broker retains the session after the client disconnects - i.e. the longest
     * connection outage that can be bridged without losing subscriptions (and QoS 1 messages queued during the
     * outage). Ignored when the effective clean-start is {@code true}, and for MQTT v3 (whose session lifetime is
     * broker-controlled). Defaults to {@code 86400} (24 h) when a persistent session is used and no value is given.
     */
    @JsonProperty("session-expiry")
    private Long sessionExpiry;

    /**
     * Per-device Last Will and Testament / retained online-offline status configuration.
     * One extra MQTT client is opened per entry, since a single MQTT connection can carry
     * only one will but may front multiple devices.
     */
    @EqualsAndHashCode.Exclude
    @JsonProperty("device-status")
    private List<MqttDeviceStatusModel> deviceStatus = new ArrayList<>();

    // RabbitMQ specific parameters
    /**
     * RabbitMQ virtual host path
     */
    @JsonProperty("virtual-host")
    private String virtualHost = "/";

    /**
     * RabbitMQ exchange name
     */
    @JsonProperty("exchange-name")
    private String exchangeName;

    /**
     * RabbitMQ routing key for message routing
     */
    @JsonProperty("routing-key")
    private String routingKey = "";

    /**
     * RabbitMQ exchange type (direct, fanout, topic, headers)
     */
    @JsonProperty("exchange-type")
    private String exchangeType = "direct";

    // Modbus specific parameters
    /**
     * Serial device path for Modbus RTU connections
     */
    private String device;

    /**
     * Serial port baud rate for Modbus RTU
     */
    @JsonProperty("baud-rate")
    private Integer baudRate;

    /**
     * Number of data bits for serial communication
     */
    @JsonProperty("data-bits")
    private Integer dataBits;

    /**
     * Parity mode for serial communication (none, even, odd, mark, space)
     */
    private String parity;

    /**
     * Number of stop bits for serial communication
     */
    @JsonProperty("stop-bits")
    private Integer stopBits;

    // NATS specific parameters
    /**
     * Maximum number of pings without a response before considering the connection lost
     */
    @JsonProperty("max-pings-out")
    private Integer maxPingsOut;

    /**
     * Interval in milliseconds between pings to the server
     */
    @JsonProperty("ping-interval")
    private Integer pingInterval;

    /**
     * Interval in milliseconds to clean up old requests
     */
    @JsonProperty("request-cleanup-interval")
    private Integer requestCleanupInterval;

    /**
     * Connection timeout in milliseconds
     */
    @JsonProperty("connection-timeout")
    private Integer connectionTimeout;

    /**
     * Size of the buffer for reconnecting in bytes
     */
    @JsonProperty("reconnect-buffer-size")
    private Integer reconnectBufferSize;

    /**
     * Wait time in milliseconds before attempting to reconnect
     */
    @JsonProperty("reconnect-wait")
    private Integer reconnectWait;

    /**
     * Maximum number of reconnect attempts
     */
    @JsonProperty("max-reconnects")
    private Integer maxReconnects;

    /**
     * Jitter random delay to apply to reconnect wait time
     */
    @JsonProperty("reconnect-jitter")
    private Integer reconnectJitter;

    /**
     * Jitter random delay to apply to reconnect wait time when using TLS
     */
    @JsonProperty("reconnect-jitter-tls")
    private Integer reconnectJitterTls;

    /**
     * Default MQTT v5 session-expiry (24 h) applied when a persistent session is used and no
     * {@code session-expiry} is configured.
     */
    public static final long DEFAULT_SESSION_EXPIRY_SECONDS = 86_400L;

    /**
     * Effective MQTT session persistence: the explicit {@link #cleanStart} when set, otherwise persistent
     * (non-clean) exactly when {@link #reconnect} is enabled. See {@link #cleanStart}.
     *
     * @return {@code true} when the broker should keep the session across reconnects
     */
    public boolean isPersistentSession() {
        return cleanStart != null ? !cleanStart : Boolean.TRUE.equals(reconnect);
    }

    /**
     * Effective MQTT v5 session-expiry in seconds: the configured {@link #sessionExpiry} when set, otherwise
     * {@link #DEFAULT_SESSION_EXPIRY_SECONDS}. Only meaningful when {@link #isPersistentSession()} is {@code true}.
     *
     * @return session-expiry interval in seconds
     */
    public long resolveSessionExpirySeconds() {
        return sessionExpiry != null ? sessionExpiry : DEFAULT_SESSION_EXPIRY_SECONDS;
    }
}
