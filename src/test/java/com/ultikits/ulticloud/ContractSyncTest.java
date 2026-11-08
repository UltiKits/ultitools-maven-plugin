package com.ultikits.ulticloud;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import junit.framework.TestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The copied contract export matches its MANIFEST.json byte for byte. */
public class ContractSyncTest extends TestCase {

    private JSONObject files() throws Exception {
        File manifest = new File(StubServer.contractRoot(), "MANIFEST.json");
        JSONObject m = JSONUtil.parseObj(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
        assertEquals("1.1.0", m.getStr("contractVersion"));
        assertEquals("12e8d561ce14d7c5b9f4ee197dd7014dfa62921951157018809140eacbc46c17",
                UltiCloudClient.sha256Hex(Files.readAllBytes(manifest.toPath())));
        return m.getJSONObject("files");
    }

    public void testEveryCopiedFileMatchesTheManifest() throws Exception {
        JSONObject files = files();
        assertTrue(files.size() > 200);
        for (String path : files.keySet()) {
            byte[] bytes = Files.readAllBytes(new File(StubServer.contractRoot(), path).toPath());
            assertEquals(path, files.getStr(path), UltiCloudClient.sha256Hex(bytes));
        }
    }

    public void testNoFileOutsideTheManifest() throws Exception {
        // The copied directory holds exactly the manifest's files plus MANIFEST.json itself (#5).
        java.util.Set<String> expected = new java.util.TreeSet<String>(files().keySet());
        expected.add("MANIFEST.json");
        java.util.Set<String> onDisk = new java.util.TreeSet<String>();
        final java.nio.file.Path root = StubServer.contractRoot().toPath();
        java.util.Iterator<java.nio.file.Path> it = Files.walk(root).iterator();
        while (it.hasNext()) {
            java.nio.file.Path f = it.next();
            if (Files.isRegularFile(f)) {
                onDisk.add(root.relativize(f).toString().replace(File.separatorChar, '/'));
            }
        }
        assertEquals(expected, onDisk);
    }

    public void testControlAOneByteChangeIsDetected() throws Exception {
        JSONObject files = files();
        String path = "DEVELOPER-CLIENT.md";
        byte[] bytes = Files.readAllBytes(new File(StubServer.contractRoot(), path).toPath());
        bytes[0] ^= 1;
        assertFalse(files.getStr(path).equals(UltiCloudClient.sha256Hex(bytes)));
    }
}
