/*
 * Copyright (C) 2016 Red Hat, Inc.
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
package io.syndesis.server.endpoint.v1.handler.integration.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.validation.constraints.NotNull;
import javax.ws.rs.ClientErrorException;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import io.syndesis.common.model.Dependency;
import io.syndesis.common.model.Kind;
import io.syndesis.common.model.ListResult;
import io.syndesis.common.model.ModelExport;
import io.syndesis.common.model.ResourceIdentifier;
import io.syndesis.common.model.Schema;
import io.syndesis.common.model.WithId;
import io.syndesis.common.model.WithName;
import io.syndesis.common.model.WithResourceId;
import io.syndesis.common.model.connection.Connection;
import io.syndesis.common.model.connection.Connector;
import io.syndesis.common.model.extension.Extension;
import io.syndesis.common.model.icon.Icon;
import io.syndesis.common.model.integration.Integration;
import io.syndesis.common.model.integration.IntegrationDeployment;
import io.syndesis.common.model.integration.IntegrationOverview;
import io.syndesis.common.model.integration.Step;
import io.syndesis.common.model.openapi.OpenApi;
import io.syndesis.common.util.Json;
import io.syndesis.common.util.Names;
import io.syndesis.integration.api.IntegrationProjectGenerator;
import io.syndesis.integration.api.IntegrationResourceManager;
import io.syndesis.server.dao.file.FileDataManager;
import io.syndesis.server.dao.file.IconDao;
import io.syndesis.server.dao.manager.DataManager;
import io.syndesis.server.endpoint.v1.handler.integration.IntegrationHandler;
import io.syndesis.server.jsondb.CloseableJsonDB;
import io.syndesis.server.jsondb.JsonDB;
import io.syndesis.server.jsondb.dao.JsonDbDao;
import io.syndesis.server.jsondb.dao.Migrator;
import io.syndesis.server.jsondb.impl.MemorySqlJsonDB;
import io.syndesis.server.jsondb.impl.SqlJsonDB;

import static org.springframework.util.StreamUtils.nonClosing;

@Path("/integration-support")
@Api(value = "integration-support")
@Component
@SuppressWarnings({ "PMD.ExcessiveImports", "PMD.GodClass", "PMD.StdCyclomaticComplexity", "PMD.ModifiedCyclomaticComplexity", "PMD.CyclomaticComplexity" })
public class IntegrationSupportHandler {

    private static final String EXPORT_MODEL_INFO_FILE_NAME = "model-info.json";
    private static final String EXPORT_MODEL_FILE_NAME = "model.json";

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationSupportHandler.class);
    private static final String IMPORTED_SUFFIX = "-imported-";

    private static final BiFunction<Extension, String, Extension> RENAME_EXTENSION = (e, n) -> new Extension.Builder().createFrom(e).name(n).build();
    private static final BiFunction<Connection, String, Connection> RENAME_CONNECTION = (c, n) -> new Connection.Builder().createFrom(c).name(n).build();

    private final Migrator migrator;
    private final SqlJsonDB jsondb;
    private final IntegrationProjectGenerator projectGenerator;
    private final DataManager dataManager;
    private final IntegrationResourceManager resourceManager;
    private final IntegrationHandler integrationHandler;
    private final FileDataManager extensionDataManager;
    private final IconDao iconDao;

    public IntegrationSupportHandler(
        final Migrator migrator,
        final SqlJsonDB jsondb,
        final IntegrationProjectGenerator projectGenerator,
        final DataManager dataManager,
        final IntegrationResourceManager resourceManager,
        final IntegrationHandler integrationHandler,
        final FileDataManager extensionDataManager,
        final IconDao iconDao) {
        this.migrator = migrator;
        this.jsondb = jsondb;

        this.projectGenerator = projectGenerator;
        this.dataManager = dataManager;
        this.resourceManager = resourceManager;
        this.integrationHandler = integrationHandler;
        this.extensionDataManager = extensionDataManager;
        this.iconDao = iconDao;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path(value = "/overviews")
    public ListResult<IntegrationOverview> getOverviews(@Context  UriInfo uriInfo) {
        return integrationHandler.list(uriInfo);
    }

    @POST
    @Path("/generate/pom.xml")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public byte[] projectPom(Integration integration) throws IOException {
        return projectGenerator.generatePom(integration);
    }

    @GET
    @Path("/export.zip")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public StreamingOutput export(@NotNull @QueryParam("id") @ApiParam(required = true) List<String> requestedIds) throws IOException {

        List<String> ids = requestedIds;
        if ( ids ==null || ids.isEmpty() ) {
            throw new ClientErrorException("No 'integration' query parameter specified.", Response.Status.BAD_REQUEST);
        }

        CloseableJsonDB memJsonDB = MemorySqlJsonDB.create(jsondb.getIndexes());
        if( ids.contains("all") ) {
            ids = new ArrayList<>();
            for (Integration integration : dataManager.fetchAll(Integration.class)) {
                ids.add(integration.getId().get());
            }
        }
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        LinkedHashSet<String> icons = new LinkedHashSet<>();
        for (String id : ids) {
            Integration integration = integrationHandler.getIntegration(id);
            addToExport(memJsonDB, integration);
            Collection<Dependency> dependencies = resourceManager.collectDependencies(integration.getFlows().stream()
                    .flatMap(flow -> flow.getSteps().stream())
                    .collect(Collectors.toList()), true);
            dependencies.stream()
                .filter(d -> d.isExtension() || d.isIcon() )
                .map(Dependency::getId)
                .forEach(extensions::add);
        }
        LOG.debug("Extensions: {}", extensions);
        LOG.debug("Icons: {}", icons);

        return out -> {
            try (ZipOutputStream tos = new ZipOutputStream(out) ) {
                ModelExport exportObject = ModelExport.of(Schema.VERSION);
                addEntry(tos, EXPORT_MODEL_INFO_FILE_NAME, Json.writer().writeValueAsBytes(exportObject));
                addEntry(tos, EXPORT_MODEL_FILE_NAME, memJsonDB.getAsByteArray("/"));
                memJsonDB.close();
                for (String extensionId : extensions) {
                    addEntry(tos, "extensions/" + Names.sanitize(extensionId) + ".jar", IOUtils.toByteArray(
                        extensionDataManager.getExtensionBinaryFile(extensionId)
                    ));
                }
                for (String iconId : icons) {
                    Icon icon = getDataManager().fetch(Icon.class, iconId);
                    String ext = MediaType.valueOf(icon.getMediaType()).getSubtype();
                    String name = iconId.substring(3);
                    byte[] bytes = IOUtils.toByteArray(iconDao.read(name));
                    addEntry(tos, "icons/" + name + "." + ext, bytes);
                }
            }
        };

    }

    private void addToExport(JsonDB export, Integration integration) {
        addModelToExport(export, integration);
        integration.getFlows().stream().flatMap(flow -> flow.getSteps().stream()).forEach(step -> {
            Optional<Connection> c = step.getConnection();
            if (c.isPresent()) {
                Connection connection = c.get();
                addModelToExport(export, connection);
                Connector connector = integrationHandler.getDataManager().fetch(Connector.class, connection.getConnectorId());
                if (connector != null) {
                    addModelToExport(export, connector);
                    if (connector.getIcon() != null && connector.getIcon().startsWith("db:")) {
                        Icon icon = integrationHandler.getDataManager().fetch(Icon.class, connector.getIcon().substring(3));
                        addModelToExport(export, icon);
                    }
                }
            }
            Optional<Extension> e = step.getExtension();
            if (e.isPresent()) {
                Extension extension = e.get();
                addModelToExport(export, extension);
            }
        });

        addResourcesToExport(export, integration);
    }

    private void addResourcesToExport(final JsonDB export, final Integration integration) {
        for (ResourceIdentifier resourceIdentifier : integration.getResources()) {
            if (resourceIdentifier.getKind() == Kind.OpenApi) {
                final Optional<OpenApi> openApiResource = resourceManager.loadOpenApiDefinition(resourceIdentifier.getId().get());

                if (openApiResource.isPresent()) {
                    addModelToExport(export, openApiResource.get());
                }
            }
        }
    }

    private static <T extends WithId<T>> void addModelToExport(JsonDB export, T model) {
        JsonDbDao<T> dao = new JsonDbDao<T>(export) {
            @Override
            public Class<T> getType() {
                return model.getKind().getModelClass();
            }
        };
        dao.set(model);
    }

    @POST
    @Path("/import")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, List<WithResourceId>> importIntegration(@Context SecurityContext sec, InputStream is) {
        try (ZipInputStream zis = new ZipInputStream(is)) {
            Map<String, List<WithResourceId>> imported = new HashMap<>();
            ModelExport modelExport = null;
            while (true) {
                ZipEntry entry = zis.getNextEntry();
                if( entry == null ) {
                    break;
                }

                if (EXPORT_MODEL_INFO_FILE_NAME.equals(entry.getName())) {
                    modelExport = Json.reader().forType(ModelExport.class).readValue(zis);
                }

                if (EXPORT_MODEL_FILE_NAME.equals(entry.getName())) {
                    CloseableJsonDB memJsonDB = MemorySqlJsonDB.create(jsondb.getIndexes());
                    memJsonDB.set("/", nonClosing(zis));
                    imported.putAll(importModels(sec, modelExport, memJsonDB));
                    memJsonDB.close();
                }

                // import missing extensions that were in the model.
                importMissingExtensions(zis, entry, imported);

                importMissingCustomIcons(zis, entry);

                zis.closeEntry();
            }
            if (imported.isEmpty()) {
                LOG.info("Could not import integration: No integration data model found.");
                throw new ClientErrorException("Does not look like an integration export.", Response.Status.BAD_REQUEST);
            }
            return imported;
        } catch (IOException e) {
            if (LOG.isInfoEnabled()) {
                LOG.info("Could not import integration: " + e, e);
            }
            throw new ClientErrorException("Could not import integration: " + e, Response.Status.BAD_REQUEST, e);
        }
    }

    private void importMissingExtensions(ZipInputStream zis, ZipEntry entry,
            Map<String, List<WithResourceId>> imported) throws IOException {

        if( entry.getName().startsWith("extensions/") ) {
            for (WithResourceId extension : imported.getOrDefault("extensions", Collections.emptyList())) {
                // use extension's correlation Id with extension ZipEntry
                final String extensionId = ((Extension)extension).getExtensionId();
                if( entry.getName().equals("extensions/" + Names.sanitize(extensionId) + ".jar") ) {
                    // path in filestore uses id instead of extensionId
                    String path = "/extensions/" + extension.getId().get();
                    InputStream existing = extensionDataManager.getExtensionDataAccess().read(path);
                    if( existing == null ) {
                        extensionDataManager.getExtensionDataAccess().write(path, zis);
                    } else {
                        existing.close();
                    }
                }
            }
        }
    }

    private void importMissingCustomIcons(ZipInputStream zis, ZipEntry entry) throws IOException {

        if( entry.getName().startsWith("icons/") ) {
            String fileName = entry.getName().substring(6);
            String id = fileName.substring(0, fileName.indexOf('.'));
            InputStream existing = iconDao.read(id);
            if( existing == null) {
                // write the icon to the (Sql)FileStore
                iconDao.write(id, zis);
            } else {
                existing.close();
            }
        }
    }

    public Map<String, List<WithResourceId>> importModels(SecurityContext sec, ModelExport export, JsonDB given) throws IOException {

        // Apply per version migrations to get the schema upgraded on the import.
        int from = export.schemaVersion();
        int to = Schema.VERSION;

        if( from > to ) {
            throw new IOException("Cannot import an export at schema version level: " + export.schemaVersion());
        }

        for (int i = from; i < to; i++) {
            int version = i + 1;
            migrator.migrate(given, version);
        }

        Map<String, List<WithResourceId>> result = new HashMap<>();
        // id->new-name map
        Map<String, String> renamedIds = new HashMap<>();

        // Import the extensions..
        final JsonDbDao<Extension> extensionDao = new JsonDbDao<Extension>(given) {
            @Override
            public Class<Extension> getType() {
                return Extension.class;
            }
        };

        // undelete existing deleted extensions that were referenced in the model
        undeleteImportedExtensions(extensionDao, result);

        importModels(extensionDao, RENAME_EXTENSION, renamedIds, result);

        // NOTE: connectors are imported without renaming and ignoring renamed ids
        // as a matter of fact, the lambda should never be called
        importModels(new JsonDbDao<Connector>(given) {
            @Override
            public Class<Connector> getType() {
                return Connector.class;
            }
        }, (c, n) -> new Connector.Builder().createFrom(c).name(c.getName()).build(), new HashMap<>(), result);

        importModels(new JsonDbDao<Connection>(given) {
            @Override
            public Class<Connection> getType() {
                return Connection.class;
            }
        }, RENAME_CONNECTION, renamedIds, result);

        importIntegrations(sec, new JsonDbDao<Integration>(given) {
            @Override
            public Class<Integration> getType() {
                return Integration.class;
            }
        }, renamedIds, result);

        importModels(new JsonDbDao<OpenApi>(given) {
            @Override
            public Class<OpenApi> getType() {
                return OpenApi.class;
            }
        }, (a, n) -> new OpenApi.Builder().createFrom(a).name(n).build(), new HashMap<>(), result);

        importModels(new JsonDbDao<Icon>(given) {
            @Override
            public Class<Icon> getType() {
                return Icon.class;
            }
        }, result);

        return result;
    }

    private void undeleteImportedExtensions(JsonDbDao<Extension> extensionDao, Map<String, List<WithResourceId>> result) {

        for (String id : extensionDao.fetchIds()) {
            final Extension extension = dataManager.fetch(Extension.class, id);
            if (extension != null) {
                final Optional<Extension.Status> status = extension.getStatus();
                if (status.isPresent() && status.get() == Extension.Status.Deleted) {
                    // undelete the extension
                    final Extension updated = new Extension.Builder()
                            .createFrom(extension)
                            .status(Extension.Status.Installed)
                            .build();
                    dataManager.update(updated);
                    addImportedItemResult(result, updated);
                }
            }
        }
    }

    private void importIntegrations(SecurityContext sec, JsonDbDao<Integration> export, Map<String, String> renamedIds, Map<String, List<WithResourceId>> result) {
        for (Integration integration : export.fetchAll().getItems()) {
            Integration.Builder builder = new Integration.Builder()
                .createFrom(integration)
                .isDeleted(false)
                .updatedAt(System.currentTimeMillis());

            // Do we need to create it?
            String id = integration.getId().get();
            Integration previous = dataManager.fetch(Integration.class, id);
            resolveDuplicateNames(integration, builder, renamedIds);
            if (previous == null) {
                LOG.info("Creating integration: {}", integration.getName());
                integrationHandler.create(sec, builder.build());
                addImportedItemResult(result, integration);
            } else {
                LOG.info("Updating integration: {}", integration.getName());
                integrationHandler.update(id, builder.version(previous.getVersion()+1).build());
                addImportedItemResult(result, integration);
            }
        }
    }

    private void resolveDuplicateNames(Integration integration, Integration.Builder builder, Map<String, String> renamedIds) {

        // check for duplicate integration name
        String integrationName = integration.getName();
        final Set<String> names = getAllPropertyValues(Integration.class, Integration::getName, i -> !i.isDeleted());
        names.addAll(getAllPropertyValues(IntegrationDeployment.class, d -> d.getSpec().getName(), d -> !d.getSpec().isDeleted()));
        if (names.contains(integrationName)) {
            integrationName = getNextAvailableName(integrationName, names);
            builder.name(integrationName);
        }

        // sync renames of other connections and steps
        builder.flows(integration.getFlows().stream()
            .map(flow -> flow.builder()
                .connections(flow.getConnections().stream()
                    .map(c -> renameIfNeeded(c, renamedIds, RENAME_CONNECTION))
                    .collect(Collectors.toList()))
                .steps(flow.getSteps().stream()
                    .map(s -> new Step.Builder().createFrom(s)
                            // step connections
                            .connection(s.getConnection().map(c -> renameIfNeeded(c, renamedIds, RENAME_CONNECTION)))
                            // step extensions
                            .extension(s.getExtension().map(e -> renameIfNeeded(e, renamedIds, RENAME_EXTENSION)))
                            .build())
                    .collect(Collectors.toList()))
                .build())
            .collect(Collectors.toList()));
    }

    private <T extends WithId<T> & WithName> T renameIfNeeded(T model, Map<String, String> renames, BiFunction<T,
            String, T> nameFunc) {
        final String name = renames.get(model.getId().get());
        return name != null ? nameFunc.apply(model, name) : model;
    }

    private <T extends WithId<T> & WithName> Set<String> getAllPropertyValues(Class<T> model, Function<T, String> propertyFunc) {
        return getAllPropertyValues(model, propertyFunc, d -> true);
    }

    private <T extends WithId<T>> Set<String> getAllPropertyValues(Class<T> model, Function<T, String> propertyFunc, Function<T, Boolean> filterFunc) {
        final ListResult<T> deployments = dataManager.fetchAll(model);
        return deployments.getItems().stream()
                .filter(filterFunc::apply)
                .map(propertyFunc::apply)
                .collect(Collectors.toSet());
    }

    private String getNextAvailableName(String name, Set<String> names) {
        String newName = null;
        for (int i = 1; newName == null; i++) {
            final String candidate = name + IMPORTED_SUFFIX + i;
            if (!names.contains(candidate)) {
                newName = candidate;
            }
        }
        return newName;
    }

    private <T extends WithId<T>> void importModels(JsonDbDao<T> export, Map<String, List<WithResourceId>> result) {
        for (T item : export.fetchAll().getItems()) {
            String id = item.getId().get();
            if (dataManager.fetch(export.getType(), id) == null) {
                // create new item
                dataManager.create(item);

                addImportedItemResult(result, item);
            }
        }
    }
    private <T extends WithId<T> & WithName> void importModels(JsonDbDao<T> export, BiFunction<T, String, T> renameFunc,
                                                               Map<String, String> renames, Map<String, List<WithResourceId>> result) {
        final Set<String> names = getAllPropertyValues(export.getType(), o -> o.getName());
        for (T item : export.fetchAll().getItems()) {
            String id = item.getId().get();
            if (dataManager.fetch(export.getType(), id) == null) {

                // resolve duplicate names
                String name = item.getName();
                if (names.contains(name)) {

                    // rename item
                    name = getNextAvailableName(name, names);
                    item = renameFunc.apply(item, name);

                    names.add(name);
                    renames.put(id, name);
                }

                // create new item
                dataManager.create(item);

                addImportedItemResult(result, item);
            }
        }
    }

    private static <T extends WithId<T>> void addImportedItemResult(Map<String, List<WithResourceId>> result,
        T item) {
        final Kind kind = item.getKind();
        final List<WithResourceId> imported = result.computeIfAbsent(kind.getPluralModelName(), (key) -> new ArrayList<>());

        imported.add(item);
    }

    private static void addEntry(ZipOutputStream os, String path, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        entry.setSize(content.length);
        os.putNextEntry(entry);
        os.write(content);
        os.closeEntry();
    }

}
