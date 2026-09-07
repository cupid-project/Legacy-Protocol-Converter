# Changelog

## Unreleased

### Bug Fixes
- Fixed a startup bug that crashed the entire HTTP layer: `/health`, `/health/live`, `/health/ready`, `/metrics`,
  `/lpc/config` and even `/` all returned 503, regardless of Docker or `java -jar` deployment. Two independent causes,
  both required:
  - `kumuluzee.health.servlet.mapping` was configured as `/health/*`, but the bundled `kumuluzee-health` library
    always appends its own `/*` suffix to a custom mapping, producing the invalid path spec `/health/*/*` and
    aborting Jetty's web app deployment. Changed to the bare name `health`, matching the library's expected format.
  - The JAX-RS `Application` class (`LegacyProtocolConverterApplication`) contained a private nested `record`
    (`StreamGobbler`); the CDI/Weld version bundled with this KumuluzEE release cannot scan a class containing a
    Java record (`NoClassDefFoundError: record`). Converted it to a plain nested class. Pre-existing since April 2025,
    unrelated to the 1.6.0 changes.

## 1.6.0 (2026-09-07)

Security and reliability hardening.

### Features
- MQTT connections support a `device-status` block: one lightweight status-only client per listed device-id,
  registering a retained "offline" Last Will and Testament and publishing a retained "online" message on connect
  and every automatic reconnect. Lets devices bridged through LPC report Online/Offline liveness to consumers,
  since MQTT itself carries no such signal. A connection's `device-status` list may contain multiple entries.

### Security
- The configuration endpoints `POST/GET /lpc/config` now enforce the `API_KEY` header (constant-time comparison).
  The check had been commented out. When no `API_KEY` is configured the endpoints are disabled (401).
- TLS contexts built from a site CA and client certificate are no longer pinned to TLS 1.2; the protocol version
  (TLS 1.2 or 1.3) is negotiated by the JDK provider.
- NATS connections support the `ssl` block (platform trust store, or CA plus client certificate for mutual TLS),
  as MQTT connections already did.
- Client keystores without a password no longer cause a NullPointerException.
- `Dockerfile`: the build argument and environment variable are now `API_KEY` (was the invalid `API-KEY`).

### Reliability
- IEEE 2030.5 schema validation is fail-closed: a non-compliant message is rejected and logged instead of being
  logged and forwarded. Validation of messages received on the outgoing (server-side) connection that are written to
  Modbus registers is now governed by `outgoing`/`both`, consistent with the non-Modbus path (previously `incoming`).
- Publish retries no longer modify the retry map while iterating it (ConcurrentModificationException) and report a
  final failure after the last retry.

### Bug Fixes
- `$timestampZ` in transformation mappings is now formatted in UTC (`ZoneOffset.UTC`) instead of the JVM's default
  time zone, since the `Z` suffix implies UTC. Previously the value was wrong on any machine not running in UTC.

### Observability
- `/health`, `/health/live`, `/health/ready` (MicroProfile Health): liveness after configuration is applied;
  readiness when all NATS/MQTT/RabbitMQ connections are connected; per-connection state in the response.
- `/metrics` (MicroProfile Metrics): `lpc_messages_transformed_total`, `lpc_messages_rejected_total`,
  `lpc_publish_failures_total` per transformation; `lpc_connections_total`, `lpc_connections_up`,
  `lpc_transformations_active`.
- A registration message publish now logs an info line with the topic it was sent to.

### Other
- `GET /lpc/config` returns `config.yaml`, the file that `POST /lpc/config` writes (was `mqtt-nats.yaml`).
- Documentation: transport security, validation semantics, health/metrics and remote configuration.
