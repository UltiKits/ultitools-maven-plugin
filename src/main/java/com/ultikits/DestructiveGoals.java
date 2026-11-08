package com.ultikits;

import com.ultikits.ulticloud.DeveloperKey;
import com.ultikits.ulticloud.UltiCloudClient;
import com.ultikits.ulticloud.UltiCloudException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.File;

/** Key resolution and client construction shared by the destructive goals, as in deploy. */
final class DestructiveGoals {

    private DestructiveGoals() {
    }

    /**
     * Reads a value the user typed for this run, from the session's user properties only
     * ({@code -D} on the command line or in {@code MAVEN_OPTS}/{@code .mvn/maven.config}).
     * POM configuration and project properties never reach it, so the confirmation and the
     * withdraw version cannot be written once into version-controlled build files.
     *
     * @param session the running session, or null in a unit test that sets none
     * @param name    the property name
     * @return the value, or null when the user gave none
     */
    static String userProperty(MavenSession session, String name) {
        if (session == null || session.getUserProperties() == null) {
            return null;
        }
        return session.getUserProperties().getProperty(name);
    }

    static UltiCloudClient client(String apiUrl, DeveloperKey.Env env, String accessKeyFile, String baseDir, Log log)
            throws MojoExecutionException {
        DeveloperKey key;
        try {
            File file = null;
            if (accessKeyFile != null && !accessKeyFile.trim().isEmpty()) {
                File f = new File(accessKeyFile.trim());
                file = f.isAbsolute() || baseDir == null ? f : new File(baseDir, accessKeyFile.trim());
            }
            key = DeveloperKey.resolve(env, file);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage());
        }
        try {
            return new UltiCloudClient(apiUrl, key, log);
        } catch (UltiCloudException e) {
            throw new MojoExecutionException(e.getMessage());
        }
    }
}
