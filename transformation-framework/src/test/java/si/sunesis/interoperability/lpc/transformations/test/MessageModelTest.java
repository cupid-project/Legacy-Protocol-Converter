package si.sunesis.interoperability.lpc.transformations.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Assert;
import org.junit.Test;
import si.sunesis.interoperability.lpc.transformations.configuration.models.MessageModel;

import java.io.IOException;

/**
 * Guards the defaults and YAML binding of the Modbus retry knobs on {@link MessageModel}.
 */
public class MessageModelTest {

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Test
    public void defaults() {
        MessageModel model = new MessageModel();
        Assert.assertEquals(Integer.valueOf(0), model.getRetryCount());
        Assert.assertEquals(Integer.valueOf(2), model.getModbusConnectionRetryCount());
    }

    @Test
    public void bindsModbusConnectionRetryCountFromYaml() throws IOException {
        MessageModel model = yaml.readValue("modbus-connection-retry-count: 0\n", MessageModel.class);
        Assert.assertEquals(Integer.valueOf(0), model.getModbusConnectionRetryCount());
    }
}
