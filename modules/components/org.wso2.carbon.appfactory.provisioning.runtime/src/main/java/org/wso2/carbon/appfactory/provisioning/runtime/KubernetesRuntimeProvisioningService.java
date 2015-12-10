/*
* Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
*/

package org.wso2.carbon.appfactory.provisioning.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.*;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.carbon.appfactory.provisioning.runtime.Utils.KubernetesProvisioningUtils;
import org.wso2.carbon.appfactory.provisioning.runtime.beans.*;
import org.wso2.carbon.appfactory.provisioning.runtime.beans.Container;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * This class will implement the runtime provisioning service specific to Kubernetes
 */
public class KubernetesRuntimeProvisioningService implements RuntimeProvisioningService{

    private static final Log log = LogFactory.getLog(KubernetesRuntimeProvisioningService.class);
    private ApplicationContext applicationContext;
    private Namespace namespace;

    public KubernetesRuntimeProvisioningService(ApplicationContext applicationContext){
        this.applicationContext = applicationContext;
        this.namespace = KubernetesProvisioningUtils.getNameSpace(applicationContext);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext)
            throws RuntimeProvisioningException {

    }

    @Override
    public void createOrganization(TenantInfo tenantInfo) throws RuntimeProvisioningException {
        KubernetesClient kubernetesClient = KubernetesProvisioningUtils.getFabric8KubernetesClient();
        kubernetesClient.namespaces().create(this.namespace);
        kubernetesClient.close();
    }

    @Override
    public void updateOrganization(TenantInfo tenantInfo) throws RuntimeProvisioningException {

    }

    @Override
    public void deleteOrganization(TenantInfo tenantInfo) throws RuntimeProvisioningException {

    }

    @Override
    public void archiveOrganization(TenantInfo tenantInfo) throws RuntimeProvisioningException {

    }

    @Override
    public int createBuild(BuildConfiguration config) throws RuntimeProvisioningException {
        return 0;
    }

    @Override
    public String getBuildStatus(String buildId) throws RuntimeProvisioningException {
        return null;
    }

    @Override
    public String getBuildLog(String buildId) throws RuntimeProvisioningException {
        return null;
    }

    @Override
    public List<String> getBuildHistory() throws RuntimeProvisioningException {
        return null;
    }

    @Override
    public boolean cancelBuild(String buildId) throws RuntimeProvisioningException {
        return false;
    }

    @Override
    public List<String> deployApplication(DeploymentConfig config) throws RuntimeProvisioningException {

        DefaultKubernetesClient kubClient = null;
        List<Container> containers = config.getContainers();
        ArrayList<io.fabric8.kubernetes.api.model.Container> kubContainerList = new ArrayList<>();

        try {
            //Deployment creation
            for (Container container : containers) {
                io.fabric8.kubernetes.api.model.Container kubContainer = new io.fabric8.kubernetes.api.model.Container();
                kubContainer.setName(container.getBaseImageName());
                kubContainer.setImage(container.getBaseImageName() + ":" + container.getBaseImageVersion());
                ContainerPort kubContainerPort = new ContainerPortBuilder()
                        .withContainerPort(container.getContainerPort())
                        .withHostPort(container.getHostPort())
                        .build();
                List<ContainerPort> containerPorts = new ArrayList<>();
                containerPorts.add(kubContainerPort);
                kubContainer.setPorts(containerPorts);
                kubContainerList.add(kubContainer);
            }

            PodSpec podSpec = new PodSpecBuilder()
                    .withContainers(kubContainerList)
                    .build();

            PodTemplateSpec podTemplateSpec = new PodTemplateSpecBuilder()
                    .withMetadata(new ObjectMetaBuilder().withLabels(config.getLables()).build())
                    .withSpec(podSpec)
                    .build();

            DeploymentSpec deploymentSpec = new DeploymentSpecBuilder()
                    .withReplicas(config.getReplicas())
                    .withTemplate(podTemplateSpec)
                    .build();

            Deployment deployment = new DeploymentBuilder().withApiVersion(Deployment.ApiVersion.EXTENSIONS_V_1_BETA_1)
                    .withKind(KubernetesPovisioningConstants.KIND_DEPLOYMENT)
                    .withMetadata(new ObjectMetaBuilder().withName(config.getDeploymentName()).build())
                    .withSpec(deploymentSpec)
                    .build();

            kubClient = new DefaultKubernetesClient();
            DeploymentList deploymentList = kubClient.extensions().deployments().list();

            if (deploymentList.getItems().contains(deployment)) {
                //Redeployment
                kubClient.inNamespace(namespace.getMetadata().getNamespace()).extensions()
                        .deployments().withName(config.getDeploymentName()).replace(deployment);

            } else {
                //New Deployment
                kubClient.inNamespace(namespace.getMetadata().getNamespace()).extensions()
                        .deployments().create(deployment);
                //Service creation
                ServicePort servicePorts = new ServicePortBuilder()
                        .withProtocol("TCP")
                        .withPort(config.getProxyPort())
                        .withTargetPort(new IntOrString(config.getServicePort()))
                        .build();
                ServiceSpec serviceSpec = new ServiceSpecBuilder()
                        .withSelector(config.getLables())
                        .withPorts(servicePorts)
                        .build();
                Service service = new ServiceBuilder()
                        .withKind(KubernetesPovisioningConstants.KIND_SERVICE)
                        .withSpec(serviceSpec)
                        .withMetadata(new ObjectMetaBuilder().withName(config.getDeploymentName()).build())
                        .build();
                kubClient.inNamespace(namespace.getMetadata().getNamespace()).services().create(service);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (kubClient != null) {
                kubClient.close();
            }
        }
        return null;

    }

    @Override
    public boolean getDeploymentStatus(DeploymentConfig config) throws RuntimeProvisioningException {

        DefaultKubernetesClient kubClient = null;
        DeploymentStatus deploymentStatus = kubClient.inNamespace(namespace.getMetadata().getNamespace())
                .extensions().deployments().withName(config.getDeploymentName()).get().getStatus();

        return true;
    }

    @Override
    public DeploymentLogs streamRuntimeLogs(DeploymentConfig deploymentConfig) throws RuntimeProvisioningException {

        Query query = new Query(true, 0, 0);

        DeploymentLogs deploymentLogs = new DeploymentLogs();
        Map<String, BufferedReader> logOutPut = new HashMap<>();
        URI uri = null;
        HttpClient httpclient = null;
        PodList podList = KubernetesProvisioningUtils.getPods(applicationContext);
        for (Pod pod : podList.getItems()) {

            try {
                uri = new URI(KubernetesPovisioningConstants.KUB_MASTER_URL + "api/v1/namespaces/"
                        + namespace.getMetadata().getNamespace()
                        + "/pods/" + pod.getMetadata().getName()
                        + "/log?follow=" + String.valueOf(query.getIsFollowing()));

                HttpGet httpGet = (HttpGet) KubernetesProvisioningUtils
                        .getHttpMethodForKubernetes(HttpGet.METHOD_NAME, uri);
                httpclient = KubernetesProvisioningUtils.getHttpClientForKubernetes();
                HttpResponse response = httpclient.execute(httpGet);
                BufferedReader logStream = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                logOutPut.put(pod.getMetadata().getName(), logStream);
                deploymentLogs.setDeploymentLogs(logOutPut);
            } catch (URISyntaxException e) {
                String msg = "Error in url syntax : " + uri + " while getting logs from container : "
                        + pod.getMetadata().getName();
                log.error(msg, e);
                throw new RuntimeProvisioningException(msg, e);
            } catch (NoSuchAlgorithmException e) {
                String msg =
                        "Error in SSL protocol while connecting to Kubernetes api while getting logs from container : "
                                + pod.getMetadata().getName();
                log.error(msg, e);
                throw new RuntimeProvisioningException(msg, e);
            } catch (IOException e) {
                String msg = "Error while reading log stream from container : " + pod.getMetadata().getName();
                log.error(msg, e);
                throw new RuntimeProvisioningException(msg, e);
            } catch (KeyManagementException e) {
                String msg = "Error creating SSL connection to Kubernetes api while getting logs from container : "
                        + pod.getMetadata().getName();
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (KeyStoreException e) {
                String msg = "Error creating SSL connection to Kubernetes api while getting logs from container : "
                        + pod.getMetadata().getName();
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } finally {
                httpclient.getConnectionManager().shutdown();
            }
        }
        return deploymentLogs;
    }

    @Override
    public DeploymentLogs getRuntimeLogs(DeploymentConfig deploymentConfig, Query query) throws RuntimeProvisioningException {

        DeploymentLogs deploymentLogs = new DeploymentLogs();
        Map<String, BufferedReader> logOutPut = new HashMap<>();
        URI uri = null;
        HttpClient httpclient = null;

        if (query != null) {
            PodList podList = KubernetesProvisioningUtils.getPods(applicationContext);
            for (Pod pod : podList.getItems()) {
                try {
                    uri = new URI(KubernetesPovisioningConstants.KUB_MASTER_URL + "api/v1/namespaces/"
                            + namespace.getMetadata().getNamespace()
                            + "/pods/" + pod.getMetadata().getName() + "/log?&previous=" + String
                            .valueOf(query.getPreviousRecordsCount()) + "&timestamps=" + String
                            .valueOf(query.getDurationInHours()));
                    HttpGet httpGet = (HttpGet) KubernetesProvisioningUtils
                            .getHttpMethodForKubernetes(HttpGet.METHOD_NAME, uri);
                    httpclient = KubernetesProvisioningUtils.getHttpClientForKubernetes();
                    HttpResponse response = httpclient.execute(httpGet);
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(response.getEntity()
                            .getContent()));
                    logOutPut.put(pod.getMetadata().getName(), bufferedReader);
                    deploymentLogs.setDeploymentLogs(logOutPut);
                } catch (URISyntaxException e) {
                    String msg =
                            "Error in url syntax : " + uri + " while getting logs from container : " + pod
                                    .getMetadata().getName();
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(msg, e);
                } catch (NoSuchAlgorithmException e) {
                    String msg =
                            "Error in SSL protocol while connecting to Kubernetes api while getting logs from container : "
                                    + pod.getMetadata().getName();
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(msg, e);
                } catch (IOException e) {
                    String msg = "Error while reading log stram from container : " + pod.getMetadata().getName();
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(msg, e);
                } catch (KeyManagementException e) {
                    String msg = "Error creating SSL connection to Kubernetes api while getting logs from container : "
                            + pod.getMetadata().getName();
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(e);
                } catch (KeyStoreException e) {
                    String msg = "Error creating SSL connection to Kubernetes api while getting logs from container : "
                            + pod.getMetadata().getName();
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(e);
                } finally {
                    httpclient.getConnectionManager().shutdown();
                }
            }
        } else {
            KubernetesClient kubernetesClient = KubernetesProvisioningUtils.getFabric8KubernetesClient();
            PodList podList = KubernetesProvisioningUtils.getPods(applicationContext);
            for (Pod pod : podList.getItems()) {
                kubernetesClient.extensions().deployments();
                String logs = kubernetesClient.pods().inNamespace(
                        namespace.getMetadata().getNamespace())
                        .withName(pod.getMetadata().getName()).getLog(true);
                InputStream is = new ByteArrayInputStream(logs.getBytes());
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is));
                logOutPut.put(pod.getMetadata().getName(), bufferedReader);
                try {
                    is.close();
                    bufferedReader.close();
                } catch (IOException e) {
                    log.error(e);
                }
            }
        }
        return deploymentLogs;
    }

    /**
     * Set runtime properties to kubernetes environment
     *
     * @param runtimeProperties runtime properties
     * @param deploymentConfig  includes deployment related details
     * @throws RuntimeProvisioningException
     */
    @Override
    public void setRuntimeProperties(List<RuntimeProperty> runtimeProperties, DeploymentConfig deploymentConfig)
            throws RuntimeProvisioningException {

        List secrets = new ArrayList();
        Container container = new Container();
        List environments = new ArrayList();

        //create a instance of kubernetes client to invoke service call
        KubernetesClient kubernetesClient = KubernetesProvisioningUtils.getFabric8KubernetesClient();

        for (RuntimeProperty runtimeProperty : runtimeProperties) {
            switch (runtimeProperty.getPropertyType()) {
            case SENSITIVE:
                if (log.isDebugEnabled()) {
                    String message = "Creating property type secret for the application:" + applicationContext.getName()
                            + " for the tenant domain:" + applicationContext.getTenantInfo().getTenantDomain();
                    log.debug(message);
                }
                Secret secret = new SecretBuilder().withKind(KubernetesPovisioningConstants.KIND_SECRETS)
                        .withApiVersion(Secret.ApiVersion.V_1).withNewMetadata()
                        .withNamespace(namespace.getMetadata().getName()).withName(runtimeProperty.getName())
                        .endMetadata().withData(runtimeProperty.getProperties()).build();

                kubernetesClient.secrets().create(secret);

                Volume volume = new VolumeBuilder()
                        .withName(KubernetesPovisioningConstants.VOLUME_MOUNT)
                        .withNewSecret()
                        .withSecretName(runtimeProperty.getName())
                        .endSecret()
                        .build();

                secrets.add(volume);

                break;
            case ENVIRONMENT:
                if (log.isDebugEnabled()) {
                    String message = "Creating property type environment for the application:"
                            + applicationContext.getName() + " for the tenant domain:"
                            + applicationContext.getTenantInfo().getTenantDomain();
                    log.debug(message);
                }
                for (Map.Entry<String, String> property : runtimeProperty.getProperties().entrySet()) {
                    EnvVar envVar = new EnvVarBuilder()
                            .withName(property.getKey())
                            .withValue(property.getValue())
                            .build();

                    environments.add(envVar);
                }

                break;
            default:
                String message = "Runtime property type is not support, property type:"
                        + runtimeProperty.getPropertyType();

                throw new IllegalArgumentException(message);

            }
        }

        container.setEnvVariables(environments);
        deploymentConfig.setContainer(container);
        deploymentConfig.setSecrets(secrets);

        //Calling application method to deploy the runtime properties with the application
        deployApplication(deploymentConfig);

    }

    /**
     * Update runtime properties already defined in the application
     *
     * @param runtimeProperties list of runtime properties
     * @param deploymentConfig  includes deployment related details
     * @throws RuntimeProvisioningException
     */
    @Override
    public void updateRuntimeProperties(List<RuntimeProperty> runtimeProperties, DeploymentConfig deploymentConfig)
            throws RuntimeProvisioningException {

        List secrets = new ArrayList();
        Container container = new Container();
        List environments = new ArrayList();

        for (RuntimeProperty runtimeProperty : runtimeProperties) {
            switch (runtimeProperty.getPropertyType()) {
            case SENSITIVE:
                if (log.isDebugEnabled()) {
                    String message = "Updating property type secret for the application:" + applicationContext.getName()
                            + " for the tenant domain:" + applicationContext.getTenantInfo().getTenantDomain();

                    log.debug(message);
                }
                Secret secret = new SecretBuilder().withKind(KubernetesPovisioningConstants.KIND_SECRETS)
                        .withApiVersion(Secret.ApiVersion.V_1)
                        .withNewMetadata()
                        .withNamespace(namespace.getMetadata().getNamespace())
                        .withName(runtimeProperty.getName())
                        .endMetadata()
                        .withData(runtimeProperty.getProperties())
                        .build();

                KubernetesClient kubernetesClient = KubernetesProvisioningUtils.getFabric8KubernetesClient();
                kubernetesClient.secrets().inNamespace(namespace.getMetadata().getName())
                        .withName(runtimeProperty.getName()).replace(secret);

                Volume volume = new VolumeBuilder()
                        .withName(KubernetesPovisioningConstants.VOLUME_MOUNT)
                        .withNewSecret()
                        .withSecretName(runtimeProperty.getName())
                        .endSecret()
                        .build();

                secrets.add(volume);

                break;
            case ENVIRONMENT:
                if (log.isDebugEnabled()) {
                    String message = "Updating property type environment for the application:"
                            + applicationContext.getName() + " for the tenant domain:"
                            + applicationContext.getTenantInfo().getTenantDomain();

                    log.debug(message);
                }
                for (Map.Entry<String, String> property : runtimeProperty.getProperties().entrySet()) {
                    EnvVar envVar = new EnvVarBuilder()
                            .withName(property.getKey())
                            .withValue(property.getValue())
                            .build();

                    environments.add(envVar);
                }

                break;
            default:
                String message = "Runtime property type is not support, property type:"
                        + runtimeProperty.getPropertyType();

                throw new IllegalArgumentException(message);
            }
        }

        container.setEnvVariables(environments);
        deploymentConfig.setContainer(container);
        deploymentConfig.setSecrets(secrets);

        //call application deployment to re deploy the application with update variables
        deployApplication(deploymentConfig);
    }

    /**
     * Provide runtime properties for given application context
     *
     * @return list of runtime properties
     * @throws RuntimeProvisioningException
     */
    @Override
    public List<RuntimeProperty> getRuntimeProperties() throws RuntimeProvisioningException {

        KubernetesClient kubernetesClient = KubernetesProvisioningUtils.getFabric8KubernetesClient();
        SecretList secretList = kubernetesClient.secrets().inNamespace(namespace.getMetadata().getName()).list();

        List<RuntimeProperty> runtimeProperties = new ArrayList<>();

        for (Secret secret : secretList.getItems()) {
            RuntimeProperty sensitiveRuntimeProperty = new RuntimeProperty();
            sensitiveRuntimeProperty.setPropertyType(RuntimeProperty.PropertyType.SENSITIVE);
            sensitiveRuntimeProperty.setName(secret.getMetadata().getName());
            sensitiveRuntimeProperty.setProperties(secret.getData());
            runtimeProperties.add(sensitiveRuntimeProperty);

        }

        Pod pod = kubernetesClient.pods().inNamespace(namespace.getMetadata().getName())
                .withName(applicationContext.getName()).get();

        //get only first container from the container list
        List<EnvVar> envVarList = pod.getSpec().getContainers().get(0).getEnv();
        HashMap<String, String> data = new HashMap<>();

        for (EnvVar envVar : envVarList) {
            data.put(envVar.getName(), envVar.getValue());
        }

        RuntimeProperty environmentVariable = new RuntimeProperty();
        environmentVariable.setPropertyType(RuntimeProperty.PropertyType.ENVIRONMENT);
        environmentVariable.setProperties(data);

        runtimeProperties.add(environmentVariable);

        return runtimeProperties;

    }

    /**
     * add a set of custom domains for an application version. This will create an ingress for each domain
     * and each service
     * @param domains set of domains
     * @throws RuntimeProvisioningException
     */
    @Override
    public void addCustomDomain(Set<String> domains) throws RuntimeProvisioningException {

        HttpClient httpclient = null;
        URI uri = null;
        String ingJson;
        ObjectMapper objectMapper = new ObjectMapper();

        ServiceList serviceList = KubernetesProvisioningUtils.getServices(applicationContext);

        for (String domain : domains) {
            for (Service service : serviceList.getItems()){
                Ingress ing = new IngressBuilder()
                        .withApiVersion(Ingress.ApiVersion.EXTENSIONS_V_1_BETA_1)
                        .withKind(KubernetesPovisioningConstants.KIND_INGRESS)
                        .withNewMetadata()
                        .withName(KubernetesProvisioningUtils
                                .createIgressMetaName(applicationContext, domain, service.getMetadata().getName()))
                        .withNamespace(namespace.getMetadata().getNamespace())
                        .endMetadata()
                        .withNewSpec().addNewRule()
                        .withHost(domain)
                        .withNewHttp().addNewPath()
                        .withNewBackend()
                        .withServiceName(service.getMetadata().getName())
                        .withServicePort(new IntOrString(80))
                        .endBackend()
                        .endPath()
                        .endHttp()
                        .endRule()
                        .endSpec()
                        .build();

                try {
                    httpclient = KubernetesProvisioningUtils.getHttpClientForKubernetes();
                    ingJson = objectMapper.writeValueAsString(ing);
                    if (log.isDebugEnabled()) {
                        log.debug(ingJson);
                    }

                    StringEntity stringEntity = new StringEntity(ingJson, "UTF-8");
                    uri = new URI(KubernetesPovisioningConstants.KUB_MASTER_URL
                            + KubernetesPovisioningConstants.INGRESS_API_NAMESPACE_RESOURCE_PATH
                            + namespace.getMetadata().getNamespace()
                            + KubernetesPovisioningConstants.INGRESS_API_RESOURCE_PATH_SUFFIX);

                    HttpPost httpPost = (HttpPost) KubernetesProvisioningUtils
                            .getHttpMethodForKubernetes(HttpPost.METHOD_NAME, uri);
                    httpPost.addHeader(HttpHeaders.CONTENT_TYPE, KubernetesPovisioningConstants.MIME_TYPE_JSON);
                    httpPost.setEntity(stringEntity);

                    HttpResponse response = httpclient.execute(httpPost);

                    if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                        throw new RuntimeProvisioningException(
                                "Failed to add domain mapping Domain: " + domains + "HTTP error code : " + response
                                        .getStatusLine().getStatusCode());
                    }

                    if (log.isDebugEnabled()) {
                        log.debug("addCustomDomain response: " + response.getEntity().getContent());
                    }

                } catch (JsonProcessingException e) {
                    String msg = "Exception in converting the ingress object to json";
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(e);
                } catch (KeyStoreException e) {
                    String msg = "Error in keystore while connecting to Kubernetes cluster";
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(msg, e);
                } catch (NoSuchAlgorithmException e) {
                    String msg = "Cryptographic algorithm not found while connecting to Kubernetes cluster";
                    log.error(msg, e);
                } catch (KeyManagementException e) {
                    String msg = "Exception in key management while while connecting to Kubernetes cluster";
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(e);
                } catch (URISyntaxException e) {
                    String msg = "Error in url syntax : " + uri;
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(e);
                } catch (UnsupportedEncodingException e) {
                    String msg = "Character encoding used is not supported";
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(e);
                } catch (ClientProtocolException e) {
                    String msg = "Exception occurred in client protocol while trying to invoke Kubernetes api";
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(e);
                } catch (IOException e) {
                    String msg = "Connection exception while connecting to Kubernetes cluster";
                    log.error(msg, e);
                    throw new RuntimeProvisioningException(e);
                } finally {
                    httpclient.getConnectionManager().shutdown();
                }
            }
        }
    }

    /**
     * update a certain domain by replacing the ingresses created for related services with new ingresses
     * @param domain domain name
     * @throws RuntimeProvisioningException
     */
    @Override
    public void updateCustomDomain(String domain) throws RuntimeProvisioningException {

        HttpClient httpclient = null;
        URI uri = null;
        String ingressName = null;
        String ingJson;
        ObjectMapper objectMapper = new ObjectMapper();

        ServiceList serviceList = KubernetesProvisioningUtils.getServices(applicationContext);

        for(Service service : serviceList.getItems()) {
            Ingress ing = new IngressBuilder().withApiVersion(Ingress.ApiVersion.EXTENSIONS_V_1_BETA_1)
                    .withKind(KubernetesPovisioningConstants.KIND_INGRESS)
                    .withNewMetadata()
                    .withName(KubernetesProvisioningUtils
                            .createIgressMetaName(applicationContext,domain,service.getMetadata().getName()))
                    .withNamespace(namespace.getMetadata().getNamespace())
                    .endMetadata()
                    .withNewSpec()
                    .addNewRule()
                    .withHost(domain)
                    .withNewHttp()
                    .addNewPath()
                    .withNewBackend()
                    .withServiceName(service.getMetadata().getName())
                    .withServicePort(new IntOrString(80))
                    .endBackend()
                    .endPath()
                    .endHttp()
                    .endRule()
                    .endSpec()
                    .build();

            try {

                ingJson = objectMapper.writeValueAsString(ing);
                StringEntity stringEntity = new StringEntity(ingJson, "UTF-8");
                uri = new URI(KubernetesPovisioningConstants.KUB_MASTER_URL
                        + KubernetesPovisioningConstants.INGRESS_API_NAMESPACE_RESOURCE_PATH
                        + namespace.getMetadata().getNamespace()
                        + KubernetesPovisioningConstants.INGRESS_API_RESOURCE_PATH_SUFFIX
                        + ingressName);

                httpclient = KubernetesProvisioningUtils.getHttpClientForKubernetes();
                HttpPut httpPut = (HttpPut) KubernetesProvisioningUtils
                        .getHttpMethodForKubernetes(HttpPut.METHOD_NAME, uri);
                httpPut.addHeader(HttpHeaders.CONTENT_TYPE, KubernetesPovisioningConstants.MIME_TYPE_JSON);
                httpPut.setEntity(stringEntity);

                HttpResponse response = httpclient.execute(httpPut);

                if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    throw new RuntimeProvisioningException(
                            "Failed to update domain mapping Domain: " + domain + "HTTP error code : " + response
                                    .getStatusLine().getStatusCode());
                }

                if (log.isDebugEnabled()) {
                    log.debug("addCustomDomain response: " + response.getEntity().getContent());
                }
            } catch (JsonProcessingException e) {
                String msg = "Exception in converting the ingress object to json";
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (KeyStoreException e) {
                String msg = "Error in keystore while connecting to Kubernetes cluster";
                log.error(msg, e);
                throw new RuntimeProvisioningException(msg, e);
            } catch (NoSuchAlgorithmException e) {
                String msg = "Cryptographic algorithm not found while connecting to Kubernetes cluster";
                log.error(msg, e);
            } catch (KeyManagementException e) {
                String msg = "Exception in key management while while connecting to Kubernetes cluster";
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (URISyntaxException e) {
                String msg = "Error in url syntax : " + uri;
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (UnsupportedEncodingException e) {
                String msg = "Character encoding used is not supported";
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (ClientProtocolException e) {
                String msg = "Exception occurred in client protocol while trying to invoke Kubernetes api";
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (IOException e) {
                String msg = "Connection exception while connecting to Kubernetes cluster";
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } finally {
                httpclient.getConnectionManager().shutdown();
            }
        }
    }

    /**
     * get a set of custom domains for a particular applicaiton context
     * @return set of domains
     * @throws RuntimeProvisioningException
     */
    @Override
    public Set<String> getCustomDomains() throws RuntimeProvisioningException {

        HttpClient httpclient = null;
        URI uri = null;
        Set<String> domains = new HashSet<>();
        String output="";

        try {

            httpclient = KubernetesProvisioningUtils.getHttpClientForKubernetes();
            uri = new URI(KubernetesPovisioningConstants.KUB_MASTER_URL
                    + KubernetesPovisioningConstants.INGRESS_API_NAMESPACE_RESOURCE_PATH
                    + namespace.getMetadata().getNamespace()
                          + KubernetesPovisioningConstants.INGRESS_API_RESOURCE_PATH_SUFFIX);

            HttpGet httpGet = (HttpGet)KubernetesProvisioningUtils.getHttpMethodForKubernetes(HttpGet.METHOD_NAME, uri);
            httpGet.addHeader(HttpHeaders.CONTENT_TYPE, KubernetesPovisioningConstants.MIME_TYPE_JSON);

            HttpResponse response = httpclient.execute(httpGet);

            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                throw new RuntimeProvisioningException("Failed to get domain mappings: HTTP error code : "
                                                       + response.getStatusLine().getStatusCode());
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader((response.getEntity().getContent())));

            while ((output = br.readLine()) != null) {
                output += output;
            }

            JSONObject jsonObject = new JSONObject(output.trim());

            for(int i = 0 ; i < jsonObject.getJSONArray(KubernetesPovisioningConstants.ITEMS).length() ; i++){
                domains.add(
                        jsonObject.getJSONArray(KubernetesPovisioningConstants.ITEMS)
                                .getJSONObject(i).getJSONObject(KubernetesPovisioningConstants.SPEC)
                                .getJSONArray(KubernetesPovisioningConstants.RULES)
                                .getJSONObject(0).getString(KubernetesPovisioningConstants.HOST));
            }

        } catch (KeyStoreException e) {
            String msg = "Error in keystore while connecting to Kubernetes cluster";
            log.error(msg, e);
            throw new RuntimeProvisioningException(msg, e);
        } catch (NoSuchAlgorithmException e) {
            String msg = "Cryptographic algorithm not found while connecting to Kubernetes cluster";
            log.error(msg, e);
        } catch (KeyManagementException e) {
            String msg = "Exception in key management while while connecting to Kubernetes cluster";
            log.error(msg, e);
            throw new RuntimeProvisioningException(e);
        } catch (URISyntaxException e) {
            String msg = "Error in url syntax : " + uri;
            log.error(msg, e);
            throw new RuntimeProvisioningException(e);
        } catch (UnsupportedEncodingException e) {
            String msg = "Character encoding used is not supported";
            log.error(msg, e);
            throw new RuntimeProvisioningException(e);
        } catch (ClientProtocolException e) {
            String msg = "Exception occurred in client protocol while trying to invoke Kubernetes api";
            log.error(msg, e);
            throw new RuntimeProvisioningException(e);
        } catch (IOException e) {
            String msg = "Connection exception while connecting to Kubernetes cluster";
            log.error(msg, e);
            throw new RuntimeProvisioningException(e);
        } catch (JSONException e) {
            String msg = "Error occured while parsing JSON: " + output;
            log.error(msg, e);
            throw new RuntimeProvisioningException(e);
        } finally {
            httpclient.getConnectionManager().shutdown();
        }
        return domains;
    }

    /**
     * delete a custom domain and delete the ingresses created for related services
     * @param domain domain name
     * @throws RuntimeProvisioningException
     */
    @Override
    public void deleteCustomDomain(String domain) throws RuntimeProvisioningException {
        HttpClient httpclient = null;
        URI uri = null;

        ServiceList serviceList = KubernetesProvisioningUtils.getServices(applicationContext);

        for(Service service : serviceList.getItems()) {
            try {

                httpclient = KubernetesProvisioningUtils.getHttpClientForKubernetes();
                uri = new URI(KubernetesPovisioningConstants.KUB_MASTER_URL
                        + KubernetesPovisioningConstants.INGRESS_API_NAMESPACE_RESOURCE_PATH
                        + namespace.getMetadata().getNamespace()
                        + KubernetesPovisioningConstants.INGRESS_API_RESOURCE_PATH_SUFFIX
                        + KubernetesProvisioningUtils
                        .createIgressMetaName(applicationContext, domain, service.getMetadata().getName()));
                HttpDelete httpDelete = (HttpDelete) KubernetesProvisioningUtils
                        .getHttpMethodForKubernetes(HttpDelete.METHOD_NAME, uri);
                httpDelete.addHeader(HttpHeaders.CONTENT_TYPE, KubernetesPovisioningConstants.MIME_TYPE_JSON);

                HttpResponse response = httpclient.execute(httpDelete);

                if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    throw new RuntimeProvisioningException("Failed to delete domain mapping: HTTP error code : "
                            + response.getStatusLine()
                            .getStatusCode());
                }
            } catch (KeyStoreException e) {
                String msg = "Error in keystore while connecting to Kubernetes cluster";
                log.error(msg, e);
                throw new RuntimeProvisioningException(msg, e);
            } catch (NoSuchAlgorithmException e) {
                String msg = "Cryptographic algorithm not found while connecting to Kubernetes cluster";
                log.error(msg, e);
            } catch (KeyManagementException e) {
                String msg = "Exception in key management while while connecting to Kubernetes cluster";
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (URISyntaxException e) {
                String msg = "Error in url syntax : " + uri;
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (UnsupportedEncodingException e) {
                String msg = "Character encoding used is not supported";
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (ClientProtocolException e) {
                String msg = "Exception occurred in client protocol while trying to invoke Kubernetes api";
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } catch (IOException e) {
                String msg = "Connection exception while connecting to Kubernetes cluster";
                log.error(msg, e);
                throw new RuntimeProvisioningException(e);
            } finally {
                httpclient.getConnectionManager().shutdown();
            }
        }
    }
}
