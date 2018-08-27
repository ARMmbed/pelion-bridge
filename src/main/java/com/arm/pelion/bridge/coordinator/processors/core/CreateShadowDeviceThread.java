/**
 * @file ErrorLogger.java
 * @brief error logging facility
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
package com.arm.pelion.bridge.coordinator.processors.core;

import com.arm.pelion.bridge.coordinator.processors.arm.PelionProcessor;
import com.arm.pelion.bridge.core.BaseClass;
import java.util.Map;

/**
 * Threaded dispatching of creating a device's shadow
 * @author Doug Anson
 */
public class CreateShadowDeviceThread extends BaseClass implements Runnable {
    private PelionProcessor m_pelion_processor = null;
    private Map m_device = null;
    
    // constructor
    public CreateShadowDeviceThread(PelionProcessor pelion_processor,Map device) {
        super(pelion_processor.errorLogger(),pelion_processor.preferences());
        this.m_pelion_processor = pelion_processor;
        this.m_device = device;
    }
    

    // run the thread to create the shadow...
    @Override
    public void run() {
        if (this.m_pelion_processor != null && this.m_device != null) {
            // DEBUG
            this.errorLogger().info("PelionProcessor(ShadowCreateThread): Setting up device shadow for: " + (String)this.m_device.get("id"));
            
            // create the device shadow
            this.m_pelion_processor.dispatchDeviceSetup(this.m_device);
        }
        else {
            // ERROR - invalid params to constructor
            this.errorLogger().warning("PelionProcessor(ShadowCreateThread): NULL processor or device map. Unable to create shadow");
        }
    }
}
