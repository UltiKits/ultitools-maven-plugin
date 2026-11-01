package com.ultikits.ulticloud;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.apache.maven.plugin.logging.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
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
 * <p>Flow (contract {@code rest-v1/developer-client/1.0.0}): declare the version with the
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
        m.put("icon_url_invalid", "图标必须是可访问的 https 地址");
        m.put("validation_failed", "请求未通过服务器校验");
        m.put("rate_limited", "请求过于频繁");
        MESSAGES = Collections.unmodifiableMap(m);
    }

    private final URI api;
    private final DeveloperKey credential;
    private final Log log;

    /**
     * Creates a client.
     *
     * @param apiUrl     the API origin, for example {@code https://api.ultikits.com}
     * @param credential the developer key or CLI credential
     * @param log        the build log
     * @throws UltiCloudException when the API address is not https (loopback excepted)
     */
    public UltiCloudClient(String apiUrl, DeveloperKey credential, Log log) throws UltiCloudException {
        this.api = parseApiUrl(apiUrl);
        this.credential = credential;
        this.log = log;
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
        Response r = send("GET", api + "/v1/developer/modules?q=" + query(moduleRef) + "&pageSize=100", null, true);
        if (r.status != 200) {
            throw failure(ApiError.of(r), moduleRef, null);
        }
        JSONObject current = null;
        JSONArray items = object(parse(r), "data").getJSONArray("items");
        if (items != null) {
            for (Object item : items) {
                if (item instanceof JSONObject && moduleRef.equals(string((JSONObject) item, "identifier"))) {
                    current = (JSONObject) item;
                }
            }
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
        headers.put("Content-Type", "application/java-archive");
        Response r = request("PUT", uploadUrl, headers, jar);
        if (r.status == 200 || r.status == 201) {
            return;
        }
        throw failure(ApiError.of(r), moduleRef, version);
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
                        if (e.retryAfter == null && details.get("retryAfterSeconds") != null) {
                            e.retryAfter = String.valueOf(details.get("retryAfterSeconds"));
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
        byte[] body = null;
        if (jsonBody != null) {
            headers.put("Content-Type", "application/json");
            body = jsonBody.getBytes(StandardCharsets.UTF_8);
        }
        return request(method, url, headers, body);
    }

    private Response request(String method, String url, Map<String, String> headers, byte[] body) throws UltiCloudException {
        try {
            if ("PATCH".equals(method) && hasJdkHttpClient()) {
                return requestWithJdkHttpClient(method, url, headers, body);
            }
            return requestWithUrlConnection(method, url, headers, body);
        } catch (IOException e) {
            throw new UltiCloudException("无法连接 UltiCloud（" + api + "）：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage().replace(url, origin(URI.create(url)))));
        }
    }

    private Response requestWithUrlConnection(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setUseCaches(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        setMethod(conn, method);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            conn.setRequestProperty(h.getKey(), h.getValue());
        }
        try {
            if (body != null) {
                conn.setDoOutput(true);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Response(status, readAll(in), conn.getHeaderField("Retry-After"));
        } finally {
            conn.disconnect();
        }
    }

    /**
     * HttpURLConnection refuses PATCH. On Java 8 the method field is set reflectively;
     * Java 16+ blocks that, so PATCH goes through {@code java.net.http} instead (see
     * {@link #requestWithJdkHttpClient}).
     */
    private static void setMethod(HttpURLConnection conn, String method) throws IOException {
        if (!"PATCH".equals(method)) {
            conn.setRequestMethod(method);
            return;
        }
        try {
            Field field = HttpURLConnection.class.getDeclaredField("method");
            field.setAccessible(true);
            field.set(conn, method);
        } catch (Exception e) {
            throw new IOException("this Java runtime cannot send PATCH", e);
        }
    }

    private static boolean hasJdkHttpClient() {
        try {
            Class.forName("java.net.http.HttpClient");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Sends through {@code java.net.http} (Java 11+) by reflection, so the code still compiles for Java 8. */
    private Response requestWithJdkHttpClient(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
        try {
            Class<?> clientClass = Class.forName("java.net.http.HttpClient");
            Class<?> requestClass = Class.forName("java.net.http.HttpRequest");
            Class<?> builderClass = Class.forName("java.net.http.HttpRequest$Builder");
            Class<?> publisherClass = Class.forName("java.net.http.HttpRequest$BodyPublisher");
            Class<?> publishersClass = Class.forName("java.net.http.HttpRequest$BodyPublishers");
            Class<?> handlerClass = Class.forName("java.net.http.HttpResponse$BodyHandler");
            Class<?> handlersClass = Class.forName("java.net.http.HttpResponse$BodyHandlers");
            Class<?> responseClass = Class.forName("java.net.http.HttpResponse");
            Class<?> headersClass = Class.forName("java.net.http.HttpHeaders");

            Object client = clientClass.getMethod("newHttpClient").invoke(null);
            Object builder = requestClass.getMethod("newBuilder", URI.class).invoke(null, URI.create(url));
            Object publisher = body == null
                    ? publishersClass.getMethod("noBody").invoke(null)
                    : publishersClass.getMethod("ofByteArray", byte[].class).invoke(null, (Object) body);
            builder = builderClass.getMethod("method", String.class, publisherClass).invoke(builder, method, publisher);
            builder = builderClass.getMethod("timeout", java.time.Duration.class)
                    .invoke(builder, java.time.Duration.ofMillis(READ_TIMEOUT_MS));
            Method header = builderClass.getMethod("header", String.class, String.class);
            builder = header.invoke(builder, "User-Agent", USER_AGENT);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                builder = header.invoke(builder, h.getKey(), h.getValue());
            }
            Object request = builderClass.getMethod("build").invoke(builder);
            Object handler = handlersClass.getMethod("ofByteArray").invoke(null);
            Object response = clientClass.getMethod("send", requestClass, handlerClass).invoke(client, request, handler);
            int status = (Integer) responseClass.getMethod("statusCode").invoke(response);
            byte[] bytes = (byte[]) responseClass.getMethod("body").invoke(response);
            Object responseHeaders = responseClass.getMethod("headers").invoke(response);
            Object retryAfter = headersClass.getMethod("firstValue", String.class).invoke(responseHeaders, "Retry-After");
            String retry = (String) ((java.util.Optional<?>) retryAfter).orElse(null);
            return new Response(status, new String(bytes, StandardCharsets.UTF_8), retry);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException(String.valueOf(cause), cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("java.net.http is not usable", e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
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
