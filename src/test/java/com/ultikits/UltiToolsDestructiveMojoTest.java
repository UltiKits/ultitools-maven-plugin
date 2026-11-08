package com.ultikits;

import com.ultikits.ulticloud.DeveloperKey;
import com.ultikits.ulticloud.RecordingLog;
import com.ultikits.ulticloud.StubServer;
import junit.framework.TestCase;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.project.MavenProject;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tests the two destructive goals, {@code ultitools:withdraw} and
 * {@code ultitools:delete-module}: the confirmation string is compared before any
 * request, refusals send nothing, and neither goal is bound to a lifecycle phase.
 */
public class UltiToolsDestructiveMojoTest extends TestCase {

    static final String KEY = UltiToolsDeployMojoTest.KEY;
    static final String MODULE = UltiToolsDeployMojoTest.MODULE;
    static final String WITHDRAW = "/v1/developer/modules/" + MODULE + "/versions/1.2.3/withdraw";
    static final String MODULE_PATH = "/v1/developer/modules/" + MODULE;

    private StubServer stub;
    private File dir;
    private RecordingLog log;
    private Map<String, String> env;

    @Override
    protected void setUp() throws Exception {
        stub = new StubServer();
        dir = Files.createTempDirectory("destructive-mojo").toFile();
        Files.write(new File(dir, "access.key").toPath(), (KEY + "\n").getBytes(StandardCharsets.UTF_8));
        log = new RecordingLog();
        env = new HashMap<String, String>();
    }

    @Override
    protected void tearDown() {
        stub.close();
    }

    private void common(AbstractMojo mojo, String confirm) throws Exception {
        UltiToolsDeployMojoTest.set(mojo, "baseDir", dir.getPath());
        UltiToolsDeployMojoTest.set(mojo, "apiUrl", stub.baseUrl());
        UltiToolsDeployMojoTest.set(mojo, "identifyString", MODULE);
        UltiToolsDeployMojoTest.set(mojo, "accessKeyFile", "access.key");
        userProperty(mojo, "ultitools.confirm", confirm);
        UltiToolsDeployMojoTest.set(mojo, "env", new DeveloperKey.Env() {
            @Override
            public String get(String name) {
                return env.get(name);
            }
        });
        mojo.setLog(log);
    }

    /**
     * Sets one {@code -D} user property of the mojo's session; null removes it. The goals read
     * the confirmation and the withdraw version only from there.
     */
    static void userProperty(AbstractMojo mojo, String name, String value) throws Exception {
        java.lang.reflect.Field f = mojo.getClass().getDeclaredField("session");
        f.setAccessible(true);
        MavenSession old = (MavenSession) f.get(mojo);
        Properties props = new Properties();
        if (old != null) {
            props.putAll(old.getUserProperties());
        }
        if (value == null) {
            props.remove(name);
        } else {
            props.setProperty(name, value);
        }
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.setUserProperties(props);
        f.set(mojo, new MavenSession(null, request, new DefaultMavenExecutionResult(), Collections.<MavenProject>emptyList()));
    }

    private UltiToolsWithdrawMojo withdraw(String confirm) throws Exception {
        UltiToolsWithdrawMojo mojo = new UltiToolsWithdrawMojo();
        common(mojo, confirm);
        userProperty(mojo, "ultitools.withdrawVersion", "1.2.3");
        return mojo;
    }

    private UltiToolsDeleteModuleMojo deleteModule(String confirm) throws Exception {
        UltiToolsDeleteModuleMojo mojo = new UltiToolsDeleteModuleMojo();
        common(mojo, confirm);
        return mojo;
    }

    private String failMessage(AbstractMojo mojo) {
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

    /** Invalid fixtures wrap the response body in {@code {expect, why, body}}. */
    private static String invalidBody(String relative) throws Exception {
        cn.hutool.json.JSONObject wrapper = cn.hutool.json.JSONUtil.parseObj(StubServer.fixture(relative));
        assertEquals("invalid", wrapper.getStr("expect"));
        return wrapper.getJSONObject("body").toString();
    }

    private void assertNoRequest() {
        assertEquals(stub.requests().toString(), 0, stub.requests().size());
    }

    private void assertNoSecret(String msg) {
        assertFalse(msg, msg.contains(KEY));
        assertFalse(log.all(), log.all().contains(KEY));
    }

    // ------------------------------------------------------------ withdraw

    public void testWithdrawWithExactConfirmationSendsOneRequest() throws Exception {
        stub.on("POST", WITHDRAW, 200, stub.fixtureHere("versionsWithdraw/response/valid/yanked.json"));
        withdraw(MODULE + "@1.2.3").execute();

        List<StubServer.Recorded> reqs = stub.requests();
        assertEquals(stub.unexpected().toString(), 0, stub.unexpected().size());
        assertEquals(1, reqs.size());
        assertEquals("POST", reqs.get(0).method);
        assertEquals(WITHDRAW, reqs.get(0).path);
        assertEquals("Bearer " + KEY, reqs.get(0).header("Authorization"));
        assertTrue(log.all(), log.all().contains("ucd_Synt••••0000"));
        assertTrue(log.all(), log.all().contains("2026-11-01T12:00:00.123Z"));
        assertFalse(log.all(), log.all().contains(KEY));
    }

    public void testWithdrawWithoutConfirmationSendsNothing() throws Exception {
        String msg = failMessage(withdraw(null));
        assertTrue(msg, msg.contains("-Dultitools.confirm=" + MODULE + "@1.2.3"));
        assertNoRequest();
        assertNoSecret(msg);
    }

    public void testWithdrawWithIdentifierOnlySendsNothing() throws Exception {
        failMessage(withdraw(MODULE));
        assertNoRequest();
    }

    public void testWithdrawConfirmationIsCaseSensitive() throws Exception {
        failMessage(withdraw("UC-P12-SAMPLE@1.2.3"));
        assertNoRequest();
    }

    public void testWithdrawConfirmationIsNotTrimmed() throws Exception {
        failMessage(withdraw(" " + MODULE + "@1.2.3"));
        failMessage(withdraw(MODULE + "@1.2.3 "));
        assertNoRequest();
    }

    public void testWithdrawConfirmationForAnotherVersionSendsNothing() throws Exception {
        failMessage(withdraw(MODULE + "@1.2.4"));
        assertNoRequest();
    }

    public void testConfirmationAndVersionAreNotPluginParameters() throws Exception {
        // Maven injects POM configuration and project properties only into declared plugin
        // parameters; neither goal may declare the confirmation or the withdraw version.
        File descriptor = new File("target/classes/META-INF/maven/plugin.xml");
        assertTrue("plugin descriptor missing: " + descriptor, descriptor.isFile());
        String xml = new String(Files.readAllBytes(descriptor.toPath()), StandardCharsets.UTF_8);
        for (String goal : new String[]{"withdraw", "delete-module"}) {
            int start = xml.indexOf("<goal>" + goal + "</goal>");
            assertTrue(goal, start >= 0);
            int end = xml.indexOf("</mojo>", start);
            String mojo = xml.substring(start, end);
            assertFalse(goal + " declares confirm", mojo.contains("<name>confirm</name>"));
            assertFalse(goal + " declares withdrawVersion", mojo.contains("<name>withdrawVersion</name>"));
            assertFalse(goal + " binds ultitools.confirm", mojo.contains("${ultitools.confirm}"));
            assertFalse(goal + " binds ultitools.withdrawVersion", mojo.contains("${ultitools.withdrawVersion}"));
        }
    }

    public void testWithdrawRequiresAnExplicitVersion() throws Exception {
        UltiToolsWithdrawMojo mojo = withdraw(MODULE + "@");
        userProperty(mojo, "ultitools.withdrawVersion", null);
        String msg = failMessage(mojo);
        assertTrue(msg, msg.contains("ultitools.withdrawVersion"));
        assertNoRequest();
    }

    public void testWithdrawRefusesAVersionThatIsNotASingleVersion() throws Exception {
        for (String v : new String[]{"LATEST", "1.2", "1.2.3/../4", "[1.0,2.0)", "1.2.3 "}) {
            UltiToolsWithdrawMojo mojo = withdraw(MODULE + "@" + v);
            userProperty(mojo, "ultitools.withdrawVersion", v);
            failMessage(mojo);
        }
        assertNoRequest();
    }

    public void testWithdrawRefusesAWrongStatusInTheResponse() throws Exception {
        stub.on("POST", WITHDRAW, 200, invalidBody("versionsWithdraw/response/invalid/status-yanked.json"));
        String msg = failMessage(withdraw(MODULE + "@1.2.3"));
        assertTrue(msg, msg.contains("withdrawn"));
    }

    public void testWithdrawScopeInsufficientMentionsManage() throws Exception {
        stub.on("POST", WITHDRAW, 403, StubServer.fixture("versionsWithdraw/error/valid/403-key-scope-insufficient.json"));
        String msg = failMessage(withdraw(MODULE + "@1.2.3"));
        assertTrue(msg, msg.contains("manage"));
        assertNoSecret(msg);
    }

    public void testWithdrawVersionNotPublished() throws Exception {
        stub.on("POST", WITHDRAW, 409, StubServer.fixture("versionsWithdraw/error/valid/409-version-not-published.json"));
        String msg = failMessage(withdraw(MODULE + "@1.2.3"));
        assertTrue(msg, msg.contains(MODULE + "@1.2.3") && msg.contains("不能撤回"));
    }

    public void testWithdrawNotFoundNamesTheVersion() throws Exception {
        stub.on("POST", WITHDRAW, 404, StubServer.fixture("versionsWithdraw/error/valid/404-not-found.json"));
        String msg = failMessage(withdraw(MODULE + "@1.2.3"));
        assertTrue(msg, msg.contains(MODULE + "@1.2.3"));
    }

    public void testWithdrawRateLimitedShowsRetryAfter() throws Exception {
        stub.on("POST", WITHDRAW, 429, StubServer.fixture("versionsWithdraw/error/valid/429-rate-limited.json"),
                Collections.singletonMap("Retry-After", "60"));
        String msg = failMessage(withdraw(MODULE + "@1.2.3"));
        assertTrue(msg, msg.contains("60 秒后"));
    }

    // ------------------------------------------------------------ delete-module

    public void testDeleteModuleWithExactConfirmationPrintsRestoreDeadline() throws Exception {
        stub.on("DELETE", MODULE_PATH, 200, stub.fixtureHere("modulesDelete/response/valid/deleted.json"));
        deleteModule(MODULE).execute();

        List<StubServer.Recorded> reqs = stub.requests();
        assertEquals(stub.unexpected().toString(), 0, stub.unexpected().size());
        assertEquals(1, reqs.size());
        assertEquals("DELETE", reqs.get(0).method);
        assertEquals(MODULE_PATH, reqs.get(0).path);
        assertEquals("Bearer " + KEY, reqs.get(0).header("Authorization"));
        String all = log.all();
        assertTrue(all, all.contains("2026-12-01T12:00:00.000Z"));
        assertTrue(all, all.contains("ultikits modules restore " + MODULE));
        assertFalse(all, all.contains(KEY));
    }

    public void testDeleteModuleWithoutConfirmationSendsNothing() throws Exception {
        String msg = failMessage(deleteModule(null));
        assertTrue(msg, msg.contains("-Dultitools.confirm=" + MODULE));
        assertNoRequest();
        assertNoSecret(msg);
    }

    public void testDeleteModuleWithWrongConfirmationSendsNothing() throws Exception {
        failMessage(deleteModule("uc-p12-other"));
        failMessage(deleteModule(MODULE + "@1.2.3"));
        failMessage(deleteModule(""));
        assertNoRequest();
    }

    public void testDeleteModuleRequiresIdentifier() throws Exception {
        UltiToolsDeleteModuleMojo mojo = deleteModule("");
        UltiToolsDeployMojoTest.set(mojo, "identifyString", "  ");
        failMessage(mojo);
        assertNoRequest();
    }

    public void testDeleteModuleRateLimitedShowsRetryAfter() throws Exception {
        stub.on("DELETE", MODULE_PATH, 429, StubServer.fixture("modulesDelete/error/valid/429-rate-limited.json"),
                Collections.singletonMap("Retry-After", "60"));
        String msg = failMessage(deleteModule(MODULE));
        assertTrue(msg, msg.contains("60 秒后"));
    }

    public void testDeleteModuleNotFoundSaysMissingOrDeleted() throws Exception {
        stub.on("DELETE", MODULE_PATH, 404, StubServer.fixture("modulesDelete/error/valid/404-not-found.json"));
        String msg = failMessage(deleteModule(MODULE));
        assertTrue(msg, msg.contains("不存在或已删除"));
    }

    public void testDeleteModuleScopeInsufficientMentionsManage() throws Exception {
        stub.on("DELETE", MODULE_PATH, 403, StubServer.fixture("modulesDelete/error/valid/403-key-scope-insufficient.json"));
        String msg = failMessage(deleteModule(MODULE));
        assertTrue(msg, msg.contains("manage"));
    }

    public void testDeleteModuleRefusesAResponseWithoutRestoreDeadline() throws Exception {
        stub.on("DELETE", MODULE_PATH, 200, invalidBody("modulesDelete/response/invalid/missing-restorable-until.json"));
        failMessage(deleteModule(MODULE));
    }

    // ------------------------------------------------------------ lifecycle binding

    /**
     * Returns the {@code <mojo>} element of the generated plugin descriptor for a goal.
     * {@code @Mojo} has CLASS retention, so reflection cannot see it; the descriptor
     * that maven-plugin-plugin writes in process-classes is what Maven actually reads.
     */
    private static String mojoDescriptor(String goal) throws Exception {
        File descriptor = new File("target/classes/META-INF/maven/plugin.xml");
        assertTrue("plugin descriptor missing: " + descriptor.getAbsolutePath(), descriptor.isFile());
        String xml = new String(Files.readAllBytes(descriptor.toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<mojo>(.*?)</mojo>", Pattern.DOTALL).matcher(xml);
        String found = null;
        while (m.find()) {
            if (m.group(1).contains("<goal>" + goal + "</goal>")) {
                assertNull("goal listed twice: " + goal, found);
                found = m.group(1);
            }
        }
        assertNotNull("goal not in descriptor: " + goal, found);
        return found;
    }

    public void testDeployIsBoundToTheDeployPhaseControl() throws Exception {
        assertTrue(mojoDescriptor("deploy").contains("<phase>deploy</phase>"));
    }

    public void testWithdrawIsNotBoundToAnyPhase() throws Exception {
        assertFalse(mojoDescriptor("withdraw").contains("<phase>"));
    }

    public void testDeleteModuleIsNotBoundToAnyPhase() throws Exception {
        assertFalse(mojoDescriptor("delete-module").contains("<phase>"));
    }
}
