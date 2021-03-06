/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.util;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AbstractPromptReceiver;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleOAuthConstants;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Preconditions;
import com.google.api.client.util.Strings;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.cloud.dataflow.sdk.options.GcpOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Provides support for loading credentials.
 */
public class Credentials {

  private static final Logger LOG = LoggerFactory.getLogger(Credentials.class);

  /** OAuth 2.0 scopes used by a local worker (not on GCE).
   *  The scope cloud-platform provides access to all Cloud Platform resources.
   *  cloud-platform isn't sufficient yet for talking to datastore so we request
   *  those resources separately.
   *
   *  Note that trusted scope relationships don't apply to OAuth tokens, so for
   *  services we access directly (GCS) as opposed to through the backend
   *  (BigQuery, GCE), we need to explicitly request that scope.
   */
  private static final List<String> WORKER_SCOPES = Arrays.asList(
      "https://www.googleapis.com/auth/cloud-platform",
      "https://www.googleapis.com/auth/devstorage.full_control",
      "https://www.googleapis.com/auth/userinfo.email",
      "https://www.googleapis.com/auth/datastore");

  private static final List<String> USER_SCOPES = Arrays.asList(
      "https://www.googleapis.com/auth/cloud-platform",
      "https://www.googleapis.com/auth/devstorage.full_control",
      "https://www.googleapis.com/auth/userinfo.email",
      "https://www.googleapis.com/auth/datastore");

  private static class PromptReceiver extends AbstractPromptReceiver {
    @Override
    public String getRedirectUri() {
      return GoogleOAuthConstants.OOB_REDIRECT_URI;
    }
  }

  /**
   * Initializes OAuth2 credential for a worker, using the
   * <a href="https://developers.google.com/accounts/docs/application-default-credentials">
   * application default credentials</a>, or from a local key file when running outside of GCE.
   */
  public static Credential getWorkerCredential(GcpOptions options)
      throws IOException {
    String keyFile = options.getServiceAccountKeyfile();
    String accountName = options.getServiceAccountName();

    if (keyFile != null && accountName != null) {
      try {
        return getCredentialFromFile(keyFile, accountName, WORKER_SCOPES);
      } catch (GeneralSecurityException e) {
        LOG.warn("Unable to obtain credentials from file {}", keyFile);
        // Fall through..
      }
    }

    return GoogleCredential.getApplicationDefault().createScoped(WORKER_SCOPES);
  }

  /**
   * Initializes OAuth2 credential for an interactive user program.
   *
   * This can use 4 different mechanisms for obtaining a credential:
   * <ol>
   *   <li>
   *     It can fetch the
   *     <a href="https://developers.google.com/accounts/docs/application-default-credentials">
   *     application default credentials</a>.
   *   </li>
   *   <li>
   *     It can run the gcloud tool in a subprocess to obtain a credential.
   *     This is the preferred mechanism.  The property "gcloud_path" can be
   *     used to specify where we search for gcloud data.
   *   </li>
   *   <li>
   *     The user can specify a client secrets file and go through the OAuth2
   *     webflow. The credential will then be cached in the user's home
   *     directory for reuse. Provide the property "secrets_file" to use this
   *     mechanism.
   *   </li>
   *   <li>
   *     The user can specify a file containing a service account.
   *     Provide the properties "service_account_keyfile" and
   *     "service_account_name" to use this mechanism.
   *   </li>
   * </ol>
   * The default mechanism is to use the
   * <a href="https://developers.google.com/accounts/docs/application-default-credentials">
   * application default credentials</a> falling back to gcloud. The other options can be
   * used by providing the corresponding properties.
   */
  public static Credential getUserCredential(GcpOptions options)
      throws IOException, GeneralSecurityException {
    String keyFile = options.getServiceAccountKeyfile();
    String accountName = options.getServiceAccountName();

    if (keyFile != null && accountName != null) {
      try {
        return getCredentialFromFile(keyFile, accountName, USER_SCOPES);
      } catch (GeneralSecurityException e) {
        throw new IOException("Unable to obtain credentials from file", e);
      }
    }

    if (options.getSecretsFile() != null) {
      return getCredentialFromClientSecrets(options, USER_SCOPES);
    }

    try {
      return GoogleCredential.getApplicationDefault().createScoped(USER_SCOPES);
    } catch (IOException e) {
      LOG.debug("Failed to get application default credentials, falling back to gcloud.");
    }

    String gcloudPath = options.getGCloudPath();
    return getCredentialFromGCloud(gcloudPath);
  }

  /**
   * Loads OAuth2 credential from a local file.
   */
  private static Credential getCredentialFromFile(
      String keyFile, String accountId, Collection<String> scopes)
      throws IOException, GeneralSecurityException {
    GoogleCredential credential = new GoogleCredential.Builder()
        .setTransport(Transport.getTransport())
        .setJsonFactory(Transport.getJsonFactory())
        .setServiceAccountId(accountId)
        .setServiceAccountScopes(scopes)
        .setServiceAccountPrivateKeyFromP12File(new File(keyFile))
        .build();

    LOG.info("Created credential from file {}", keyFile);
    return credential;
  }

  /**
   * Loads OAuth2 credential from GCloud utility.
   */
  private static Credential getCredentialFromGCloud(String gcloudPath)
      throws IOException, GeneralSecurityException {
    GCloudCredential credential;
    HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
    if (Strings.isNullOrEmpty(gcloudPath)) {
      credential = new GCloudCredential(transport);
    } else {
      credential = new GCloudCredential(gcloudPath, transport);
    }

    try {
      credential.refreshToken();
    } catch (IOException e) {
      throw new RuntimeException("Could not obtain credential using gcloud", e);
    }

    LOG.info("Got user credential from GCloud");
    return credential;
  }

  /**
   * Loads OAuth2 credential from client secrets, which may require an
   * interactive authorization prompt.
   */
  private static Credential getCredentialFromClientSecrets(
      GcpOptions options, Collection<String> scopes)
      throws IOException, GeneralSecurityException {
    String clientSecretsFile = options.getSecretsFile();

    Preconditions.checkArgument(clientSecretsFile != null);
    HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

    JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
    GoogleClientSecrets clientSecrets;

    try {
      clientSecrets = GoogleClientSecrets.load(jsonFactory,
          new FileReader(clientSecretsFile));
    } catch (IOException e) {
      throw new RuntimeException(
          "Could not read the client secrets from file: " + clientSecretsFile,
          e);
    }

    FileDataStoreFactory dataStoreFactory =
        new FileDataStoreFactory(new java.io.File(options.getCredentialDir()));

    GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
        httpTransport, jsonFactory, clientSecrets, scopes)
        .setDataStoreFactory(dataStoreFactory)
        .build();

    // The credentialId identifies the credential if we're using a persistent
    // credential store.
    Credential credential =
        new AuthorizationCodeInstalledApp(flow, new PromptReceiver())
            .authorize(options.getCredentialId());

    LOG.info("Got credential from client secret");
    return credential;
  }
}
