/**
 * @file GenericConnectablePeerProcessor.java
 * @brief Generic Connectable peer processor for pelion bridge
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
package com.arm.pelion.bridge.coordinator.processors.arm;

import com.arm.pelion.bridge.coordinator.Orchestrator;
import com.arm.pelion.bridge.core.ApiResponse;
import com.arm.pelion.bridge.coordinator.processors.core.PeerProcessor;
import com.arm.pelion.bridge.coordinator.processors.interfaces.AsyncResponseProcessor;
import com.arm.pelion.bridge.transport.HttpTransport;
import com.arm.pelion.bridge.transport.MQTTTransport;
import com.arm.pelion.bridge.transport.Transport;
import com.arm.pelion.bridge.transport.TransportReceiveThread;
import com.arm.pelion.bridge.data.SerializableHashMap;
import java.util.HashMap;
import java.util.Map;
import com.arm.pelion.bridge.coordinator.processors.interfaces.ConnectionCreator;
import com.arm.pelion.bridge.coordinator.processors.interfaces.DeviceManagerToPeerProcessorInterface;
import java.util.List;
import com.arm.pelion.bridge.coordinator.processors.interfaces.PeerProcessorInterface;
import com.arm.pelion.bridge.core.Utils;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;

/**
 * Generic Connectable Peer Processor for pelion bridge. This processor provide basic MQTT and HTTP support for Peers
 *
 * @author Doug Anson
 */
public class GenericConnectablePeerProcessor extends PeerProcessor implements DeviceManagerToPeerProcessorInterface, Transport.ReceiveListener, PeerProcessorInterface {    
    // default HTTP auth qualifier
    public static final String DEFAULT_AUTH_TOKEN_QUALIFIER = "Bearer";         // Bearer tokens used by default
    
    // default maximum number of devices we can shadow
    private static final int MAX_DEVICE_SHADOWS = 100000;                       // 100,000 devices
    
    // default generic MQTT thread key
    private static String DEFAULT_GENERIC_RT_KEY = "__generic__";
    
    // default wait time
    private static int DEFAULT_RECONNNECT_SLEEP_TIME_MS = 15000;                // 15 seconds   
    
    // defaulted maximum api request ID
    private static int MAX_API_REQUEST_ID = 32768;
    
    private int m_next_api_request_id = 0;
    
    // API Request Topic
    private String m_api_request_topic = null;
    
    // Health Statistic Qualifier
    private String m_hs_qualifier = "MQTT";                                     // default is MQTT
    
    // is MQTT in use in this processor?
    private boolean m_mqtt_utilized = true;                                     // default is TRUE
    private boolean m_http_utilized = false;                                    // default is FALSE 
    
    protected String m_mqtt_host = null;
    protected int m_mqtt_port = 0;
    protected String m_client_id = null;
    protected HttpTransport m_http = null;
    protected boolean m_use_clean_session = false;
    private String m_device_data_key = null;
    private String m_default_tr_key = null;
    protected int m_reconnect_sleep_time_ms = DEFAULT_RECONNNECT_SLEEP_TIME_MS;
    protected int m_max_shadows = MAX_DEVICE_SHADOWS;
    
    private HashMap<String, MQTTTransport> m_mqtt = null;
    protected SerializableHashMap m_endpoints = null;
    protected HashMap<String, TransportReceiveThread> m_mqtt_thread_list = null;
    
    // Auth Token and qualifier
    protected String m_http_auth_qualifier = DEFAULT_AUTH_TOKEN_QUALIFIER;
    protected String m_http_auth_token = null;

    // Factory method for initializing the Sample 3rd Party peer
    public static GenericConnectablePeerProcessor createPeerProcessor(Orchestrator manager, HttpTransport http) {
        MQTTTransport mqtt = new MQTTTransport(manager.errorLogger(), manager.preferences(), null);
        GenericConnectablePeerProcessor proc = new GenericConnectablePeerProcessor(manager, mqtt, http);
        return proc;
    }
    
    // constructor (singleton)
    public GenericConnectablePeerProcessor(Orchestrator orchestrator, MQTTTransport mqtt, HttpTransport http) {
        this(orchestrator, mqtt, null, http);
    }

    // constructor (suffix for preferences)
    public GenericConnectablePeerProcessor(Orchestrator orchestrator, MQTTTransport mqtt, String suffix, HttpTransport http) {
        super(orchestrator, suffix);

        // HTTP support if we need it
        this.m_http = http;

        // MQTT transport list
        this.m_mqtt = new HashMap<>();
        
        // init our API Request ID
        this.m_next_api_request_id = 0;
        
        // by default, this processor makes use of MQTT
        this.m_mqtt_utilized = true;
        
        // by default, this processor does NOT make use of HTTP webhooks
        this.m_http_utilized = false;
        
        // create the API Request Topic
        this.m_api_request_topic = this.createApiRequestTopic();
        
        // initialize the endpoint map
        this.m_endpoints = new SerializableHashMap(orchestrator,"ENDPOINT_MAP");
        
        // initialize the listener thread map
        this.m_mqtt_thread_list = new HashMap<>();
        
        // get the max shadows override
        this.m_max_shadows = orchestrator.preferences().intValueOf("max_shadows",this.m_suffix);
        if (this.m_max_shadows <= 0) {
            this.m_max_shadows = MAX_DEVICE_SHADOWS;
        }
        this.errorLogger().warning("GenericConnectablePeerProcessor: Max Shadows Limit: " + this.getMaxNumberOfShadows() + " devices");
        
        // get the overriden default wait time
        this.m_reconnect_sleep_time_ms = orchestrator.preferences().intValueOf("mqtt_reconnect_sleep_time_ms",this.m_suffix);
        if (this.m_reconnect_sleep_time_ms <= 0) {
            this.m_reconnect_sleep_time_ms = DEFAULT_RECONNNECT_SLEEP_TIME_MS;
        }
        this.errorLogger().info("GenericConnectablePeerProcessor: Reconnect sleep time: " + this.m_reconnect_sleep_time_ms + "ms...");
        
        // get the default generic thread key 
        this.m_default_tr_key = orchestrator.preferences().valueOf("mqtt_default_rt_key",this.m_suffix);
        if (this.m_default_tr_key == null || this.m_default_tr_key.length() == 0) {
            this.m_default_tr_key = DEFAULT_GENERIC_RT_KEY;
        }
        
        // initialize the topic root (MQTT)
        this.initTopicRoot("mqtt_mds_topic_root");

        // Get the device data key if one exists
        this.m_device_data_key = orchestrator.preferences().valueOf("mqtt_device_data_key", this.m_suffix);

        // build out our configuration
        this.m_mqtt_host = orchestrator.preferences().valueOf("mqtt_address", this.m_suffix);
        this.m_mqtt_port = orchestrator.preferences().intValueOf("mqtt_port", this.m_suffix);

        // establish the MQTT mDS request tag
        this.initRequestTag("mds_mqtt_request_tag");
        
        // clean session
        this.m_use_clean_session = this.orchestrator().preferences().booleanValueOf("mqtt_clean_session", this.m_suffix);

        // auto-subscribe behavior
        this.initAutoSubscribe("mqtt_obs_auto_subscribe");

        // setup our defaulted MQTT transport if given one
        this.setupDefaultMQTTTransport(mqtt);
    }
    
    // default # of devices we can shadow
    protected int getMaxNumberOfShadows() {
        return this.m_max_shadows;
    }
    
    // not using MQTT in this peer
    protected void notUsingMQTT() {
        this.m_mqtt_utilized = false;
    }
    
    // MQTT usage status
    public boolean mqttInUse() {
        return this.m_mqtt_utilized;
    }
    
    // using HTTP in this peer
    protected void isUsingHTTP() {
        this.m_http_utilized = true;
        this.m_hs_qualifier = "HTTP";   // adjust the qualifier for HTTP
    }
    
    // HTTP usage status
    public boolean httpInUse() {
        return this.m_http_utilized;
    }
    
    // Get the HS Qualifier
    public String hsQualifier() {
        return this.m_hs_qualifier;
    }
    
    // process the REST api request
    protected ApiResponse processApiRequestOperation(String message) {
        // pull the fields from the message and santize them as much as we can...
        String uri = this.sanitizeURIStructure(this.getApiURIFromMessage(message));
        String data = this.getApiDataFromMessage(message);
        String options = this.sanitizeRESTOptions(this.getApiOptionsFromMessage(message));
        String verb = this.sanitizeHTTPVerb(this.getApiRequestVerbFromMessage(message));
        String api_key = this.sanitizeAPIKey(this.getApiRequestAPIKeyFromMessage(message));
        String caller_id = this.sanitizeCallerID(this.getApiRequestCallerIDFromMessage(message));
        String content_type = this.sanitizeContentType(this.getApiContentTypeFromMessage(message));

        // call the orchestrator to route this API request for processing...
        return this.orchestrator().processApiRequestOperation(uri,data,options,verb,this.getNextApiRequestId(),api_key,caller_id,content_type);
    }
    
    // get the api content type from the request message
    private String getApiContentTypeFromMessage(String message) {
        return this.getJSONStringValueFromJSONKey(message,"api_content_type");
    }
    
    // get the api caller id from the request message
    private String getApiRequestCallerIDFromMessage(String message) {
        return this.getJSONStringValueFromJSONKey(message,"api_caller_id");
    }
    
    // get the api data from the request message
    private String getApiDataFromMessage(String message) {
        return this.getJSONStringValueFromJSONKey(message,"api_request_data");
    }
    
    // get the api URI from the request message
    private String getApiURIFromMessage(String message) {
        return this.getJSONStringValueFromJSONKey(message,"api_uri");
    }
    
    // get the api options from the request message
    private String getApiOptionsFromMessage(String message) {
        return this.getJSONStringValueFromJSONKey(message,"api_options");
    }
    
    // get the api request verb from the request message
    private String getApiRequestVerbFromMessage(String message) {
        return this.getJSONStringValueFromJSONKey(message,"api_verb");
    }
    
    // get the api APIKey from the request message
    private String getApiRequestAPIKeyFromMessage(String message) {
        return this.getJSONStringValueFromJSONKey(message,"api_key");
    }
    
    // get the api request ID from the request message
    private int getNextApiRequestId() {
        ++this.m_next_api_request_id;
        if (this.m_next_api_request_id >= MAX_API_REQUEST_ID) {
            m_next_api_request_id = 1;
        }
        return this.m_next_api_request_id;
    }
    
    // create a fudged topic (since we dont use mqtt...)
    protected String createFudgedTopic(String ep_name) {
        // we can fudge the topic... it really wont be needed for anything other than the EP_NAME
        // the format is: <topic_root>/<verb>/<ept>/<ep>/
        return "dontcare/dontcare/dontcare/" + ep_name + "/";
    }
    
    // get the endpoint name from the topic (notification topic sent) 
    // format: <topic_root>/notify/<ep_type>/<endpoint name>/<URI> POSITION SENSITIVE
    protected String getEndpointNameFromNotificationTopic(String topic) {
        String[] items = topic.split("/");
        if (items.length >= 4 && items[3].trim().length() > 0) { // POSITION SENSITIVE
            return items[3].trim();                              // POSITION SENSITIVE
        }
        return null;
    }
    
    // get the JSON value from the JSON key
    private String getJSONStringValueFromJSONKey(String json,String key) {
        Map parsed = this.tryJSONParse(json);
        if (parsed != null) {
            try {
                // if empty.. return NULL
                if (parsed.get(key) == null) {
                    this.errorLogger().info("GenericConnectablePeerProcessor: EMPTY. Key: " + key);
                    return null;
                }
                
                // if instance of String, keep formatting
                if (parsed.get(key) instanceof String) {
                    // convert to string
                    String s_val = (String)parsed.get(key);
                    
                    // handle empty strings
                    if (s_val == null || s_val.length() == 0) {
                        s_val = null;
                    }
                    
                    // DEBUG
                    this.errorLogger().info("GenericConnectablePeerProcessor: STRING. Key: " + key + " DATA: " + s_val);
                    
                    // return the string
                    return s_val;
                }
                
                // if instance of Integer, convert to String
                if (parsed.get(key) instanceof Integer) {
                    // convert to Integer
                    Integer i_val = (Integer)parsed.get(key);
                    
                    // DEBUG
                    this.errorLogger().info("GenericConnectablePeerProcessor: INTEGER. Key: " + key + " DATA: " + i_val);
                    
                    // convert to String
                    return "" + i_val;
                }
                
                // if instance of Float, convert to String
                if (parsed.get(key) instanceof Float) {                    
                    // convert to Float
                    Float f_val = (Float)parsed.get(key);

                    // DEBUG
                    this.errorLogger().info("GenericConnectablePeerProcessor: FLOAT. Key: " + key + " DATA: " + f_val);

                    // convert to String
                    return "" + f_val;
                }
                
                // if instance of Map, then its JSON
                if (parsed.get(key) instanceof Map) {                    
                    // convert to Map
                    Map map = (Map)parsed.get(key);
                   
                    // DEBUG
                    this.errorLogger().info("GenericConnectablePeerProcessor: MAP. Key: " + key + " DATA: " + map);
                    
                    // create String with JSON generator
                    return this.jsonGenerator().generateJson(map);
                }
                
                // if instance of List, then its JSON
                if (parsed.get(key) instanceof List) {
                    // convert to Map
                    List list = (List)parsed.get(key);
                    
                    // DEBUG
                    this.errorLogger().info("GenericConnectablePeerProcessor: LIST. Key: " + key + " DATA: " + list);
                    
                    // create String with JSON generator
                    return this.jsonGenerator().generateJson(list);
                }
                
                // unknown type
                HashMap<String,String> error = new HashMap<>();
                String error_type = parsed.get(key).getClass().getTypeName();
                
                // create the Map
                error.put("type", error_type);
                
                // DEBUG
                this.errorLogger().warning("GenericConnectablePeerProcessor: UNEXPECTED TYPE. Key: " + key + " TYPE: " + error_type + " Location: ",new Exception());
                
                // create String with JSON generator
                return this.jsonGenerator().generateJson(error);
                
            }
            catch(Exception ex) {
                // exception caught
                this.errorLogger().critical("GenericConnectablePeerProcessor: ERROR Exception: " + ex.getMessage() + " Key: " + key, ex);
            }
        }
        return null;
    }
    
    // send the API Response back through the topic
    private void sendApiResponse(String topic,ApiResponse response) {        
        // publish
        this.mqtt().sendMessage(topic, response.createResponseJSON());
    }
    
    // messages from MQTT come here and are processed...
    @Override
    public void onMessageReceive(String topic, String message) {
        // process any API requests...
        if (this.isApiRequest(message)) {
            // process the message
            String reply_topic = this.createBaseTopic(this.m_api_response_key);
            this.sendApiResponse(reply_topic,this.processApiRequestOperation(message));
            
            // return as we are done with the API request... no AsyncResponses necessary for raw API requests...
            return;
        }
        
        // just process via super class
        super.onMessageReceive(topic, message);
    }
    
    // default MQTT Thread listener setup
    protected void setupDefaultMQTTTransport(MQTTTransport mqtt) {
        if (mqtt != null) {
            // clear our single list
            this.initMQTTTransportList();
            
            // assign our MQTT transport if we have one...
            this.m_client_id = mqtt.getClientID();
            this.addMQTTTransport(this.m_client_id, mqtt);
            
            // MQTT PeerProcessor listener thread setup
            TransportReceiveThread rt = new TransportReceiveThread(this.mqtt());
            rt.setOnReceiveListener(this);
            this.m_mqtt_thread_list.put(this.m_default_tr_key, rt);
        }
    }
    
    // subscribe to new devices
    @Override
    public void completeNewDeviceRegistration(Map endpoint) {
        // unused in base class
    }
    
    // create the API request topic
    protected String createApiRequestTopic() {
        return this.getTopicRoot() + "/api";
    }
    
    // subscribe to the API request topic
    protected void subscribeToAPIRequestTopic(String ep_name) {
        Topic[] api_topics = new Topic[1];
        api_topics[0] = new Topic(this.m_api_request_topic,QoS.AT_LEAST_ONCE);
        if (ep_name != null) {
            this.mqtt(ep_name).subscribe(api_topics);
        }
        else {
            this.mqtt().subscribe(api_topics);
        }
    }
    
    // subscribe MQTT Topics
    protected void subscribeToTopics(String ep_name, Topic topics[]) {
        if (this.mqtt(ep_name) != null) {
            // subscribe to endpoint specific topics
            this.errorLogger().info("GenericConnectablePeerProcessor(subscribe_to_topics): subscribing to topics...");
            this.mqtt(ep_name).subscribe(topics);
            
            // subscribe to the API Request topic
            this.errorLogger().info("GenericConnectablePeerProcessor(subscribe_to_topics): subscribing to API request topic...");
            this.subscribeToAPIRequestTopic(ep_name);
        }
    }

    // does this endpoint already have registered subscriptions?
    protected boolean hasSubscriptions(String ep_name) {
        try {
            if (this.m_endpoints.get(ep_name) != null) {
                HashMap<String, Object> topic_data = (HashMap<String, Object>) this.m_endpoints.get(ep_name);
                if (topic_data != null && topic_data.size() > 0) {
                    return true;
                }
            }
        }
        catch (Exception ex) {
            //silent
        }
        return false;
    }
    
    // create endpoint topic data
    protected HashMap<String, Object> createEndpointTopicData(String ep_name, String ep_type) {
        // base class is empty
        return null;
    }
    
    // register topics for CoAP commands
    protected void subscribe(String ep_name, String ep_type,HashMap<String, Object> topic_data,ConnectionCreator cc) {
        if (ep_name != null && this.validateMQTTConnection(cc, ep_name, ep_type, null)) {
            // DEBUG
            this.orchestrator().errorLogger().info("GenericConnectablePeerProcessor: Subscribing to CoAP command topics for endpoint: " + ep_name + " type: " + ep_type);
            try {
                if (topic_data != null) {
                    // get,put,post,delete enablement
                    this.m_endpoints.remove(ep_name);
                    this.m_endpoints.put(ep_name, topic_data);
                    this.setEndpointTypeFromEndpointName(ep_name, ep_type);
                    cc.subscribeToTopics(ep_name, (Topic[]) topic_data.get("topic_list"));
                }
                else {
                    // unable to register as topic data is NULL
                    this.orchestrator().errorLogger().warning("GenericConnectablePeerProcessor: GET/PUT/POST/DELETE topic data NULL. Unable to subscribe(): ep_name: " + ep_name + " ept: " + ep_type);
                }
            }
            catch (Exception ex) {
                this.orchestrator().errorLogger().warning("GenericConnectablePeerProcessor: Exception in subscribe for " + ep_name + " : " + ex.getMessage(),ex);
            }
        }
        else if (ep_name != null) {
            // unable to validate the MQTT connection
            this.orchestrator().errorLogger().warning("GenericConnectablePeerProcessor: Unable to validate MQTT connection. Unable to subscribe(): ep_name: " + ep_name + " ept: " + ep_type);
        }
        else {
            // NULL endpoint name
            this.orchestrator().errorLogger().warning("GenericConnectablePeerProcessor: NULL Endpoint name in subscribe()... ignoring...");
        }
    }
    
    // validate the MQTT Connection
    protected synchronized boolean validateMQTTConnection(ConnectionCreator cc,String ep_name, String ep_type, Topic topics[]) {
        if (cc != null) {
            // create a MQTT connection via the connector validator
            return cc.createAndStartMQTTForEndpoint(ep_name, ep_type, topics);
        }
        else {
            // invalid params
            this.errorLogger().critical("GenericConnectablePeerProcessor: validateMQTTConnection(). Unable to call createAndStartMQTTForEndpoint() as ConnectionCreator parameter is NULL");
        }
        return false;
    }
    
    // un-register topics for CoAP commands
    protected boolean unsubscribe(String ep_name) {
        boolean unsubscribed = false;
        if (ep_name != null && this.mqtt(ep_name) != null) {
            // DEBUG
            this.orchestrator().errorLogger().info("GenericConnectablePeerProcessor: Un-Subscribing to CoAP command topics for device: " + ep_name);
            try {
                HashMap<String, Object> topic_data = (HashMap<String, Object>) this.m_endpoints.get(ep_name);
                if (topic_data != null) {
                    // unsubscribe...
                    this.mqtt(ep_name).unsubscribe((String[]) topic_data.get("topic_string_list"));
                }
                else {
                    // not in subscription list (OK)
                    this.orchestrator().errorLogger().info("GenericConnectablePeerProcessor: Device: " + ep_name + " not in subscription list (OK).");
                    unsubscribed = true;
                }
            }
            catch (Exception ex) {
                this.orchestrator().errorLogger().info("GenericConnectablePeerProcessor: Exception in unsubscribe for " + ep_name + " : " + ex.getMessage());
            }
        }
        else if (this.mqtt(ep_name) != null) {
            this.orchestrator().errorLogger().info("GenericConnectablePeerProcessor: NULL device name... ignoring unsubscribe()...");
            unsubscribed = true;
        }
        else {
            this.orchestrator().errorLogger().info("GenericConnectablePeerProcessor: No MQTT connection for " + ep_name + "... ignoring unsubscribe()...");
            unsubscribed = true;
        }

        // clean up
        if (ep_name != null) {
            this.m_endpoints.remove(ep_name);
            this.removeEndpointTypeFromEndpointName(ep_name);
        }

        // return the unsubscribe status
        return unsubscribed;
    }

    // discover the endpoint attributes
    protected void retrieveEndpointAttributes(Map endpoint,AsyncResponseProcessor arp) {
        // DEBUG
        this.errorLogger().info("GenericConnectablePeerProcessor: Getting device attributes for device: " + endpoint);

        // pre-populate the new endpoint with initial values for registration
        this.orchestrator().pullDeviceMetadata(endpoint, arp);
    }
    
    // create our MQTT-based authentication hash
    @Override
    public String createAuthenticationHash() {
        return this.mqtt().createAuthenticationHash();
    }
    
    // disconnect
    protected void disconnect(String ep_name) {
        if (this.isConnected(ep_name)) {
            // DEBUG
            this.errorLogger().warning("GenericConnectablePeerProcessor: Disconnecting MQTT for device: " + ep_name + "...");
            this.mqtt(ep_name).disconnect(true);
        }
        this.remove(ep_name);
    }
    
    // are we connected (indexed by ep_name)
    protected boolean isConnected(String ep_name) {
        if (this.mqtt(ep_name) != null) {
            return this.mqtt(ep_name).isConnected();
        }
        return false;
    }
    
    // are we connected (non-indexed)
    protected boolean isConnected() {
        if (this.mqtt() != null) {
            return this.mqtt().isConnected();
        }
        return false;
    }
    
    // start our MQTT listener
    @Override
    public void initListener() {
        // connect and begin listening for requests (wildcard based on request TAG and domain)
        if (this.connectMQTT()) {
            this.subscribeToMQTTTopics();
            TransportReceiveThread rt = this.m_mqtt_thread_list.get(this.m_default_tr_key);
            if (rt != null) {
                rt.start();
            }
        }
    }

    // stop our MQTT listener
    @Override
    public void stopListener() {
        if (this.mqtt() != null) {
            this.mqtt().disconnect();
        }
    }
    
    // reformat topic to fit draft MQTT standard
    private String draftObservationTopicReformat(String topic, String message) {
        String reformatted_topic = topic;
    
        // parse the actual message...
        Map parsed = this.orchestrator().getJSONParser().parseJson(message);
        
        // get the endpoint name
        String ep = Utils.valueFromValidKey(parsed, "ep", "deviceId");
        
        // construct the modified observation topic
        reformatted_topic = this.orchestrator().getTenantID() + "/lwm2m/ob/" + ep; 
        
        // return the reformatted topic
        return reformatted_topic;
    }
    
    // reformat message to fit draft MQTT standard
    private byte[] draftMessageReformat(String topic, String message) {
        Integer token = 0;
        
        // parse the actual message...
        Map parsed = this.orchestrator().getJSONParser().parseJson(message);
        
        // get the uri/paths
        String paths = Utils.valueFromValidKey(parsed, "path", "uri");
        
        // handle the token
        if (parsed.containsKey("token") == true) {
            token = (Integer)parsed.get("token");
        }
        
        // construct the new message format...
        HashMap<String,Object> msg = new HashMap<>();
        msg.put("operation",19);
        msg.put("token",token);
        msg.put("paths",paths);
        
        try { 
            // DEBUG
            this.errorLogger().info("GenericConnectablePeerProcessor(DRAFT): PAYLOAD: " + parsed.get("payload"));
            
            // process the CoAP payload back into native JSON format
            String b64_coap_payload = (String)parsed.get("payload");
            Object obj = Utils.decodeCoAPPayloadToObject(b64_coap_payload);
            msg.put("payload",obj);
        }
        catch (Exception ex) {
            // warn that we were unable to process the payload... so just attach
            if (parsed != null && parsed.get("payload") != null) {
                msg.put("payload",parsed.get("payload"));
            }
            else {
                // unable to transfer payload
                this.errorLogger().warning("GenericConnectablePeerProcessor(DRAFT): EMPTY payload found.");
                msg.put("payload",new HashMap<String,Object>());
            }
        }
        
        // Create the JSON...
        String json_str = this.orchestrator().getJSONGenerator().generateJson(msg);
        
        // Convert the JSON to CBOR and return the raw bytes
        return this.jsonToCbor(json_str);
    }
    
    // GenericSender Implementation: send a message (binary)
    @Override
    public boolean sendMessage(String topic, byte[] bytes) { 
        String msg = null;
        if (this.draftMQTTFormatsEnabled() == true) {
            // CBOR format
            msg = this.cborToJson(bytes);
        }
        else {
            // String format
            msg = new String(bytes);
        }
        
        // DEBUG
        this.errorLogger().info("GenericProcessor(BINARY): topic: " + topic + " message: " + msg);

        // send the message over MQTT
        return this.mqtt().sendMessage(topic, bytes);
    }
    
    // GenericSender Implementation: send a message (string)
    @Override
    public boolean sendMessage(String topic, String json_str) {  
        boolean sent = false;
        
        // reformat if MQTT DRAFT formats are enabled
        if (this.draftMQTTFormatsEnabled() == true) {
            // reformat topic
            String reformatted_topic = this.draftObservationTopicReformat(topic,new String(json_str));
            
            // reformat message
            byte[] reformatted_bytes = this.draftMessageReformat(topic,new String(json_str));
            
            // send a message over MQTT (reformatted)
            this.errorLogger().info("GenericProcessor(DRAFT_FORMAT): topic: " + reformatted_topic + " message: " + this.cborToJson(reformatted_bytes));
            
            // send the message over MQTT
            this.mqtt().sendMessage(reformatted_topic, reformatted_bytes);
        }
       
        // send a message over MQTT
        this.errorLogger().info("GenericProcessor(): topic: " + topic + " message: " + new String(json_str));
        
        // send the message over MQTT
        sent = this.mqtt().sendMessage(topic, json_str);
        
        // return our status
        return sent;
    }
    
    // process an inbound command message to this peer
    protected String processCommandMessage(String json,HttpServletRequest request) {
        // unused in base class
        return "{}";
    }
    
    // process an inbound command message to this peer
    @Override
    public void processCommandMessage(HttpServletRequest request, HttpServletResponse response) {
        String response_json = "{}";
        String response_headers = "";
        
        // read the request...
        String json = this.read(request);
        if (json != null && json.length() > 0 && json.equalsIgnoreCase("{}") == false) {
            // process and route the mbed Cloud message
            response_json = this.processCommandMessage(json, request);
        }
        
        // ALWAYS send the response back as an ACK to command requestor
        this.sendResponseToCommandRequestor("application/json;charset=utf-8", request, response, response_headers, response_json);
    }

    // OVERRIDE: Topics for stock MQTT...
    protected void subscribeToMQTTTopics() {
        String request_topic_str = this.getTopicRoot() + this.getRequestTag() + "/#";
        this.errorLogger().warning("GenericConnectablePeerProcessor(subscribeToMQTTTopics): listening on REQUEST topic: " + request_topic_str);
        Topic request_topic = new Topic(request_topic_str, QoS.AT_LEAST_ONCE);
        Topic[] topic_list = {request_topic};
        this.mqtt().subscribe(topic_list);
        
        try {
            // additionally add subscriptions if draft MQTT formats are enabled...
            if (this.draftMQTTFormatsEnabled() == true) {
                String draft_request_topic_str = this.orchestrator().getTenantID() + "/lwm2m/rd/+/uplink";
                this.errorLogger().warning("GenericConnectablePeerProcessor(subscribeToMQTTTopics): listening on REQUEST topic (DRAFT FORMAT): " + draft_request_topic_str);
                Topic draft_request_topic = new Topic(draft_request_topic_str, QoS.AT_LEAST_ONCE);
                Topic[] draft_topic_list = {draft_request_topic};
                this.mqtt().subscribe(draft_topic_list);
            }
        }
        catch (Exception ex) {
            this.errorLogger().warning("GenericConnectablePeerProcessor(subscribeToMQTTTopics): Exception: " + ex.getMessage(),ex);
        }
    }

    // get our defaulted reply topic (defaulted)
    public String getReplyTopic(String ep_name, String ep_type, String def) {
        return def;
    }
    
    // OVERRIDE: Connection stock MQTT...
    protected boolean connectMQTT() {
        if (this.mqtt() != null && this.m_mqtt_host != null && this.m_mqtt_host.length() > 0 && this.m_mqtt_host.equalsIgnoreCase("Your_MQTT_broker_IP_address_Goes_Here") == false) {
            return this.mqtt().connect(this.m_mqtt_host, this.m_mqtt_port, null, true);
        }
        return false;
    }

    // add a MQTT transport instance
    protected synchronized void addMQTTTransport(String id, MQTTTransport mqtt) {
        if (this.m_mqtt != null) {
            this.m_mqtt.remove(id);
            this.m_mqtt.put(id, mqtt);
        }
    }

    // initialize the MQTT transport instance list
    protected void initMQTTTransportList() {
        this.closeMQTTTransportList();
        if (this.m_mqtt != null) {
            this.m_mqtt.clear();
        }
    }
    
    // PROTECTED: get the MQTT transport for the default clientID
    protected MQTTTransport mqtt() {
        if (this.m_mqtt != null) {
            return this.mqtt(this.m_client_id); // clientID is default "id"
        }
        return null;
    }

    // PROTECTED: get the MQTT transport for a given clientID
    protected MQTTTransport mqtt(String id) {
        if (this.m_mqtt != null) {
            return this.m_mqtt.get(id);
        }
        return null;
    }

    // PROTECTED: remove MQTT Transport for a given clientID
    protected synchronized void remove(String id) {
        if (this.m_mqtt != null) {
            this.m_mqtt.remove(id);
        }
    }

    // close the tranports in the list
    private void closeMQTTTransportList() {
        for (String key : this.m_mqtt.keySet()) {
            try {
                MQTTTransport mqtt = this.m_mqtt.get(key);
                if (mqtt != null) {
                    if (mqtt.isConnected()) {
                        mqtt.disconnect(true);
                    }
                }
            }
            catch (Exception ex) {
                // silent
            }
        }
    }
    
    // Health Stats: Get connection status from MQTT connection(s)
    public boolean mqttConnectionsOK() {
        boolean ok = true; 
        
        // in some instances, we are not using MQTT... so we can ignore our status
        if (this.m_mqtt_utilized == true) {
            // we are using MQTT - so check our real status...
            for (Map.Entry<String, MQTTTransport> entry : m_mqtt.entrySet()) {
                MQTTTransport t = entry.getValue();
                if (t != null) {
                    ok = (ok & t.isConnected());
                }
            }   
        }
        
        return ok;
    }

    // stop the defaulted listener thread
    protected void stopListenerThread() {
        this.stopListenerThread(this.m_default_tr_key);
    }
    
    // stop the listener thread
    protected void stopListenerThread(String ep_name) {
        // ensure we only have 1 thread/endpoint
        if (this.m_mqtt_thread_list.get(ep_name) != null) {
            TransportReceiveThread listener = (TransportReceiveThread) this.m_mqtt_thread_list.get(ep_name);
            this.m_mqtt_thread_list.remove(ep_name);
            listener.halt();
        }
    }
    
    // HTTP support for this peer processor 
    @Override
    public String httpsGet(String url) {
        return this.httpsGet(this.m_http,url);
    }
    
    // GET specific data to a given URL 
    protected String httpsGet(HttpTransport http,String url) {
        http.setAuthorizationQualifier(this.m_http_auth_qualifier);
        String result = http.httpsGetApiTokenAuth(url, this.m_http_auth_token, null, "application/json");
        return result;
    }

    // PUT specific data to a given URL (with data)
    @Override
    public String httpsPut(String url, String payload) {
        return this.httpsPut(this.m_http,url,payload);
    }
    
    // PUT specific data to a given URL (with data)
    protected String httpsPut(HttpTransport http,String url, String payload) {
        http.setAuthorizationQualifier(this.m_http_auth_qualifier);
        String result = http.httpsPutApiTokenAuth(url, this.m_http_auth_token, payload, "application/json");
        return result;
    }
    
    // PATCH specific data to a given URL (with data)
    @Override
    public String httpsPatch(String url, String payload) {
        return this.httpsPatch(this.m_http,url,payload);
    }
    
    // PATCH specific data to a given URL (with data)
    protected String httpsPatch(HttpTransport http,String url, String payload) {
        http.setAuthorizationQualifier(this.m_http_auth_qualifier);
        String result = http.httpsPatchApiTokenAuth(url, this.m_http_auth_token, payload, "application/json");
        return result;
    }
    
    // POST specific data to a given URL (with data)
    @Override
    public String httpsPost(String url, String payload) {
        return this.httpsPost(this.m_http,url,payload);
    }
    
    // POST specific data to a given URL (with data)
    protected String httpsPost(HttpTransport http, String url, String payload) {
        http.setAuthorizationQualifier(this.m_http_auth_qualifier);
        String result = http.httpsPostApiTokenAuth(url, this.m_http_auth_token, payload, "application/json");
        return result;
    }

    // DELETE specific data to a given URL (with data)
    protected String httpsDelete(String url, String etag) {
        return this.httpsDelete(this.m_http, url, etag, null);
    }
    
    // DELETE specific data to a given URL (with data)
    protected String httpsDelete(HttpTransport http,String url, String etag) {
        return this.httpsDelete(http, url, etag, null);
    }

    // DELETE specific data to a given URL (with data)
    @Override
    public String httpsDelete(String url, String etag, String payload) {
        return this.httpsDelete(this.m_http, url, etag, payload);
    }
    
    // DELETE specific data to a given URL (with data)
    protected String httpsDelete(HttpTransport http, String url, String etag, String payload) {
        http.setAuthorizationQualifier(this.m_http_auth_qualifier);
        http.setETagValue(etag);             // ETag header required...
        http.setIfMatchValue("*");           // If-Match header required... 
        String result = http.httpsDeleteApiTokenAuth(url, this.m_http_auth_token, payload, "application/json");
        return result;
    }
    
    // get HTTP if needed
    protected HttpTransport http() {
        return this.m_http;
    }
    
    // validte the HTTP status if being used in this processor
    public boolean httpStatusOK() {
        boolean ok = true; 
        
        // in some instances, we are not using HTTP... so we can ignore our status
        if (this.m_http_utilized == true) {
            // XXX IMPLEMENT
        }
        
        return ok;
    }
}