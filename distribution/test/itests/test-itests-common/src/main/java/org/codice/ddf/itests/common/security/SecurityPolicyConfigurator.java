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
package org.codice.ddf.itests.common.security;

import static io.restassured.RestAssured.when;
import static org.awaitility.Awaitility.await;

import ddf.security.audit.impl.SecurityLoggerImpl;
import ddf.security.service.impl.SubjectUtils;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import java.time.Duration;
import java.util.Collections;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.codice.ddf.itests.common.AbstractIntegrationTest;
import org.codice.ddf.itests.common.ServiceManager;
import org.codice.ddf.itests.common.SynchronizedConfiguration;
import org.codice.ddf.security.policy.context.ContextPolicy;
import org.codice.ddf.security.policy.context.ContextPolicyManager;
import org.codice.ddf.security.policy.context.impl.PolicyManager;
import org.osgi.service.cm.ConfigurationAdmin;

public class SecurityPolicyConfigurator {

  private static final String SYMBOLIC_NAME = "security-policy-context";

  private static final String FACTORY_PID =
      "org.codice.ddf.security.policy.context.impl.PolicyManager";

  private static final String DEFAULT_WHITELIST =
      "/services/SecurityTokenService,/services/internal/metrics,/services/saml,/proxy,/services/platform/config/ui";

  private ServiceManager services;

  private ConfigurationAdmin configAdmin;

  public SecurityPolicyConfigurator(ServiceManager services, ConfigurationAdmin configAdmin) {
    this.services = services;
    this.configAdmin = configAdmin;
  }

  public Map<String, Object> configureRestForGuest() throws Exception {
    return configureRestForGuest(null);
  }

  public Map<String, Object> configureRestForGuest(String whitelist) throws Exception {
    return configureWebContextPolicy("", "", null, createWhitelist(whitelist));
  }

  public Map<String, Object> configureRestForBasic() throws Exception {
    return configureRestForBasic(null);
  }

  public Map<String, Object> configureRestForBasic(String whitelist) throws Exception {
    return configureWebContextPolicy("BASIC", "BASIC", null, createWhitelist(whitelist));
  }

  public Map<String, Object> configureRestForSaml(String whitelist) throws Exception {
    return configureWebContextPolicy("SAML", "SAML", null, createWhitelist(whitelist));
  }

  public void waitForBasicAuthReady(String url) {
    await("Waiting for basic auth")
        .atMost(AbstractIntegrationTest.GENERIC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pollDelay(1, TimeUnit.SECONDS)
        .until(() -> when().get(url).then().extract().statusCode() == 401);
  }

  public void waitForGuestAuthReady(String url) {
    await("Waiting for guest auth")
        .atMost(AbstractIntegrationTest.GENERIC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pollDelay(1, TimeUnit.SECONDS)
        .until(() -> when().get(url).then().extract().statusCode() == 200);
  }

  private static String createWhitelist(String whitelist) {
    return DEFAULT_WHITELIST + (StringUtils.isNotBlank(whitelist) ? "," + whitelist : "");
  }

  public Map<String, Object> configureWebContextPolicy(
      String webAuthTypes, String endpointAuthTypes, String requiredAttributes, String whitelist)
      throws Exception {

    RetryPolicy<Map<String, Object>> retryPolicy =
        RetryPolicy.<Map<String, Object>>builder()
            .withDelay(Duration.ofSeconds(2))
            .withMaxDuration(Duration.ofMinutes(1))
            .withMaxRetries(-1)
            .handleResult(null)
            .handleResultIf(Map::isEmpty)
            .build();

    Map<String, Object> policyProperties =
        Failsafe.with(retryPolicy)
            .get(() -> services.getMetatypeDefaults(SYMBOLIC_NAME, FACTORY_PID));

    if (webAuthTypes != null) {
      policyProperties.put("webAuthenticationTypes", webAuthTypes);
    }
    if (endpointAuthTypes != null) {
      policyProperties.put("endpointAuthenticationTypes", endpointAuthTypes);
    }
    if (requiredAttributes != null) {
      putPolicyValues(policyProperties, "requiredAttributes", requiredAttributes);
    }
    if (whitelist != null) {
      putPolicyValues(policyProperties, "whiteListContexts", whitelist);
    }

    policyProperties.put("guestAccess", true);

    policyProperties.put("sessionAccess", true);

    return updateWebContextPolicy(policyProperties);
  }

  public Map<String, Object> updateWebContextPolicy(Map<String, Object> policyProperties)
      throws Exception {
    Dictionary<String, Object> oldProps =
        new SynchronizedConfiguration(
                FACTORY_PID, null, policyProperties, createChecker(policyProperties), configAdmin)
            .updateConfig();

    services.waitForAllBundles();

    return convertToMap(oldProps);
  }

  private Map<String, Object> convertToMap(Dictionary<String, Object> oldProps) {
    if (oldProps == null || oldProps.isEmpty()) {
      return services.getMetatypeDefaults(SYMBOLIC_NAME, FACTORY_PID);
    } else {
      List<String> keys = Collections.list(oldProps.keys());
      return keys.stream().collect(Collectors.toMap(Function.identity(), oldProps::get));
    }
  }

  private void putPolicyValues(Map<String, Object> properties, String key, String value) {
    if (StringUtils.isNotBlank(value)) {
      properties.put(key, StringUtils.split(value, ","));
    }
  }

  private Callable<Boolean> createChecker(final Map<String, Object> policyProperties) {

    final ContextPolicyManager ctxPolicyMgr = services.getService(ContextPolicyManager.class);

    final PolicyManager targetPolicies = new PolicyManager();
    targetPolicies.setSecurityLogger(new SecurityLoggerImpl(new SubjectUtils()));
    targetPolicies.setPolicies(policyProperties);

    return () -> {
      for (ContextPolicy policy : ctxPolicyMgr.getAllContextPolicies()) {
        ContextPolicy targetPolicy = targetPolicies.getContextPolicy(policy.getContextPath());

        if (targetPolicy == null
            || !targetPolicy.getContextPath().equals(policy.getContextPath())
            || !targetPolicy
                .getAuthenticationMethods()
                .containsAll(policy.getAuthenticationMethods())
            || !targetPolicy
                .getAllowedAttributeNames()
                .containsAll(policy.getAllowedAttributeNames())) {
          return false;
        }
      }

      return true;
    };
  }
}
