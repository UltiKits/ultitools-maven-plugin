package com.ultikits.ulticloud;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An UltiCloud machine credential: a developer key ({@code ucd_...}) or a CLI
 * credential ({@code ucc_...}).
 *
 * <p>The full value is only ever handed to the HTTP layer through
 * {@link #value()}. {@link #toString()} and {@link #masked()} return the masked
 * form so that the key cannot reach a build log by accident.</p>
 */
public final class DeveloperKey {

    /** Environment variable read before {@code accessKeyFile}. */
    public static final String ENV_NAME = "ULTIKITS_TOKEN";

    private static final Pattern FORMAT = Pattern.compile("^uc[dc]_[A-Za-z0-9_-]{43}$");

    private final String value;

    private DeveloperKey(String value) {
        this.value = value;
    }

    /**
     * Looks up an environment variable. The Mojo uses {@code System::getenv};
     * tests inject a map so that the real environment is never changed.
     */
    public interface Env {
        /**
         * Returns the value of an environment variable.
         *
         * @param name the variable name
         * @return the value, or null when it is not set
         */
        String get(String name);
    }

    /**
     * Parses and validates a credential value.
     *
     * @param raw the value as read, surrounding whitespace allowed
     * @return the credential
     * @throws IllegalArgumentException when the value is not a developer key or CLI credential;
     *                                  the message never contains the value
     */
    public static DeveloperKey parse(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!FORMAT.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "这不是 UltiCloud 开发者密钥（旧版访问令牌已失效，请在开发者中心创建开发者密钥）");
        }
        return new DeveloperKey(trimmed);
    }

    /**
     * Resolves the credential: {@code ULTIKITS_TOKEN} first, then the first line of
     * the access key file.
     *
     * @param env           environment lookup
     * @param accessKeyFile the configured key file, or null when none is configured
     * @return the credential
     * @throws IllegalArgumentException when no source is configured, the file cannot be read,
     *                                  or the value has the wrong format
     */
    public static DeveloperKey resolve(Env env, File accessKeyFile) {
        String fromEnv = env == null ? null : env.get(ENV_NAME);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return parse(fromEnv);
        }
        if (accessKeyFile != null) {
            List<String> lines;
            try {
                lines = Files.readAllLines(accessKeyFile.toPath(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalArgumentException("无法读取 accessKeyFile：" + accessKeyFile.getPath(), e);
            }
            if (!lines.isEmpty() && !lines.get(0).trim().isEmpty()) {
                return parse(lines.get(0));
            }
        }
        throw new IllegalArgumentException("需要开发者密钥：设 ULTIKITS_TOKEN 或 accessKeyFile");
    }

    /**
     * Returns the full credential, for the Authorization header only.
     *
     * @return the full value
     */
    public String value() {
        return value;
    }

    /**
     * Returns the masked form: the prefix, the first four and the last four characters.
     *
     * @return for example {@code ucd_Synt••••0000}
     */
    public String masked() {
        String body = value.substring(4);
        return value.substring(0, 4) + body.substring(0, 4) + "••••" + body.substring(body.length() - 4);
    }

    /**
     * Returns the masked form, never the full value.
     *
     * @return the masked form
     */
    @Override
    public String toString() {
        return masked();
    }
}
