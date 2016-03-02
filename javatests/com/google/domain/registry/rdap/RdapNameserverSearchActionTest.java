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
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeDomainResource;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeHostResource;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static com.google.domain.registry.testing.FullFieldsTestEntityHelper.makeRegistrarContacts;
import static com.google.domain.registry.testing.TestDataHelper.loadFileWithSubstitutions;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.google.domain.registry.model.domain.DomainResource;
import com.google.domain.registry.model.host.HostResource;
import com.google.domain.registry.model.ofy.Ofy;
import com.google.domain.registry.model.registrar.Registrar;
import com.google.domain.registry.testing.AppEngineRule;
import com.google.domain.registry.testing.FakeClock;
import com.google.domain.registry.testing.FakeResponse;
import com.google.domain.registry.testing.InjectRule;

import com.googlecode.objectify.Ref;

import org.joda.time.DateTime;
import org.json.simple.JSONValue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RdapNameserverSearchAction}. */
@RunWith(JUnit4.class)
public class RdapNameserverSearchActionTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Rule public final InjectRule inject = new InjectRule();

  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock(DateTime.parse("2009-06-29T20:13:00Z"));

  private final RdapNameserverSearchAction action = new RdapNameserverSearchAction();

  private HostResource hostNs1CatLol;
  private HostResource hostNs2CatLol;
  private HostResource hostNs1Cat2Lol;

  private Object generateActualJsonWithName(String name) {
    action.nameParam = Optional.of(name);
    action.run();
    return JSONValue.parse(response.getPayload());
  }

  private Object generateActualJsonWithIp(String ipString) {
    action.ipParam = Optional.of(InetAddresses.forString(ipString));
    action.run();
    return JSONValue.parse(response.getPayload());
  }

  @Before
  public void setUp() throws Exception {
    // cat.lol and cat2.lol
    createTld("lol");
    Registrar registrar =
        persistResource(
            makeRegistrar("evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistSimpleGlobalResources(makeRegistrarContacts(registrar));
    hostNs1CatLol = persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    hostNs2CatLol = persistResource(makeHostResource("ns2.cat.lol", "bad:f00d:cafe::15:beef"));
    hostNs1Cat2Lol =
        persistResource(makeHostResource("ns1.cat2.lol", "1.2.3.3", "bad:f00d:cafe::15:beef"));
    persistResource(makeHostResource("ns1.cat.external", null));

    // cat.みんな
    createTld("xn--q9jyb4c");
    registrar = persistResource(makeRegistrar("unicoderegistrar", "みんな", Registrar.State.ACTIVE));
    persistSimpleGlobalResources(makeRegistrarContacts(registrar));
    persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.5"));

    // cat.1.test
    createTld("1.test");
    registrar = persistResource(makeRegistrar("multiregistrar", "1.test", Registrar.State.ACTIVE));
    persistSimpleGlobalResources(makeRegistrarContacts(registrar));
    persistResource(makeHostResource("ns1.cat.1.test", "1.2.3.6"));

    // create a domain so that we can use it as a test nameserver search string suffix
    DomainResource domainCatLol =
        persistResource(
            makeDomainResource(
                    "cat.lol",
                    persistResource(
                        makeContactResource("5372808-ERL", "Goblin Market", "lol@cat.lol")),
                    persistResource(
                        makeContactResource("5372808-IRL", "Santa Claus", "BOFH@cat.lol")),
                    persistResource(makeContactResource("5372808-TRL", "The Raven", "bog@cat.lol")),
                    hostNs1CatLol,
                    hostNs2CatLol,
                    registrar)
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of("ns1.cat.lol", "ns2.cat.lol"))
                .build());
    persistResource(
        hostNs1CatLol.asBuilder().setSuperordinateDomain(Ref.create(domainCatLol)).build());
    persistResource(
        hostNs2CatLol.asBuilder().setSuperordinateDomain(Ref.create(domainCatLol)).build());

    inject.setStaticField(Ofy.class, "clock", clock);
    action.clock = clock;
    action.requestPath = RdapNameserverSearchAction.PATH;
    action.response = response;
    action.rdapResultSetMaxSize = 100;
    action.rdapLinkBase = "https://example.tld/rdap/";
    action.rdapWhoisServer = "whois.example.tld";
    action.ipParam = Optional.absent();
    action.nameParam = Optional.absent();
  }

  private Object generateExpectedJson(String name, String expectedOutputFile) {
    return generateExpectedJson(name, null, null, null, null, expectedOutputFile);
  }

  private Object generateExpectedJson(
      String name,
      String punycodeName,
      String handle,
      String ipAddressType,
      String ipAddress,
      String expectedOutputFile) {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    builder.put("NAME", name);
    builder.put("PUNYCODENAME", (punycodeName == null) ? name : punycodeName);
    if (handle != null) {
      builder.put("HANDLE", handle);
    }
    if (ipAddressType != null) {
      builder.put("ADDRESSTYPE", ipAddressType);
    }
    if (ipAddress != null) {
      builder.put("ADDRESS", ipAddress);
    }
    builder.put("TYPE", "nameserver");
    return JSONValue.parse(
        loadFileWithSubstitutions(this.getClass(), expectedOutputFile, builder.build()));
  }

  private Object generateExpectedJsonForNameserver(
      String name,
      String punycodeName,
      String handle,
      String ipAddressType,
      String ipAddress,
      String expectedOutputFile) {
    Object obj =
        generateExpectedJson(
            name, punycodeName, handle, ipAddressType, ipAddress, expectedOutputFile);
    ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<>();
    builder.put("nameserverSearchResults", ImmutableList.of(obj));
    builder.put("rdapConformance", ImmutableList.of("rdap_level_0"));
    return builder.build();
  }

  @Test
  public void testInvalidPath_rejected() throws Exception {
    action.requestPath = RdapDomainSearchAction.PATH + "/path";
    action.run();
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  public void testInvalidRequest_rejected() throws Exception {
    action.run();
    assertThat(JSONValue.parse(response.getPayload()))
        .isEqualTo(
            generateExpectedJson(
                "You must specify either name=XXXX or ip=YYYY", "rdap_error_400.json"));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  public void testInvalidSuffix_rejected() throws Exception {
    assertThat(generateActualJsonWithName("exam*ple"))
        .isEqualTo(
            generateExpectedJson(
                "Suffix after wildcard must be one or more domain"
                    + " name labels, e.g. exam*.tld, ns*.example.tld",
                "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
  }

  @Test
  public void testNonexistentDomainSuffix_notFound() throws Exception {
    assertThat(generateActualJsonWithName("exam*.foo.bar"))
        .isEqualTo(
            generateExpectedJson(
                "No domain found for specified nameserver suffix", "rdap_error_404.json"));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testMultipleWildcards_rejected() throws Exception {
    assertThat(generateActualJsonWithName("*.*"))
        .isEqualTo(generateExpectedJson("Only one wildcard allowed", "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
  }

  @Test
  public void testFewerThanTwoCharactersToMatch_rejected() throws Exception {
    assertThat(generateActualJsonWithName("a*"))
        .isEqualTo(
            generateExpectedJson(
                "At least two characters must be specified", "rdap_error_422.json"));
    assertThat(response.getStatus()).isEqualTo(422);
  }

  @Test
  public void testNameMatch_ns1_cat_lol_found() throws Exception {
    assertThat(generateActualJsonWithName("ns1.cat.lol"))
        .isEqualTo(
            generateExpectedJsonForNameserver(
                "ns1.cat.lol", null, "2-ROID", "v4", "1.2.3.4", "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_ns2_cat_lol_found() throws Exception {
    assertThat(generateActualJsonWithName("ns2.cat.lol"))
        .isEqualTo(
            generateExpectedJsonForNameserver(
                "ns2.cat.lol", null, "3-ROID", "v6", "bad:f00d:cafe::15:beef", "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_ns1_cat2_lol_found() throws Exception {
    // ns1.cat2.lol has two IP addresses; just test that we are able to find it
    generateActualJsonWithName("ns1.cat2.lol");
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_ns1_cat_external_found() throws Exception {
    assertThat(generateActualJsonWithName("ns1.cat.external"))
        .isEqualTo(
            generateExpectedJsonForNameserver(
                "ns1.cat.external", null, "5-ROID", null, null, "rdap_host_external.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_ns1_cat_idn_unicode_badRequest() throws Exception {
    // name must use punycode.
    generateActualJsonWithName("ns1.cat.みんな");
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  public void testNameMatch_ns1_cat_idn_punycode_found() throws Exception {
    assertThat(generateActualJsonWithName("ns1.cat.xn--q9jyb4c"))
        .isEqualTo(generateExpectedJsonForNameserver(
            "ns1.cat.みんな", "ns1.cat.xn--q9jyb4c",
            "7-ROID",
            "v4",
            "1.2.3.5",
            "rdap_host_unicode.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_ns1_cat_1_test_found() throws Exception {
    assertThat(generateActualJsonWithName("ns1.cat.1.test"))
        .isEqualTo(
            generateExpectedJsonForNameserver(
                "ns1.cat.1.test", null, "9-ROID", "v4", "1.2.3.6", "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_nsstar_cat_lol_found() throws Exception {
    generateActualJsonWithName("ns*.cat.lol");
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_nsstar_found() throws Exception {
    generateActualJsonWithName("ns*");
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_ns1_cat_lstar_found() throws Exception {
    generateActualJsonWithName("ns1.cat.l*");
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_ns1_castar_found() throws Exception {
    generateActualJsonWithName("ns1.ca*");
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testNameMatch_dogstar_notFound() throws Exception {
    generateActualJsonWithName("dog*");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testAddressMatchV4Address_found() throws Exception {
    assertThat(generateActualJsonWithIp("1.2.3.4"))
        .isEqualTo(
            generateExpectedJsonForNameserver(
                "ns1.cat.lol", null, "2-ROID", "v4", "1.2.3.4", "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testAddressMatchV6Address_foundMultiple() throws Exception {
    assertThat(generateActualJsonWithIp("bad:f00d:cafe::15:beef"))
        .isEqualTo(generateExpectedJson("ns1.cat.external", "rdap_multiple_hosts.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  public void testAddressMatchLocalhost_notFound() throws Exception {
    generateActualJsonWithIp("127.0.0.1");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testNameMatchDeletedHost_notFound() throws Exception {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    assertThat(generateActualJsonWithName("ns1.cat.lol"))
        .isEqualTo(generateExpectedJson("No nameservers found", "rdap_error_404.json"));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testNameMatchDeletedHostWithWildcard_notFound() throws Exception {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    assertThat(generateActualJsonWithName("cat.lo*"))
        .isEqualTo(generateExpectedJson("No nameservers found", "rdap_error_404.json"));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testAddressMatchDeletedHost_notFound() throws Exception {
    persistResource(hostNs1CatLol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    assertThat(generateActualJsonWithIp("1.2.3.4"))
        .isEqualTo(generateExpectedJson("No nameservers found", "rdap_error_404.json"));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  public void testNameMatchDeletedHost_foundTheOtherHost() throws Exception {
    persistResource(
        hostNs1Cat2Lol.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    assertThat(generateActualJsonWithIp("bad:f00d:cafe::15:beef"))
        .isEqualTo(
            generateExpectedJsonForNameserver(
                "ns2.cat.lol", null, "3-ROID", "v6", "bad:f00d:cafe::15:beef", "rdap_host.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }
}