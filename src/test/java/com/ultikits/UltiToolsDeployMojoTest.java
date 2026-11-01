package com.ultikits;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.ultikits.ulticloud.DeveloperKey;
import com.ultikits.ulticloud.RecordingLog;
import com.ultikits.ulticloud.StubServer;
import com.ultikits.ulticloud.UltiCloudClient;
import junit.framework.TestCase;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class UltiToolsDeployMojoTest extends TestCase {

    static final String KEY = "ucd_SyntheticDeveloperKey0000000000000000000000";
    static final String CLI = "ucc_SyntheticCliCredential000000000000000000000";
    static final String MODULE = "uc-p12-sample";
    static final String VERSIONS = "/v1/developer/modules/" + MODULE + "/versions";
    static final String UPLOAD = "/v1/uploads/ucu_SyntheticUploadTicket0000000000000000000000";

    private StubServer stub;
    private File dir;
    private byte[] jar;
    private RecordingLog log;
    private Map<String, String> env;

    @Override
    protected void setUp() throws Exception {
        stub = new StubServer();
        dir = Files.createTempDirectory("deploy-mojo").toFile();
        jar = new byte[4096];
        for (int i = 0; i < jar.length; i++) {
            jar[i] = (byte) (i * 31 + 7);
        }
        Files.write(new File(dir, "sample-1.2.3.jar").toPath(), jar);
        Files.write(new File(dir, "access.key").toPath(), (KEY + "\nsecond line ignored\n").getBytes(StandardCharsets.UTF_8));
        log = new RecordingLog();
        env = new HashMap<String, String>();
    }

    @Override
    protected void tearDown() {
        stub.close();
    }

    private UltiToolsDeployMojo mojo() throws Exception {
        UltiToolsDeployMojo mojo = new UltiToolsDeployMojo();
        set(mojo, "baseDir", dir.getPath());
        set(mojo, "apiUrl", stub.baseUrl());
        set(mojo, "identifyString", MODULE);
        set(mojo, "version", "1.2.3");
        set(mojo, "accessKeyFile", "access.key");
        set(mojo, "outputDirectory", dir);
        set(mojo, "fileName", "sample-1.2.3.jar");
        set(mojo, "env", new DeveloperKey.Env() {
            @Override
            public String get(String name) {
                return env.get(name);
            }
        });
        mojo.setLog(log);
        return mojo;
    }

    static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void happyPath() throws Exception {
        stub.on("POST", VERSIONS, 200, stub.fixtureHere("versionsCreate/response/valid/pending.json"));
        stub.on("PUT", UPLOAD, 200, stub.fixtureHere("uploadPut/response/valid/published.json"));
    }

    private String failMessage(UltiToolsDeployMojo mojo) {
        try {
            mojo.execute();
        } catch (MojoExecutionException e) {
            return e.getMessage();
        } catch (Exception e) {
            fail("unexpected " + e);
        }
        fail("execute() should have failed");
        return null;
    }

    // ------------------------------------------------------------ Task 1 tracer

    public void testDeployCreatesVersionThenPutsTheSameBytes() throws Exception {
        happyPath();
        mojo().execute();

        List<StubServer.Recorded> reqs = stub.requests();
        assertEquals(stub.unexpected().toString(), 0, stub.unexpected().size());
        assertEquals(2, reqs.size());

        StubServer.Recorded create = reqs.get(0);
        assertEquals("POST", create.method);
        assertEquals(VERSIONS, create.path);
        assertEquals("Bearer " + KEY, create.header("Authorization"));
        JSONObject body = JSONUtil.parseObj(create.bodyText());
        assertEquals(new HashSet<String>(Arrays.asList("version", "changelog", "sha256", "sizeBytes")), body.keySet());
        assertEquals("1.2.3", body.getStr("version"));
        assertEquals(UltiCloudClient.sha256Hex(jar), body.getStr("sha256"));
        assertEquals(jar.length, body.getInt("sizeBytes").intValue());
        assertTrue("changelog must be JSON null", create.bodyText().contains("\"changelog\":null"));

        StubServer.Recorded put = reqs.get(1);
        assertEquals("PUT", put.method);
        assertEquals(UPLOAD, put.path);
        assertNull(put.header("Authorization"));
        assertEquals("application/java-archive", put.header("Content-Type"));
        assertTrue(Arrays.equals(jar, put.body));
    }

    public void testOldEndpointsAreNeverCalled() throws Exception {
        happyPath();
        mojo().execute();
        for (StubServer.Recorded r : stub.requests()) {
            assertFalse(r.path, r.path.startsWith("/plugin/") || r.path.startsWith("/developer/plugin"));
            assertNull(r.header("Accesskey"));
        }
    }

    // ------------------------------------------------------------ Task 2: key source and masking

    public void testEnvironmentVariableWinsOverKeyFile() throws Exception {
        env.put("ULTIKITS_TOKEN", CLI);
        happyPath();
        mojo().execute();
        assertEquals("Bearer " + CLI, stub.requests().get(0).header("Authorization"));
    }

    public void testNoKeySourceFailsBeforeAnyRequest() throws Exception {
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "accessKeyFile", null);
        String msg = failMessage(mojo);
        assertTrue(msg, msg.contains("需要开发者密钥：设 ULTIKITS_TOKEN 或 accessKeyFile"));
        assertEquals(0, stub.requests().size());
    }

    public void testLegacyAccessTokenIsRejectedWithoutRequest() throws Exception {
        String legacy = "0123456789abcdef0123456789abcdef";
        env.put("ULTIKITS_TOKEN", legacy);
        String msg = failMessage(mojo());
        assertTrue(msg, msg.contains("这不是 UltiCloud 开发者密钥"));
        assertFalse(msg.contains(legacy));
        assertFalse(log.all().contains(legacy));
        assertEquals(0, stub.requests().size());
    }

    public void testLogsShowOnlyTheMaskedKey() throws Exception {
        happyPath();
        mojo().execute();
        String all = log.all();
        assertFalse(all, all.contains(KEY));
        assertTrue(all, all.contains("ucd_Synt••••0000"));
    }

    public void testFailureMessagesShowOnlyTheMaskedKey() throws Exception {
        stub.on("POST", VERSIONS, 401, StubServer.fixture("versionsCreate/error/valid/401-invalid-credential.json"));
        String msg = failMessage(mojo());
        assertFalse(msg.contains(KEY));
        assertFalse(log.all().contains(KEY));
    }

    // ------------------------------------------------------------ Task 2: snapshot, address, changelog

    public void testSnapshotIsRefusedBeforeAnyRequest() throws Exception {
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "version", "1.2.3-SNAPSHOT");
        String msg = failMessage(mojo);
        assertTrue(msg, msg.contains("不发布快照版本；需要时设 -Dultitools.allowSnapshot=true"));
        assertEquals(0, stub.requests().size());
    }

    public void testSnapshotIsPublishedWhenAllowed() throws Exception {
        happyPath();
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "version", "1.2.3-SNAPSHOT");
        set(mojo, "allowSnapshot", true);
        mojo.execute();
        assertEquals("1.2.3-SNAPSHOT", JSONUtil.parseObj(stub.requests().get(0).bodyText()).getStr("version"));
    }

    public void testPlainHttpApiIsRefusedBeforeAnyRequest() throws Exception {
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "apiUrl", "http://example.com");
        String msg = failMessage(mojo);
        assertTrue(msg, msg.contains("https"));
        assertEquals(0, stub.requests().size());
    }

    public void testUploadUrlOnAnotherOriginIsNotUploaded() throws Exception {
        // The fixture's uploadUrl stays on https://api.ultikits.com, which is not the stub.
        stub.on("POST", VERSIONS, 200, StubServer.fixture("versionsCreate/response/valid/pending.json"));
        String msg = failMessage(mojo());
        assertTrue(msg, msg.contains("没有上传"));
        assertEquals(1, stub.requests().size());
        assertEquals(0, stub.unexpected().size());
    }

    public void testChangelogIsSent() throws Exception {
        happyPath();
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "changelog", "Fixes kit cooldowns.");
        mojo.execute();
        assertEquals("Fixes kit cooldowns.", JSONUtil.parseObj(stub.requests().get(0).bodyText()).getStr("changelog"));
    }

    public void testOverlongChangelogFailsBeforeAnyRequest() throws Exception {
        UltiToolsDeployMojo mojo = mojo();
        char[] c = new char[5001];
        Arrays.fill(c, 'x');
        set(mojo, "changelog", new String(c));
        String msg = failMessage(mojo);
        assertTrue(msg, msg.contains("5000"));
        assertEquals(0, stub.requests().size());
    }

    public void testIdentifyStringIsStillRequired() throws Exception {
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "identifyString", " ");
        String msg = failMessage(mojo);
        assertTrue(msg, msg.contains("identifyString"));
        assertEquals(0, stub.requests().size());
    }

    public void testMissingJarFailsBeforeAnyRequest() throws Exception {
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "fileName", "missing.jar");
        String msg = failMessage(mojo);
        assertTrue(msg, msg.contains("missing.jar"));
        assertEquals(0, stub.requests().size());
    }

    // ------------------------------------------------------------ Task 2: create module and profile sync

    public void testMissingModuleIsCreatedFromName() throws Exception {
        stub.on("POST", VERSIONS, 404, StubServer.fixture("versionsCreate/error/valid/404-not-found.json"));
        stub.on("POST", "/v1/developer/modules", 200, StubServer.fixture("modulesCreate/response/valid/created.json"));
        happyPath();
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "name", "Sample Module");
        mojo.execute();

        List<StubServer.Recorded> reqs = stub.requests();
        assertEquals(0, stub.unexpected().size());
        assertEquals(4, reqs.size());
        StubServer.Recorded create = reqs.get(1);
        assertEquals("POST", create.method);
        assertEquals("/v1/developer/modules", create.path);
        JSONObject body = JSONUtil.parseObj(create.bodyText(), false);
        assertEquals(new HashSet<String>(Arrays.asList("name", "identifier", "shortDescription", "description",
                "categoryId", "compatibleApiVersions", "tags")), body.keySet());
        assertEquals("Sample Module", body.getStr("name"));
        assertEquals(MODULE, body.getStr("identifier"));
        assertEquals("", body.getStr("shortDescription"));
        String text = create.bodyText();
        assertTrue(text, text.contains("\"description\":null"));
        assertTrue(text, text.contains("\"categoryId\":null"));
        assertTrue(text, text.contains("\"compatibleApiVersions\":null"));
        assertTrue(text, text.contains("\"tags\":[]"));
        assertEquals(VERSIONS, reqs.get(2).path);
        assertEquals("PUT", reqs.get(3).method);
    }

    public void testMissingModuleWithoutNameFails() throws Exception {
        stub.on("POST", VERSIONS, 404, StubServer.fixture("versionsCreate/error/valid/404-not-found.json"));
        String msg = failMessage(mojo());
        assertTrue(msg, msg.contains("模块 " + MODULE + " 不存在：配置 name 以自动建模块"));
        assertEquals(1, stub.requests().size());
    }

    public void testCreatingModuleWithUploadKeyExplainsScope() throws Exception {
        stub.on("POST", VERSIONS, 404, StubServer.fixture("versionsCreate/error/valid/404-not-found.json"));
        stub.on("POST", "/v1/developer/modules", 403, StubServer.fixture("modulesCreate/error/valid/403-key-scope-insufficient.json"));
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "name", "Sample Module");
        String msg = failMessage(mojo);
        assertTrue(msg, msg.contains("建模块需要 manage 作用域的开发者密钥"));
        assertEquals(2, stub.requests().size());
    }

    public void testCreatingTakenModulePointsAtRestore() throws Exception {
        stub.on("POST", VERSIONS, 404, StubServer.fixture("versionsCreate/error/valid/404-not-found.json"));
        stub.on("POST", "/v1/developer/modules", 409, StubServer.fixture("modulesCreate/error/valid/409-module-identifier-taken.json"));
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "name", "Sample Module");
        String msg = failMessage(mojo);
        assertTrue(msg, msg.contains("ultikits modules restore " + MODULE));
    }

    public void testExistingModuleProfileIsPatchedWhenDifferent() throws Exception {
        stub.on("POST", VERSIONS, 200, stub.fixtureHere("versionsCreate/response/valid/pending.json"));
        stub.on("GET", "/v1/developer/modules", 200, StubServer.fixture("modulesList/response/valid/page.json"));
        stub.on("PATCH", "/v1/developer/modules/" + MODULE, 200, StubServer.fixture("modulesUpdate/response/valid/updated.json"));
        stub.on("PUT", UPLOAD, 200, stub.fixtureHere("uploadPut/response/valid/published.json"));
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "name", "Renamed Module");
        set(mojo, "shortDescription", "A synthetic sample module.");
        mojo.execute();

        List<StubServer.Recorded> reqs = stub.requests();
        assertEquals(0, stub.unexpected().size());
        assertEquals(4, reqs.size());
        assertEquals("GET", reqs.get(1).method);
        assertTrue(reqs.get(1).query, reqs.get(1).query.contains("q=" + MODULE));
        StubServer.Recorded patch = reqs.get(2);
        assertEquals("PATCH", patch.method);
        assertEquals("Bearer " + KEY, patch.header("Authorization"));
        JSONObject body = JSONUtil.parseObj(patch.bodyText());
        assertEquals(new HashSet<String>(Arrays.asList("name")), body.keySet());
        assertEquals("Renamed Module", body.getStr("name"));
        assertEquals("PUT", reqs.get(3).method);
    }

    public void testExistingModuleProfileIsNotPatchedWhenEqual() throws Exception {
        stub.on("POST", VERSIONS, 200, stub.fixtureHere("versionsCreate/response/valid/pending.json"));
        stub.on("GET", "/v1/developer/modules", 200, StubServer.fixture("modulesList/response/valid/page.json"));
        stub.on("PUT", UPLOAD, 200, stub.fixtureHere("uploadPut/response/valid/published.json"));
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "name", "Sample Module");
        mojo.execute();
        assertEquals(0, stub.unexpected().size());
        assertEquals(3, stub.requests().size());
    }

    public void testProfileSyncWithUploadKeyWarnsAndStillUploads() throws Exception {
        stub.on("POST", VERSIONS, 200, stub.fixtureHere("versionsCreate/response/valid/pending.json"));
        stub.on("GET", "/v1/developer/modules", 200, StubServer.fixture("modulesList/response/valid/page.json"));
        stub.on("PATCH", "/v1/developer/modules/" + MODULE, 403, StubServer.fixture("modulesUpdate/error/valid/403-key-scope-insufficient.json"));
        stub.on("PUT", UPLOAD, 200, stub.fixtureHere("uploadPut/response/valid/published.json"));
        UltiToolsDeployMojo mojo = mojo();
        set(mojo, "name", "Renamed Module");
        mojo.execute();
        assertEquals(0, stub.unexpected().size());
        assertEquals("PUT", stub.requests().get(3).method);
        assertTrue(log.all(), log.all().contains("WARN") && log.all().contains("manage"));
    }
}
