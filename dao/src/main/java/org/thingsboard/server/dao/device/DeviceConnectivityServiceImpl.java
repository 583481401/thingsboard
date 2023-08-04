/**
 * Copyright © 2016-2023 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.DeviceTransportType;
import org.thingsboard.server.common.data.ResourceUtils;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.device.profile.MqttDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.data.security.DeviceCredentialsType;
import org.thingsboard.server.dao.util.DeviceConnectivityUtil;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.thingsboard.server.dao.service.Validator.validateId;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.CHECK_DOCUMENTATION;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.COAP;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.COAPS;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.DOCKER;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.HTTP;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.HTTPS;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.LINUX;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.MQTT;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.MQTTS;
import static org.thingsboard.server.dao.util.DeviceConnectivityUtil.WINDOWS;

@Service("DeviceConnectivityDaoService")
@Slf4j
public class DeviceConnectivityServiceImpl implements DeviceConnectivityService {

    public static final String INCORRECT_TENANT_ID = "Incorrect tenantId ";
    public static final String INCORRECT_DEVICE_ID = "Incorrect deviceId ";
    public static final String DEFAULT_DEVICE_TELEMETRY_TOPIC = "v1/devices/me/telemetry";

    @Autowired
    private DeviceCredentialsService deviceCredentialsService;

    @Autowired
    private DeviceProfileService deviceProfileService;

    @Autowired
    private DeviceConnectivityConfiguration deviceConnectivityConfiguration;

    @Override
    public JsonNode findDevicePublishTelemetryCommands(String baseUrl, Device device) throws URISyntaxException {
        DeviceId deviceId = device.getId();
        log.trace("Executing findDevicePublishTelemetryCommands [{}]", deviceId);
        validateId(deviceId, INCORRECT_DEVICE_ID + deviceId);

        DeviceCredentials creds = deviceCredentialsService.findDeviceCredentialsByDeviceId(device.getTenantId(), deviceId);
        DeviceProfile deviceProfile = deviceProfileService.findDeviceProfileById(device.getTenantId(), device.getDeviceProfileId());
        DeviceTransportType transportType = deviceProfile.getTransportType();

        ObjectNode commands = JacksonUtil.newObjectNode();
        switch (transportType) {
            case DEFAULT:
                Optional.ofNullable(getHttpTransportPublishCommands(baseUrl, creds))
                        .ifPresent(v -> commands.set(HTTP, v));
                Optional.ofNullable(getMqttTransportPublishCommands(baseUrl, creds))
                        .ifPresent(v -> commands.set(MQTT, v));
                Optional.ofNullable(getCoapTransportPublishCommands(baseUrl, creds))
                        .ifPresent(v -> commands.set(COAP, v));
                break;
            case MQTT:
                MqttDeviceProfileTransportConfiguration transportConfiguration =
                        (MqttDeviceProfileTransportConfiguration) deviceProfile.getProfileData().getTransportConfiguration();
                //TODO: add sparkplug command with emulator (check SSL)
                if (transportConfiguration.isSparkplug()) {
                    ObjectNode sparkplug = JacksonUtil.newObjectNode();
                    sparkplug.put("sparkplug", CHECK_DOCUMENTATION);
                    commands.set(MQTT, sparkplug);
                } else {
                    String topicName = transportConfiguration.getDeviceTelemetryTopic();

                    Optional.ofNullable(getMqttTransportPublishCommands(baseUrl, topicName, creds))
                            .ifPresent(v -> commands.set(MQTT, v));
                }
                break;
            case COAP:
                Optional.ofNullable(getCoapTransportPublishCommands(baseUrl, creds))
                        .ifPresent(v -> commands.set(COAP, v));
                break;
            default:
                commands.put(transportType.name(), CHECK_DOCUMENTATION);
        }
        return commands;
    }

    @Override
    public JsonNode findGatewayLaunchCommands(String baseUrl, Device device) throws URISyntaxException {
        DeviceId deviceId = device.getId();
        log.trace("Executing findDevicePublishTelemetryCommands [{}]", deviceId);
        validateId(deviceId, INCORRECT_DEVICE_ID + deviceId);

        DeviceCredentials creds = deviceCredentialsService.findDeviceCredentialsByDeviceId(device.getTenantId(), deviceId);

        ObjectNode commands = JacksonUtil.newObjectNode();
        if (deviceConnectivityConfiguration.isEnabled(MQTT)) {
            Optional.ofNullable(getGatewayDockerCommands(baseUrl, creds, MQTT))
                    .ifPresent(v -> commands.set(MQTT, v));
        }
        if (deviceConnectivityConfiguration.isEnabled(MQTTS)) {
            Optional.ofNullable(getGatewayDockerCommands(baseUrl, creds, MQTTS))
                    .ifPresent(v -> commands.set(MQTTS, v));
        }
        return commands;
    }

    @Override
    public Resource getPemCertFile(String protocol) {
        String certFilePath = deviceConnectivityConfiguration.getConnectivity()
                .get(protocol)
                .getPemCertFile();

        if (StringUtils.isNotBlank(certFilePath) && ResourceUtils.resourceExists(this, certFilePath)) {
            return new ClassPathResource(certFilePath);
        } else {
            return null;
        }
    }

    private JsonNode getHttpTransportPublishCommands(String defaultHostname, DeviceCredentials deviceCredentials) throws URISyntaxException {
        ObjectNode httpCommands = JacksonUtil.newObjectNode();
        Optional.ofNullable(getHttpPublishCommand(HTTP, defaultHostname, deviceCredentials))
                .ifPresent(v -> httpCommands.put(HTTP, v));
        Optional.ofNullable(getHttpPublishCommand(HTTPS, defaultHostname, deviceCredentials))
                .ifPresent(v -> httpCommands.put(HTTPS, v));
        return httpCommands.isEmpty() ? null : httpCommands;
    }

    private String getHttpPublishCommand(String protocol, String baseUrl, DeviceCredentials deviceCredentials) throws URISyntaxException {
        DeviceConnectivityInfo properties = deviceConnectivityConfiguration.getConnectivity().get(protocol);
        if (properties == null || !properties.isEnabled() ||
                deviceCredentials.getCredentialsType() != DeviceCredentialsType.ACCESS_TOKEN) {
            return null;
        }
        String hostName = getHost(baseUrl, properties);
        String port = properties.getPort().isEmpty() ? "" : ":" + properties.getPort();

        return DeviceConnectivityUtil.getHttpPublishCommand(protocol, hostName, port, deviceCredentials);
    }

    private JsonNode getMqttTransportPublishCommands(String baseUrl, DeviceCredentials deviceCredentials) throws URISyntaxException {
        return getMqttTransportPublishCommands(baseUrl, DEFAULT_DEVICE_TELEMETRY_TOPIC, deviceCredentials);
    }

    private JsonNode getMqttTransportPublishCommands(String baseUrl, String topic, DeviceCredentials deviceCredentials) throws URISyntaxException {
        ObjectNode mqttCommands = JacksonUtil.newObjectNode();

        if (deviceCredentials.getCredentialsType() == DeviceCredentialsType.X509_CERTIFICATE) {
            mqttCommands.put(MQTTS, CHECK_DOCUMENTATION);
            return mqttCommands;
        }

        ObjectNode dockerMqttCommands = JacksonUtil.newObjectNode();

        if (deviceConnectivityConfiguration.isEnabled(MQTT)) {
            Optional.ofNullable(getMqttPublishCommand(baseUrl, topic, deviceCredentials)).
                    ifPresent(v -> mqttCommands.put(MQTT, v));

            Optional.ofNullable(getDockerMqttPublishCommand(MQTT, baseUrl, topic, deviceCredentials))
                    .ifPresent(v -> dockerMqttCommands.put(MQTT, v));
        }

        if (deviceConnectivityConfiguration.isEnabled(MQTTS)) {
            List<String> mqttsPublishCommand = getMqttsPublishCommand(baseUrl, topic, deviceCredentials);
            if (mqttsPublishCommand != null) {
                ArrayNode arrayNode = mqttCommands.putArray(MQTTS);
                mqttsPublishCommand.forEach(arrayNode::add);
            }

            Optional.ofNullable(getDockerMqttPublishCommand(MQTTS, baseUrl, topic, deviceCredentials))
                    .ifPresent(v -> dockerMqttCommands.put(MQTTS, v));
        }

        if (!dockerMqttCommands.isEmpty()) {
            mqttCommands.set(DOCKER, dockerMqttCommands);
        }
        return mqttCommands.isEmpty() ? null : mqttCommands;
    }

    private String getMqttPublishCommand(String baseUrl, String deviceTelemetryTopic, DeviceCredentials deviceCredentials) throws URISyntaxException {
        DeviceConnectivityInfo properties = deviceConnectivityConfiguration.getConnectivity().get(MQTT);
        String mqttHost = getHost(baseUrl, properties);
        String mqttPort = properties.getPort().isEmpty() ? null : properties.getPort();
        return DeviceConnectivityUtil.getMqttPublishCommand(MQTT, mqttHost, mqttPort, deviceTelemetryTopic, deviceCredentials);
    }

    private List<String> getMqttsPublishCommand(String baseUrl, String deviceTelemetryTopic, DeviceCredentials deviceCredentials) throws URISyntaxException {
        DeviceConnectivityInfo properties = deviceConnectivityConfiguration.getConnectivity().get(MQTTS);
        String mqttHost = getHost(baseUrl, properties);
        String mqttPort = properties.getPort().isEmpty() ? null : properties.getPort();
        String pubCommand = DeviceConnectivityUtil.getMqttPublishCommand(MQTTS, mqttHost, mqttPort, deviceTelemetryTopic, deviceCredentials);

        ArrayList<String> commands = new ArrayList<>();
        if (pubCommand != null) {
            commands.add(DeviceConnectivityUtil.getCurlPemCertCommand(baseUrl, MQTTS));
            commands.add(pubCommand);
            return commands;
        }
        return null;
    }

    private JsonNode getGatewayDockerCommands(String baseUrl, DeviceCredentials deviceCredentials, String mqttType) throws URISyntaxException {
        ObjectNode dockerLaunchCommands = JacksonUtil.newObjectNode();
        DeviceConnectivityInfo properties = deviceConnectivityConfiguration.getConnectivity().get(mqttType);
        String mqttHost = getHost(baseUrl, properties);
        String mqttPort = properties.getPort().isEmpty() ? null : properties.getPort();
        Optional.ofNullable(DeviceConnectivityUtil.getGatewayLaunchCommand(LINUX, mqttHost, mqttPort, deviceCredentials))
                .ifPresent(v -> dockerLaunchCommands.put(LINUX, v));
        Optional.ofNullable(DeviceConnectivityUtil.getGatewayLaunchCommand(WINDOWS, mqttHost, mqttPort, deviceCredentials))
                .ifPresent(v -> dockerLaunchCommands.put(WINDOWS, v));
        return dockerLaunchCommands.isEmpty() ? null : dockerLaunchCommands;
    }

    private String getDockerMqttPublishCommand(String protocol, String baseUrl, String deviceTelemetryTopic, DeviceCredentials deviceCredentials) throws URISyntaxException {
        DeviceConnectivityInfo properties = deviceConnectivityConfiguration.getConnectivity().get(protocol);
        String mqttHost = getHost(baseUrl, properties);
        String mqttPort = properties.getPort().isEmpty() ? null : properties.getPort();
        return DeviceConnectivityUtil.getDockerMqttPublishCommand(protocol, baseUrl, mqttHost, mqttPort, deviceTelemetryTopic, deviceCredentials);
    }

    private JsonNode getCoapTransportPublishCommands(String baseUrl, DeviceCredentials deviceCredentials) throws URISyntaxException {
        ObjectNode coapCommands = JacksonUtil.newObjectNode();

        if (deviceCredentials.getCredentialsType() == DeviceCredentialsType.X509_CERTIFICATE) {
            coapCommands.put(COAPS, CHECK_DOCUMENTATION);
            return coapCommands;
        }

        ObjectNode dockerCoapCommands = JacksonUtil.newObjectNode();

        if (deviceConnectivityConfiguration.isEnabled(COAP)) {
            Optional.ofNullable(getCoapPublishCommand(COAP, baseUrl, deviceCredentials))
                    .ifPresent(v -> coapCommands.put(COAP, v));

            Optional.ofNullable(getDockerCoapPublishCommand(COAP, baseUrl, deviceCredentials))
                    .ifPresent(v -> dockerCoapCommands.put(COAP, v));
        }

        if (deviceConnectivityConfiguration.isEnabled(COAPS)) {
            Optional.ofNullable(getCoapPublishCommand(COAPS, baseUrl, deviceCredentials))
                    .ifPresent(v -> coapCommands.put(COAPS, v));

            Optional.ofNullable(getDockerCoapPublishCommand(COAPS, baseUrl, deviceCredentials))
                    .ifPresent(v -> dockerCoapCommands.put(COAPS, v));
        }

        if (!dockerCoapCommands.isEmpty()) {
            coapCommands.set(DOCKER, dockerCoapCommands);
        }

        return coapCommands.isEmpty() ? null : coapCommands;
    }

    private String getCoapPublishCommand(String protocol, String baseUrl, DeviceCredentials deviceCredentials) throws URISyntaxException {
        DeviceConnectivityInfo properties = deviceConnectivityConfiguration.getConnectivity().get(protocol);
        String hostName = getHost(baseUrl, properties);
        String port = properties.getPort().isEmpty() ? "" : ":" + properties.getPort();
        return DeviceConnectivityUtil.getCoapPublishCommand(protocol, hostName, port, deviceCredentials);
    }

    private String getDockerCoapPublishCommand(String protocol, String baseUrl, DeviceCredentials deviceCredentials) throws URISyntaxException {
        DeviceConnectivityInfo properties = deviceConnectivityConfiguration.getConnectivity().get(protocol);
        String host = getHost(baseUrl, properties);
        String port = properties.getPort().isEmpty() ? "" : ":" + properties.getPort();
        return DeviceConnectivityUtil.getDockerCoapPublishCommand(protocol, host, port, deviceCredentials);
    }

    private String getHost(String baseUrl, DeviceConnectivityInfo properties) throws URISyntaxException {
        return properties.getHost().isEmpty() ? new URI(baseUrl).getHost() : properties.getHost();
    }

}
