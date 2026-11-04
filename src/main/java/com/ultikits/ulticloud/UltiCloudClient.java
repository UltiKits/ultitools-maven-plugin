package com.ultikits.ulticloud;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.util.Timeout;
import org.apache.maven.plugin.logging.Log;

import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Publishes a module version to UltiCloud with a developer key, independent of the
 * Maven API so that it can be unit tested against a local stub server.
 *
 * <p>Flow (contract {@code rest-v1/developer-client/1.1.0}): declare the version with the
 * SHA-256 and size of the JAR, PUT the same bytes to the upload ticket URL; a successful
 * upload publishes the version. A version that already exists and is still waiting for
 * its upload is resumed with a fresh ticket. A missing module is created from the
 * configured name; an existing module's name and short description are synchronised.</p>
 */
public class UltiCloudClient {

    /** Largest JAR the API accepts, in bytes (50 MiB). */
    public static final long MAX_JAR_BYTES = 52428800L;

    /** Longest changelog the API accepts, in characters. */
    public static final int MAX_CHANGELOG_CHARS = 5000;

    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int READ_TIMEOUT_MS = 300000;
    private static final int LIST_PAGE_SIZE = 100;
    private static final int MAX_LIST_PAGES = 1000;
    private static final String USER_AGENT = "ultitools-maven-plugin";

    /**
     * Messages for every error code the copied contract schemas name. Kept in one place;
     * each key must appear in the copied schemas (a test checks this).
     */
    private static final Map<String, String> MESSAGES;

    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("invalid_credential", "开发者密钥无效、已撤销或已过期：请在开发者中心创建新的开发者密钥，并更新 ULTIKITS_TOKEN 或 accessKeyFile");
        m.put("role_required", "该账号还不是开发者：请先在开发者中心开通开发者身份");
        m.put("key_scope_insufficient", "该操作需要 manage 作用域的开发者密钥：当前密钥只能上传");
        m.put("not_found", "模块 {module} 不存在或不属于该密钥的开发者：请检查 identifyString");
        m.put("version_exists", "{module}@{version} 已存在");
        m.put("version_not_greater", "{module}@{version} 不大于已有的最新版本：请提高版本号");
        m.put("version_not_pending", "{module}@{version} 已发布，请升版本号");
        m.put("version_not_published", "{module}@{version} 还在等待上传，不能撤回");
        m.put("storage_quota_exceeded", "存储配额已用完：请在开发者中心撤回旧版本，或联系支持");
        m.put("module_identifier_taken", "模块标识符 {module} 已被占用：若这是 30 天内删除的模块，可用 ultikits modules restore {module} 或在开发者中心恢复；否则换一个 identifyString");
        m.put("module_name_taken", "模块名称已被占用：若这是 30 天内删除的模块，可用 ultikits modules restore {module} 或在开发者中心恢复；否则换一个 name");
        m.put("upload_token_expired", "上传票据已过期或版本已被删除：重新运行 deploy 即可续传");
        m.put("upload_too_large", "JAR 超过声明的大小或 50 MiB 上限：请检查构建产物");
        m.put("upload_invalid_file_type", "服务器只接受 application/java-archive 类型的上传");
        m.put("upload_checksum_mismatch", "服务器算出的 SHA-256 与声明的不一致：JAR 可能在上传途中被改动，请重新构建后再 deploy");
        m.put("upload_api_level_missing", "plugin.yml 需要整数 api-version（例如 api-version: 13）：补上后重新构建");
        m.put("upload_jar_invalid", "上传的文件不是有效的 ZIP/JAR：检查打包产物");
        m.put("icon_url_invalid", "图标必须是可访问的 https 地址");
        m.put("validation_failed", "请求未通过服务器校验");
        m.put("rate_limited", "请求过于频繁");
        MESSAGES = Collections.unmodifiableMap(m);
    }

    private final URI api;
    private final DeveloperKey credential;
    private final Log log;
    private final SSLContext sslContext;

    /**
     * Creates a client.
     *
     * @param apiUrl     the API origin, for example {@code https://api.ultikits.com}
     * @param credential the developer key or CLI credential
     * @param log        the build log
     * @throws UltiCloudException when the API address is not https (loopback excepted)
     */
    public UltiCloudClient(String apiUrl, DeveloperKey credential, Log log) throws UltiCloudException {
        this(apiUrl, credential, log, null);
    }

    /**
     * Creates a client with a specific TLS context (tests use a self-signed certificate).
     *
     * @param apiUrl     the API origin
     * @param credential the developer key or CLI credential
     * @param log        the build log
     * @param sslContext the TLS context, or null for the JVM default trust store
     * @throws UltiCloudException when the API address is not https (loopback excepted)
     */
    UltiCloudClient(String apiUrl, DeveloperKey credential, Log log, SSLContext sslContext) throws UltiCloudException {
        this.api = parseApiUrl(apiUrl);
        this.credential = credential;
        this.log = log;
        this.sslContext = sslContext;
    }

    /**
     * Name and short description from the build configuration; either may be null
     * when it is not configured.
     */
    public static final class ModuleProfile {
        private final String name;
        private final String shortDescription;

        /**
         * Creates a profile.
         *
         * @param name             the module name, or null
         * @param shortDescription the short description, or null
         */
        public ModuleProfile(String name, String shortDescription) {
            this.name = blankToNull(name);
            this.shortDescription = shortDescription;
        }

        boolean isEmpty() {
            return name == null && shortDescription == null;
        }
    }

    /**
     * Validates and normalises the API address.
     *
     * @param apiUrl the configured address
     * @return the origin as a URI without path
     * @throws UltiCloudException when the address is not an https origin or a loopback http origin
     */
    static URI parseApiUrl(String apiUrl) throws UltiCloudException {
        String raw = apiUrl == null ? "" : apiUrl.trim();
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new UltiCloudException("apiUrl 不是有效的地址：" + apiUrl);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new UltiCloudException("apiUrl 只能是源站地址（例如 https://api.ultikits.com），不能带路径、查询或用户信息：" + apiUrl);
        }
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            throw new UltiCloudException("apiUrl 必须是 https 地址（只有 http://127.0.0.1 与 http://localhost 例外）：" + apiUrl);
        }
        try {
            return new URI(scheme, null, host.toLowerCase(Locale.ROOT), uri.getPort(), null, null, null);
        } catch (URISyntaxException e) {
            throw new UltiCloudException("apiUrl 不是有效的地址：" + apiUrl);
        }
    }

    /**
     * Returns the developer center address for the configured API host.
     *
     * @return the panel origin, or null for an unknown host
     */
    String developerCenter() {
        String host = api.getHost();
        if ("api.ultikits.com".equals(host)) {
            return "https://panel.ultikits.com";
        }
        if ("api-staging.ultikits.com".equals(host)) {
            return "https://panel-staging.ultikits.com";
        }
        return null;
    }

    /**
     * Publishes one version.
     *
     * @param moduleRef the module identifier ({@code identifyString})
     * @param version   the version, {@code X.Y.Z} with an optional pre-release suffix
     * @param changelog the changelog, or null
     * @param jar       the JAR bytes; the same array is hashed, measured and uploaded
     * @param profile   the configured name and short description
     * @throws UltiCloudException when any step fails; the message is meant for the build output
     */
    public void publish(String moduleRef, String version, String changelog, byte[] jar, ModuleProfile profile)
            throws UltiCloudException {
        if (jar.length == 0) {
            throw new UltiCloudException("JAR 是空文件：请检查构建产物");
        }
        if (jar.length > MAX_JAR_BYTES) {
            throw new UltiCloudException("JAR 有 " + jar.length + " 字节，超过 UltiCloud 的 50 MiB（52428800 字节）上限");
        }
        if (changelog != null && changelog.length() > MAX_CHANGELOG_CHARS) {
            throw new UltiCloudException("changelog 超过 5000 个字符");
        }
        String sha256 = sha256Hex(jar);
        log.info("UltiCloud " + api + "：发布 " + moduleRef + "@" + version + "（" + jar.length + " 字节，SHA-256 " + sha256
                + "），密钥 " + credential.masked());

        boolean created = false;
        Response first = postVersion(moduleRef, version, changelog, sha256, jar.length);
        if (first.status == 404) {
            // Only a 404 from the first version call means the module itself is missing.
            ApiError missing = ApiError.of(first);
            if (profile.name == null) {
                throw new UltiCloudException(withCenter("模块 " + moduleRef + " 不存在：配置 name 以自动建模块（需要 manage 作用域的密钥），或先在开发者中心建模块")
                        + requestIdSuffix(missing.requestId), first.status, missing.code, missing.requestId);
            }
            createModule(moduleRef, profile);
            created = true;
            first = postVersion(moduleRef, version, changelog, sha256, jar.length);
        }
        JSONObject ticket = ticketOrResume(first, moduleRef, version);
        if (!created && !profile.isEmpty()) {
            syncProfile(moduleRef, profile);
        }
        upload(moduleRef, version, ticket, jar);
        log.info("已发布 " + moduleRef + "@" + version + (developerCenter() == null ? "" : "；开发者中心：" + developerCenter()));
    }

    private Response postVersion(String moduleRef, String version, String changelog, String sha256, long size)
            throws UltiCloudException {
        JSONObject body = jsonWithNulls();
        body.set("version", version);
        body.set("changelog", changelog == null ? JSONNull.NULL : changelog);
        body.set("sha256", sha256);
        body.set("sizeBytes", size);
        return send("POST", api + versionsPath(moduleRef), body.toString(), true);
    }

    private String versionsPath(String moduleRef) {
        return "/v1/developer/modules/" + segment(moduleRef) + "/versions";
    }

    private JSONObject ticketOrResume(Response r, String moduleRef, String version) throws UltiCloudException {
        if (r.status == 200 || r.status == 201) {
            return object(object(parse(r), "data"), "upload");
        }
        ApiError err = ApiError.of(r);
        if (r.status == 409 && "version_exists".equals(err.code)) {
            log.info(moduleRef + "@" + version + " 已存在且等待上传，换取新的上传票据续传");
            Response t = send("POST", api + versionsPath(moduleRef) + "/" + segment(version) + "/upload-token", null, true);
            if (t.status == 200 || t.status == 201) {
                return object(parse(t), "data");
            }
            throw failure(ApiError.of(t), moduleRef, version);
        }
        throw failure(err, moduleRef, version);
    }

    private void createModule(String moduleRef, ModuleProfile profile) throws UltiCloudException {
        log.info("模块 " + moduleRef + " 不存在，正在用 name 建模块");
        JSONObject body = jsonWithNulls();
        body.set("name", profile.name);
        body.set("identifier", moduleRef);
        body.set("shortDescription", profile.shortDescription == null ? "" : profile.shortDescription);
        body.set("description", JSONNull.NULL);
        body.set("categoryId", JSONNull.NULL);
        body.set("compatibleApiVersions", JSONNull.NULL);
        body.set("tags", new JSONArray());
        Response r = send("POST", api + "/v1/developer/modules", body.toString(), true);
        if (r.status == 200 || r.status == 201) {
            return;
        }
        ApiError err = ApiError.of(r);
        if (r.status == 403 && "key_scope_insufficient".equals(err.code)) {
            throw new UltiCloudException(withCenter("建模块需要 manage 作用域的开发者密钥（当前密钥只能上传）：换用 manage 密钥，或先在开发者中心建模块")
                    + requestIdSuffix(err.requestId), r.status, err.code, err.requestId);
        }
        throw failure(err, moduleRef, null);
    }

    private void syncProfile(String moduleRef, ModuleProfile profile) throws UltiCloudException {
        JSONObject current = null;
        int page = 1;
        while (current == null) {
            Response r = send("GET", api + "/v1/developer/modules?q=" + query(moduleRef) + "&page=" + page + "&pageSize="
                    + LIST_PAGE_SIZE, null, true);
            if (r.status != 200) {
                throw failure(ApiError.of(r), moduleRef, null);
            }
            JSONObject data = object(parse(r), "data");
            JSONArray items = data.getJSONArray("items");
            if (items == null || items.isEmpty()) {
                break;
            }
            for (Object item : items) {
                if (item instanceof JSONObject && moduleRef.equals(string((JSONObject) item, "identifier"))) {
                    current = (JSONObject) item;
                }
            }
            Long total = data.getLong("total");
            if (total == null || (long) page * LIST_PAGE_SIZE >= total || page >= MAX_LIST_PAGES) {
                break;
            }
            page++;
        }
        if (current == null) {
            log.warn("模块列表里找不到 " + moduleRef + "，跳过 name/shortDescription 同步");
            return;
        }
        JSONObject patch = new JSONObject();
        if (profile.name != null && !profile.name.equals(string(current, "name"))) {
            patch.set("name", profile.name);
        }
        if (profile.shortDescription != null && !profile.shortDescription.equals(string(current, "shortDescription"))) {
            patch.set("shortDescription", profile.shortDescription);
        }
        if (patch.isEmpty()) {
            return;
        }
        log.info("同步模块资料 " + patch.keySet() + " 为 pom 中的值");
        Response p = send("PATCH", api + "/v1/developer/modules/" + segment(moduleRef), patch.toString(), true);
        if (p.status == 200) {
            return;
        }
        ApiError err = ApiError.of(p);
        if (p.status == 403) {
            log.warn("没有同步模块资料（" + (err.code == null ? "HTTP 403" : err.code)
                    + "）：同步 name/shortDescription 需要 manage 作用域的开发者密钥；继续上传");
            return;
        }
        throw failure(err, moduleRef, null);
    }

    private void upload(String moduleRef, String version, JSONObject ticket, byte[] jar) throws UltiCloudException {
        String uploadUrl = string(ticket, "uploadUrl");
        String method = string(ticket, "method");
        if (uploadUrl == null || (method != null && !"PUT".equals(method))) {
            throw new UltiCloudException("服务器返回的上传票据无法识别，没有上传");
        }
        URI target;
        try {
            target = new URI(uploadUrl);
        } catch (URISyntaxException e) {
            throw new UltiCloudException("服务器返回的上传地址无效，没有上传");
        }
        if (!sameOrigin(api, target)) {
            throw new UltiCloudException("上传地址的源站（" + origin(target) + "）与 apiUrl（" + api + "）不同，没有上传");
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        JSONObject ticketHeaders = ticket.getJSONObject("headers");
        if (ticketHeaders != null) {
            for (String name : ticketHeaders.keySet()) {
                if (!"authorization".equalsIgnoreCase(name) && !"content-type".equalsIgnoreCase(name)) {
                    headers.put(name, string(ticketHeaders, name));
                }
            }
        }
        Response r = request("PUT", uploadUrl, headers, jar, ContentType.create("application/java-archive"));
        if (r.status == 200 || r.status == 201) {
            return;
        }
        throw failure(ApiError.of(r), moduleRef, version);
    }

    // ---------------------------------------------------------------- destructive goals

    /** One plain version, {@code X.Y.Z} with an optional pre-release suffix; the API's own pattern. */
    private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$");

    /**
     * Tells whether a string names exactly one version (no range, no keyword, no spaces).
     *
     * @param version the candidate
     * @return true for {@code X.Y.Z} with an optional pre-release suffix, at most 32 characters
     */
    public static boolean isVersion(String version) {
        return version != null && version.length() <= 32 && VERSION.matcher(version).matches();
    }

    /**
     * Withdraws one published version. Withdrawal cannot be undone.
     *
     * @param moduleRef the module identifier
     * @param version   the version
     * @return the server's {@code withdrawnAt} timestamp
     * @throws UltiCloudException when the request fails or the response does not report {@code withdrawn}
     */
    public String withdraw(String moduleRef, String version) throws UltiCloudException {
        log.info("UltiCloud " + api + "：撤回 " + moduleRef + "@" + version + "，密钥 " + credential.masked());
        Response r = send("POST", api + versionsPath(moduleRef) + "/" + segment(version) + "/withdraw", null, true);
        if (r.status != 200) {
            ApiError err = ApiError.of(r);
            if (r.status == 404) {
                throw new UltiCloudException(withCenter(moduleRef + "@" + version
                        + " 不存在或不属于该密钥的开发者：请检查 identifyString 与 -Dultitools.version")
                        + requestIdSuffix(err.requestId), r.status, err.code, err.requestId);
            }
            throw failure(err, moduleRef, version);
        }
        JSONObject v = object(object(parse(r), "data"), "version");
        String withdrawnAt = string(v, "withdrawnAt");
        if (!"withdrawn".equals(string(v, "status")) || withdrawnAt == null) {
            throw new UltiCloudException("服务器的响应没有报告 " + moduleRef + "@" + version
                    + " 的状态为 withdrawn：请在开发者中心核对该版本" + (developerCenter() == null ? "" : "（" + developerCenter() + "）"));
        }
        log.info("已撤回 " + moduleRef + "@" + version + "（withdrawnAt " + withdrawnAt + "）；撤回不可恢复");
        return withdrawnAt;
    }

    /**
     * Deletes a module recoverably: it can be restored until the returned deadline.
     *
     * @param moduleRef the module identifier
     * @return the server's {@code restorableUntil} timestamp
     * @throws UltiCloudException when the request fails or the response has no restore deadline
     */
    public String deleteModule(String moduleRef) throws UltiCloudException {
        log.info("UltiCloud " + api + "：删除模块 " + moduleRef + "，密钥 " + credential.masked());
        Response r = send("DELETE", api + "/v1/developer/modules/" + segment(moduleRef), null, true);
        if (r.status != 200) {
            ApiError err = ApiError.of(r);
            if (r.status == 404) {
                throw new UltiCloudException(withCenter("模块 " + moduleRef + " 不存在或已删除（或不属于该密钥的开发者）：请检查 identifyString")
                        + requestIdSuffix(err.requestId), r.status, err.code, err.requestId);
            }
            throw failure(err, moduleRef, null);
        }
        String restorableUntil = string(object(parse(r), "data"), "restorableUntil");
        if (restorableUntil == null) {
            throw new UltiCloudException("服务器的响应缺少恢复截止时间 restorableUntil：请在开发者中心核对模块 " + moduleRef
                    + (developerCenter() == null ? "" : "（" + developerCenter() + "）"));
        }
        log.info("已删除模块 " + moduleRef + "，可在 " + restorableUntil + " 之前恢复：运行 ultikits modules restore " + moduleRef
                + "，或在开发者中心恢复" + (developerCenter() == null ? "" : "（" + developerCenter() + "）"));
        return restorableUntil;
    }

    // ---------------------------------------------------------------- errors

    /**
     * Returns the message for an error code.
     *
     * @param code the error code
     * @return the message template, or null for a code the contract does not name
     */
    static String messageFor(String code) {
        return code == null ? null : MESSAGES.get(code);
    }

    /**
     * Returns every error code that has a message.
     *
     * @return the codes
     */
    static java.util.Set<String> knownCodes() {
        return MESSAGES.keySet();
    }

    private UltiCloudException failure(ApiError err, String moduleRef, String version) {
        String template = messageFor(err.code);
        StringBuilder msg = new StringBuilder();
        if (template == null) {
            msg.append("UltiCloud 返回 HTTP ").append(err.status);
            if (err.code != null) {
                msg.append("（").append(err.code).append("）");
            }
        } else {
            msg.append(template.replace("{module}", moduleRef).replace("{version}", version == null ? "" : version));
        }
        if ("validation_failed".equals(err.code) && err.fields != null && !err.fields.isEmpty()) {
            msg.append("：");
            boolean first = true;
            for (String field : err.fields.keySet()) {
                msg.append(first ? "" : "；").append(field).append(" ").append(String.valueOf(err.fields.get(field)));
                first = false;
            }
        }
        if (err.status == 429 || "rate_limited".equals(err.code)) {
            msg.append("：请在 ").append(err.retryAfter == null ? "稍后" : err.retryAfter + " 秒后").append("重试（插件不自动重试）");
        }
        if ("not_found".equals(err.code) || "invalid_credential".equals(err.code) || "role_required".equals(err.code)) {
            msg = new StringBuilder(withCenter(msg.toString()));
        }
        msg.append(requestIdSuffix(err.requestId));
        return new UltiCloudException(msg.toString(), err.status, err.code, err.requestId);
    }

    private String withCenter(String message) {
        String center = developerCenter();
        return center == null ? message : message + "（开发者中心：" + center + "）";
    }

    private static String requestIdSuffix(String requestId) {
        return requestId == null ? "" : "（requestId: " + requestId + "）";
    }

    /** The parts of an error response the plugin uses; never the body text. */
    private static final class ApiError {
        int status;
        String code;
        String requestId;
        String retryAfter;
        JSONObject fields;

        static ApiError of(Response r) {
            ApiError e = new ApiError();
            e.status = r.status;
            e.retryAfter = r.retryAfter;
            try {
                JSONObject error = JSONUtil.parseObj(r.body).getJSONObject("error");
                if (error != null) {
                    e.code = string(error, "code");
                    e.requestId = string(error, "requestId");
                    JSONObject details = error.getJSONObject("details");
                    if (details != null) {
                        e.fields = details.getJSONObject("fields");
                        Object retry = details.get("retryAfterSeconds");
                        if (retry instanceof Number) {
                            // A bounded JSON integer is authoritative; otherwise keep the header fallback.
                            try {
                                int seconds = new BigDecimal(retry.toString()).intValueExact();
                                if (seconds >= 1 && seconds <= 86400) {
                                    e.retryAfter = String.valueOf(seconds);
                                }
                            } catch (ArithmeticException ignored) {
                                // Fractional or overflowing values are not contract-valid delays.
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Not an error envelope (a proxy page, an empty body): status only.
            }
            return e;
        }
    }

    // ---------------------------------------------------------------- HTTP

    /** A received response. */
    private static final class Response {
        final int status;
        final String body;
        final String retryAfter;

        Response(int status, String body, String retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    private Response send(String method, String url, String jsonBody, boolean authorized) throws UltiCloudException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json");
        if (authorized) {
            headers.put("Authorization", "Bearer " + credential.value());
        }
        byte[] body = jsonBody == null ? null : jsonBody.getBytes(StandardCharsets.UTF_8);
        return request(method, url, headers, body, body == null ? null : ContentType.APPLICATION_JSON);
    }

    /**
     * Sends one request through Apache HttpClient 5, the single HTTP path on every Java
     * version (HttpURLConnection cannot send PATCH). Redirects, retries, cookies and
     * content compression are off, so each request goes exactly where and how it was built.
     */
    private Response request(String method, String url, Map<String, String> headers, byte[] body, ContentType type)
            throws UltiCloudException {
        ClassicHttpRequest req = ClassicRequestBuilder.create(method).setUri(url).build();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            req.setHeader(h.getKey(), h.getValue());
        }
        if (body != null) {
            req.setEntity(new ByteArrayEntity(body, type));
        }
        CloseableHttpClient http = httpClient();
        try {
            try {
                return http.execute(req, new HttpClientResponseHandler<Response>() {
                    @Override
                    public Response handleResponse(ClassicHttpResponse response) throws IOException {
                        HttpEntity entity = response.getEntity();
                        byte[] bytes = entity == null ? new byte[0] : EntityUtils.toByteArray(entity);
                        Header retry = response.getFirstHeader("Retry-After");
                        return new Response(response.getCode(), new String(bytes, StandardCharsets.UTF_8),
                                retry == null ? null : retry.getValue());
                    }
                });
            } finally {
                http.close();
            }
        } catch (IOException e) {
            throw new UltiCloudException("无法连接 UltiCloud（" + api + "）：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage().replace(url, origin(URI.create(url)))));
        }
    }

    private CloseableHttpClient httpClient() {
        PoolingHttpClientConnectionManagerBuilder cm = PoolingHttpClientConnectionManagerBuilder.create()
                .useSystemProperties()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
                        .setSocketTimeout(Timeout.ofMilliseconds(READ_TIMEOUT_MS))
                        .build());
        if (sslContext != null) {
            cm.setTlsSocketStrategy(new DefaultClientTlsStrategy(sslContext));
        }
        return HttpClients.custom()
                .setConnectionManager(cm.build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(READ_TIMEOUT_MS))
                        .build())
                .setUserAgent(USER_AGENT)
                .useSystemProperties()
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .disableAuthCaching()
                .build();
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Returns the lowercase hexadecimal SHA-256 of the bytes.
     *
     * @param bytes the bytes
     * @return 64 hexadecimal characters
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && b.getScheme() != null && a.getHost() != null && b.getHost() != null
                && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && port(a) == port(b)
                && b.getRawUserInfo() == null;
    }

    private static int port(URI u) {
        if (u.getPort() != -1) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static String origin(URI u) {
        return u.getScheme() + "://" + u.getHost() + (u.getPort() == -1 ? "" : ":" + u.getPort());
    }

    private static JSONObject jsonWithNulls() {
        return new JSONObject(JSONConfig.create().setIgnoreNullValue(false));
    }

    private static JSONObject parse(Response r) throws UltiCloudException {
        try {
            return JSONUtil.parseObj(r.body);
        } catch (RuntimeException e) {
            throw new UltiCloudException("UltiCloud 返回了无法解析的响应（HTTP " + r.status + "）", r.status, null, null);
        }
    }

    private static JSONObject object(JSONObject parent, String key) throws UltiCloudException {
        Object value = parent == null ? null : parent.get(key);
        if (!(value instanceof JSONObject)) {
            throw new UltiCloudException("UltiCloud 的响应缺少 " + key);
        }
        return (JSONObject) value;
    }

    private static String string(JSONObject o, String key) {
        Object v = o.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s;
    }

    private static String query(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String segment(String s) {
        return query(s).replace("+", "%20");
    }
}
