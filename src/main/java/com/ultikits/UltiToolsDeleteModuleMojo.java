package com.ultikits;

import com.ultikits.ulticloud.Confirmation;
import com.ultikits.ulticloud.DeveloperKey;
import com.ultikits.ulticloud.UltiCloudClient;
import com.ultikits.ulticloud.UltiCloudException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Deletes a module on UltiCloud recoverably: it can be restored for 30 days with
 * {@code ultikits modules restore} or in the developer center.
 *
 * <p>The goal is deliberately not bound to any lifecycle phase; it runs only as
 * {@code mvn ultitools:delete-module}. It needs {@code -Dultitools.confirm=<identifyString>},
 * compared exactly before any request, and a developer key with the manage scope.</p>
 */
@Mojo(name = "delete-module")
public class UltiToolsDeleteModuleMojo extends AbstractMojo {
    @Parameter(name = "baseDir", defaultValue = "${basedir}")
    private String baseDir;
    /** Module identifier on UltiCloud. */
    @Parameter(name = "identifyString", required = true)
    private String identifyString;
    /** Must be exactly {@code <identifyString>}. */
    @Parameter(name = "confirm", property = Confirmation.PROPERTY)
    private String confirm;
    /** File whose first line is the developer key; used when ULTIKITS_TOKEN is not set. */
    @Parameter(name = "accessKeyFile")
    private String accessKeyFile;
    /** UltiCloud API origin; https only, loopback http allowed for tests. */
    @Parameter(name = "apiUrl", property = "ultitools.apiUrl", defaultValue = "https://api.ultikits.com")
    private String apiUrl;

    /** Environment lookup; replaced in tests so that the real environment is never changed. */
    private DeveloperKey.Env env = new DeveloperKey.Env() {
        @Override
        public String get(String variable) {
            return System.getenv(variable);
        }
    };

    /**
     * Checks the target and the confirmation, then deletes. Every check runs before the
     * first network request.
     *
     * @throws MojoExecutionException when a check fails or the deletion fails
     */
    public void execute() throws MojoExecutionException {
        if (identifyString == null || identifyString.trim().isEmpty()) {
            throw new MojoExecutionException("identifyString 必填：UltiCloud 上的模块标识符");
        }
        String moduleRef = identifyString.trim();
        try {
            Confirmation.require("删除模块 " + moduleRef + "（30 天内可恢复）", moduleRef, confirm);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage());
        }
        UltiCloudClient client = DestructiveGoals.client(apiUrl, env, accessKeyFile, baseDir, getLog());
        try {
            client.deleteModule(moduleRef);
        } catch (UltiCloudException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }
}
