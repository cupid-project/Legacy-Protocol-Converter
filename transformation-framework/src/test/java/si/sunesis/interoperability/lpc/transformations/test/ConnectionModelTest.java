package si.sunesis.interoperability.lpc.transformations.test;

import org.junit.Assert;
import org.junit.Test;
import si.sunesis.interoperability.lpc.transformations.configuration.models.ConnectionModel;

/**
 * Covers the effective MQTT session-persistence resolution added for reconnect resilience:
 * an explicit {@code clean-start} wins, otherwise the session is persistent exactly when {@code reconnect} is on.
 */
public class ConnectionModelTest {

    @Test
    public void defaultsToCleanSessionWhenReconnectDisabled() {
        ConnectionModel connection = new ConnectionModel();
        Assert.assertFalse(connection.isPersistentSession());
    }

    @Test
    public void defaultsToPersistentSessionWhenReconnectEnabled() {
        ConnectionModel connection = new ConnectionModel();
        connection.setReconnect(true);
        Assert.assertTrue(connection.isPersistentSession());
    }

    @Test
    public void explicitCleanStartOverridesReconnectDefault() {
        ConnectionModel connection = new ConnectionModel();
        connection.setReconnect(true);
        connection.setCleanStart(true);
        Assert.assertFalse(connection.isPersistentSession());

        connection.setReconnect(false);
        connection.setCleanStart(false);
        Assert.assertTrue(connection.isPersistentSession());
    }

    @Test
    public void sessionExpiryFallsBackToDefault() {
        ConnectionModel connection = new ConnectionModel();
        Assert.assertEquals(ConnectionModel.DEFAULT_SESSION_EXPIRY_SECONDS, connection.resolveSessionExpirySeconds());

        connection.setSessionExpiry(3600L);
        Assert.assertEquals(3600L, connection.resolveSessionExpirySeconds());
    }
}
