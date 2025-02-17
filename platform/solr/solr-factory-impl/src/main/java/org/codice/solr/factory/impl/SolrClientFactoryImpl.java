/**
 * Copyright (c) Codice Foundation
 *
 * <p>This is free software: you can redistribute it and/or modify it under the terms of the GNU
 * Lesser General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or any later version.
 *
 * <p>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details. A copy of the GNU Lesser General Public
 * License is distributed along with this program and can be found at
 * <http://www.gnu.org/licenses/lgpl.html>.
 */
package org.codice.solr.factory.impl;

import static org.apache.commons.lang.Validate.notNull;

import com.google.common.annotations.VisibleForTesting;
import java.security.AccessController;
import java.security.PrivilegedAction;
import org.apache.solr.client.solrj.SolrClient;
import org.codice.solr.factory.SolrClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory class used to create the proper {@link SolrClient} based on the current {@code
 * solr.client} system property.
 */
public final class SolrClientFactoryImpl implements SolrClientFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(SolrClientFactoryImpl.class);

  private String clientType;
  private SolrClientFactory factory;

  public SolrClientFactoryImpl() {
    this.clientType =
        AccessController.doPrivileged(
            (PrivilegedAction<String>) () -> System.getProperty("solr.client", "CloudSolrClient"));

    if (!"CloudSolrClient".equals(clientType)) {
      LOGGER.warn("Unknown solr.client system property set. Using Solr Cloud client instead.");
    }

    factory = new SolrCloudClientFactory();
  }

  @Override
  public SolrClient newClient(String core) {
    notNull(core, "Solr core name cannot be null");
    return factory.newClient(core);
  }

  @VisibleForTesting
  SolrClientFactory getFactory() {
    return factory;
  }
}
