/**
 * @file    WatsonIoTMQTTProcessor.java
 * @brief IBM WatsonIoT MQTT Peer Processor
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
package com.arm.connector.bridge.coordinator.processors.ibm;

import com.arm.connector.bridge.coordinator.processors.arm.GenericMQTTProcessor;
import com.arm.connector.bridge.coordinator.Orchestrator;
import com.arm.connector.bridge.coordinator.processors.core.ApiResponse;
import com.arm.connector.bridge.coordinator.processors.interfaces.AsyncResponseProcessor;
import com.arm.connector.bridge.coordinator.processors.interfaces.ConnectionCreator;
import com.arm.connector.bridge.coordinator.processors.interfaces.PeerInterface;
import com.arm.connector.bridge.coordinator.processors.interfaces.ReconnectionInterface;
import com.arm.connector.bridge.core.Utils;
import com.arm.connector.bridge.transport.HttpTransport;
import com.arm.connector.bridge.transport.MQTTTransport;
import com.arm.connector.bridge.core.Transport;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;

/**
 * IBM WatsonIoT peer processor based on MQTT with MessageSight
 *
 * @author Doug Anson
 */
public class WatsonIoTMQTTProcessor extends GenericMQTTProcessor implements ReconnectionInterface, ConnectionCreator, Transport.ReceiveListener, PeerInterface, AsyncResponseProcessor {
    private String m_mqtt_ip_address = null;
    private String m_watson_iot_observe_notification_topic = null;
    private String m_watson_iot_coap_cmd_topic_get = null;
    private String m_watson_iot_coap_cmd_topic_put = null;
    private String m_watson_iot_coap_cmd_topic_post = null;
    private String m_watson_iot_coap_cmd_topic_delete = null;
    private String m_watson_iot_coap_cmd_topic_api = null;
    private String m_watson_iot_org_id = null;
    private String m_watson_iot_org_key = null;
    private String m_client_id_template = null;
    private String m_watson_iot_device_data_key = null;

    // WatsonIoT bindings
    private String m_watson_iot_api_key = null;
    private String m_watson_iot_auth_token = null;

    // Legacy bridge
    private boolean m_watson_legacy_bridge = false;

    // WatsonIoT Device Manager
    private WatsonIoTDeviceManager m_device_manager = null;

    // constructor (singleton)
    public WatsonIoTMQTTProcessor(Orchestrator manager, MQTTTransport mqtt, HttpTransport http) {
        this(manager, mqtt, null, http);
    }

    // constructor (with suffix for preferences)
    public WatsonIoTMQTTProcessor(Orchestrator manager, MQTTTransport mqtt, String suffix, HttpTransport http) {
        super(manager, mqtt, suffix, http);

        // WatsonIoT Processor Announce
        this.errorLogger().info("IBM Watson IoT Processor ENABLED.");

        // get our defaults
        this.m_watson_iot_org_id = this.orchestrator().preferences().valueOf("iotf_org_id", this.m_suffix);
        this.m_watson_iot_org_key = this.orchestrator().preferences().valueOf("iotf_org_key", this.m_suffix);
        this.m_mqtt_ip_address = this.orchestrator().preferences().valueOf("iotf_mqtt_ip_address", this.m_suffix);
        this.m_mqtt_port = this.orchestrator().preferences().intValueOf("iotf_mqtt_port", this.m_suffix);
        
        // set defaults for keys
        this.m_observation_key = "notify";
        this.m_cmd_response_key = "cmd-response";

        // legacy bridge?
        this.m_watson_legacy_bridge = this.orchestrator().preferences().booleanValueOf("iotf_legacy_bridge", this.m_suffix);
        if (this.m_watson_legacy_bridge == true) {
            this.errorLogger().warning("Watson IoT Bridge in Legacy Mode");
            this.m_observation_key = "observation";
        }

        // get our configured device data key (legacy mode only)
        if (this.m_watson_legacy_bridge == true) {
            this.m_watson_iot_device_data_key = this.orchestrator().preferences().valueOf("iotf_device_data_key", this.m_suffix);
            if (this.m_watson_iot_device_data_key == null || this.m_watson_iot_device_data_key.length() <= 0) {
                // default
                this.m_watson_iot_device_data_key = "off";
            }
            if (this.m_watson_iot_device_data_key.equalsIgnoreCase("off")) {
                this.m_watson_iot_device_data_key = null;
            }
        }

        // Observation notifications
        this.m_watson_iot_observe_notification_topic = this.orchestrator().preferences().valueOf("iotf_observe_notification_topic", this.m_suffix).replace("__EVENT_TYPE__", this.m_observation_key);

        // Send CoAP commands back through mDS into the endpoint via these Topics... 
        if (this.legacyBridge() == true) {
            // lower-case for the topics
            this.m_watson_iot_coap_cmd_topic_get = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "get");
            this.m_watson_iot_coap_cmd_topic_put = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "put");
            this.m_watson_iot_coap_cmd_topic_post = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "post");
            this.m_watson_iot_coap_cmd_topic_delete = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "delete");
            this.m_watson_iot_coap_cmd_topic_api = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "api");
        }
        else {
            // upper-case for the topics
            this.m_watson_iot_coap_cmd_topic_get = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "GET");
            this.m_watson_iot_coap_cmd_topic_put = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "PUT");
            this.m_watson_iot_coap_cmd_topic_post = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "POST");
            this.m_watson_iot_coap_cmd_topic_delete = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "DELETE");
            this.m_watson_iot_coap_cmd_topic_api = this.orchestrator().preferences().valueOf("iotf_coap_cmd_topic", this.m_suffix).replace("__COMMAND_TYPE__", "API");
        }

        // establish default bindings
        this.m_watson_iot_api_key = this.orchestrator().preferences().valueOf("iotf_api_key", this.m_suffix).replace("__ORG_ID__", this.m_watson_iot_org_id).replace("__ORG_KEY__", this.m_watson_iot_org_key);
        this.m_watson_iot_auth_token = this.orchestrator().preferences().valueOf("iotf_auth_token", this.m_suffix);

        // resync org_id and m_watson_iot_org_key
        this.parseWatsonIoTUsername();

        // create the client ID
        this.m_client_id_template = this.orchestrator().preferences().valueOf("iotf_client_id_template", this.m_suffix).replace("__ORG_ID__", this.m_watson_iot_org_id);
        this.m_client_id = this.createWatsonIoTClientID(this.m_mds_domain);

        // Watson IoT Device Manager - will initialize and upsert our WatsonIoT bindings/metadata
        this.m_device_manager = new WatsonIoTDeviceManager(this.orchestrator().errorLogger(),this.orchestrator().preferences(),this.m_suffix,http,this.orchestrator());
        this.m_device_manager.updateWatsonIoTBindings(this.m_watson_iot_org_id, this.m_watson_iot_org_key);
        this.m_watson_iot_api_key = this.m_device_manager.updateUsernameBinding(this.m_watson_iot_api_key);
        this.m_watson_iot_auth_token = this.m_device_manager.updatePasswordBinding(this.m_watson_iot_auth_token);
        this.m_client_id = this.m_device_manager.updateClientIDBinding(this.m_client_id);
        this.m_mqtt_ip_address = this.m_device_manager.updateHostnameBinding(this.m_mqtt_ip_address);

        // RESET in case we want to just connect as an WatsonIoT Application
        if (this.orchestrator().preferences().booleanValueOf("iotf_force_app_binding", this.m_suffix) == true) {
            // DEBUG
            this.errorLogger().warning("WatsonIoT Processor: FORCED binding as WatsonIoT Application - ENABLED");

            // override - simply bind as a WatsonIoT applciation
            this.m_watson_iot_api_key = this.orchestrator().preferences().valueOf("iotf_api_key", this.m_suffix).replace("__ORG_ID__", this.m_watson_iot_org_id).replace("__ORG_KEY__", this.m_watson_iot_org_key);
            this.m_watson_iot_auth_token = this.orchestrator().preferences().valueOf("iotf_auth_token", this.m_suffix);

            // resync org_id and m_watson_iot_org_key
            this.parseWatsonIoTUsername();

            // create the client ID
            this.m_client_id_template = this.orchestrator().preferences().valueOf("iotf_client_id_template", this.m_suffix).replace("__ORG_ID__", this.m_watson_iot_org_id);
            this.m_client_id = this.createWatsonIoTClientID(this.m_mds_domain);
        }

        // create the transport
        mqtt.setUsername(this.m_watson_iot_api_key);
        mqtt.setPassword(this.m_watson_iot_auth_token);

        // add the transport
        this.initMQTTTransportList();
        this.addMQTTTransport(this.m_client_id, mqtt);

        // DEBUG
        //this.errorLogger().info("WatsonIoT Credentials: Username: " + this.m_mqtt.getUsername() + " PW: " + this.m_mqtt.getPassword());
    }

    // OVERRIDE: process a received new registration for WatsonIoT
    @Override
    protected void processRegistration(Map data, String key) {
        List endpoints = (List) data.get(key);
        for (int i = 0; endpoints != null && i < endpoints.size(); ++i) {
            Map endpoint = (Map) endpoints.get(i);
            List resources = (List) endpoint.get("resources");
            for (int j = 0; resources != null && j < resources.size(); ++j) {
                Map resource = (Map) resources.get(j);

                // re-subscribe
                if (this.subscriptionsManager().containsSubscription(this.m_mds_domain, (String) endpoint.get("ep"), (String) endpoint.get("ept"), (String) resource.get("path"))) {
                    // re-subscribe to this resource
                    this.orchestrator().subscribeToEndpointResource((String) endpoint.get("ep"), (String) resource.get("path"), false);

                    // SYNC: here we dont have to worry about Sync options - we simply dispatch the subscription to mDS and setup for it...
                    this.subscriptionsManager().removeSubscription(this.m_mds_domain, (String) endpoint.get("ep"), (String) endpoint.get("ept"), (String) resource.get("path"));
                    this.subscriptionsManager().addSubscription(this.m_mds_domain, (String) endpoint.get("ep"), (String) endpoint.get("ept"), (String) resource.get("path"), this.isObservableResource(resource));
                }

                // auto-subscribe
                else if (this.isObservableResource(resource) && this.m_auto_subscribe_to_obs_resources == true) {
                    // auto-subscribe to observable resources... if enabled.
                    this.orchestrator().subscribeToEndpointResource((String) endpoint.get("ep"), (String) resource.get("path"), false);

                    // SYNC: here we dont have to worry about Sync options - we simply dispatch the subscription to mDS and setup for it...
                    this.subscriptionsManager().removeSubscription(this.m_mds_domain, (String) endpoint.get("ep"), (String) endpoint.get("ept"), (String) resource.get("path"));
                    this.subscriptionsManager().addSubscription(this.m_mds_domain, (String) endpoint.get("ep"), (String) endpoint.get("ept"), (String) resource.get("path"), this.isObservableResource(resource));
                }
            }

            // invoke a GET to get the resource information for this endpoint... we will upsert the Metadata when it arrives
            this.retrieveEndpointAttributes(endpoint,this);
        }
    }
    
    // OVERRIDE: process a re-registration in WatsonIoT
    @Override
    public void processReRegistration(Map data) {
        List notifications = (List) data.get("reg-updates");
        for (int i = 0; notifications != null && i < notifications.size(); ++i) {
            Map entry = (Map) notifications.get(i);
            // DEBUG
            // this.errorLogger().info("WatsonIoT: CoAP re-registration: " + entry);
            if (this.hasSubscriptions((String) entry.get("ep")) == false) {
                // no subscriptions - so process as a new registration
                this.errorLogger().info("Watson IoT : CoAP re-registration: no subscriptions.. processing as new registration...");
                this.processRegistration(data,"reg-updates");
            }
            else {
                // already subscribed (OK)
                this.errorLogger().info("Watson IoT : CoAP re-registration: already subscribed (OK)");
            }
        }
    }

    // OVERRIDE: process a deregistration (deletion TEST)
    @Override
    public String[] processDeregistrations(Map parsed) {        
        // TEST: We can actually DELETE the device on deregistration to test device-delete before the device-delete message goes live
        if (this.orchestrator().deviceRemovedOnDeRegistration() == true) {
            // processing deregistration as device deletion
            this.errorLogger().info("processDeregistrations(Watson): processing de-registration as device deletion (OK).");
            this.processDeviceDeletions(parsed, true);
        }
        else {
            // not processing deregistration as a deletion
            this.errorLogger().info("processDeregistrations(Watson): Not processing de-registration as device deletion (OK).");
        }
        
        // always by default...
        return super.processDeregistrations(parsed);
    }
    
    // OVERRIDE: handle device deletions Watson IoT
    @Override
    public String[] processDeviceDeletions(Map parsed) {
        return this.processDeviceDeletions(parsed,false);
    }
    
    // handle device deletions Watson IoT
    private String[] processDeviceDeletions(Map parsed,boolean use_deregistration) {
        String[] deletions = null;
        if (use_deregistration == true) {
            deletions = super.processDeregistrations(parsed);
        }
        else {
            deletions = super.processDeviceDeletions(parsed);
        }
        for (int i = 0; deletions != null && i < deletions.length; ++i) {
            // DEBUG
            this.errorLogger().info("Watson IoT : processing device deletion for device: " + deletions[i]);

            // Watson IoT add-on... 
            this.unsubscribe(deletions[i]);

            // Remove from Watson IoT
            this.deleteDevice(deletions[i]);
        }
        return deletions;
    }

    // OVERRIDE: process a mDS notification for WatsonIoT
    @Override
    public void processNotification(Map data) {
        // DEBUG
        //this.errorLogger().info("processIncomingDeviceServerMessage(WatsonIoT)...");

        // get the list of parsed notifications
        List notifications = (List) data.get("notifications");
        for (int i = 0; notifications != null && i < notifications.size(); ++i) {
            // we have to process the payload... this may be dependent on being a string core type... 
            Map notification = (Map) notifications.get(i);

            // decode the Payload...
            String b64_coap_payload = (String) notification.get("payload");
            String decoded_coap_payload = Utils.decodeCoAPPayload(b64_coap_payload);

            // DEBUG
            //this.errorLogger().info("Watson IoT: Decoded Payload: " + decoded_coap_payload);
            // Try a JSON parse... if it succeeds, assume the payload is a composite JSON value...
            Map json_parsed = this.tryJSONParse(decoded_coap_payload);
            if (json_parsed != null && json_parsed.isEmpty() == false) {
                // add in a JSON object payload value directly... 
                notification.put("value", Utils.retypeMap(json_parsed, this.fundamentalTypeDecoder()));             // its JSON (flat...)                                                   // its JSON 
            }
            else {
                // add in a decoded payload value as a fundamental type...
                notification.put("value", this.fundamentalTypeDecoder().getFundamentalValue(decoded_coap_payload)); // its a Float, Integer, or String
            }

            // get our endpoint name
            String ep_name = (String) notification.get("ep");
            String path = (String) notification.get("path");

            if (this.legacyBridge() == false) {
                // add compatibility with the production version of IBM's Connector Bridge
                notification.put("resourceId", path.substring(1));           // strip leading "/" off of the URI...
                notification.put("deviceId", notification.get("ep"));        // ep
            }

            // we will send the raw CoAP JSON... WatsonIoT can parse that... 
            String coap_raw_json = this.jsonGenerator().generateJson(notification);

            // strip off []...
            String coap_json_stripped = this.stripArrayChars(coap_raw_json);

            // encapsulate into a coap/device packet...
            String iotf_coap_json = coap_json_stripped;
            if (this.m_watson_iot_device_data_key != null && this.m_watson_iot_device_data_key.length() > 0) {
                iotf_coap_json = "{ \"" + this.m_watson_iot_device_data_key + "\":" + coap_json_stripped + "}";
            }

            // DEBUG
            this.errorLogger().info("Watson IoT: CoAP notification: " + iotf_coap_json);

            // send to WatsonIoT...
            if (this.mqtt() != null) {
                boolean status = this.mqtt().sendMessage(this.customizeTopic(this.m_watson_iot_observe_notification_topic, ep_name, this.m_device_manager.getDeviceType(ep_name)), iotf_coap_json, QoS.AT_MOST_ONCE);
                if (status == true) {
                    // not connected
                    this.errorLogger().info("Watson IoT: CoAP notification sent. SUCCESS");
                }
                else {
                    // send failed
                    this.errorLogger().warning("Watson IoT: CoAP notification not sent. SEND FAILED");
                }
            }
            else {
                // not connected
                this.errorLogger().warning("Watson IoT: CoAP notification not sent. NOT CONNECTED");
            }
        }
    }

    // get our defaulted reply topic
    @Override
    public String getReplyTopic(String ep_name, String ep_type, String def) {
        String val = this.customizeTopic(this.m_watson_iot_observe_notification_topic, ep_name, ep_type).replace(this.m_observation_key, this.m_cmd_response_key);
        //this.errorLogger().warning("Watson IoT: REPLY TOPIC: " + val);
        return val;
    }
    
    // add a MQTT transport for a given endpoint - this is how MS AWSIoT MQTT integration works... 
    @Override 
    public boolean createAndStartMQTTForEndpoint(String ep_name, String ep_type, Topic topics[]) {
        // Watson IoT uses a shared MQTT connection for all endpoints... its already setup... so just return true
        return true;
    }

    // OVERRIDE: Connection to Watson IoT vs. stock MQTT...
    @Override
    protected boolean connectMQTT() {
        // if not connected attempt
        if (!this.isConnected()) {
            // use SSL for security
            this.mqtt().useSSLConnection(true);
            
            // ... but do not use self-signed certs/keys
            this.mqtt().noSelfSignedCertsOrKeys(true);
            
            // now connect
            if (this.mqtt().connect(this.m_mqtt_ip_address, this.m_mqtt_port, this.m_client_id, this.m_use_clean_session)) {
                this.orchestrator().errorLogger().info("Watson IoT: Setting CoAP command listener...");
                this.mqtt().setOnReceiveListener(this);
                this.orchestrator().errorLogger().info("Watson IoT: connection completed successfully");
            }
        }
        else {
            // already connected
            this.orchestrator().errorLogger().info("Watson IoT: Already connected (OK)...");
        }

        // return our connection status
        this.orchestrator().errorLogger().info("Watson IoT: Connection status: " + this.isConnected());
        return this.isConnected();
    }

    // OVERRIDE: (Listening) Topics for WatsonIoT vs. stock MQTT...
    @Override
    protected void subscribeToMQTTTopics() {
        // unused: WatsonIoT will have "listenable" topics for the CoAP verbs via the CMD event type...
    }

    // legacy mode
    private boolean legacyBridge() {
        return this.m_watson_legacy_bridge;
    }
    
    // parse the WatsonIoT Username
    private void parseWatsonIoTUsername() {
        String[] elements = this.m_watson_iot_api_key.replace("-", " ").split(" ");
        if (elements != null && elements.length >= 3) {
            this.m_watson_iot_org_id = elements[1];
            this.m_watson_iot_org_key = elements[2];
            //this.errorLogger().info("WatsonIoT: org_id: " + elements[1] + " apikey: " + elements[2]);
        }
        else {
            this.errorLogger().info("Watson IoT: unable to parse WatsonIoT Username: " + this.m_watson_iot_api_key);
        }
    }
    
    // create the WatsonIoT clientID
    private String createWatsonIoTClientID(String domain) {
        int length = 12;
        if (domain == null) {
            domain = this.prefValue("mds_def_domain", this.m_suffix);
        }
        if (domain.length() < 12) {
            length = domain.length();
        }
        return this.m_client_id_template + domain.substring(0, length);  // 12 digits only of the domain
    }

    // Watson IoT: create the endpoint WatsonIoT topic data
    @Override
    protected HashMap<String, Object> createEndpointTopicData(String ep_name, String ep_type) {
        HashMap<String, Object> topic_data = null;
        if (this.m_watson_iot_coap_cmd_topic_get != null) {
            Topic[] list = new Topic[NUM_COAP_VERBS];
            String[] topic_string_list = new String[NUM_COAP_VERBS];
            topic_string_list[0] = this.customizeTopic(this.m_watson_iot_coap_cmd_topic_get, ep_name, ep_type);
            topic_string_list[1] = this.customizeTopic(this.m_watson_iot_coap_cmd_topic_put, ep_name, ep_type);
            topic_string_list[2] = this.customizeTopic(this.m_watson_iot_coap_cmd_topic_post, ep_name, ep_type);
            topic_string_list[3] = this.customizeTopic(this.m_watson_iot_coap_cmd_topic_delete, ep_name, ep_type);
            for (int i = 0; i < NUM_COAP_VERBS; ++i) {
                list[i] = new Topic(topic_string_list[i], QoS.AT_LEAST_ONCE);
            }
            topic_data = new HashMap<>();
            topic_data.put("topic_list", list);
            topic_data.put("topic_string_list", topic_string_list);
        }
        return topic_data;
    }

    // final customization of a MQTT Topic...
    private String customizeTopic(String topic, String ep_name, String ep_type) {
        String cust_topic = topic.replace("__EPNAME__", ep_name);
        if (ep_type == null) {
            ep_type = this.getEndpointTypeFromEndpointName(ep_name);
        }
        if (ep_type != null) {
            cust_topic = cust_topic.replace("__DEVICE_TYPE__", ep_type);
            this.errorLogger().info("Watson IoT:  Customized Topic: " + cust_topic);
        }
        else {
            // replace with "default"
            cust_topic = cust_topic.replace("__DEVICE_TYPE__", "default");
            
            // WARN
            this.errorLogger().warning("Watson IoT Customized Topic (EPT UNK): " + cust_topic);
        }
        return cust_topic;
    }
    
    // Watson IoT: subscribe to the API Request topic for each device
    @Override
    protected void subscribe_to_api_request_topic(String ep_name) {
        Topic[] api_topics = new Topic[1];
        String topic_str = this.customizeTopic(this.m_watson_iot_coap_cmd_topic_api, ep_name, this.getEndpointTypeFromEndpointName(ep_name));
        api_topics[0] = new Topic(topic_str,QoS.AT_LEAST_ONCE);
        this.mqtt().subscribe(api_topics);
    }

    // subscribe to the WatsonIoT MQTT topics
    @Override
    public void subscribe_to_topics(String ep_name, Topic topics[]) {
        // subscribe to the device topics
        this.errorLogger().info("subscribe_to_topics(WatsonIoT): subscribing to topics...");
        this.mqtt().subscribe(topics);
        
        // now subscribe to the API topic
        this.errorLogger().info("subscribe_to_topics(WatsonIoT): subscribing to API request topic...");
        this.subscribe_to_api_request_topic(ep_name);
    }

    // Watson IoT Specific: un-register topics for CoAP commands
    @Override
    protected boolean unsubscribe(String ep_name) {
        boolean do_register = false;
        if (ep_name != null) {
            // DEBUG
            this.orchestrator().errorLogger().info("Watson IoT: Un-Subscribing to CoAP command topics for endpoint: " + ep_name);
            try {
                HashMap<String, Object> topic_data = (HashMap<String, Object>) this.m_endpoints.get(ep_name);
                if (topic_data != null) {
                    // unsubscribe...(Watson IoT specific MQTT handle...)
                    this.mqtt().unsubscribe((String[]) topic_data.get("topic_string_list"));
                }
                else {
                    // not in subscription list (OK)
                    this.orchestrator().errorLogger().info("Watson IoT: Endpoint: " + ep_name + " not in subscription list (OK).");
                    do_register = true;
                }
            }
            catch (Exception ex) {
                this.orchestrator().errorLogger().info("Watson IoT: Exception in unsubscribe for " + ep_name + " : " + ex.getMessage());
            }
        }
        else {
            this.orchestrator().errorLogger().info("Watson IoT: NULL Endpoint name in unsubscribe()... ignoring...");
        }

        // clean up
        if (ep_name != null) {
            this.m_endpoints.remove(ep_name);
        }

        // return the unsubscribe status
        return do_register;
    }
    
    // send the API Response back through the topic
    private void sendApiResponse(String topic,ApiResponse response) {        
        // publish
        this.mqtt().sendMessage(topic, response.createResponseJSON());
    }
    
    // CoAP command handler - processes CoAP commands coming over MQTT channel
    @Override
    public void onMessageReceive(String topic, String message) {
        // DEBUG
        this.errorLogger().info("Watson IoT(CoAP Command): Topic: " + topic + " message: " + message);
        
        // parse the topic to get the endpoint and CoAP verb
        // format: iot-2/type/mbed/id/mbed-eth-observe/cmd/put/fmt/json
        String ep_name = this.getEndpointNameFromTopic(topic);
                    
        // process any API requests...
        if (this.isApiRequest(message)) {
            // process the message
            String reply_topic = this.customizeTopic(this.m_watson_iot_observe_notification_topic, ep_name, this.m_device_manager.getDeviceType(ep_name));
            reply_topic = reply_topic.replace(this.m_observation_key,this.m_api_response_key);
            this.sendApiResponse(reply_topic,this.processApiRequestOperation(message));
            
            // return as we are done with the API request... no AsyncResponses necessary for raw API requests...
            return;
        }

        // pull the CoAP URI and Payload from the message itself... its JSON... 
        // format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get" }
        String uri = this.getCoAPURI(message);
        String value = this.getCoAPValue(message);

        // pull the CoAP verb from the message itself... its JSON... (PRIMARY)
        // format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get" }
        String coap_verb = this.getCoAPVerb(message);
        if (coap_verb == null || coap_verb.length() == 0) {
            // optionally pull the CoAP verb from the MQTT Topic (SECONDARY)
            coap_verb = this.getCoAPVerbFromTopic(topic);
        }

        // if the ep_name is wildcarded... get the endpoint name from the JSON payload
        // format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get" }
        if (ep_name == null || ep_name.length() <= 0 || ep_name.equalsIgnoreCase("+")) {
            ep_name = this.getCoAPEndpointName(message);
        }

        // if there are mDC/mDS REST options... lets add them
        // format: { "path":"/303/0/5850", "new_value":"0", "ep":"mbed-eth-observe", "coap_verb": "get", "options":"noResp=true" }
        String options = this.getRESTOptions(message);

        // dispatch the coap resource operation request (GET,PUT,POST,DELETE handled here)
        String response = this.orchestrator().processEndpointResourceOperation(coap_verb, ep_name, uri, value, options);

        // examine the response
        if (response != null && response.length() > 0) {
            // SYNC: We only process AsyncResponses from GET verbs... we dont sent HTTP status back through WatsonIoT.
            this.errorLogger().info("Watson IoT(CoAP Command): Response: " + response);

            // AsyncResponse detection and recording...
            if (this.isAsyncResponse(response) == true) {
                // CoAP GET and PUT provides AsyncResponses...
                if (coap_verb.equalsIgnoreCase("get") == true || coap_verb.equalsIgnoreCase("put") == true) {
                    // its an AsyncResponse.. so record it...
                    this.recordAsyncResponse(response, coap_verb, this.mqtt(), this, topic, this.getReplyTopic(ep_name, this.getEndpointTypeFromEndpointName(ep_name), uri), message, ep_name, uri);
                }
                else {
                    // we ignore AsyncResponses to PUT,POST,DELETE
                    this.errorLogger().info("Watson IoT(CoAP Command): Ignoring AsyncResponse for " + coap_verb + " (OK).");
                }
            }
            else if (coap_verb.equalsIgnoreCase("get")) {
                // not an AsyncResponse... so just emit it immediately... only for GET...
                this.errorLogger().info("Watson IoT(CoAP Command): Response: " + response + " from GET... creating observation...");

                // we have to format as an observation...
                String observation = this.createObservation(coap_verb, ep_name, uri, response);

                // DEBUG
                this.errorLogger().info("Watson IoT(CoAP Command): Sending Observation(GET): " + observation);

                // send the observation (GET reply)...
                if (this.mqtt() != null) {
                    String reply_topic = this.customizeTopic(this.m_watson_iot_observe_notification_topic, ep_name, this.m_device_manager.getDeviceType(ep_name));
                    reply_topic = reply_topic.replace(this.m_observation_key, this.m_cmd_response_key);
                    boolean status = this.mqtt().sendMessage(reply_topic, observation, QoS.AT_MOST_ONCE);
                    if (status == true) {
                        // success
                        this.errorLogger().info("Watson IoT(CoAP Command): CoAP observation(get) sent. SUCCESS");
                    }
                    else {
                        // send failed
                        this.errorLogger().warning("Watson IoT(CoAP Command): CoAP observation(get) not sent. SEND FAILED");
                    }
                }
                else {
                    // not connected
                    this.errorLogger().info("Watson IoT(CoAP Command): CoAP observation(get) not sent. NOT CONNECTED");
                }
            }
        }
    }

    // Watson IoT Specific: create an observation JSON as a response to a GET request...
    @Override
    protected String createObservation(String verb, String ep_name, String uri, String value) {
        // use the base class to create the observation
        String base_observation_json = super.createObservation(verb, ep_name, uri, value);
        
        // encapsulate into a Watson IoT compatible observation...
        String iotf_coap_json = base_observation_json;
        if (this.m_watson_iot_device_data_key != null && this.m_watson_iot_device_data_key.length() > 0) {
            iotf_coap_json = "{ \"" + this.m_watson_iot_device_data_key + "\":" + base_observation_json + "}";
        }

        // DEBUG
        this.errorLogger().info("Watson IoT: CoAP notification(" + verb + " REPLY): " + iotf_coap_json);

        // return the WatsonIoT-specific observation JSON...
        return iotf_coap_json;
    }

    // process new device registration
    @Override
    protected Boolean registerNewDevice(Map message) {
        if (this.m_device_manager != null) {
            return this.m_device_manager.registerNewDevice(message);
        }
        return false;
    }

    // process device de-registration
    @Override
    protected Boolean deleteDevice(String device) {
        if (this.m_device_manager != null) {
            return this.m_device_manager.deleteDevice(device);
        }
        return false;
    }

    // AsyncResponse response processor
    @Override
    public boolean processAsyncResponse(Map endpoint) {
        // with the attributes added, we finally create the device in Watson IoT
        this.completeNewDeviceRegistration(endpoint);

        // return our processing status
        return true;
    }
    
    // restart our device connection 
    @Override
    public boolean startReconnection(String ep_name,String ep_type,Topic topics[]) {
        // stop our listener thread
        this.stopListenerThread();
        
        // reset our default MQTT connection
        this.mqtt().disconnect();
        
        // create a new MQTT instance
        MQTTTransport mqtt = new MQTTTransport(this.errorLogger(), this.preferences(),null);
        this.setupDefaultMQTTTransport(mqtt);
        
        // re-connect...
        if (this.connectMQTT() == true) {
            // complete the device registration
            HashMap<String,Serializable> endpoint = new HashMap<>();
            endpoint.put("ep",ep_name);
            endpoint.put("ept",ep_type);
            this.completeNewDeviceRegistration(endpoint);
            return true;
        }
        return false;
    }

    // complete processing of adding the new device
    @Override
    public void completeNewDeviceRegistration(Map endpoint) {
        try {
            // create the device in WatsonIoT
            this.errorLogger().info("Watson IoT: completeNewDeviceRegistration: calling registerNewDevice(): " + endpoint);
            this.registerNewDevice(endpoint);
            this.errorLogger().info("Watson IoT: completeNewDeviceRegistration: registerNewDevice() completed");
        }
        catch (Exception ex) {
            this.errorLogger().warning("Watson IoT: completeNewDeviceRegistration: caught exception in registerNewDevice(): " + endpoint, ex);
        }

        try {
            // subscribe for WatsonIoT as well..
            String ep_name = (String) endpoint.get("ep");
            String ep_type = (String) endpoint.get("ept");
            this.errorLogger().info("Watson IoT: completeNewDeviceRegistration: calling subscribe(): " + endpoint);
            this.subscribe(ep_name,ep_type,this.createEndpointTopicData(ep_name, ep_type),this);
            this.errorLogger().info("Watson IoT: completeNewDeviceRegistration: subscribe() completed");
        }
        catch (Exception ex) {
            this.errorLogger().warning("Watson IoT: completeNewDeviceRegistration: caught exception in registerNewDevice(): " + endpoint, ex);
        }
    }
    
    // get the endpoint name from the MQTT topic
    @Override
    public String getEndpointNameFromTopic(String topic) {
        // format: iot-2/type/mbed/id/mbed-eth-observe/cmd/put/fmt/json
        return this.getTopicElement(topic, 4);
    }

    // get the CoAP verb from the MQTT topic
    @Override
    public String getCoAPVerbFromTopic(String topic) {
        // format: iot-2/type/mbed/id/mbed-eth-observe/cmd/put/fmt/json
        return this.getTopicElement(topic, 6);
    }
}
