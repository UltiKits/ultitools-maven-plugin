package com.ultikits.ulticloud;

import cn.hutool.json.JSONUtil;
import junit.framework.TestCase;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class UltiCloudClientTest extends TestCase {

    static final String KEY = "ucd_SyntheticDeveloperKey0000000000000000000000";
    static final String MODULE = "uc-p12-sample";
    static final String VERSIONS = "/v1/developer/modules/" + MODULE + "/versions";
    static final String TOKEN = VERSIONS + "/1.2.3/upload-token";
    static final String UPLOAD = "/v1/uploads/ucu_SyntheticUploadTicket0000000000000000000000";
    static final String REQUEST_ID = "01JB2Z8Y3K4M5N6P7Q8R9S0REQ";

    private StubServer stub;
    private RecordingLog log;
    private final byte[] jar = "synthetic jar bytes".getBytes(StandardCharsets.UTF_8);
    private final UltiCloudClient.ModuleProfile noProfile = new UltiCloudClient.ModuleProfile(null, null);

    @Override
    protected void setUp() throws Exception {
        stub = new StubServer();
        log = new RecordingLog();
    }

    @Override
    protected void tearDown() {
        stub.close();
    }

    private UltiCloudClient client() throws Exception {
        return new UltiCloudClient(stub.baseUrl(), DeveloperKey.parse(KEY), log);
    }

    private UltiCloudException publishFails() throws Exception {
        try {
            client().publish(MODULE, "1.2.3", null, jar, noProfile);
        } catch (UltiCloudException e) {
            assertFalse(e.getMessage().contains(KEY));
            return e;
        }
        fail("publish should have failed");
        return null;
    }

    // ------------------------------------------------------------ resume

    public void testVersionExistsResumesWithANewTicket() throws Exception {
        stub.on("POST", VERSIONS, 409, StubServer.fixture("versionsCreate/error/valid/409-version-exists.json"));
        stub.on("POST", TOKEN, 200, stub.fixtureHere("uploadToken/response/valid/ticket.json"));
        stub.on("PUT", UPLOAD, 200, stub.fixtureHere("uploadPut/response/valid/published.json"));
        client().publish(MODULE, "1.2.3", null, jar, noProfile);

        List<StubServer.Recorded> reqs = stub.requests();
        assertEquals(0, stub.unexpected().size());
        assertEquals(3, reqs.size());
        assertEquals(TOKEN, reqs.get(1).path);
        assertEquals("Bearer " + KEY, reqs.get(1).header("Authorization"));
        assertEquals("PUT", reqs.get(2).method);
        assertNull(reqs.get(2).header("Authorization"));
        assertTrue(Arrays.equals(jar, reqs.get(2).body));
    }

    public void testPublishedVersionAsksForANewVersionNumber() throws Exception {
        stub.on("POST", VERSIONS, 409, StubServer.fixture("versionsCreate/error/valid/409-version-exists.json"));
        stub.on("POST", TOKEN, 409, StubServer.fixture("uploadToken/error/valid/409-version-not-pending.json"));
        UltiCloudException e = publishFails();
        assertTrue(e.getMessage(), e.getMessage().startsWith(MODULE + "@1.2.3 已发布，请升版本号"));
        assertEquals(2, stub.requests().size());
    }

    // ------------------------------------------------------------ every error fixture

    private void assertSpecific(String fixture, UltiCloudException e) throws Exception {
        String code = JSONUtil.parseObj(StubServer.fixture(fixture)).getJSONObject("error").getStr("code");
        assertEquals(fixture, code, e.getCode());
        assertNotNull(fixture, UltiCloudClient.messageFor(code));
        assertFalse(fixture + ": " + e.getMessage(), e.getMessage().startsWith("UltiCloud 返回 HTTP"));
        assertTrue(fixture + ": " + e.getMessage(), e.getMessage().contains(REQUEST_ID));
    }

    private static int status(String fileName) {
        return Integer.parseInt(fileName.substring(0, 3));
    }

    private static String[] errorFixtures(String operation) {
        File dir = new File(StubServer.contractRoot(), "fixtures/" + operation + "/error/valid");
        String[] names = dir.list();
        assertNotNull(names);
        Arrays.sort(names);
        assertTrue(operation, names.length > 0);
        return names;
    }

    public void testEveryVersionsCreateErrorHasASpecificMessage() throws Exception {
        for (String name : errorFixtures("versionsCreate")) {
            String fixture = "versionsCreate/error/valid/" + name;
            if (name.contains("version-exists")) {
                continue; // resumes instead of failing: testVersionExistsResumesWithANewTicket
            }
            setUp();
            stub.on("POST", VERSIONS, status(name), StubServer.fixture(fixture));
            assertSpecific(fixture, publishFails());
            tearDown();
        }
    }

    public void testEveryUploadTokenErrorHasASpecificMessage() throws Exception {
        for (String name : errorFixtures("uploadToken")) {
            String fixture = "uploadToken/error/valid/" + name;
            setUp();
            stub.on("POST", VERSIONS, 409, StubServer.fixture("versionsCreate/error/valid/409-version-exists.json"));
            stub.on("POST", TOKEN, status(name), StubServer.fixture(fixture));
            assertSpecific(fixture, publishFails());
            tearDown();
        }
    }

    public void testEveryUploadPutErrorHasASpecificMessage() throws Exception {
        for (String name : errorFixtures("uploadPut")) {
            String fixture = "uploadPut/error/valid/" + name;
            setUp();
            stub.on("POST", VERSIONS, 200, stub.fixtureHere("versionsCreate/response/valid/pending.json"));
            stub.on("PUT", UPLOAD, status(name), StubServer.fixture(fixture));
            assertSpecific(fixture, publishFails());
            tearDown();
        }
    }

    public void testEveryMessageKeyIsACodeInTheCopiedSchemas() throws Exception {
        File schemas = new File(StubServer.contractRoot(), "schemas");
        StringBuilder all = new StringBuilder();
        for (String name : schemas.list()) {
            all.append(new String(Files.readAllBytes(new File(schemas, name).toPath()), StandardCharsets.UTF_8));
        }
        for (String code : UltiCloudClient.knownCodes()) {
            assertTrue(code, all.indexOf("\"const\": \"" + code + "\"") >= 0);
        }
    }

    public void testSpecificMessagesForCommonFailures() throws Exception {
        stub.on("POST", VERSIONS, 422, StubServer.fixture("versionsCreate/error/valid/422-validation-failed.json"));
        assertTrue(publishFails().getMessage().contains("version Not a version."));

        setUp();
        stub.on("POST", VERSIONS, 200, stub.fixtureHere("versionsCreate/response/valid/pending.json"));
        stub.on("PUT", UPLOAD, 422, StubServer.fixture("uploadPut/error/valid/422-upload-api-level-missing.json"));
        assertTrue(publishFails().getMessage().contains("plugin.yml 需要整数 api-version"));

        setUp();
        stub.on("POST", VERSIONS, 404, StubServer.fixture("versionsCreate/error/valid/404-not-found.json"));
        stub.on("POST", "/v1/developer/modules", 200, StubServer.fixture("modulesCreate/response/valid/created.json"));
        stub.on("POST", VERSIONS, 404, StubServer.fixture("versionsCreate/error/valid/404-not-found.json"));
        try {
            client().publish(MODULE, "1.2.3", null, jar, new UltiCloudClient.ModuleProfile("Sample Module", null));
            fail();
        } catch (UltiCloudException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("模块 " + MODULE + " 不存在或不属于该密钥的开发者"));
        }
    }

    public void testRateLimitReportsRetryAfterWithoutRetrying() throws Exception {
        stub.on("POST", VERSIONS, 429, StubServer.fixture("modulesCreate/error/valid/429-rate-limited.json"),
                Collections.singletonMap("Retry-After", "42"));
        UltiCloudException e = publishFails();
        assertTrue(e.getMessage(), e.getMessage().contains("42 秒后"));
        assertEquals(1, stub.requests().size());
    }

    public void testUnknownCodeFallsBackToStatus() throws Exception {
        stub.on("POST", VERSIONS, 500, "<html>bad gateway</html>");
        UltiCloudException e = publishFails();
        assertTrue(e.getMessage(), e.getMessage().startsWith("UltiCloud 返回 HTTP 500"));
        assertFalse(e.getMessage().contains("bad gateway"));
    }

    // ------------------------------------------------------------ HTTPS and paging

    /**
     * Every method, PATCH included, must reach an HTTPS server unchanged. On Java 8 the
     * old reflective PATCH override only changed the outer HttpsURLConnection, so the
     * request left as POST; this test runs on the JDK 8 CI.
     */
    public void testProfileSyncOverHttpsSendsARealPatch() throws Exception {
        StubServer https = new StubServer(true);
        try {
            https.on("POST", VERSIONS, 200, https.fixtureHere("versionsCreate/response/valid/pending.json"));
            https.on("GET", "/v1/developer/modules", 200, StubServer.fixture("modulesList/response/valid/page.json"));
            https.on("PATCH", "/v1/developer/modules/" + MODULE, 200, StubServer.fixture("modulesUpdate/response/valid/updated.json"));
            https.on("PUT", UPLOAD, 200, https.fixtureHere("uploadPut/response/valid/published.json"));
            new UltiCloudClient(https.baseUrl(), DeveloperKey.parse(KEY), log, StubServer.testTlsContext())
                    .publish(MODULE, "1.2.3", null, jar, new UltiCloudClient.ModuleProfile("Renamed Module", null));

            assertEquals(https.unexpected().toString(), 0, https.unexpected().size());
            List<StubServer.Recorded> reqs = https.requests();
            assertEquals(4, reqs.size());
            assertEquals("PATCH", reqs.get(2).method);
            assertEquals("{\"name\":\"Renamed Module\"}", reqs.get(2).bodyText());
            assertNull(reqs.get(3).header("Authorization"));
            assertTrue(Arrays.equals(jar, reqs.get(3).body));
        } finally {
            https.close();
        }
    }

    private static String listPage(int page, int total, boolean includeTarget) {
        StringBuilder items = new StringBuilder();
        int count = includeTarget ? 1 : 100;
        for (int i = 0; i < count; i++) {
            String id = includeTarget ? MODULE : MODULE + "-other-" + i;
            items.append(i == 0 ? "" : ",").append("{\"id\":\"M").append(i).append("\",\"name\":\"Sample Module\",")
                    .append("\"identifier\":\"").append(id).append("\",\"shortDescription\":\"\",\"iconUrl\":null,")
                    .append("\"category\":null,\"downloads\":0,\"latestVersion\":null,\"updatedAt\":\"2026-11-01T12:00:00.000Z\"}");
        }
        return "{\"data\":{\"items\":[" + items + "],\"total\":" + total + ",\"page\":" + page + ",\"pageSize\":100}}";
    }

    public void testProfileSyncFollowsPagesUntilTheExactIdentifier() throws Exception {
        stub.on("POST", VERSIONS, 200, stub.fixtureHere("versionsCreate/response/valid/pending.json"));
        stub.on("GET", "/v1/developer/modules", 200, listPage(1, 101, false));
        stub.on("GET", "/v1/developer/modules", 200, listPage(2, 101, true));
        stub.on("PATCH", "/v1/developer/modules/" + MODULE, 200, StubServer.fixture("modulesUpdate/response/valid/updated.json"));
        stub.on("PUT", UPLOAD, 200, stub.fixtureHere("uploadPut/response/valid/published.json"));
        client().publish(MODULE, "1.2.3", null, jar, new UltiCloudClient.ModuleProfile("Renamed Module", null));

        assertEquals(stub.unexpected().toString(), 0, stub.unexpected().size());
        List<StubServer.Recorded> reqs = stub.requests();
        assertEquals(5, reqs.size());
        assertTrue(reqs.get(1).query, reqs.get(1).query.contains("page=1"));
        assertTrue(reqs.get(2).query, reqs.get(2).query.contains("page=2"));
        assertEquals("PATCH", reqs.get(3).method);
    }

    // ------------------------------------------------------------ pre-request checks

    public void testOversizedJarFailsBeforeAnyRequest() throws Exception {
        try {
            client().publish(MODULE, "1.2.3", null, new byte[52428801], noProfile);
            fail();
        } catch (UltiCloudException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("52428800"));
        }
        assertEquals(0, stub.requests().size());
    }

    public void testApiUrlRules() throws Exception {
        assertEquals("https://api.ultikits.com", UltiCloudClient.parseApiUrl("https://api.ultikits.com/").toString());
        assertEquals("http://localhost:8787", UltiCloudClient.parseApiUrl("http://localhost:8787").toString());
        assertEquals("http://127.0.0.1:1", UltiCloudClient.parseApiUrl("http://127.0.0.1:1").toString());
        for (String bad : new String[]{"http://example.com", "https://api.ultikits.com/v1", "ftp://api.ultikits.com",
                "https://user@api.ultikits.com", "https://api.ultikits.com?x=1", "", "http://127.0.0.2"}) {
            try {
                UltiCloudClient.parseApiUrl(bad);
                fail(bad);
            } catch (UltiCloudException expected) {
                // refused
            }
        }
        URI api = UltiCloudClient.parseApiUrl("https://api.ultikits.com");
        assertTrue(UltiCloudClient.sameOrigin(api, URI.create("https://API.ultikits.com:443/v1/uploads/x")));
        assertFalse(UltiCloudClient.sameOrigin(api, URI.create("https://evil.example/v1/uploads/x")));
        assertFalse(UltiCloudClient.sameOrigin(api, URI.create("http://api.ultikits.com/v1/uploads/x")));
        assertFalse(UltiCloudClient.sameOrigin(api, URI.create("https://api.ultikits.com:8443/v1/uploads/x")));
    }

    public void testDeveloperCenterHosts() throws Exception {
        DeveloperKey key = DeveloperKey.parse(KEY);
        assertEquals("https://panel.ultikits.com", new UltiCloudClient("https://api.ultikits.com", key, log).developerCenter());
        assertEquals("https://panel-staging.ultikits.com",
                new UltiCloudClient("https://api-staging.ultikits.com", key, log).developerCenter());
        assertNull(client().developerCenter());
    }

    public void testKeyFormatAndMasking() throws Exception {
        DeveloperKey key = DeveloperKey.parse("  " + KEY + "\n");
        assertEquals(KEY, key.value());
        assertEquals("ucd_Synt••••0000", key.masked());
        assertEquals(key.masked(), key.toString());
        for (String bad : new String[]{"ucd_short", "uck_SyntheticDeveloperKey0000000000000000000000", KEY + "x", ""}) {
            try {
                DeveloperKey.parse(bad);
                fail(bad);
            } catch (IllegalArgumentException e) {
                assertTrue(bad.isEmpty() || !e.getMessage().contains(bad));
            }
        }
    }
}
