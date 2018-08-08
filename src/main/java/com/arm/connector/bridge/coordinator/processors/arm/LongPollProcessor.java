/**
 * @file    LongPollProcessor.java
 * @brief mDS long polling processor
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
package com.arm.connector.bridge.coordinator.processors.arm;

import com.arm.connector.bridge.core.ErrorLogger;

/**
 * This class periodically polls mDS/mDC per the long-poll option.
 *
 * Notifications are dispatched to the bridge
 * in the same way that webhook-originated notifications are dispatched.
 *
 * @author Doug Anson
 */
public class LongPollProcessor extends Thread {

    private mbedDeviceServerProcessor m_mds = null;
    private boolean m_running = false;

    // default constructor
    public LongPollProcessor(mbedDeviceServerProcessor mds) {
        this.m_mds = mds;
        this.m_running = false;
    }

    // get our error logger
    private ErrorLogger errorLogger() {
        return this.m_mds.errorLogger();
    }

    // initialize the poller
    public void startPolling() {
        // DEBUG
        this.errorLogger().info("Beginning long polling...");

        // start our thread...
        this.start();
    }

    // poll 
    private void poll() {
        String response = null;

        // persistent GET over https()
        if (this.m_mds.usingSSLInWebhookEstablishment() == true) {
            // use SSL
            this.m_mds.errorLogger().info("poll: using HTTPS persistent get...");
            response = this.m_mds.persistentHTTPSGet(this.m_mds.longPollURL());
        }
        else {
            // no SSL
            this.m_mds.errorLogger().info("poll: using HTTP persistent get...");
            response = this.m_mds.persistentHTTPGet(this.m_mds.longPollURL());
        }
        
        // note the response code
        int last_code = this.m_mds.getLastResponseCode();
        if (last_code == 400) {
            // API key already has a callback webhook setup
            this.errorLogger().warning("poll: using API Key that has already setup a callback webhook... please use another key!");
        }
        else if (last_code == 401) {
            // API Key might be wrong?
            this.errorLogger().warning("poll: API Key does not appear to be valid (401 - Unauthorized). Please check the key.");
        }
        else if (last_code == 410) {
            // Pull channel is borked - reset API Key
            this.errorLogger().critical("poll: polling error code 410 seen. Pull channel is not functioning properly. Please create and use another API Key");
        }
        else {
            // OK
            this.errorLogger().info("poll: (OK).");
            
            // make sure we have a message to process...
            if (response != null && response.length() > 0) {
                // DEBUG
                this.errorLogger().info("poll: processing recevied message: " + response);
                
                // send whatever we get back as if we have received it via the webhook...
                this.m_mds.processDeviceServerMessage(response);
            }
            else {
                // DEBUG
                this.errorLogger().info("poll: received message: <empty>");
                
                // nothing to process
                this.errorLogger().info("poll: Nothing to process (OK)");
            }
        }
    }

    /**
     * run method for the receive thread
     */
    @Override
    public void run() {
        if (!this.m_running) {
            this.m_running = true;
            this.pollingLooper();
        }
    }

    /**
     * main thread loop
     */
    @SuppressWarnings("empty-statement")
    private void pollingLooper() {
        while (this.m_running == true) {
            // validate the webhook and subscriptions
            this.poll();
        }
    }
}
