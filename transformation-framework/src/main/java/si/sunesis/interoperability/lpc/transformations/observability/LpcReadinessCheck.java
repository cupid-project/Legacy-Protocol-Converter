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

import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import si.sunesis.interoperability.lpc.transformations.connections.Connections;
import si.sunesis.interoperability.lpc.transformations.transformation.TransformationsHandler;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Map;

/**
 * Readiness check of the Legacy Protocol Converter, exposed on /health/ready (and aggregated on /health).
 * <p>
 * Readiness is UP when a configuration has been applied and every messaging connection (NATS, MQTT, RabbitMQ)
 * is connected. Polled field-bus connections (Modbus) are reported as data but do not affect readiness, because
 * a device that is switched off must not take the converter out of service for the other devices it serves.
 * The check also registers the connection and transformation gauges of {@link LpcMetrics}.
 *
 * @author Sunesis
 * @since 1.6.0
 */
@Slf4j
@Readiness
@ApplicationScoped
public class LpcReadinessCheck implements HealthCheck {

    @Inject
    private TransformationsHandler transformationsHandler;

    @Inject
    private LpcMetrics metrics;

    @PostConstruct
    public void init() {
        metrics.registerGauges(
                () -> ConnectionStatus.distinctHandlers(transformationsHandler.getActiveConnections()),
                () -> ConnectionStatus.distinctHandlersUp(transformationsHandler.getActiveConnections()),
                () -> (long) transformationsHandler.getActiveTransformationCount());
    }

    @Override
    public HealthCheckResponse call() {
        Connections connections = transformationsHandler.getActiveConnections();
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("lpc-ready");

        if (connections == null) {
            return builder.withData("state", "no configuration applied yet").down().build();
        }

        boolean ready = true;
        Map<String, ConnectionStatus.State> states = ConnectionStatus.states(connections);
        for (Map.Entry<String, ConnectionStatus.State> entry : states.entrySet()) {
            builder.withData(entry.getKey(), entry.getValue().name());
            if (entry.getValue() == ConnectionStatus.State.DOWN && !ConnectionStatus.isFieldBus(connections, entry.getKey())) {
                ready = false;
            }
        }
        builder.withData("transformations", transformationsHandler.getActiveTransformationCount());

        return ready ? builder.up().build() : builder.down().build();
    }
}
