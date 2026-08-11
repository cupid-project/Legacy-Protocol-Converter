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

/**
 * Configuration model for a single device's MQTT Last Will and Testament (LWT) and
 * retained online/offline status reporting on an MQTT connection.
 * <p>
 * A separate MQTT client is opened per device-status entry, since MQTT allows exactly
 * one will per connection and a single MQTT connection may front multiple devices.
 *
 * @author David Trafela, Sunesis
 * @since 1.0.0
 */
@Data
public class MqttDeviceStatusModel {

    /**
     * Identifier of the device this status client represents
     */
    @JsonProperty("device-id")
    private String deviceId;

    /**
     * Topic on which the will and online/offline status messages are published.
     * Supports the {deviceId} placeholder.
     */
    private String topic = "devices/{deviceId}/status";

    /**
     * Message published (retained) once the status client connects or reconnects
     */
    @JsonProperty("online-message")
    private String onlineMessage = "{\"status\":\"online\"}";

    /**
     * Will message the broker publishes (retained) if the status client disconnects ungracefully
     */
    @JsonProperty("offline-message")
    private String offlineMessage = "{\"status\":\"offline\"}";

    /**
     * QoS used for the will and the online/offline status publishes
     */
    private Integer qos = 2;

    /**
     * Whether the will and online/offline status publishes are retained
     */
    private Boolean retain = true;
}
