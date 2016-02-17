/**
 * Copyright (c) Codice Foundation
 * <p>
 * This is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. A copy of the GNU Lesser General Public License
 * is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.solr.factory;

import static org.apache.commons.lang.StringUtils.isNotBlank;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.DirectoryFactory;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.schema.IndexSchema;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class EmbeddedSolrFactory extends SolrServerFactory {

    public static final String DEFAULT_EMBEDDED_CORE_NAME = "embedded";

    public static final String IMMEMORY_SOLRCONFIG_XML = "solrconfig-inmemory.xml";

    public static final String DEFAULT_SOLR_XML = "solr.xml";

    /**
     * Hiding constructor
     */
    private EmbeddedSolrFactory() {

    }

    /**
     * @return {@link org.apache.solr.client.solrj.SolrClient} instance
     */
    public static SolrClient getEmbeddedSolrServer() {
        return getEmbeddedSolrServer(DEFAULT_SOLRCONFIG_XML, null, null);
    }

    public static EmbeddedSolrServer getEmbeddedSolrServer(String solrConfigXml) {
        return getEmbeddedSolrServer(solrConfigXml, null, null);
    }

    /**
     * Provides an already instantiated {@link org.apache.solr.client.solrj.SolrClient} object. If an instance has not already
     * been instantiated, then the single instance will be instantiated with the provided
     * configuration file. If an instance already exists, it cannot be overwritten with a new
     * configuration.
     *
     * @param solrConfigXml        the name of the solr configuration filename such as solrconfig.xml
     * @param schemaXml            filename of the schema such as schema.xml
     * @param givenConfigFileProxy a ConfigurationFileProxy instance. If instance is <code>null</code>, a new
     *                             {@link ConfigurationFileProxy} is used instead.
     * @return {@link org.apache.solr.client.solrj.SolrClient} instance
     */
    public static EmbeddedSolrServer getEmbeddedSolrServer(String solrConfigXml, String schemaXml,
            ConfigurationFileProxy givenConfigFileProxy) {

        LOGGER.debug("Retrieving embedded solr with the following properties: [{},{},{}]",
                solrConfigXml,
                schemaXml,
                givenConfigFileProxy);

        String solrConfigFileName = DEFAULT_SOLRCONFIG_XML;
        String schemaFileName = DEFAULT_SCHEMA_XML;

        if (isNotBlank(solrConfigXml)) {
            solrConfigFileName = solrConfigXml;
        }

        if (isNotBlank(schemaXml)) {
            schemaFileName = schemaXml;
        }

        ConfigurationFileProxy configProxy = givenConfigFileProxy;

        if (givenConfigFileProxy == null) {
            configProxy = new ConfigurationFileProxy(ConfigurationStore.getInstance());
        }

        File solrConfigFile = getConfigFile(solrConfigFileName, configProxy);
        File solrSchemaFile = getConfigFile(schemaFileName, configProxy);

        File solrConfigHome = new File(solrConfigFile.getParent());

        ClassLoader tccl = Thread.currentThread()
                .getContextClassLoader();
        try {
            Thread.currentThread()
                    .setContextClassLoader(EmbeddedSolrFactory.class.getClassLoader());

            // NamedSPILoader uses the thread context classloader to lookup
            // codecs, posting formats, and analyzers
            SolrConfig solrConfig = new SolrConfig(Paths.get(solrConfigHome.getParent()),
                    solrConfigFileName,
                    new InputSource(FileUtils.openInputStream(solrConfigFile)));
            IndexSchema indexSchema = new IndexSchema(solrConfig,
                    schemaFileName,
                    new InputSource(FileUtils.openInputStream(solrSchemaFile)));
            SolrResourceLoader loader = new SolrResourceLoader(Paths.get(solrConfigHome.getAbsolutePath()));
            SolrCoreContainer container = new SolrCoreContainer(loader);

            String dataDirPath = null;
            if (!ConfigurationStore.getInstance()
                    .isInMemory()) {
                File dataDir = configProxy.getDataDirectory();
                if (dataDir != null) {
                    LOGGER.debug("Using data directory [{}]", dataDir);
                    dataDirPath = dataDir.getAbsolutePath();
                }
            } else {
                PluginInfo info = solrConfig.getPluginInfo(DirectoryFactory.class.getName());
                if (!"solr.RAMDirectoryFactory".equals(info.className)) {
                    LOGGER.warn("Using in-memory configuration without RAMDirectoryFactory.");
                }
            }
            CoreDescriptor coreDescriptor = new CoreDescriptor(container,
                    DEFAULT_EMBEDDED_CORE_NAME,
                    solrConfig.getResourceLoader()
                            .getInstancePath().toString());

            SolrCore core = new SolrCore(DEFAULT_EMBEDDED_CORE_NAME,
                    dataDirPath,
                    solrConfig,
                    indexSchema,
                    null,
                    coreDescriptor,
                    null,
                    null,
                    null);
            container.register(DEFAULT_EMBEDDED_CORE_NAME, core, false);

            return new EmbeddedSolrServer(container, DEFAULT_EMBEDDED_CORE_NAME);
        } catch (ParserConfigurationException | IOException | SAXException e) {
            throw new IllegalArgumentException(
                    "Unable to parse Solr configuration file: " + solrConfigFileName, e);
        } finally {
            Thread.currentThread()
                    .setContextClassLoader(tccl);
        }
    }

    public static File getConfigFile(String configFileName, ConfigurationFileProxy configProxy) {
        return FileUtils.toFile(configProxy.getResource(configFileName));
    }

}
