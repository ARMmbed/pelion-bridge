/**
 * @file    GoogleCloudPeerProcessorFactory.java
 * @brief Google Cloud Peer Processor Factory
 * @author Doug Anson
 * @version 1.0
 * @see
 *
 * Copyright 2018. ARM Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.arm.connector.bridge.coordinator.processors.google;

import com.arm.connector.bridge.coordinator.processors.arm.GenericMQTTProcessor;
import com.arm.connector.bridge.coordinator.Orchestrator;
import com.arm.connector.bridge.coordinator.processors.core.BasePeerProcessorFactory;
import com.arm.connector.bridge.coordinator.processors.interfaces.PeerInterface;
import com.arm.connector.bridge.transport.HttpTransport;
import com.arm.connector.bridge.core.Transport;
import java.util.ArrayList;

/**
 * Google Cloud Peer Processor Manager: Factory for initiating a peer processor for Google Cloud Pub/Sub
 *
 * @author Doug Anson
 */
public class GoogleCloudPeerProcessorFactory extends BasePeerProcessorFactory implements Transport.ReceiveListener, PeerInterface {

    // Factory method for initializing the AWS IotHub MQTT collection orchestrator
    public static GoogleCloudPeerProcessorFactory createPeerProcessor(Orchestrator manager, HttpTransport http) {
        // create me
        GoogleCloudPeerProcessorFactory me = new GoogleCloudPeerProcessorFactory(manager, http);

        // initialize me
        boolean google_cloud_gw_enabled = manager.preferences().booleanValueOf("enable_google_cloud_addon");
        String mgr_config = manager.preferences().valueOf("mqtt_mgr_config");
        if (mgr_config != null && mgr_config.length() > 0) {
            // muliple MQTT brokers requested... follow configuration and assign suffixes
            String[] config = mgr_config.split(";");
            for (int i = 0; i < config.length; ++i) {
                if (google_cloud_gw_enabled == true && config[i].equalsIgnoreCase("google_cloud") == true) {
                    manager.errorLogger().info("Registering Google Cloud MQTT processor...");
                    GenericMQTTProcessor p = new GoogleCloudMQTTProcessor(manager, null, "" + i, http);
                    me.addProcessor(p);
                }
                if (google_cloud_gw_enabled == true && config[i].equalsIgnoreCase("google_cloud-d") == true) {
                    manager.errorLogger().info("Registering Google Cloud MQTT processor (default)...");
                    GenericMQTTProcessor p = new GoogleCloudMQTTProcessor(manager, null, "" + i, http);
                    me.addProcessor(p, true);
                }
            }
        }
        else // single MQTT broker configuration requested
        {
            if (google_cloud_gw_enabled == true) {
                manager.errorLogger().info("Registering Google Cloud MQTT processor (singleton)...");
                GenericMQTTProcessor p = new GoogleCloudMQTTProcessor(manager, null, http);
                me.addProcessor(p);
            }
        }

        // return me
        return me;
    }

    // constructor
    public GoogleCloudPeerProcessorFactory(Orchestrator manager, HttpTransport http) {
        super(manager, null);
        this.m_http = http;
        this.m_mqtt_list = new ArrayList<>();
    }
}