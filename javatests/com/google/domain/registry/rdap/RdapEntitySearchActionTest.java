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

package com.google.domain.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.testing.DatastoreHelper.createTld;
import static com.google.domain.registry.testing.DatastoreHelper.persistResource;
import static com.google.domain.registry.testing.DatastoreHelper.persistSimpleGlobalResources;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeContactResource;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeRegistrarContacts;
import static com.google.domain.registry.testing.TestDataHelper.loadFileWithSubstitutions;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.domain.registry.model.contact.ContactResource;
import com.google.domain.registry.model.ofy.Ofy;
import com.google.domain.registry.model.registrar.Registrar;
import com.google.domain.registry.testing.AppEngineRule;
import com.google.domain.registry.testing.FakeClock;
import com.google.domain.registry.testing.FakeResponse;
import com.google.domain.registry.testing.InjectRule;

import org.joda.time.DateTime;
import org.json.simple.JSONValue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.annotation.Nullable;

/** Unit tests for {@link RdapEntitySearchAction}. */
@RunWith(JUnit4.class)
public class RdapEntitySearchActionTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Rule public final InjectRule inject = new InjectRule();

  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock(DateTime.parse("2009-06-29T20:13:00Z"));

  private final RdapEntitySearchAction action = new RdapEntitySearchAction();

  private ContactResource contact;
  private Registrar registrar;
  private Registrar registrarInactive;
  private Registrar registrarTest;

  private Object generateActualJsonWithFullName(String fn) {
    action.fnParam = Optional.of(fn);
    action.run();
    return JSONValue.parse(response.getPayload());
  }

  private Object generateActualJsonWithHandle(String handle) {
    action.handleParam = Optional.of(handle);
    action.run();
    return JSONValue.parse(response.getPayload());
  }

  @Before
  public void setUp() throws Exception {
    createTld("tld");

    contact = persistResource(
        makeContactResource(
            "blinky",
            "Blinky (赤ベイ)",
            "blinky@b.tld",
            ImmutableList.of("123 Blinky St", "Blinkyland")));

    // deleted
    persistResource(
        makeContactResource("clyde", "Clyde (愚図た)", "clyde@c.tld")
            .asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());

    registrar =
        persistResource(
            makeRegistrar("2-Registrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistSimpleGlobalResources(makeRegistrarContacts(registrar));

    // inactive
    registrarInactive =
        persistResource(makeRegistrar("2-RegistrarInact", "No Way", Registrar.State.PENDING));
    persistSimpleGlobalResources(makeRegistrarContacts(registrarInactive));

    // test
    registrarTest =
        persistResource(
            makeRegistrar("2-RegistrarTest", "No Way", Registrar.State.ACTIVE)
                .asBuilder()
                .setType(Registrar.Type.TEST)
                .setIanaIdentifier(null)
                .build());
    persistSimpleGlobalResources(makeRegistrarContacts(registrarTest));

    inject.setStaticField(Ofy.class, "clock", clock);
    action.clock = clock;
    action.requestPath = RdapEntitySearchAction.PATH;
    action.response = response;
    action.rdapResultSetMaxSize = 100;
    action.rdapLinkBase = "https://example.com/rdap/";
    action.rdapWhoisServer = "whois.example.tld";
    action.fnParam = Optional.absent();
    action.handleParam = Optional.absent();
  }

  private Object generateExpectedJson(String expectedOutputFile) {
    return JSONValue.parse(loadFileWithSubstitutions(
        this.getClass(),
        expectedOutputFile,
        ImmutableMap.of("TYPE", "entity")));
  }

  private Object generateExpectedJson(String name, String expectedOutputFile) {
    return generateExpectedJson(name, null, null, null, expectedOutputFile);
  }

  private Object generateExpectedJson(
      String handle,
      @Nullable String fullName,
      @Nullable String email,
      @Nullable String address,
      String expectedOutputFile) {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    builder.put("NAME", handle);
    if (fullName != null) {
      builder.put("FULLNAME", fullName);
    }
    if (email != null) {
      builder.put("EMAIL", email);
    }
    if (address != null) {
      builder.put("ADDRESS", address);
    }
    builder.put("TYPE", "entity");
    return JSONValue.parse(
        loadFileWithSubstitutions(this.getClass(), expectedOutputFile, builder.build()));
  }

  private Object generateExpectedJsonForEntity(
      String handle,
      String fullName,
      @Nullable String email,
      @Nullable String address,
      String expectedOutputFile) {
    Object obj =
        generateExpectedJson(
            handle, fullName, email, address, expectedOutputFile);
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
    builder.put("entitySearchResults", ImmutableList.of(obj));
    builder.put("rdapConformance", ImmutableList.of("rdap_level_0"));
    return builder.build();
  }

  @Test
  public void testInvalidPath_rejected() throws Exception {
    action.requestPath = RdapEntitySearchAction.PATH + "/path";
    action.run();
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  public void testInvalidRequest_rejected() throws Exception {
    action.run();
    assertThat(JSONValue.parse(response.getPayload()))
        .isEqualTo(
            generateExpectedJson(
                "You must specify either fn=XXXX or handle=YYYY", "rdap_error_400.json"));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  public void testSuffix_rejected() throws Exception {
    assertThat(generateActualJsonWithHandle("exam*ple"))
        .isEqualTo(
            generateExpectedJson("Suffix not allowed after wildcard", "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
  }

  @Test
  public void testMultipleWildcards_rejected() throws Exception {
    assertThat(generateActualJsonWithHandle("*.*"))
        .isEqualTo(generateExpectedJson("Only one wildcard allowed", "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
  }

  @Test
  public void testFewerThanTwoCharactersToMatch_rejected() throws Exception {
    assertThat(generateActualJsonWithHandle("a*"))
        .isEqualTo(
            generateExpectedJson(
                "At least two characters must be specified", "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
  }

  @Test
  public void testNameMatch_notImplemented() throws Exception {
    assertThat(generateActualJsonWithFullName("hello"))
        .isEqualTo(
            generateExpectedJson("Entity name search not implemented", "rdap_error_501.json"));
    assertThat(response.getStatus()).isEqualTo(501);
  }

  @Test
  public void testHandleMatch_2roid_found() throws Exception {
    assertThat(generateActualJsonWithHandle("2-ROID"))
        .isEqualTo(
            generateExpectedJsonForEntity(
                "2-ROID",
                "Blinky (赤ベイ)",
                "blinky@b.tld",
                "\"123 Blinky St\", \"Blinkyland\"",
                "rdap_contact.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testHandleMatch_registrar_found() throws Exception {
    assertThat(generateActualJsonWithHandle("2-Registrar"))
        .isEqualTo(
            generateExpectedJsonForEntity(
                "2-Registrar", "Yes Virginia <script>", null, null, "rdap_registrar.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_2registrarInactive_notFound() throws Exception {
    generateActualJsonWithHandle("2-RegistrarInact");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testNameMatch_2registrarTest_notFound() throws Exception {
    generateActualJsonWithHandle("2-RegistrarTest");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testHandleMatch_2rstar_found() throws Exception {
    assertThat(generateActualJsonWithHandle("2-R*"))
        .isEqualTo(generateExpectedJson("rdap_multiple_contacts.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testHandleMatch_2rstarWithResultSetSize1_foundOne() throws Exception {
    action.rdapResultSetMaxSize = 1;
    assertThat(generateActualJsonWithHandle("2-R*"))
        .isEqualTo(
            generateExpectedJsonForEntity(
                "2-ROID",
                "Blinky (赤ベイ)",
                "blinky@b.tld",
                "\"123 Blinky St\", \"Blinkyland\"",
                "rdap_contact.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testHandleMatch_2rostar_found() throws Exception {
    assertThat(generateActualJsonWithHandle("2-RO*"))
        .isEqualTo(
            generateExpectedJsonForEntity(
                contact.getRepoId(),
                "Blinky (赤ベイ)",
                "blinky@b.tld",
                "\"123 Blinky St\", \"Blinkyland\"",
                "rdap_contact.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testHandleMatch_2registstar_found() throws Exception {
    assertThat(generateActualJsonWithHandle("2-Regist*"))
        .isEqualTo(
            generateExpectedJsonForEntity(
                "2-Registrar", "Yes Virginia <script>", null, null, "rdap_registrar.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testHandleMatch_3teststar_notFound() throws Exception {
    generateActualJsonWithHandle("3test*");
    assertThat(response.getStatus()).isEqualTo(404);
  }
}