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

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import si.sunesis.interoperability.lpc.transformations.transformation.TransformationsHandler;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * Liveness check of the Legacy Protocol Converter, exposed on /health/live (and aggregated on /health).
 * <p>
 * Liveness is UP once a configuration has been applied. Connection outages do not affect liveness: they are
 * recovered by the clients' automatic reconnection and are reported by the readiness check instead, so that an
 * orchestrator does not restart a healthy converter during a broker outage.
 *
 * @author Sunesis
 * @since 1.6.0
 */
@Liveness
@ApplicationScoped
public class LpcLivenessCheck implements HealthCheck {

    @Inject
    private TransformationsHandler transformationsHandler;

    @Override
    public HealthCheckResponse call() {
        boolean configured = transformationsHandler.getActiveConnections() != null;
        return HealthCheckResponse.named("lpc-live")
                .withData("transformations", transformationsHandler.getActiveTransformationCount())
                .status(configured)
                .build();
    }
}
