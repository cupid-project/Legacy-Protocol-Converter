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
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import si.sunesis.interoperability.lpc.transformations.configuration.models.ConnectionModel;
import si.sunesis.interoperability.lpc.transformations.configuration.models.MqttDeviceStatusModel;
import si.sunesis.interoperability.lpc.transformations.exceptions.LPCException;

import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns a single, status-only MQTT client for one device: registers a Last Will and Testament
 * (retained "offline" message) on connect, and publishes a retained "online" message once
 * connected and again after every automatic reconnect.
 * <p>
 * A separate client is required per device because MQTT allows exactly one will per connection,
 * while a single LPC MQTT connection may front multiple devices.
 * <p>
 * Deliberately never disconnects gracefully on JVM shutdown: an abrupt process death lets the
 * broker fire the will and flip the device to offline on its own, so no explicit shutdown
 * publish is needed. A graceful {@link #close(boolean)} is only used when a device is removed
 * from the running configuration.
 *
 * @author David Trafela, Sunesis
 * @since 1.0.0
 */
@Slf4j
class DeviceStatusClient {

    private static final long RETRY_DELAY_SECONDS = 30;
    private static final long ACTION_TIMEOUT_MS = 10_000;

    private final String logId;
    private final String clientId;
    private final int mqttVersion;
    private final ConnectionModel connection;
    private final ScheduledExecutorService scheduler;

    private final String resolvedTopic;
    private final String onlineMessage;
    private final String offlineMessage;
    private final int qos;
    private final boolean retain;

    private volatile org.eclipse.paho.client.mqttv3.MqttAsyncClient v3Client;
    private volatile MqttAsyncClient v5Client;
    private volatile boolean closed = false;
    private volatile ScheduledFuture<?> pendingRetry;

    DeviceStatusClient(String connectionName, ConnectionModel connection, MqttDeviceStatusModel spec, ScheduledExecutorService scheduler) throws LPCException {
        this.connection = connection;
        this.scheduler = scheduler;
        this.mqttVersion = connection.getVersion() == null ? 5 : connection.getVersion();

        String deviceId = spec.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            throw new LPCException("device-status entry on connection '" + connectionName + "' is missing device-id");
        }

        this.logId = connectionName + "|" + deviceId;

        String topicTemplate = spec.getTopic() != null ? spec.getTopic() : "devices/{deviceId}/status";
        String resolved = topicTemplate.replace("{deviceId}", deviceId);

        if (resolved.contains("{")) {
            throw new LPCException("device-status topic '" + topicTemplate + "' for " + logId + " has unresolved placeholders");
        }
        if (resolved.contains("+") || resolved.contains("#")) {
            throw new LPCException("device-status topic '" + resolved + "' for " + logId + " must not contain MQTT wildcards");
        }

        this.resolvedTopic = resolved;
        this.onlineMessage = spec.getOnlineMessage() != null ? spec.getOnlineMessage() : "{\"status\":\"online\"}";
        this.offlineMessage = spec.getOfflineMessage() != null ? spec.getOfflineMessage() : "{\"status\":\"offline\"}";
        this.qos = spec.getQos() != null ? spec.getQos() : 2;
        this.retain = spec.getRetain() == null || spec.getRetain();

        if (qos < 0 || qos > 2) {
            throw new LPCException("device-status qos for " + logId + " must be between 0 and 2, was " + qos);
        }

        this.clientId = connectionName + "-status-" + deviceId;
        if (this.clientId.length() > 23) {
            log.warn("Device status clientId '{}' for {} exceeds 23 characters; some brokers may reject it", clientId, logId);
        }

        connectAsync();
    }

    private void connectAsync() {
        if (closed) {
            return;
        }

        try {
            if (mqttVersion == 3) {
                connectV3();
            } else {
                connectV5();
            }

            log.info("Device status client connected for {} (topic={})", logId, resolvedTopic);
        } catch (Exception e) {
            log.error("Error connecting device status client for {}, retrying in {}s", logId, RETRY_DELAY_SECONDS, e);
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (closed) {
            return;
        }

        pendingRetry = scheduler.schedule(this::connectAsync, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void connectV3() throws LPCException, org.eclipse.paho.client.mqttv3.MqttException, KeyManagementException, NoSuchAlgorithmException {
        org.eclipse.paho.client.mqttv3.MqttConnectOptions options = new org.eclipse.paho.client.mqttv3.MqttConnectOptions();
        String serverURI = Connections.configureMqtt3Options(connection, options);

        options.setMqttVersion(org.eclipse.paho.client.mqttv3.MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setAutomaticReconnect(true);
        options.setWill(resolvedTopic, offlineMessage.getBytes(StandardCharsets.UTF_8), qos, retain);

        org.eclipse.paho.client.mqttv3.MqttAsyncClient client = new org.eclipse.paho.client.mqttv3.MqttAsyncClient(serverURI, clientId);
        client.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                publishOnlineV3(client, reconnect);
            }

            @Override
            public void connectionLost(Throwable cause) {
                log.warn("Device status connection lost for {}", logId, cause);
            }

            @Override
            public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                // status clients never subscribe
            }

            @Override
            public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                // no-op
            }
        });

        client.connect(options).waitForCompletion(ACTION_TIMEOUT_MS);
        this.v3Client = client;
    }

    private void publishOnlineV3(org.eclipse.paho.client.mqttv3.MqttAsyncClient client, boolean reconnect) {
        try {
            org.eclipse.paho.client.mqttv3.MqttMessage message = new org.eclipse.paho.client.mqttv3.MqttMessage(onlineMessage.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retain);
            client.publish(resolvedTopic, message);

            log.info("Published online status for {} (reconnect={})", logId, reconnect);
        } catch (Exception e) {
            log.error("Error publishing online status for {}", logId, e);
        }
    }

    private void connectV5() throws LPCException, MqttException, KeyManagementException, NoSuchAlgorithmException {
        MqttConnectionOptions options = new MqttConnectionOptions();
        String serverURI = Connections.configureMqtt5Options(connection, options);

        options.setAutomaticReconnect(true);

        MqttMessage will = new MqttMessage(offlineMessage.getBytes(StandardCharsets.UTF_8));
        will.setQos(qos);
        will.setRetained(retain);
        options.setWill(resolvedTopic, will);

        MqttAsyncClient client = new MqttAsyncClient(serverURI, clientId);
        client.setCallback(new MqttCallback() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                publishOnlineV5(client, reconnect);
            }

            @Override
            public void disconnected(MqttDisconnectResponse response) {
                log.warn("Device status connection disconnected for {}: {}", logId, response);
            }

            @Override
            public void mqttErrorOccurred(MqttException exception) {
                log.warn("Device status MQTT error for {}", logId, exception);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // status clients never subscribe
            }

            @Override
            public void deliveryComplete(IMqttToken token) {
                // no-op
            }

            @Override
            public void authPacketArrived(int reasonCode, MqttProperties properties) {
                // no-op
            }
        });

        client.connect(options).waitForCompletion(ACTION_TIMEOUT_MS);
        this.v5Client = client;
    }

    private void publishOnlineV5(MqttAsyncClient client, boolean reconnect) {
        try {
            MqttMessage message = new MqttMessage(onlineMessage.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retain);
            client.publish(resolvedTopic, message);

            log.info("Published online status for {} (reconnect={})", logId, reconnect);
        } catch (Exception e) {
            log.error("Error publishing online status for {}", logId, e);
        }
    }

    /**
     * Closes this status client.
     *
     * @param publishOffline when true, publishes the retained offline message before
     *                       disconnecting gracefully. Used when a device is removed from the
     *                       running configuration, since a graceful disconnect does not trigger
     *                       the will.
     */
    void close(boolean publishOffline) {
        closed = true;

        if (pendingRetry != null) {
            pendingRetry.cancel(false);
        }

        try {
            if (v3Client != null) {
                if (publishOffline && v3Client.isConnected()) {
                    org.eclipse.paho.client.mqttv3.MqttMessage message = new org.eclipse.paho.client.mqttv3.MqttMessage(offlineMessage.getBytes(StandardCharsets.UTF_8));
                    message.setQos(qos);
                    message.setRetained(retain);
                    v3Client.publish(resolvedTopic, message).waitForCompletion(ACTION_TIMEOUT_MS);
                }

                v3Client.disconnect().waitForCompletion(ACTION_TIMEOUT_MS);
                v3Client.close();
            } else if (v5Client != null) {
                if (publishOffline && v5Client.isConnected()) {
                    MqttMessage message = new MqttMessage(offlineMessage.getBytes(StandardCharsets.UTF_8));
                    message.setQos(qos);
                    message.setRetained(retain);
                    v5Client.publish(resolvedTopic, message).waitForCompletion(ACTION_TIMEOUT_MS);
                }

                v5Client.disconnect().waitForCompletion(ACTION_TIMEOUT_MS);
                v5Client.close();
            }
        } catch (Exception e) {
            log.warn("Error closing device status client for {}", logId, e);
        }

        log.info("Device status client closed for {} (published offline={})", logId, publishOffline);
    }
}
