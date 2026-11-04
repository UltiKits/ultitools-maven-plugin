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
 * Withdraws one published version of a module on UltiCloud. Withdrawal cannot be undone.
 *
 * <p>The goal is deliberately not bound to any lifecycle phase, so {@code mvn package} or
 * {@code mvn deploy} never runs it; it runs only as {@code mvn ultitools:withdraw}. It
 * needs {@code -Dultitools.version=<version>} and
 * {@code -Dultitools.confirm=<identifyString>@<version>}, compared exactly before any
 * request, and a developer key with the manage scope.</p>
 */
@Mojo(name = "withdraw")
public class UltiToolsWithdrawMojo extends AbstractMojo {
    @Parameter(name = "baseDir", defaultValue = "${basedir}")
    private String baseDir;
    /** Module identifier on UltiCloud. */
    @Parameter(name = "identifyString", required = true)
    private String identifyString;
    /** The version to withdraw; deliberately has no default (never the project version). */
    @Parameter(name = "version", property = "ultitools.version")
    private String version;
    /** Must be exactly {@code <identifyString>@<version>}. */
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
     * Checks the target and the confirmation, then withdraws. Every check runs before the
     * first network request.
     *
     * @throws MojoExecutionException when a check fails or the withdrawal fails
     */
    public void execute() throws MojoExecutionException {
        if (identifyString == null || identifyString.trim().isEmpty()) {
            throw new MojoExecutionException("identifyString 必填：UltiCloud 上的模块标识符");
        }
        String moduleRef = identifyString.trim();
        if (version == null || version.isEmpty()) {
            throw new MojoExecutionException("撤回需要显式给出版本：-Dultitools.version=<版本>（不使用项目版本）。没有发送任何请求");
        }
        if (!UltiCloudClient.isVersion(version)) {
            throw new MojoExecutionException("-Dultitools.version 必须是单个版本号 X.Y.Z（可带预发布后缀），不能是范围或关键字："
                    + version + "。没有发送任何请求");
        }
        try {
            Confirmation.require("撤回 " + moduleRef + "@" + version + " 不可恢复，", moduleRef + "@" + version, confirm);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage());
        }
        UltiCloudClient client = DestructiveGoals.client(apiUrl, env, accessKeyFile, baseDir, getLog());
        try {
            client.withdraw(moduleRef, version);
        } catch (UltiCloudException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }

}
