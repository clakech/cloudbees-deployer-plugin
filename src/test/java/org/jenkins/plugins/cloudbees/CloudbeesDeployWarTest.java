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

import hudson.maven.MavenModuleSet;
import hudson.maven.MavenModuleSetBuild;
import hudson.model.FreeStyleProject;
import hudson.tasks.Ant;
import hudson.tasks.Maven;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jvnet.hudson.test.ExtractResourceSCM;

/**
 * @author Olivier Lamy
 */
public class CloudbeesDeployWarTest extends AbstractCloudbeesDeployerPluginTest {

    public void testWithMaven2Project() throws Exception {
        MavenModuleSet m = createMavenProject();
        Maven.MavenInstallation mavenInstallation = configureDefaultMaven();
        m.setMaven(mavenInstallation.getName());

        m.setGoals("clean install");
        m.setScm(new ExtractResourceSCM(getClass().getResource("test-project.zip")));
        m.getPublishers().add(new CloudbeesPublisher("olamy", "foo/beer", null, null));
        MavenModuleSetBuild mmsb = buildAndAssertSuccess(m);
        assertOnFileItems();
    }

    /**
     * Fails with : Caused by: java.io.IOException: Remote call on channel failed at
     * hudson.remoting.Channel.call(Channel.java:652) at
     * hudson.remoting.RemoteInvocationHandler.invoke(RemoteInvocationHandler.java:158) ... 31 more Caused by:
     * java.lang.AssertionError at hudson.model.Run.setResult(Run.java:329) at
     * hudson.maven.MavenBuild$ProxyImpl.setResult(MavenBuild.java:427)
     */
    public void failtestWithMaven3Project() throws Exception {
        MavenModuleSet m = createMavenProject();
        Maven.MavenInstallation mavenInstallation = configureMaven3();
        m.setMaven(mavenInstallation.getName());

        m.setGoals("clean install");
        m.setScm(new ExtractResourceSCM(getClass().getResource("test-project.zip")));
        m.getPublishers().add(new CloudbeesPublisher("olamy", "foo/beer", null, null));
        MavenModuleSetBuild mmsb = buildAndAssertSuccess(m);
        assertOnFileItems();
    }

    public void testFreestyleAnt() throws Exception {
        FreeStyleProject p = createFreeStyleProject();
        p.setScm(new ExtractResourceSCM(getClass().getResource("test-project.zip")));
        String antName = configureDefaultAnt().getName();
        p.getPublishersList().add(new CloudbeesPublisher("olamy", "foo/beer", "build-war/*.war", null));
        p.getBuildersList().add(new Ant("", antName, null, null, "vBAR=<xml/>\n"));
        buildAndAssertSuccess(p);
        assertOnFileItems();
    }

    public void assertOnFileItems() throws IOException {

        for (FileItem fileItem : cloudbeesServer.cloudbessServlet.items) {

            // archive check it's a war content

            // description check Jenkins BUILD_ID
            if (fileItem.getFieldName().equals("description")) {
                String description = fileItem.getString();
                // sample Jenkins build 2011-04-17_17-35-05
                assertTrue(description.contains("Jenkins build"));
                String id = StringUtils.substringAfter(description, "Jenkins build").trim();
                // TODO assert it's a date time with a parsing !!
                System.out.println("id : " + id);
            } else if (fileItem.getFieldName().equals("api_key")) {
                assertEquals("key", fileItem.getString());
            } else if (fileItem.getFieldName().equals("app_id")) {
                assertEquals("foo/beer", fileItem.getString());
            } else if (fileItem.getFieldName().equals("archive")) {
                assertOnArchive(fileItem.getInputStream());
            } else {
                System.out.println(" item " + fileItem);
            }

        }

    }

    public void assertOnArchive(InputStream inputStream) throws IOException {
        List<String> fileNames = new ArrayList<String>();
        ZipInputStream zipInputStream = null;
        try {
            zipInputStream = new ZipInputStream(inputStream);
            ZipEntry zipEntry = zipInputStream.getNextEntry();
            while (zipEntry != null) {
                fileNames.add(zipEntry.getName());
                zipEntry = zipInputStream.getNextEntry();
            }
        } finally {
            IOUtils.closeQuietly(zipInputStream);
        }
        assertTrue(fileNames.contains("META-INF/maven/org.olamy.puzzle.translate/translate-puzzle-webapp/pom.xml"));
        assertTrue(fileNames.contains("WEB-INF/lib/javax.inject-1.jar"));
    }
}
