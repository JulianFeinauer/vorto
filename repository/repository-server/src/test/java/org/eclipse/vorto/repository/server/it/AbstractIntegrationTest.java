/**
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.vorto.repository.server.it;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.IOUtils;
import org.eclipse.vorto.model.ModelId;
import org.eclipse.vorto.model.ModelType;
import org.eclipse.vorto.repository.core.IModelPolicyManager;
import org.eclipse.vorto.repository.core.PolicyEntry;
import org.eclipse.vorto.repository.core.PolicyEntry.Permission;
import org.eclipse.vorto.repository.core.PolicyEntry.PrincipalType;
import org.eclipse.vorto.repository.oauth.internal.SpringUserUtils;
import org.eclipse.vorto.repository.web.VortoRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.embedded.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.*;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringJUnit4ClassRunner.class)
@ActiveProfiles( profiles={"local-test"})
@ContextConfiguration
@SpringBootTest(classes = VortoRepository.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// https://github.com/spring-projects/spring-boot/issues/12280
@TestPropertySource(properties = {"repo.configFile = vorto-repository-config-h2.json"})
public abstract class AbstractIntegrationTest {

  protected MockMvc repositoryServer;
  protected TestModel testModel;

  private List<ModelId> createdModels = new ArrayList<>();

  @Autowired
  protected WebApplicationContext wac;

  @LocalServerPort
  protected int port;

  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userAdmin;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userStandard;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userStandard2;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userStandard3;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userStandard4;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userStandard5;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userStandard6;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userStandard7;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userStandard8;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userCreator;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userCreator2;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor userCreator3;
  protected SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor nonTenantUser;

  // fixed this to ensure null values are serialized as we are returning nulls in some response DTOs
  protected Gson gson = new GsonBuilder().serializeNulls().create();

  /**
   * This is a wrapper to allow comparison of JSON payloads involving {@link ModelId}, as it features
   * a public method {@link ModelId#getPrettyFormat()} that does not have a correspective field. <br/>
   * Therefore, expecting {@link ModelId} alone will fail when comparing JSON content because the
   * actual response will contain the pretty format string whereas the expectation will not.
   */
  public static class SerializableModelId extends ModelId {
    protected String prettyFormat;
    protected SerializableModelId(ModelId from) {
      this.prettyFormat = from.getPrettyFormat();
      this.setName(from.getName());
      this.setNamespace(from.getNamespace());
      this.setVersion(from.getVersion());
    }
  }

  @BeforeClass
  public static void configureOAuthConfiguration() {
    System.setProperty("github_clientid", "foo");
    System.setProperty("github_clientSecret", "foo");
    System.setProperty("eidp_clientid", "foo");
    System.setProperty("eidp_clientSecret", "foo");
    System.setProperty("line.separator", "\n");
  }

  @Before
  public void startUpServer() throws Exception {
    repositoryServer = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    userAdmin = user(ApplicationConfig.USER_ADMIN).password("pass").authorities(SpringUserUtils.toAuthorityList(
        Sets.newHashSet(NamespaceRoleConstants.USER, NamespaceRoleConstants.SYS_ADMIN, NamespaceRoleConstants.NAMESPACE_ADMIN, NamespaceRoleConstants.MODEL_CREATOR, NamespaceRoleConstants.MODEL_PROMOTER, NamespaceRoleConstants.MODEL_REVIEWER, NamespaceRoleConstants.MODEL_PUBLISHER)));
    userStandard = user(ApplicationConfig.USER_STANDARD).password("pass")
        .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER)));
    userStandard2 = user(ApplicationConfig.USER_STANDARD2).password("pass")
            .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER)));
    userStandard3 = user(ApplicationConfig.USER_STANDARD3).password("pass")
            .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER)));
    userStandard4 = user(ApplicationConfig.USER_STANDARD4).password("pass")
            .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER)));
    userStandard5 = user(ApplicationConfig.USER_STANDARD5).password("pass")
            .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER)));
    userStandard6 = user(ApplicationConfig.USER_STANDARD6).password("pass")
            .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER)));
    userStandard7 = user(ApplicationConfig.USER_STANDARD7).password("pass")
            .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER)));
    userStandard8 = user(ApplicationConfig.USER_STANDARD8).password("pass")
            .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER)));
    userCreator = user(ApplicationConfig.USER_CREATOR).password("pass")
        .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER, NamespaceRoleConstants.MODEL_CREATOR)));
    userCreator2 = user(ApplicationConfig.USER_CREATOR2).password("pass")
            .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER, NamespaceRoleConstants.MODEL_CREATOR)));
    userCreator3 = user(ApplicationConfig.USER_CREATOR3).password("pass")
            .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER, NamespaceRoleConstants.MODEL_CREATOR)));
    nonTenantUser = user(ApplicationConfig.NON_TENANT_USER).password("pass")
        .authorities(SpringUserUtils.toAuthorityList(Sets.newHashSet(NamespaceRoleConstants.USER, NamespaceRoleConstants.MODEL_CREATOR)));
    
    setUpTest();
  }

  @After
  public void cleanData() {
    removeTestDataRecursive();
  }

  private void removeTestDataRecursive() {

    for (Iterator<ModelId> iter = createdModels.iterator(); iter.hasNext();) {
      try {
        deleteModel(iter.next().getPrettyFormat());
        iter.remove();
      } catch (Exception ex) {
      }
    }

    if (!this.createdModels.isEmpty()) {
      removeTestDataRecursive();
    }
  }

  public void deleteModel(String modelId) throws Exception {
    repositoryServer.perform(delete("/rest/models/" + modelId).with(userAdmin)
        .contentType(MediaType.APPLICATION_JSON));
  }

  protected abstract void setUpTest() throws Exception;


  public void createModel(String fileName, String modelId) throws Exception {
    createModel(userAdmin, fileName, modelId);
    createdModels.add(ModelId.fromPrettyFormat(modelId));
  }

  public void releaseModel(String modelId) throws Exception {
    repositoryServer.perform(put("/rest/workflows/" + modelId + "/actions/Release")
        .with(userAdmin).contentType(MediaType.APPLICATION_JSON));

    repositoryServer.perform(put("/rest/workflows/" + modelId + "/actions/Approve")
        .with(userAdmin).contentType(MediaType.APPLICATION_JSON));
  }
  
  public void setPublic(String modelId) throws Exception {
    PolicyEntry publicPolicyEntry = new PolicyEntry();
    publicPolicyEntry.setPrincipalId(IModelPolicyManager.ANONYMOUS_ACCESS_POLICY);
    publicPolicyEntry.setPermission(Permission.READ);
    publicPolicyEntry.setPrincipalType(PrincipalType.User);
    
    String publicPolicyEntryStr = new Gson().toJson(publicPolicyEntry);
    
    repositoryServer.perform(put("/rest/models/" + modelId + "/policies")
        .with(userAdmin).contentType(MediaType.APPLICATION_JSON).content(publicPolicyEntryStr));
  }

  public void createAndReleaseModel(String fileName, String modelId) throws Exception {
    createModel(fileName, modelId);
    releaseModel(modelId);
  }

  protected void createModel(SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor user,
      String fileName, String modelId) throws Exception {

    repositoryServer
    .perform(post("/rest/models/" + modelId + "/" + ModelType.fromFileName(fileName))
        .with(user).contentType(MediaType.APPLICATION_JSON))
    .andExpect(status().isCreated());
    
    repositoryServer
    .perform(put("/rest/models/" + modelId).with(user)
        .contentType(MediaType.APPLICATION_JSON).content(createContent(fileName)))
    .andExpect(status().isOk());
  }

  protected String createContent(String fileName) throws Exception {
    String dslContent =
        IOUtils.toString(new ClassPathResource("models/" + fileName).getInputStream());

    Map<String, Object> content = new HashMap<>();
    content.put("contentDsl", dslContent);
    content.put("type", ModelType.fromFileName(fileName));

    return new GsonBuilder().create().toJson(content);
  }

  public ResultActions addAttachment(String modelId,
      SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor user, String fileName,
      MediaType mediaType, Integer size) throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", fileName, mediaType.toString(), size == null ? "{\"test\":123}".getBytes() : new byte[size]);
    MockMultipartHttpServletRequestBuilder builder =
        MockMvcRequestBuilders.fileUpload("/api/v1/attachments/" + modelId);
    return repositoryServer.perform(builder.file(file).with(request -> {
      request.setMethod("PUT");
      return request;
    }).contentType(MediaType.MULTIPART_FORM_DATA).with(user));
  }

  public ResultActions addAttachment(String modelId,
      SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor user, String fileName,
      MediaType mediaType) throws Exception {
    return addAttachment(modelId, user, fileName, mediaType, null);
  }


}
