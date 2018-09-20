/**
 * @file MSIoTHubPeerProcessorFactory.java
 * @brief MS IoTHub Peer Processor Factory
 * @author Doug Anson
 * @version 1.0
 * @see
 *
 * Copyright 2015. ARM Ltd. All rights reserved.
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
package com.arm.pelion.bridge.coordinator.processors.factories;

import com.arm.pelion.bridge.coordinator.processors.arm.GenericConnectablePeerProcessor;
import com.arm.pelion.bridge.coordinator.Orchestrator;
import com.arm.pelion.bridge.coordinator.processors.ms.IoTHubMQTTProcessor;
import com.arm.pelion.bridge.transport.HttpTransport;
import com.arm.pelion.bridge.transport.Transport;
import java.util.ArrayList;
import com.arm.pelion.bridge.coordinator.processors.interfaces.PeerProcessorInterface;

/**
 * MS IoTHub Peer Processor Manager: Factory for initiating a peer processor for MS IoTHub
 *
 * @author Doug Anson
 */
public class MSIoTHubPeerProcessorFactory extends BasePeerProcessorFactory implements Transport.ReceiveListener, PeerProcessorInterface {

    // Factory method for initializing the MS IoTHub MQTT collection orchestrator
    public static MSIoTHubPeerProcessorFactory createPeerProcessor(Orchestrator manager, HttpTransport http) {
        // create me
        MSIoTHubPeerProcessorFactory me = new MSIoTHubPeerProcessorFactory(manager, http);

        // initialize me
        boolean iot_event_hub_enabled = manager.preferences().booleanValueOf("enable_iot_event_hub_addon");
        if (iot_event_hub_enabled == true) {
            manager.errorLogger().info("Registering MS IoTHub MQTT processor...");
            GenericConnectablePeerProcessor p = new IoTHubMQTTProcessor(manager, null, http);
            me.addProcessor(p);
        }

        // return me
        return me;
    }

    // constructor
    public MSIoTHubPeerProcessorFactory(Orchestrator manager, HttpTransport http) {
        super(manager, null);
        this.m_http = http;
        this.m_peer_processor_list = new ArrayList<>();
    }
}
