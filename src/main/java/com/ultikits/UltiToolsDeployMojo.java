package com.ultikits;

import com.ultikits.ulticloud.DeveloperKey;
import com.ultikits.ulticloud.UltiCloudClient;
import com.ultikits.ulticloud.UltiCloudException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Publishes the built module JAR to UltiCloud with a developer key.
 *
 * <p>The key comes from the {@code ULTIKITS_TOKEN} environment variable, or else from
 * the first line of {@code accessKeyFile}. The version is declared with the SHA-256 and
 * size of the JAR, then the same bytes are uploaded to the returned ticket URL; a
 * successful upload publishes the version. Build logs only ever show the masked key.</p>
 */
@Mojo(name = "deploy", defaultPhase = LifecyclePhase.DEPLOY)
public class UltiToolsDeployMojo extends AbstractMojo {
    @Parameter(name = "baseDir", defaultValue = "${basedir}")
    private String baseDir;
    @Parameter(name = "version", defaultValue = "${project.version}")
    private String version;
    @Parameter(name = "outputDirectory", defaultValue = "${project.build.directory}")
    private File outputDirectory;
    @Parameter(name = "fileName", defaultValue = "${project.build.finalName}" + ".jar")
    private String fileName;
    /** File whose first line is the developer key; used when ULTIKITS_TOKEN is not set. */
    @Parameter(name = "accessKeyFile")
    private String accessKeyFile;
    /** Module name; creates a missing module and is synchronised to an existing one. */
    @Parameter(name = "name")
    private String name;
    /** Module identifier on UltiCloud. */
    @Parameter(name = "identifyString", required = true)
    private String identifyString;
    /** Module short description; synchronised like {@code name}. */
    @Parameter(name = "shortDescription")
    private String shortDescription;
    /** UltiCloud API origin; https only, loopback http allowed for tests. */
    @Parameter(name = "apiUrl", property = "ultitools.apiUrl", defaultValue = "https://api.ultikits.com")
    private String apiUrl;
    /** Changelog for this version, at most 5000 characters. */
    @Parameter(name = "changelog", property = "ultitools.changelog")
    private String changelog;
    /** Whether a -SNAPSHOT version may be published; published versions cannot be deleted. */
    @Parameter(name = "allowSnapshot", property = "ultitools.allowSnapshot", defaultValue = "false")
    private boolean allowSnapshot;

    /** Environment lookup; replaced in tests so that the real environment is never changed. */
    private DeveloperKey.Env env = new DeveloperKey.Env() {
        @Override
        public String get(String variable) {
            return System.getenv(variable);
        }
    };

    /**
     * Validates the configuration, then publishes. Every check runs before the first
     * network request.
     *
     * @throws MojoExecutionException when the configuration is invalid or publishing fails
     */
    public void execute() throws MojoExecutionException {
        if (identifyString == null || identifyString.trim().isEmpty()) {
            throw new MojoExecutionException("identifyString 必填：UltiCloud 上的模块标识符");
        }
        String moduleRef = identifyString.trim();
        if (version == null || version.trim().isEmpty()) {
            throw new MojoExecutionException("version 为空");
        }
        if (version.endsWith("-SNAPSHOT") && !allowSnapshot) {
            throw new MojoExecutionException("不发布快照版本；需要时设 -Dultitools.allowSnapshot=true");
        }
        if (changelog != null && changelog.length() > UltiCloudClient.MAX_CHANGELOG_CHARS) {
            throw new MojoExecutionException("changelog 超过 5000 个字符");
        }

        DeveloperKey key;
        try {
            key = DeveloperKey.resolve(env, accessKeyFile == null || accessKeyFile.trim().isEmpty()
                    ? null : resolve(accessKeyFile.trim()));
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage());
        }

        UltiCloudClient client;
        try {
            client = new UltiCloudClient(apiUrl, key, getLog());
        } catch (UltiCloudException e) {
            throw new MojoExecutionException(e.getMessage());
        }

        File jarFile = new File(outputDirectory, fileName);
        if (!jarFile.isFile()) {
            throw new MojoExecutionException("找不到构建产物 " + jarFile.getPath() + "：先运行 mvn package");
        }
        if (jarFile.length() > UltiCloudClient.MAX_JAR_BYTES) {
            throw new MojoExecutionException("JAR 有 " + jarFile.length() + " 字节，超过 UltiCloud 的 50 MiB（52428800 字节）上限");
        }
        byte[] jar;
        try {
            // Read once: the same bytes are hashed, measured and uploaded.
            jar = Files.readAllBytes(jarFile.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("无法读取 " + jarFile.getPath(), e);
        }

        try {
            client.publish(moduleRef, version.trim(), changelog,
                    jar, new UltiCloudClient.ModuleProfile(name, shortDescription));
        } catch (UltiCloudException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }

    private File resolve(String path) {
        File f = new File(path);
        return f.isAbsolute() || baseDir == null ? f : new File(baseDir, path);
    }
}
