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

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.AutoCompletionCandidates;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.CopyOnWriteList;
import hudson.util.FormValidation;
import hudson.util.IOException2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.jenkins.plugins.cloudbees.util.FileFinder;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.export.Exported;

import com.cloudbees.api.ApplicationInfo;
import com.cloudbees.api.ApplicationListResponse;
import com.cloudbees.api.BeesClientException;
import com.cloudbees.api.UploadProgress;

/**
 * @author Olivier Lamy
 */
public class CloudbeesPublisher extends Notifier {

    // old fields are left so that old config data can be read in, but
    // they are deprecated. transient so that they won't show up in XML
    // when writing back
    @Deprecated
    public final transient String      accountName;
    @Deprecated
    public final transient String      applicationId;
    @Deprecated
    public final transient String      filePattern;

    public final List<CloudbeesDeploy> deploys;

    /**
     * Store a config version so we're able to migrate config on various functionality upgrades.
     */
    private Long                       configVersion;

    // @DataBoundConstructor
    // public CloudbeesPublisher(String accountName, String applicationId, String filePattern) throws Exception {
    // this(accountName, applicationId, filePattern, null);
    // }

    @DataBoundConstructor
    public CloudbeesPublisher(String accountName, String applicationId, String filePattern,
            List<CloudbeesDeploy> deploys) throws Exception {
        if (accountName == null) {
            // revert to first one
            CloudbeesAccount[] accounts = DESCRIPTOR.getAccounts();
            if (accounts != null && accounts.length > 0) {
                accountName = accounts[0].name;
            } else {
                accountName = "";
            }
        }
        this.accountName = accountName;

        this.applicationId = applicationId;

        this.filePattern = filePattern;

        configVersion = 1L;

        if (deploys == null) {
            deploys = new ArrayList<CloudbeesDeploy>();
        }
        this.deploys = deploys;

    }

    public Object readResolve() {
        // Migrate data

        // Default unspecified to v0
        if (configVersion == null) {
            configVersion = 0L;
        }

        if (configVersion < 1 && applicationId != null) {
            deploys.clear();
            deploys.add(new CloudbeesDeploy(accountName, applicationId, filePattern));
        }

        return this;
    }

    @Exported
    public CloudbeesDeploy[] getDeploys() {
        return deploys.toArray(new CloudbeesDeploy[deploys.size()]);
    }

    public CloudbeesAccount getCloudbeesAccount() {
        CloudbeesAccount[] accounts = DESCRIPTOR.getAccounts();
        if (accountName == null && accounts.length > 0) {
            // return default
            if (accounts != null) {
                return accounts[0];
            }
        }

        for (CloudbeesAccount account : accounts) {
            if (account.name.equals(accountName)) {
                return account;
            }
        }
        return null;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, final BuildListener listener)
            throws InterruptedException, IOException {

        CloudbeesAccount cloudbeesAccount = getCloudbeesAccount();

        if (cloudbeesAccount == null) {
            listener.getLogger().println(Messages._CloudbeesPublisher_noAccount());
            return false;
        }

        listener.getLogger().println(Messages._CloudbeesPublisher_perform(getCloudbeesAccount().name, applicationId));

        CloudbeesApiHelper.CloudbeesApiRequest apiRequest = new CloudbeesApiHelper.CloudbeesApiRequest(
                DescriptorImpl.CLOUDBEES_API_URL, cloudbeesAccount.apiKey, cloudbeesAccount.secretKey);

        List<ArtifactFilePathSaveAction> artifactFilePathSaveActions = retrieveArtifactFilePathSaveActions(build);

        if (artifactFilePathSaveActions.isEmpty() && StringUtils.isBlank(filePattern)) {
            listener.getLogger().println(Messages._CloudbeesPublisher_noArtifacts(build.getProject().getName()));
            return true;
        }

        String warPath = null;
        findWarPath: for (ArtifactFilePathSaveAction artifactFilePathSaveAction : artifactFilePathSaveActions) {
            for (MavenArtifactWithFilePath artifactWithFilePath : artifactFilePathSaveAction.mavenArtifactWithFilePaths) {
                if (StringUtils.equals("war", artifactWithFilePath.type)) {
                    listener.getLogger().println(Messages.CloudbeesPublisher_WarPathFound(artifactWithFilePath));
                    warPath = artifactWithFilePath.filePath;
                    break findWarPath;
                }
            }
        }

        if (StringUtils.isBlank(warPath)) {
            if (StringUtils.isBlank(filePattern)) {
                listener.getLogger().println(Messages._CloudbeesPublisher_noWarArtifacts());
                return false;
            } else {
                // search file in the workspace with the pattern
                FileFinder fileFinder = new FileFinder(filePattern);
                List<String> fileNames = build.getWorkspace().act(fileFinder);
                listener.getLogger().println("found remote files : " + fileNames);
                if (fileNames.size() > 1) {
                    listener.getLogger().println(Messages.CloudbeesPublisher_ToManyFilesMatchingPattern());
                    return false;
                } else if (fileNames.size() == 0) {
                    listener.getLogger().println(Messages._CloudbeesPublisher_noArtifactsFound(filePattern));
                    return false;
                }
                // so we use only the first found
                warPath = fileNames.get(0);
            }
        }

        File tmpArchive = File.createTempFile("jenkins", "temp-cloudbees-deploy");

        try {

            // handle remote slave case so copy war locally
            Node buildNode = Hudson.getInstance().getNode(build.getBuiltOnStr());
            FilePath filePath = new FilePath(tmpArchive);

            FilePath remoteWar = build.getWorkspace().child(warPath);

            remoteWar.copyTo(filePath);

            warPath = tmpArchive.getPath();

            listener.getLogger().println(Messages.CloudbeesPublisher_Deploying(applicationId));

            String description = "Jenkins build " + build.getId();
            CloudbeesApiHelper.getBeesClient(apiRequest).applicationDeployWar(applicationId, "environnement",
                    description, warPath, warPath, new ConsoleListenerUploadProgress(listener));
            CloudbeesDeployerAction cloudbeesDeployerAction = new CloudbeesDeployerAction(applicationId);
            cloudbeesDeployerAction.setDescription(description);

            build.addAction(cloudbeesDeployerAction);
        } catch (Exception e) {
            listener.getLogger().println("issue during deploying war " + e.getMessage());
            throw new IOException2(e.getMessage(), e);
        } finally {
            FileUtils.deleteQuietly(tmpArchive);
        }

        return true;
    }

    private List<ArtifactFilePathSaveAction> retrieveArtifactFilePathSaveActions(AbstractBuild<?, ?> build) {
        List<ArtifactFilePathSaveAction> artifactFilePathSaveActions = new ArrayList<ArtifactFilePathSaveAction>();
        List<ArtifactFilePathSaveAction> actions = build.getActions(ArtifactFilePathSaveAction.class);
        if (actions != null) {
            artifactFilePathSaveActions.addAll(actions);
        }

        if (build instanceof MavenModuleSetBuild) {
            for (List<MavenBuild> mavenBuilds : ((MavenModuleSetBuild) build).getModuleBuilds().values()) {
                for (MavenBuild mavenBuild : mavenBuilds) {
                    actions = mavenBuild.getActions(ArtifactFilePathSaveAction.class);
                    if (actions != null) {
                        artifactFilePathSaveActions.addAll(actions);
                    }
                }
            }
        }
        return artifactFilePathSaveActions;
    }

    private static class ConsoleListenerUploadProgress implements UploadProgress {
        private final BuildListener listener;

        ConsoleListenerUploadProgress(BuildListener buildListener) {
            listener = buildListener;
        }

        public void handleBytesWritten(long deltaCount, long totalWritten, long totalToSend) {
            listener.getLogger().println(
                    " upload : " + deltaCount / 1024 + " ko, status " + totalWritten / 1014 + " ko/" + totalToSend
                            / 1024 + " ko");
        }
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private final CopyOnWriteList<CloudbeesAccount> accounts            = new CopyOnWriteList<CloudbeesAccount>();

        // public so could be disable programatically
        public boolean                                  disableAccountSetup = false;

        // configurable ?
        // so here last with a public static field it's possible to change tru a groovy script
        public static String                            CLOUDBEES_API_URL   = "https://api.cloudbees.com/api";

        public DescriptorImpl() {
            super(CloudbeesPublisher.class);
            load();
        }

        @Override
        public String getDisplayName() {
            return Messages.CloudbeesPublisher_displayName();
        }

        @Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData) {
            CloudbeesPublisher cpp = req.bindParameters(CloudbeesPublisher.class, "cloudbeesaccount.");
            // if (cpp.accountName == null) {
            // cpp = null;
            // }
            return cpp;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) {
            List<CloudbeesAccount> accountList = req.bindParametersToList(CloudbeesAccount.class, "cloudbeesaccount.");
            accounts.replaceBy(accountList);
            save();
            return true;
        }

        /**
         *
         */
        public FormValidation doNameCheck(@QueryParameter final String name) throws IOException, ServletException {
            if (StringUtils.isBlank(name)) {
                return FormValidation.error(Messages._CloudbeesPublisher_nameNotEmpty().toString());
            }
            return FormValidation.ok();
        }

        /**
         *
         */
        public FormValidation doApiKeyCheck(@QueryParameter final String apiKey) throws IOException, ServletException {
            if (StringUtils.isBlank(apiKey)) {
                return FormValidation.error(Messages._CloudbeesPublisher_apiKeyNotEmpty().toString());
            }
            return FormValidation.ok();
        }

        public FormValidation doSecretKeyCheck(StaplerRequest request) throws IOException, ServletException {
            String secretKey = Util.fixEmpty(request.getParameter("secretKey"));
            if (StringUtils.isBlank(secretKey)) {
                return FormValidation.error(Messages._CloudbeesPublisher_secretKeyNotEmpty().toString());
            }
            // check valid account
            String apiKey = Util.fixEmpty(request.getParameter("apiKey"));
            if (StringUtils.isBlank(apiKey)) {
                return FormValidation.error(Messages._CloudbeesPublisher_apiKeyNotEmpty().toString());
            }

            CloudbeesApiHelper.CloudbeesApiRequest apiRequest = new CloudbeesApiHelper.CloudbeesApiRequest(
                    CLOUDBEES_API_URL, apiKey, secretKey);

            try {
                CloudbeesApiHelper.ping(apiRequest);
            } catch (BeesClientException e) {
                if (e.getError() == null) {
                    LOGGER.log(Level.SEVERE, "Error during calling cloudbees api", e);
                    return FormValidation.error("Unknown error check server logs");
                } else {
                    // we assume here it's a authz issue
                    LOGGER.warning(e.getError().getMessage());
                    // return FormValidation.error(e.getError().getMessage());
                    return FormValidation.error(Messages._CloudbeesPublisher_authenticationFailure().toString());
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error during calling cloudbees api", e);
                return FormValidation.error("Unknown error check server logs");
            }
            return FormValidation.ok();
        }

        public void setAccounts(List<CloudbeesAccount> cloudbeesAccount) {
            accounts.clear();
            accounts.addAll(cloudbeesAccount);
        }

        public CloudbeesAccount[] getAccounts() {
            return accounts.toArray(new CloudbeesAccount[accounts.size()]);
        }

        public boolean isDisableAccountSetup() {
            return disableAccountSetup || "true".equalsIgnoreCase(System.getProperty("cloudbees.disableAccountSetup"));
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            // check if type of FreeStyleProject.class or MavenModuleSet.class
            return true;
        }

        public FormValidation doApplicationIdCheck(@QueryParameter final String applicationId,
                @QueryParameter final String cloudbeesAccountName) throws IOException, ServletException {
            try {

                if (StringUtils.isBlank(applicationId)) {
                    return FormValidation.error(Messages._CloudbeesPublisher_applicationIdNotEmpty().toString());
                }

                CloudbeesAccount cloudbeesAccount = getCloudbeesAccount(cloudbeesAccountName);
                ApplicationListResponse applicationListResponse = CloudbeesApiHelper
                        .applicationsList(new CloudbeesApiHelper.CloudbeesApiRequest(CLOUDBEES_API_URL,
                                cloudbeesAccount));
                List<ApplicationInfo> applicationInfos = applicationListResponse.getApplications();

                List<String> applicationIds = new ArrayList<String>(applicationInfos.size());

                AutoCompletionCandidates candidates = new AutoCompletionCandidates();
                for (ApplicationInfo applicationInfo : applicationInfos) {
                    if (StringUtils.equals(applicationInfo.getId(), applicationId)) {
                        return FormValidation.ok();
                    }
                    applicationIds.add(applicationInfo.getId());
                }
                StringBuilder sb = new StringBuilder();

                for (String appId : applicationIds) {
                    sb.append(appId + " ");
                }

                return FormValidation
                        .ok("This application ID was not found, so using it will create a new application. Existing application ID's are: \n "
                                + sb.toString());
            } catch (Exception e) {
                return FormValidation.error(e, "error during check applicationId " + e.getMessage());
            }
        }

        // TODO fix those try to find a way to pass cloudbeesAccountName in autoCompleteUrl
        public AutoCompletionCandidates doAutoCompleteApplications(StaplerRequest staplerRequest) throws Exception {

            Enumeration enumeration = staplerRequest.getParameterNames();
            while (enumeration.hasMoreElements()) {
                System.out.println("name " + (String) enumeration.nextElement());
            }

            String value = staplerRequest.getParameter("value");
            String cloudbeesAccountName = staplerRequest.getParameter("cloudbeesAccountName");
            System.out.println("in doAutoCompleteApplications value:" + value + ",cloudbeesAccountName"
                    + cloudbeesAccountName);
            CloudbeesAccount cloudbeesAccount = getCloudbeesAccount(cloudbeesAccountName);

            ApplicationListResponse applicationListResponse = CloudbeesApiHelper
                    .applicationsList(new CloudbeesApiHelper.CloudbeesApiRequest(CLOUDBEES_API_URL, cloudbeesAccount));
            List<ApplicationInfo> applicationInfos = applicationListResponse.getApplications();
            System.out.println("found " + applicationInfos.size() + " applications");

            AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            for (ApplicationInfo applicationInfo : applicationInfos) {
                if (StringUtils.startsWith(applicationInfo.getId(), value)) {
                    System.out.println("found candidate " + applicationInfo.getId());
                    candidates.add(applicationInfo.getId());// applicationInfo.getTitle(),
                }
            }

            return candidates;
        }

        public static CloudbeesAccount getCloudbeesAccount(String cloudbeesAccountName) {
            CloudbeesAccount[] accounts = DESCRIPTOR.getAccounts();
            if (cloudbeesAccountName == null && accounts.length > 0) {
                // return default
                return accounts[0];
            }
            for (CloudbeesAccount account : accounts) {
                if (account.name.equals(cloudbeesAccountName)) {
                    return account;
                }
            }
            return null;
        }

    }

    public static boolean maven3orLater(String mavenVersion) {
        // null or empty so false !
        if (StringUtils.isBlank(mavenVersion)) {
            return false;
        }
        return new ComparableVersion(mavenVersion).compareTo(new ComparableVersion("3.0")) >= 0;
    }

    private static final Logger LOGGER = Logger.getLogger(CloudbeesPublisher.class.getName());
}
