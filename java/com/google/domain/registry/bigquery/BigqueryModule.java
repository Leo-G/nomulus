// Copyright 2016 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.bigquery;

import static dagger.Provides.Type.SET_VALUES;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.BigqueryScopes;
import com.google.domain.registry.config.ConfigModule.Config;
import com.google.domain.registry.request.OAuthScopes;

import dagger.Module;
import dagger.Provides;

import java.util.Set;

/**
 * Dagger module for Google {@link Bigquery} connection objects.
 *
 * @see com.google.domain.registry.config.ConfigModule
 * @see com.google.domain.registry.request.Modules.UrlFetchTransportModule
 * @see com.google.domain.registry.request.Modules.Jackson2Module
 * @see com.google.domain.registry.request.Modules.AppIdentityCredentialModule
 * @see com.google.domain.registry.request.Modules.UseAppIdentityCredentialForGoogleApisModule
 */
@Module
public final class BigqueryModule {

  /** Provides OAuth2 scopes for the Bigquery service needed by Domain Registry. */
  @Provides(type = SET_VALUES)
  @OAuthScopes
  static Set<String> provideBigqueryOAuthScopes() {
    return BigqueryScopes.all();
  }

  @Provides
  static Bigquery provideBigquery(
      HttpTransport transport,
      JsonFactory jsonFactory,
      HttpRequestInitializer httpRequestInitializer,
      @Config("projectId") String projectId) {
    return new Bigquery.Builder(transport, jsonFactory, httpRequestInitializer)
        .setApplicationName(projectId)
        .build();
  }
}