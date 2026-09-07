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

import com.kumuluz.ee.metrics.producers.MetricRegistryProducer;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.metrics.Counter;
import org.eclipse.microprofile.metrics.Metadata;
import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.MetricType;
import org.eclipse.microprofile.metrics.Tag;

import javax.enterprise.context.ApplicationScoped;
import java.util.function.Supplier;

/**
 * Application metrics of the Legacy Protocol Converter, exposed on the /metrics endpoint
 * (MicroProfile Metrics, JSON and Prometheus formats).
 * <p>
 * Metrics are recorded per transformation (tag "transformation"):
 * <ul>
 *     <li>lpc_messages_transformed_total: messages successfully transformed and handed to the outgoing side</li>
 *     <li>lpc_messages_rejected_total: messages rejected by IEEE 2030.5 schema validation</li>
 *     <li>lpc_publish_failures_total: publish attempts that failed after all retries</li>
 * </ul>
 * and globally:
 * <ul>
 *     <li>lpc_connections_total / lpc_connections_up: configured and currently connected connections</li>
 *     <li>lpc_transformations_active: number of active transformation handlers</li>
 * </ul>
 *
 * @author Sunesis
 * @since 1.6.0
 */
@Slf4j
@ApplicationScoped
public class LpcMetrics {

    public static final String MESSAGES_TRANSFORMED = "lpc_messages_transformed_total";
    public static final String MESSAGES_REJECTED = "lpc_messages_rejected_total";
    public static final String PUBLISH_FAILURES = "lpc_publish_failures_total";
    public static final String CONNECTIONS_TOTAL = "lpc_connections_total";
    public static final String CONNECTIONS_UP = "lpc_connections_up";
    public static final String TRANSFORMATIONS_ACTIVE = "lpc_transformations_active";

    private static final String TAG_TRANSFORMATION = "transformation";

    private MetricRegistry registry() {
        return MetricRegistryProducer.getApplicationRegistry();
    }

    private Counter counter(String name, String description, String transformation) {
        Metadata metadata = Metadata.builder()
                .withName(name)
                .withDescription(description)
                .withType(MetricType.COUNTER)
                .build();
        return registry().counter(metadata, new Tag(TAG_TRANSFORMATION, transformation == null ? "unknown" : transformation));
    }

    /**
     * Records a successfully transformed message.
     *
     * @param transformation Name of the transformation
     */
    public void messageTransformed(String transformation) {
        try {
            counter(MESSAGES_TRANSFORMED, "Messages successfully transformed", transformation).inc();
        } catch (Exception e) {
            log.debug("Metrics unavailable: {}", e.getMessage());
        }
    }

    /**
     * Records a message rejected by IEEE 2030.5 schema validation.
     *
     * @param transformation Name of the transformation
     */
    public void messageRejected(String transformation) {
        try {
            counter(MESSAGES_REJECTED, "Messages rejected by IEEE 2030.5 schema validation", transformation).inc();
        } catch (Exception e) {
            log.debug("Metrics unavailable: {}", e.getMessage());
        }
    }

    /**
     * Records publish attempts that failed after all configured retries.
     *
     * @param transformation Name of the transformation
     * @param count          Number of failed connections
     */
    public void publishFailed(String transformation, long count) {
        try {
            counter(PUBLISH_FAILURES, "Publish attempts that failed after all retries", transformation).inc(count);
        } catch (Exception e) {
            log.debug("Metrics unavailable: {}", e.getMessage());
        }
    }

    /**
     * Registers the connection and transformation gauges. Safe to call more than once; an existing gauge is kept.
     *
     * @param connectionsTotal Supplier of the number of configured connections
     * @param connectionsUp    Supplier of the number of currently connected connections
     * @param transformations  Supplier of the number of active transformations
     */
    public void registerGauges(Supplier<Long> connectionsTotal, Supplier<Long> connectionsUp, Supplier<Long> transformations) {
        registerGauge(CONNECTIONS_TOTAL, "Configured connections", connectionsTotal);
        registerGauge(CONNECTIONS_UP, "Currently connected connections", connectionsUp);
        registerGauge(TRANSFORMATIONS_ACTIVE, "Active transformation handlers", transformations);
    }

    private void registerGauge(String name, String description, Supplier<Long> supplier) {
        try {
            if (registry().getGauges().keySet().stream().anyMatch(id -> id.getName().equals(name))) {
                return;
            }
            Metadata metadata = Metadata.builder()
                    .withName(name)
                    .withDescription(description)
                    .withType(MetricType.GAUGE)
                    .build();
            registry().gauge(metadata, supplier);
        } catch (Exception e) {
            log.debug("Metrics unavailable: {}", e.getMessage());
        }
    }
}
