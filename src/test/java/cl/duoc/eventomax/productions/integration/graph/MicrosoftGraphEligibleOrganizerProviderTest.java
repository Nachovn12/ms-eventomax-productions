package cl.duoc.eventomax.productions.integration.graph;

import cl.duoc.eventomax.productions.dto.DirectorySyncStatus;
import cl.duoc.eventomax.productions.dto.EligibleOrganizerSource;
import cl.duoc.eventomax.productions.service.DirectoryFetchResult;
import cl.duoc.eventomax.productions.service.EligibleOrganizerCandidate;
import cl.duoc.eventomax.productions.service.ExternalDirectoryUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MicrosoftGraphEligibleOrganizerProviderTest {

    private static final String TENANT = "f191bdc8-7926-4722-9ec0-cba663701940";
    private static final String SP_ID = "d861e591-af7a-4088-b4a9-c8d56d12ffb5";
    private static final String ORG_ROLE = "1890322f-cbfe-4487-b0e0-e47b7c472ac2";
    private static final String PROD_ROLE = "dc4a3792-de45-4b6f-8e17-9f9c76d67aec";
    private static final String OID_MEMBER = "11111111-1111-4111-8111-111111111111";
    private static final String OID_GUEST = "e5660fea-f5dd-4974-abb0-47e92f389d63";
    private static final String OID_DISABLED = "22222222-2222-4222-8222-222222222222";
    private static final String OID_PRODUCTOR = "7d300514-1e39-4013-85e5-02efa3fd42df";
    private static final String OID_NO_MAIL = "33333333-3333-4333-8333-333333333333";

    private MicrosoftGraphProperties properties;
    private MockRestServiceServer server;
    private MicrosoftGraphEligibleOrganizerProvider provider;

    @BeforeEach
    void setUp() {
        properties = new MicrosoftGraphProperties();
        properties.setEnabled(true);
        properties.setTenantId(TENANT);
        properties.setClientId("client-id-test");
        properties.setClientSecret("test-secret-not-real");
        properties.setServicePrincipalId(SP_ID);
        properties.setOrganizerAppRoleId(ORG_ROLE);
        properties.setTokenBaseUrl("https://login.test");
        properties.setGraphBaseUrl("https://graph.test/v1.0");
        properties.setCacheTtl(Duration.ofMinutes(5));

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new MicrosoftGraphEligibleOrganizerProvider(properties, builder.build());
    }

    private void stubToken() {
        server.expect(requestTo("https://login.test/" + TENANT + "/oauth2/v2.0/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"mock-graph-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
    }

    @Test
    void disabledByConfiguration_returnsDisabledWithoutHttpCalls_andVerifyFailsClosed() {
        properties.setEnabled(false);

        DirectoryFetchResult result = provider.fetchEligibleOrganizers();
        assertThat(result.status()).isEqualTo(DirectorySyncStatus.DISABLED);
        assertThat(result.candidates()).isEmpty();
        assertThat(result.authoritative()).isFalse();

        assertThatThrownBy(() -> provider.verifyEligibleOrganizer(TENANT + ":" + OID_GUEST))
                .isInstanceOf(ExternalDirectoryUnavailableException.class);
        server.verify();
    }

    @Test
    void fetchesMemberAndGuestOrganizersAcrossPages_excludesProductorOnlyAndDisabled_andHandlesMissingMail() {
        stubToken();
        String page1 = """
                {
                  "value": [
                    {"appRoleId":"%s","principalId":"%s","principalDisplayName":"Member Org","principalType":"User"},
                    {"appRoleId":"%s","principalId":"%s","principalDisplayName":"Productor Only","principalType":"User"},
                    {"appRoleId":"%s","principalId":"%s","principalDisplayName":"Disabled Org","principalType":"User"}
                  ],
                  "@odata.nextLink": "https://graph.test/v1.0/servicePrincipals/%s/appRoleAssignedTo?page=2"
                }
                """.formatted(ORG_ROLE, OID_MEMBER, PROD_ROLE, OID_PRODUCTOR, ORG_ROLE, OID_DISABLED, SP_ID);

        String page2 = """
                {
                  "value": [
                    {"appRoleId":"%s","principalId":"%s","principalDisplayName":"Guest Org","principalType":"User"},
                    {"appRoleId":"%s","principalId":"%s","principalDisplayName":"No Mail Org","principalType":"User"}
                  ]
                }
                """.formatted(ORG_ROLE, OID_GUEST, ORG_ROLE, OID_NO_MAIL);

        server.expect(requestTo("https://graph.test/v1.0/servicePrincipals/" + SP_ID
                        + "/appRoleAssignedTo?$select=appRoleId,principalId,principalDisplayName,principalType"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://graph.test/v1.0/servicePrincipals/" + SP_ID + "/appRoleAssignedTo?page=2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(page2, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_MEMBER + "?$select=id,displayName,accountEnabled,userType,mail"))
                .andRespond(withSuccess("{\"id\":\"" + OID_MEMBER + "\",\"displayName\":\"Member Org\",\"accountEnabled\":true,\"userType\":\"Member\",\"mail\":\"member@duocuc.cl\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_DISABLED + "?$select=id,displayName,accountEnabled,userType,mail"))
                .andRespond(withSuccess("{\"id\":\"" + OID_DISABLED + "\",\"displayName\":\"Disabled Org\",\"accountEnabled\":false,\"userType\":\"Member\",\"mail\":\"disabled@duocuc.cl\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_GUEST + "?$select=id,displayName,accountEnabled,userType,mail"))
                .andRespond(withSuccess("{\"id\":\"" + OID_GUEST + "\",\"displayName\":\"Guest Org\",\"accountEnabled\":true,\"userType\":\"Guest\",\"mail\":\"guest@duocuc.cl\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_NO_MAIL + "?$select=id,displayName,accountEnabled,userType,mail"))
                .andRespond(withSuccess("{\"id\":\"" + OID_NO_MAIL + "\",\"displayName\":\"No Mail Org\",\"accountEnabled\":true,\"userType\":\"Member\",\"mail\":null}", MediaType.APPLICATION_JSON));

        DirectoryFetchResult result = provider.fetchEligibleOrganizers();

        assertThat(result.status()).isEqualTo(DirectorySyncStatus.OK);
        assertThat(result.candidates()).hasSize(3);
        assertThat(result.candidates()).extracting(EligibleOrganizerCandidate::userOid)
                .containsExactly(OID_MEMBER, OID_GUEST, OID_NO_MAIL);
        assertThat(result.candidates()).extracting(EligibleOrganizerCandidate::deliverableEmail)
                .containsExactly("member@duocuc.cl", "guest@duocuc.cl", null);
        assertThat(result.candidates()).allMatch(c -> c.source() == EligibleOrganizerSource.ENTRA_ORGANIZER);
        server.verify();
    }

    @Test
    void graph403Forbidden_degradesListingAndFailsClosedOnVerify() {
        stubToken();
        server.expect(requestTo("https://graph.test/v1.0/servicePrincipals/" + SP_ID
                        + "/appRoleAssignedTo?$select=appRoleId,principalId,principalDisplayName,principalType"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        DirectoryFetchResult result = provider.fetchEligibleOrganizers();
        assertThat(result.status()).isEqualTo(DirectorySyncStatus.DEGRADED);
        assertThat(result.candidates()).isEmpty();
        server.verify();
        server.reset();

        stubToken();
        server.expect(requestTo("https://graph.test/v1.0/servicePrincipals/" + SP_ID
                        + "/appRoleAssignedTo?$select=appRoleId,principalId,principalDisplayName,principalType"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> provider.verifyEligibleOrganizer(TENANT + ":" + OID_GUEST))
                .isInstanceOf(ExternalDirectoryUnavailableException.class);
        server.verify();
    }

    @Test
    void graph429TooManyRequests_retriesOnceAndSucceeds() {
        stubToken();
        HttpHeaders retryHeaders = new HttpHeaders();
        retryHeaders.set("Retry-After", "0");

        server.expect(requestTo("https://graph.test/v1.0/servicePrincipals/" + SP_ID
                        + "/appRoleAssignedTo?$select=appRoleId,principalId,principalDisplayName,principalType"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(retryHeaders));

        String assignments = "{\"value\":[{\"appRoleId\":\"" + ORG_ROLE + "\",\"principalId\":\"" + OID_GUEST
                + "\",\"principalDisplayName\":\"Guest Org\",\"principalType\":\"User\"}]}";
        server.expect(requestTo("https://graph.test/v1.0/servicePrincipals/" + SP_ID
                        + "/appRoleAssignedTo?$select=appRoleId,principalId,principalDisplayName,principalType"))
                .andRespond(withSuccess(assignments, MediaType.APPLICATION_JSON));

        server.expect(requestTo("https://graph.test/v1.0/users/" + OID_GUEST + "?$select=id,displayName,accountEnabled,userType,mail"))
                .andRespond(withSuccess("{\"id\":\"" + OID_GUEST + "\",\"displayName\":\"Guest Org\",\"accountEnabled\":true,\"userType\":\"Guest\",\"mail\":\"guest@duocuc.cl\"}", MediaType.APPLICATION_JSON));

        Optional<EligibleOrganizerCandidate> verified = provider.verifyEligibleOrganizer(TENANT + ":" + OID_GUEST);
        assertThat(verified).isPresent();
        assertThat(verified.get().deliverableEmail()).isEqualTo("guest@duocuc.cl");
        server.verify();
    }

    @Test
    void graph503TransientError_degradesListing() {
        stubToken();
        server.expect(requestTo("https://graph.test/v1.0/servicePrincipals/" + SP_ID
                        + "/appRoleAssignedTo?$select=appRoleId,principalId,principalDisplayName,principalType"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        DirectoryFetchResult result = provider.fetchEligibleOrganizers();
        assertThat(result.status()).isEqualTo(DirectorySyncStatus.DEGRADED);
        assertThat(result.candidates()).isEmpty();
        server.verify();
    }

    @Test
    void nextLinkOriginValidation_rejectsForeignHostSchemeDowngradePortAnd3xxRedirects() {
        // Different host
        assertThatThrownBy(() -> provider.validateGraphUri("https://evil.example/v1.0/servicePrincipals/" + SP_ID + "/appRoleAssignedTo?page=2"))
                .isInstanceOf(ExternalDirectoryUnavailableException.class);
        // Scheme downgrade https -> http
        assertThatThrownBy(() -> provider.validateGraphUri("http://graph.test/v1.0/servicePrincipals/" + SP_ID + "/appRoleAssignedTo?page=2"))
                .isInstanceOf(ExternalDirectoryUnavailableException.class);
        // Different port
        assertThatThrownBy(() -> provider.validateGraphUri("https://graph.test:8443/v1.0/servicePrincipals/" + SP_ID + "/appRoleAssignedTo?page=2"))
                .isInstanceOf(ExternalDirectoryUnavailableException.class);
        // Same origin allowed
        assertThat(provider.validateGraphUri("https://graph.test/v1.0/servicePrincipals/" + SP_ID + "/appRoleAssignedTo?page=2").getHost())
                .isEqualTo("graph.test");

        // Runtime pagination with untrusted @odata.nextLink does NOT call foreign host
        stubToken();
        String page1WithEvilNext = """
                {
                  "value": [],
                  "@odata.nextLink": "https://attacker.invalid/steal-token"
                }
                """;
        server.expect(requestTo("https://graph.test/v1.0/servicePrincipals/" + SP_ID
                        + "/appRoleAssignedTo?$select=appRoleId,principalId,principalDisplayName,principalType"))
                .andRespond(withSuccess(page1WithEvilNext, MediaType.APPLICATION_JSON));

        assertThat(provider.fetchEligibleOrganizers().status()).isEqualTo(DirectorySyncStatus.DEGRADED);
        server.verify();
        server.reset();
        provider.clearCache();

        // HTTP 302 redirect blocked without following to unauthorized Location
        stubToken();
        HttpHeaders redirectHeaders = new HttpHeaders();
        redirectHeaders.set("Location", "https://attacker.invalid/leak");
        server.expect(requestTo("https://graph.test/v1.0/servicePrincipals/" + SP_ID
                        + "/appRoleAssignedTo?$select=appRoleId,principalId,principalDisplayName,principalType"))
                .andRespond(withStatus(HttpStatus.FOUND).headers(redirectHeaders));

        assertThatThrownBy(() -> provider.verifyEligibleOrganizer(TENANT + ":" + OID_GUEST))
                .isInstanceOf(ExternalDirectoryUnavailableException.class);
        server.verify();
    }
}
