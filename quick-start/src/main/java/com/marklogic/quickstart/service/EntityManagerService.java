/*
 * Copyright 2012-2016 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.quickstart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.appdeployer.AppConfig;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.ext.modulesloader.impl.AssetFileLoader;
import com.marklogic.client.ext.modulesloader.impl.DefaultModulesLoader;
import com.marklogic.client.ext.modulesloader.impl.PropertiesModuleManager;
import com.marklogic.client.extensions.ResourceManager;
import com.marklogic.client.extensions.ResourceServices;
import com.marklogic.client.io.JacksonHandle;
import com.marklogic.client.io.StringHandle;
import com.marklogic.client.util.RequestParameters;
import com.marklogic.hub.DataHub;
import com.marklogic.hub.HubConfig;
import com.marklogic.hub.flow.FlowType;
import com.marklogic.hub.scaffold.Scaffolding;
import com.marklogic.quickstart.auth.ConnectionAuthenticationToken;
import com.marklogic.quickstart.model.EnvironmentConfig;
import com.marklogic.quickstart.model.FlowModel;
import com.marklogic.quickstart.model.PluginModel;
import com.marklogic.quickstart.model.entity_services.EntityModel;
import com.marklogic.quickstart.model.entity_services.HubUIData;
import com.marklogic.quickstart.model.entity_services.InfoType;
import com.marklogic.quickstart.util.FileUtil;
import com.sun.jersey.api.client.ClientHandlerException;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class EntityManagerService {

    private static final String UI_LAYOUT_FILE = "entities.layout.json";
    private static final String PLUGINS_DIR = "plugins";
    private static final String ENTITIES_DIR = "entities";
    private static final String ENTITY_FILE_EXTENSION = ".entity.json";

    @Autowired
    private FlowManagerService flowManagerService;

    @Autowired
    private FileSystemWatcherService watcherService;

    private EnvironmentConfig envConfig() {
        ConnectionAuthenticationToken authenticationToken = (ConnectionAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        return authenticationToken.getEnvironmentConfig();
    }

    public List<EntityModel> getLegacyEntities() throws IOException {
        String projectDir = envConfig().getProjectDir();
        List<EntityModel> entities = new ArrayList<>();
        Path entitiesDir = envConfig().getMlSettings().getHubEntitiesDir();
        List<String> entityNames = FileUtil.listDirectFolders(entitiesDir.toFile());
        for (String entityName : entityNames) {
            EntityModel entityModel = new EntityModel();
            InfoType infoType = new InfoType();
            infoType.setTitle(entityName);
            entityModel.setInfo(infoType);
            entityModel.inputFlows = flowManagerService.getFlows(projectDir, entityName, FlowType.INPUT);
            entityModel.harmonizeFlows = flowManagerService.getFlows(projectDir, entityName, FlowType.HARMONIZE);
            entities.add(entityModel);
        }
        return entities;
    }

    public List<EntityModel> getEntities() throws IOException {
        if (envConfig().getMarklogicVersion().startsWith("8")) {
            return getLegacyEntities();
        }

        String projectDir = envConfig().getProjectDir();

        Map<String, HubUIData> hubUiData = getUiData();
        List<EntityModel> entities = new ArrayList<>();
        Path entitiesPath = Paths.get(envConfig().getProjectDir(), PLUGINS_DIR, ENTITIES_DIR);
        List<String> entityNames = FileUtil.listDirectFolders(entitiesPath.toFile());
        ObjectMapper objectMapper = new ObjectMapper();
        for (String entityName : entityNames) {
            File[] entityDefs = entitiesPath.resolve(entityName).toFile().listFiles((dir, name) -> name.endsWith(ENTITY_FILE_EXTENSION));
            for (File entityDef : entityDefs) {
                FileInputStream fileInputStream = new FileInputStream(entityDef);
                JsonNode node = objectMapper.readTree(fileInputStream);
                fileInputStream.close();
                EntityModel entityModel = EntityModel.fromJson(entityDef.getAbsolutePath(), node);
                if (entityModel != null) {
                    HubUIData data = hubUiData.get(entityModel.getInfo().getTitle());
                    if (data == null) {
                        data = new HubUIData();
                    }
                    entityModel.setHubUi(data);
                    entityModel.inputFlows = flowManagerService.getFlows(projectDir, entityName, FlowType.INPUT);
                    entityModel.harmonizeFlows = flowManagerService.getFlows(projectDir, entityName, FlowType.HARMONIZE);

                    entities.add(entityModel);
                }
            }
        }

        return entities;
    }

    public EntityModel createEntity(String projectDir, EntityModel newEntity) throws IOException {
        Scaffolding scaffolding = new Scaffolding(projectDir, envConfig().getFinalClient());
        scaffolding.createEntity(newEntity.getName());

        if (newEntity.inputFlows != null) {
            for (FlowModel flow : newEntity.inputFlows) {
                scaffolding.createFlow(newEntity.getName(), flow.flowName, FlowType.INPUT, flow.codeFormat, flow.dataFormat);
            }
        }

        if (newEntity.harmonizeFlows != null) {
            for (FlowModel flow : newEntity.harmonizeFlows) {
                scaffolding.createFlow(newEntity.getName(), flow.flowName, FlowType.HARMONIZE, flow.codeFormat, flow.dataFormat);
            }
        }

        return getEntity(newEntity.getName());
    }

    public EntityModel saveEntity(EntityModel entity) throws IOException {
        JsonNode node = entity.toJson();
        ObjectMapper objectMapper = new ObjectMapper();
        String filename = entity.getFilename();
        if (filename == null) {
            String title = entity.getInfo().getTitle();
            Path dir = Paths.get(envConfig().getProjectDir(), PLUGINS_DIR, ENTITIES_DIR, title);
            if (!dir.toFile().exists()) {
                dir.toFile().mkdirs();
            }
            filename = Paths.get(dir.toString(), title + ENTITY_FILE_EXTENSION).toString();
        }

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        FileUtils.writeStringToFile(new File(filename), json);

        return entity;
    }

    public void deleteEntity(String entity) throws IOException {
        Path dir = Paths.get(envConfig().getProjectDir(), PLUGINS_DIR, ENTITIES_DIR, entity);
        if (dir.toFile().exists()) {
            watcherService.unwatch(dir.getParent().toString());
            FileUtils.deleteDirectory(dir.toFile());
        }
    }

    public List<JsonNode> getRawEntities(EnvironmentConfig environmentConfig) throws IOException {
        List<JsonNode> entities = new ArrayList<>();
        Path entitiesPath = Paths.get(environmentConfig.getProjectDir(), PLUGINS_DIR, ENTITIES_DIR);
        List<String> entityNames = FileUtil.listDirectFolders(entitiesPath.toFile());
        ObjectMapper objectMapper = new ObjectMapper();
        for (String entityName : entityNames) {
            File[] entityDefs = entitiesPath.resolve(entityName).toFile().listFiles((dir, name) -> name.endsWith(ENTITY_FILE_EXTENSION));
            for (File entityDef : entityDefs) {
                FileInputStream fileInputStream = new FileInputStream(entityDef);
                entities.add(objectMapper.readTree(fileInputStream));
                fileInputStream.close();
            }
        }
        return entities;
    }

    public void saveSearchOptions(EnvironmentConfig environmentConfig) {

        HubConfig hubConfig = environmentConfig.getMlSettings();

        DefaultModulesLoader modulesLoader = new DefaultModulesLoader(new AssetFileLoader(hubConfig.newFinalClient()));
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setCorePoolSize(16);

        // 10 minutes should be plenty of time to wait for REST API modules to be loaded
        threadPoolTaskExecutor.setAwaitTerminationSeconds(60 * 10);
        threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(true);

        threadPoolTaskExecutor.afterPropertiesSet();
        modulesLoader.setTaskExecutor(threadPoolTaskExecutor);

        File timestampFile = hubConfig.getHubModulesDeployTimestampFile();
        PropertiesModuleManager propsManager = new PropertiesModuleManager(timestampFile);
        propsManager.deletePropertiesFile();
        modulesLoader.setModulesManager(propsManager);
        modulesLoader.setDatabaseClient(hubConfig.newFinalClient());
        modulesLoader.setShutdownTaskExecutorAfterLoadingModules(false);

        SearchOptionsGenerator generator = new SearchOptionsGenerator(environmentConfig.getStagingClient());
        try {
            List<JsonNode> entities = getRawEntities(environmentConfig);
            if (entities.size() > 0) {
                String options = generator.generateOptions(entities);
                Path dir = Paths.get(environmentConfig.getProjectDir(), HubConfig.ENTITY_CONFIG_DIR);
                if (!dir.toFile().exists()) {
                    dir.toFile().mkdirs();
                }

                File file = Paths.get(dir.toString(), HubConfig.ENTITY_SEARCH_OPTIONS_FILE).toFile();
                FileUtils.writeStringToFile(file, options);
                modulesLoader.installQueryOptions(new FileSystemResource(file));
            }
        }
        catch(IOException e) {
            e.printStackTrace();
        }
    }

    public void saveDbIndexes(EnvironmentConfig environmentConfig) {
        DbIndexGenerator generator = new DbIndexGenerator(environmentConfig.getFinalClient());
        try {
            String indexes = generator.getIndexes(getRawEntities(environmentConfig));

            Path dir = environmentConfig.getMlSettings().getEntityDatabaseDir();
            if (!dir.toFile().exists()) {
                dir.toFile().mkdirs();
            }
            File file = dir.resolve("final-database.json").toFile();
            FileUtils.writeStringToFile(file, indexes);

            file = dir.resolve("staging-database.json").toFile();
            FileUtils.writeStringToFile(file, indexes);
        }
        catch(IOException e) {
            e.printStackTrace();
        }
    }

    public void saveAllUiData(List<EntityModel> entities) throws IOException {
        ObjectNode uiData;
        JsonNode json = getUiRawData();
        if (json != null) {
            uiData = (ObjectNode) json;
        }
        else {
            uiData = JsonNodeFactory.instance.objectNode();
        }

        Path dir = Paths.get(envConfig().getProjectDir(), HubConfig.USER_CONFIG_DIR);
        if (!dir.toFile().exists()) {
            dir.toFile().mkdirs();
        }
        File file = Paths.get(dir.toString(), UI_LAYOUT_FILE).toFile();

        ObjectNode cUiData = uiData;
        entities.forEach((entity) -> {
            JsonNode node = entity.getHubUi().toJson();
            cUiData.set(entity.getInfo().getTitle(), node);
        });

        ObjectMapper objectMapper = new ObjectMapper();
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(fileOutputStream, uiData);
        fileOutputStream.flush();
        fileOutputStream.close();
    }

    public void saveEntityUiData(EntityModel entity) throws IOException {

        ObjectNode uiData;
        JsonNode json = getUiRawData();
        if (json != null) {
            uiData = (ObjectNode) json;
        }
        else {
            uiData = JsonNodeFactory.instance.objectNode();
        }

        Path dir = Paths.get(envConfig().getProjectDir(), HubConfig.USER_CONFIG_DIR);
        if (!dir.toFile().exists()) {
            dir.toFile().mkdirs();
        }
        File file = Paths.get(dir.toString(), UI_LAYOUT_FILE).toFile();

        JsonNode node = entity.getHubUi().toJson();
        uiData.set(entity.getInfo().getTitle(), node);

        ObjectMapper objectMapper = new ObjectMapper();
        FileOutputStream fileOutputStream = new FileOutputStream(file);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(fileOutputStream, uiData);
        fileOutputStream.flush();
        fileOutputStream.close();
    }

    public EntityModel getEntity(String entityName) throws IOException {
        List<EntityModel> entities = getEntities();

        for (EntityModel entity : entities) {
            if (entity.getName().equals(entityName)) {
                return entity;
            }
        }

        return null;
    }

    public FlowModel getFlow(String entityName, FlowType flowType, String flowName) throws IOException {
        EntityModel entity = getEntity(entityName);

        List<FlowModel> flows;
        if (flowType.equals(FlowType.INPUT)) {
            flows = entity.inputFlows;
        }
        else {
            flows = entity.harmonizeFlows;
        }

        for (FlowModel flow : flows) {
            if (flow.flowName.equals(flowName)) {
                return flow;
            }
        }

        return null;
    }

    public FlowModel createFlow(String projectDir, String entityName, FlowType flowType, FlowModel newFlow) throws IOException {
        Scaffolding scaffolding = new Scaffolding(projectDir, envConfig().getFinalClient());
        newFlow.entityName = entityName;
        scaffolding.createFlow(entityName, newFlow.flowName, flowType, newFlow.codeFormat, newFlow.dataFormat, newFlow.useEsModel);
        return getFlow(entityName, flowType, newFlow.flowName);
    }

    public void deleteFlow(String projectDir, String entityName, String flowName, FlowType flowType) throws IOException {
        Scaffolding scaffolding = new Scaffolding(projectDir, envConfig().getFinalClient());
        Path flowDir = scaffolding.getFlowDir(entityName, flowName, flowType);
        FileUtils.deleteDirectory(flowDir.toFile());
    }

    public JsonNode validatePlugin(
        HubConfig config,
        String entityName,
        String flowName,
        PluginModel plugin
    ) throws IOException {
        JsonNode result = null;
        String type;
        if (plugin.pluginType.endsWith("sjs")) {
            type = "javascript";
        }
        else {
            type = "xquery";
        }
        return (new DataHub(config)).validateUserModule(entityName, flowName, plugin.fileContents.replaceAll("\\.(sjs|xqy)", ""), type, plugin.fileContents);
    }

    public void saveFlowPlugin(
        PluginModel plugin
    ) throws IOException {
        String pluginContent = plugin.fileContents;
        Files.write(Paths.get(plugin.pluginPath), pluginContent.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);

    }
    private JsonNode getUiRawData() {
        JsonNode json = null;
        Path dir = Paths.get(envConfig().getProjectDir(), HubConfig.USER_CONFIG_DIR);
        File file = Paths.get(dir.toString(), UI_LAYOUT_FILE).toFile();
        if (file.exists()) {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                json = objectMapper.readTree(file);
            }
            catch(IOException e) {
                e.printStackTrace();
            }
        }
        return json;
    }

    private Map<String, HubUIData> getUiData() throws IOException {
        HashMap<String, HubUIData> uiDataList = new HashMap<>();

        JsonNode json = getUiRawData();
        if (json != null) {
            Iterator<String> fieldItr = json.fieldNames();
            while (fieldItr.hasNext()) {
                String key = fieldItr.next();
                JsonNode uiNode = json.get(key);
                if (uiNode != null) {
                    HubUIData uiData = HubUIData.fromJson(uiNode);
                    uiDataList.put(key, uiData);
                }
            }
        }

        return uiDataList;
    }

    public class SearchOptionsGenerator extends ResourceManager {
        private static final String NAME = "search-options-generator";

        private RequestParameters params = new RequestParameters();

        SearchOptionsGenerator(DatabaseClient client) {
            super();
            client.init(NAME, this);
        }

        String generateOptions(List<JsonNode> entities) throws IOException {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode node = objectMapper.valueToTree(entities);
                ResourceServices.ServiceResultIterator resultItr = this.getServices().post(params, new JacksonHandle(node));
                if (resultItr == null || ! resultItr.hasNext()) {
                    throw new IOException("Unable to generate search options");
                }
                ResourceServices.ServiceResult res = resultItr.next();
                return res.getContent(new StringHandle()).get();
            }
            catch(ClientHandlerException e) {
                e.printStackTrace();
            }
            return "{}";
        }
    }

    public class DbIndexGenerator extends ResourceManager {
        private static final String NAME = "db-configs";

        private RequestParameters params = new RequestParameters();

        DbIndexGenerator(DatabaseClient client) {
            super();
            client.init(NAME, this);
        }

        public String getIndexes(List<JsonNode> entities) throws IOException {
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode node = objectMapper.valueToTree(entities);
                ResourceServices.ServiceResultIterator resultItr = this.getServices().post(params, new JacksonHandle(node));
                if (resultItr == null || ! resultItr.hasNext()) {
                    throw new IOException("Unable to generate search options");
                }
                ResourceServices.ServiceResult res = resultItr.next();
                return res.getContent(new StringHandle()).get();
            }
            catch(ClientHandlerException e) {
                e.printStackTrace();
            }
            return "{}";
        }
    }
}
