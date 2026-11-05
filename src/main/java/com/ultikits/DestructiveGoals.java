package com.ultikits;

import com.ultikits.ulticloud.DeveloperKey;
import com.ultikits.ulticloud.UltiCloudClient;
import com.ultikits.ulticloud.UltiCloudException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.File;

/** Key resolution and client construction shared by the destructive goals, as in deploy. */
final class DestructiveGoals {

    private DestructiveGoals() {
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
