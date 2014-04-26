/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.provider.child;

import io.fabric8.api.Constants;
import io.fabric8.api.Container;
import io.fabric8.api.ContainerAutoScaler;
import io.fabric8.api.ContainerAutoScalerFactory;
import io.fabric8.api.ContainerProvider;
import io.fabric8.api.CreateChildContainerMetadata;
import io.fabric8.api.CreateChildContainerOptions;
import io.fabric8.api.CreateEnsembleOptions;
import io.fabric8.api.CreationStateListener;
import io.fabric8.api.DataStore;
import io.fabric8.api.FabricService;
import io.fabric8.api.PortService;
import io.fabric8.api.Profile;
import io.fabric8.api.Version;
import io.fabric8.api.jcip.ThreadSafe;
import io.fabric8.api.scr.AbstractComponent;
import io.fabric8.api.scr.ValidatingReference;
import io.fabric8.internal.ContainerImpl;
import io.fabric8.internal.ProfileOverlayImpl;
import io.fabric8.process.manager.ProcessManager;
import io.fabric8.service.ContainerTemplate;
import io.fabric8.utils.AuthenticationUtils;
import io.fabric8.utils.Ports;
import io.fabric8.utils.Strings;
import io.fabric8.zookeeper.ZkDefs;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.karaf.admin.management.AdminServiceMBean;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.fabric8.utils.Ports.mapPortToRange;

@ThreadSafe
@Component(name = "io.fabric8.container.provider.child", label = "Fabric8 Child Container Provider", immediate = true, metatype = false)
@Service(ContainerProvider.class)
public final class ChildContainerProvider extends AbstractComponent implements ContainerProvider<CreateChildContainerOptions, CreateChildContainerMetadata>, ContainerAutoScalerFactory {

    private static final String SCHEME = "child";

    @Reference(referenceInterface = FabricService.class)
    private final ValidatingReference<FabricService> fabricService = new ValidatingReference<FabricService>();

    @Reference(referenceInterface = ProcessManager.class)
    private final ValidatingReference<ProcessManager> processManager = new ValidatingReference<ProcessManager>();

    @Activate
    void activate() {
        ProcessManager manager = getProcessManager();
        System.out.println("ChildContainerProvider has ProcessManager: " + manager);
        activateComponent();
    }

    @Deactivate
    void deactivate() {
        deactivateComponent();
    }


    @Override
    public CreateChildContainerOptions.Builder newBuilder() {
        return CreateChildContainerOptions.builder();
    }

    @Override
    public CreateChildContainerMetadata create(final CreateChildContainerOptions options, final CreationStateListener listener) throws Exception {
        assertValid();

        ChildContainerController controller = createController(options);
        return controller.create(options, listener);

    }


    @Override
    public void start(final Container container) {
        getContainerController(container).start(container);
    }

    @Override
    public void stop(final Container container) {
        getContainerController(container).stop(container);
    }

    @Override
    public void destroy(final Container container) {
        getContainerController(container).destroy(container);
    }

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public Class<CreateChildContainerOptions> getOptionsType() {
        return CreateChildContainerOptions.class;
    }

    @Override
    public Class<CreateChildContainerMetadata> getMetadataType() {
        return CreateChildContainerMetadata.class;
    }

    @Override
    public ContainerAutoScaler createAutoScaler() {
        return new ChildAutoScaler(this);
    }

    protected ChildContainerController createController(CreateChildContainerOptions options) {
        String containerId = options.getName();

        // allow values to be extracted from the profile configuration
        // such as the image
        Set<String> profiles = options.getProfiles();
        String versionId = options.getVersion();
        FabricService service = getFabricService();
        Map<String, String> configOverlay = new HashMap<String, String>();
        Map<String, String> envVarsOverlay = new HashMap<String, String>();
        Map<String, String> ports = null;
        Map<String, String> dockerProviderConfig = new HashMap<String, String>();


        List<Profile> profileOverlays = new ArrayList<Profile>();
        Version version = null;
        if (profiles != null && versionId != null) {
            version = service.getVersion(versionId);
            if (version != null) {
                for (String profileId : profiles) {
                    Profile profile = version.getProfile(profileId);
                    if (profile != null) {
                        Profile overlay = profile.getOverlay();
                        profileOverlays.add(overlay);
                        Map<String, String> dockerConfig = overlay.getConfiguration(DockerConstants.DOCKER_PROVIDER_PID);
                        if (dockerConfig != null) {
                            configOverlay.putAll(dockerConfig);
                        }
                        Map<String, String> envVars = overlay.getConfiguration(DockerConstants.ENVIRONMENT_VARIABLES_PID);
                        if (envVars != null) {
                            envVarsOverlay.putAll(envVars);
                        }
                        if (ports == null || ports.size() == 0) {
                            ports = overlay.getConfiguration(DockerConstants.PORTS_PID);
                        }
                    }
                }
                if (version.hasProfile(DockerConstants.DOCKER_PROVIDER_PROFILE_ID)) {
                    Profile profile = version.getProfile(DockerConstants.DOCKER_PROVIDER_PROFILE_ID);
                    if (profile != null) {
                        Map<String, String> dockerConfig = profile.getOverlay().getConfiguration(DockerConstants.DOCKER_PROVIDER_PID);
                        if (dockerConfig != null) {
                            dockerProviderConfig.putAll(dockerConfig);
                        }
                    }
                }
            }
        }
        if (ports == null || ports.size() == 0) {
            // lets find the defaults from the docker profile
            if (version == null) {
                version = service.getDefaultVersion();
            }
            Profile dockerProfile = version.getProfile("docker");
            ports = dockerProfile.getConfiguration(DockerConstants.PORTS_PID);
            if (ports == null || ports.size() == 0) {
                LOG.warn("Could not a docker ports configuration for: " + DockerConstants.PORTS_PID);
                ports = new HashMap<String, String>();
            }
        }
        LOG.info("Got port configuration: " + ports);
        String image = containerConfig.getImage();
        if (Strings.isNullOrBlank(image)) {
            image = configOverlay.get(DockerConstants.PROPERTIES.IMAGE);
            if (Strings.isNullOrBlank(image)) {
                image = System.getenv(DockerConstants.ENV_VARS.FABRIC8_DOCKER_DEFAULT_IMAGE);
            }
            if (Strings.isNullOrBlank(image)) {
                image = dockerProviderConfig.get(DockerConstants.PROPERTIES.IMAGE);
            }
            if (Strings.isNullOrBlank(image)) {
                image = DockerConstants.DEFAULT_IMAGE;
            }
            containerConfig.setImage(image);
        }

        return createKarafContainerController();
    }

    protected ChildContainerController getContainerController(Container container) {
        assertValid();
        ChildContainerController answer = null;
        // TODO get the container type from the container metadata...

        if (answer == null) {
            answer = createKarafContainerController();
        }
        return answer;
    }

    protected ChildContainerController createKarafContainerController() {
        return new ChildContainerController() {
            @Override
            public CreateChildContainerMetadata create(final CreateChildContainerOptions options, final CreationStateListener listener) {
                final Container parent = fabricService.get().getContainer(options.getParent());
                ContainerTemplate containerTemplate =  new ContainerTemplate(parent, options.getJmxUser(), options.getJmxPassword(), false);

                return containerTemplate.execute(new ContainerTemplate.AdminServiceCallback<CreateChildContainerMetadata>() {
                    public CreateChildContainerMetadata doWithAdminService(AdminServiceMBean adminService) throws Exception {
                        return doCreateKaraf(adminService, options, listener, parent);
                    }
                });
            }

            @Override
            public void start(final Container container) {
                getContainerTemplateForChild(container).execute(new ContainerTemplate.AdminServiceCallback<Object>() {
                    public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                        adminService.startInstance(container.getId(), null);
                        return null;
                    }
                });
            }

            @Override
            public void stop(final Container container) {
                getContainerTemplateForChild(container).execute(new ContainerTemplate.AdminServiceCallback<Object>() {
                    public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                        adminService.stopInstance(container.getId());
                        return null;
                    }
                });
            }

            @Override
            public void destroy(final Container container) {
                getContainerTemplateForChild(container).execute(new ContainerTemplate.AdminServiceCallback<Object>() {
                    public Object doWithAdminService(AdminServiceMBean adminService) throws Exception {
                        adminService.destroyInstance(container.getId());
                        return null;
                    }
                });
            }
        };
    }



    private CreateChildContainerMetadata doCreateKaraf(AdminServiceMBean adminService,
                                                       CreateChildContainerOptions options,
                                                       CreationStateListener listener,
                                                       final Container parent) throws Exception {
        StringBuilder jvmOptsBuilder = new StringBuilder();

        String zkPasswordEncode = System.getProperty("zookeeper.password.encode", "true");
        jvmOptsBuilder.append("-server -Dcom.sun.management.jmxremote")
                .append(options.getZookeeperUrl() != null ? " -Dzookeeper.url=\"" + options.getZookeeperUrl() + "\"" : "")
                .append(zkPasswordEncode != null ? " -Dzookeeper.password.encode=\"" + zkPasswordEncode + "\"" : "")
                .append(options.getZookeeperPassword() != null ? " -Dzookeeper.password=\"" + options.getZookeeperPassword() + "\"" : "");

        if (options.getJvmOpts() == null || !options.getJvmOpts().contains("-Xmx")) {
            jvmOptsBuilder.append(" -Xmx512m");
        }
        if (options.isEnsembleServer()) {
            jvmOptsBuilder.append(" ").append(CreateEnsembleOptions.ENSEMBLE_AUTOSTART+"=true");
        }

        if (options.getJvmOpts() != null && !options.getJvmOpts().isEmpty()) {
            jvmOptsBuilder.append(" ").append(options.getJvmOpts());
        }

        if (options.getJvmOpts() == null || !options.getJvmOpts().contains("-XX:+UnlockDiagnosticVMOptions -XX:+UnsyncloadClass")) {
            jvmOptsBuilder.append(" -XX:+UnlockDiagnosticVMOptions -XX:+UnsyncloadClass");
        }

        if (options.getBindAddress() != null && !options.getBindAddress().isEmpty()) {
            jvmOptsBuilder.append(" -D" + ZkDefs.BIND_ADDRESS + "=" + options.getBindAddress());
        }

        if (options.getResolver() != null && !options.getResolver().isEmpty()) {
            jvmOptsBuilder.append(" -D" + ZkDefs.LOCAL_RESOLVER_PROPERTY + "=" + options.getResolver());
        }

        if (options.getManualIp() != null && !options.getManualIp().isEmpty()) {
            jvmOptsBuilder.append(" -D" + ZkDefs.MANUAL_IP + "=" + options.getManualIp());
        }

        FabricService fservice = fabricService.get();
        Map<String, String> dataStoreProperties = new HashMap<String, String>(options.getDataStoreProperties());
        dataStoreProperties.put(DataStore.DATASTORE_TYPE_PROPERTY, fservice.getDataStore().getType());

        for (Map.Entry<String, String> dataStoreEntries : options.getDataStoreProperties().entrySet()) {
            String key = dataStoreEntries.getKey();
            String value = dataStoreEntries.getValue();
            jvmOptsBuilder.append(" -D" + Constants.DATASTORE_TYPE_PID +"." + key + "=" + value);
        }

        Profile profile = parent.getVersion().getProfile("default");
        Profile defaultProfile = new ProfileOverlayImpl(profile, fservice.getEnvironment(), true, fservice);
        String featuresUrls = collectionAsString(defaultProfile.getRepositories());
        Set<String> features = new LinkedHashSet<String>();

        features.add("fabric-agent");
        features.add("fabric-git");
        //features.addAll(defaultProfile.getFeatures());
        String containerName = options.getName();

        PortService portService = fservice.getPortService();
        Set<Integer> usedPorts = portService.findUsedPortByHost(parent);

        CreateChildContainerMetadata metadata = new CreateChildContainerMetadata();

        metadata.setCreateOptions(options);
        metadata.setContainerName(containerName);
        int minimumPort = parent.getMinimumPort();
        int maximumPort = parent.getMaximumPort();

        fservice.getDataStore().setContainerAttribute(containerName, DataStore.ContainerAttribute.PortMin, String.valueOf(minimumPort));
        fservice.getDataStore().setContainerAttribute(containerName, DataStore.ContainerAttribute.PortMax, String.valueOf(maximumPort));
        inheritAddresses(fservice, parent.getId(), containerName, options);

        //We are creating a container instance, just for the needs of port registration.
        Container child = new ContainerImpl(parent, containerName, fservice) {
            @Override
            public String getIp() {
                return parent.getIp();
            }
        };

        int sshFrom = mapPortToRange(Ports.DEFAULT_KARAF_SSH_PORT , minimumPort, maximumPort);
        int sshTo = mapPortToRange(Ports.DEFAULT_KARAF_SSH_PORT + 100 , minimumPort, maximumPort);
        int sshPort = portService.registerPort(child, "org.apache.karaf.shell", "sshPort", sshFrom, sshTo, usedPorts);


        int httpFrom = mapPortToRange(Ports.DEFAULT_HTTP_PORT , minimumPort, maximumPort);
        int httpTo = mapPortToRange(Ports.DEFAULT_HTTP_PORT + 100 , minimumPort, maximumPort);
        portService.registerPort(child, "org.ops4j.pax.web", "org.osgi.service.http.port", httpFrom, httpTo, usedPorts);

        int rmiServerFrom = mapPortToRange(Ports.DEFAULT_RMI_SERVER_PORT , minimumPort, maximumPort);
        int rmiServerTo = mapPortToRange(Ports.DEFAULT_RMI_SERVER_PORT + 100 , minimumPort, maximumPort);
        int rmiServerPort = portService.registerPort(child, "org.apache.karaf.management", "rmiServerPort", rmiServerFrom, rmiServerTo, usedPorts);

        int rmiRegistryFrom = mapPortToRange(Ports.DEFAULT_RMI_REGISTRY_PORT , minimumPort, maximumPort);
        int rmiRegistryTo = mapPortToRange(Ports.DEFAULT_RMI_REGISTRY_PORT + 100 , minimumPort, maximumPort);
        int rmiRegistryPort = portService.registerPort(child, "org.apache.karaf.management", "rmiRegistryPort", rmiRegistryFrom, rmiRegistryTo, usedPorts);


        try {
            adminService.createInstance(containerName,
                    sshPort,
                    rmiRegistryPort,
                    rmiServerPort, null, jvmOptsBuilder.toString(), collectionAsString(features), featuresUrls);
            adminService.startInstance(containerName, null);
        } catch (Throwable t) {
            metadata.setFailure(t);
        }
        return metadata;
    }

    /**
     * Returns the {@link ContainerTemplate} of the parent of the specified child {@link Container}.
     */
    private ContainerTemplate getContainerTemplateForChild(Container container) {
        CreateChildContainerOptions options = (CreateChildContainerOptions) container.getMetadata().getCreateOptions();

        String username = AuthenticationUtils.retrieveJaasUser();
        String password = AuthenticationUtils.retrieveJaasPassword();

        if (username != null && password != null) {
            options = (CreateChildContainerOptions) options.updateCredentials(username, password);
        }

        return new ContainerTemplate(container.getParent(), options.getJmxUser(), options.getJmxPassword(), false);
    }

    /**
     * Links child container resolver and addresses to its parents resolver and addresses.
     */
    private void inheritAddresses(FabricService service, String parent, String name, CreateChildContainerOptions options) throws Exception {
        if (options.getManualIp() != null) {
            service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.ManualIp, options.getManualIp());
        } else {
            service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.ManualIp, "${zk:" + parent + "/manualip}");
        }

        //Link to the addresses from the parent container.
        service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.LocalHostName, "${zk:" + parent + "/localhostname}");
        service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.LocalIp, "${zk:" + parent + "/localip}");
        service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.PublicIp, "${zk:" + parent + "/publicip}");

        if (options.getResolver() != null) {
            service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.Resolver, options.getResolver());
        } else {
            service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.Resolver, "${zk:" + parent + "/resolver}");
        }

        if (options.getBindAddress() != null) {
            service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.BindAddress, options.getBindAddress());
        } else {
            service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.BindAddress,  "${zk:" + parent + "/bindaddress}");
        }

        service.getDataStore().setContainerAttribute(name, DataStore.ContainerAttribute.Ip, "${zk:" + name + "/${zk:" + name + "/resolver}}");
    }

    FabricService getFabricService() {
        return fabricService.get();
    }

    protected ProcessManager getProcessManager() {
        return processManager.get();
    }

    private static String collectionAsString(Collection<String> value) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (value != null) {
            for (String el : value) {
                if (first) {
                    first = false;
                } else {
                    sb.append(",");
                }
                sb.append(el);
            }
        }
        return sb.toString();
    }

    void bindFabricService(FabricService fabricService) {
        this.fabricService.bind(fabricService);
    }

    void unbindFabricService(FabricService fabricService) {
        this.fabricService.unbind(fabricService);
    }

    void bindProcessManager(ProcessManager processManager) {
        this.processManager.bind(processManager);
    }

    void unbindProcessManager(ProcessManager processManager) {
        this.processManager.unbind(processManager);
    }
}
