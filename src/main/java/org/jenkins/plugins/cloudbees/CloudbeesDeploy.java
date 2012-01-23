/*
 * Copyright 2010-2011, CloudBees Inc.
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
package org.jenkins.plugins.cloudbees;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * 
 * @author @cyril_lakech
 * 
 */
public class CloudbeesDeploy {

    public final String accountName;

    public final String applicationId;

    public final String filePattern;

    @DataBoundConstructor
    public CloudbeesDeploy(String accountName, String applicationId, String filePattern) {
        this.accountName = accountName;
        this.applicationId = applicationId;
        this.filePattern = filePattern;
    }

    @Override
    public String toString() {
        return "accountName=" + accountName + " applicationId=" + applicationId + " filePattern=" + filePattern;
    }

}
