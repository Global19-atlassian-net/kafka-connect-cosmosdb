package com.microsoft.azure.cosmosdb.kafka.connect.sink;

import com.microsoft.azure.cosmosdb.kafka.connect.Setting;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigValue;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Tests the configuration of Sink Provider
 */
public class CosmosDBSinkConnectorConfigTest {
    private static final Setting TIMEOUT_SETTING = new SinkSettings().getAllSettings().stream().filter(s -> s.getDisplayName().equals("Task Timeout")).findFirst().orElse(null);
    private static final Setting COSMOSDB_ENDPOINT_SETTING = new SinkSettings().getAllSettings().stream().filter(s -> s.getDisplayName().equals("CosmosDB Database Name")).findFirst().orElse(null);

    private Map<String, String> newMapWithMinimalSettings() {
        HashMap<String, String> minimumSettings = new HashMap<>();
        minimumSettings.put(COSMOSDB_ENDPOINT_SETTING.getName(), "http://example.org/notarealendpoint");
        return minimumSettings;
    }

    @Test
    public void testConfig() {
        ConfigDef configDef = new CosmosDBSinkConnector().config();
        assertNotNull(configDef);

        //Ensure all settings are represented
        Set<String> allSettingsNames = new SinkSettings().getAllSettings().stream().map(Setting::getName).collect(Collectors.toSet());
        assertEquals("Not all settings are representeed", allSettingsNames, configDef.names());
    }


    @Test
    public void testAbsentDefaults() {
        //Database name does not have a default setting. Let's see if the configdef does

        Setting dbNameSetting = new SinkSettings().getAllSettings().stream().filter(s -> s.getDisplayName().equals("CosmosDB Database Name")).findFirst().orElse(null);
        assertNotNull(dbNameSetting);
        assertNull(new CosmosDBSinkConnector().config().defaultValues().get(dbNameSetting.getName()));
    }

    @Test
    public void testPresentDefaults() {
        //The task timeout has a default setting. Let's see if the configdef does
        assertNotNull(TIMEOUT_SETTING.getDefaultValue().get());
        assertEquals(TIMEOUT_SETTING.getDefaultValue().get(), new CosmosDBSinkConnector().config().defaultValues().get(TIMEOUT_SETTING.getName()));
    }

    @Test
    public void testNumericValidation() {
        Map<String, String> settingAssignment = newMapWithMinimalSettings();

        settingAssignment.put(TIMEOUT_SETTING.getName(), "definitely not a number");
        ConfigDef config = new CosmosDBSinkConnector().config();

        List<ConfigValue> postValidation = config.validate(settingAssignment);
        ConfigValue timeoutConfigValue = postValidation.stream().filter(item -> item.name().equals(TIMEOUT_SETTING.getName())).findFirst().get();
        assertEquals("Expected error message when assigning non-numeric value to task timeout", 1, timeoutConfigValue.errorMessages().size());
    }
}
