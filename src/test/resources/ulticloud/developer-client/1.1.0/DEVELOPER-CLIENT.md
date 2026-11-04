# UltiCloud 机器客户端 REST 契约 1.1.0

由 `pnpm --filter @ulticloud/contracts generate:developer-client` 从 `packages/contracts/src/rest/` 的 REST 契约生成，请勿手改。

本目录是 `ultikits-cli`（TypeScript）与 `ultitools-maven-plugin`（Java 8）调用的 14 个机器接口的 JSON Schema（Draft 2020-12）与金标准夹具。两个客户端都不能 import zod：各自按 `MANIFEST.json` 的 SHA-256 拷贝整个目录，用同一批夹具做测试桩。每个 schema 都不含 `format` 关键字，只用锚定的可移植正则，所以不开格式断言的校验器（例如 JDK 正则下的 networknt json-schema-validator）与服务端得出同样的判定。

## 操作

| 操作 | 路由 | 契约 id |
|---|---|---|
| `deviceAuthorize` | `POST /v1/device/authorize` | `device.authorize` |
| `deviceToken` | `POST /v1/device/token` | `device.token` |
| `whoami` | `GET /v1/developer/whoami` | `developer.whoami` |
| `modulesList` | `GET /v1/developer/modules` | `developer.modules.list` |
| `modulesCreate` | `POST /v1/developer/modules` | `developer.modules.create` |
| `modulesUpdate` | `PATCH /v1/developer/modules/:moduleRef` | `developer.modules.update` |
| `modulesDelete` | `DELETE /v1/developer/modules/:moduleRef` | `developer.modules.delete` |
| `modulesRestore` | `POST /v1/developer/modules/:moduleRef/restore` | `developer.modules.restore` |
| `versionsCreate` | `POST /v1/developer/modules/:moduleRef/versions` | `developer.modules.versions.create` |
| `uploadToken` | `POST /v1/developer/modules/:moduleRef/versions/:version/upload-token` | `developer.modules.versions.uploadToken` |
| `versionsWithdraw` | `POST /v1/developer/modules/:moduleRef/versions/:version/withdraw` | `developer.modules.versions.withdraw` |
| `uploadPut` | `PUT /v1/uploads/:ticketId` | `uploads.put` |
| `credentialRevoke` | `POST /v1/developer/credential/revoke` | `developer.credential.revoke` |
| `versionsList` | `GET /v1/catalogue/modules/:moduleRef/versions` | `catalogue.modules.versions.list` |

Schema 文件名是 `schemas/<操作>.<部件>[.<状态码>].schema.json`。部件：`params`（解码后的路径参数）、`query`（解码后的查询串）、`request`（JSON 请求体）、`response`（成功体）、`error`（按 HTTP 状态码给出的错误信封）。`uploadPut` 的请求体是 JAR 本身，没有 `request` schema。

夹具在 `fixtures/<操作>/<部件>/{valid,invalid}/<名字>.json`。valid 夹具就是 JSON 值本身；invalid 夹具是 `{ "expect": "invalid", "why": "…", "body": … }`。文件名带 `<状态码>-` 前缀（例如 `403-key-scope-insufficient.json`）时只用该状态码的 schema 判定。夹具里所有凭据与票据都是合成值，不可用于真实服务。

查询夹具与 `query` schema 描述的是解码之后的查询串：整数是 JSON 数字，枚举是字符串。URL 里整数照常写成十进制字符串，服务端先解码再校验。同一键出现两次或出现未声明的键，回 422 `validation_failed`。校验器不要开类型转换，夹具已经是解码后的值。

## 鉴权

机器接口只认 `Authorization: Bearer ucd_…`（开发者密钥）或 `Authorization: Bearer ucc_…`（CLI 凭据）。不收 Cookie，也不带 `x-ulticloud-client`。上传接口 `uploadPut` 是例外：它凭 URL 里的一次性票据 `ucu_…` 鉴权，请求**不带 Authorization**。设备授权两个接口（`deviceAuthorize`、`deviceToken`）是公开的，不带任何凭据。

开发者密钥有两种作用域：`upload`（缺省）只能读取身份、列出模块、建版本、取上传票据、上传与撤销自己；`manage` 还能建模块、改资料、删除、恢复与撤回版本。CLI 凭据恒为 `manage`。`modulesCreate`、`modulesUpdate`、`modulesDelete`、`modulesRestore`、`versionsWithdraw` 这五个管理操作，用 `upload` 作用域的密钥调用时回 403 `key_scope_insufficient`，`details.required` 为 `"manage"`。`whoami` 的 `credential { kind, scope }` 告诉客户端当前凭据的种类与作用域；字段缺失时按「未知」处理。

删除是 30 天内可恢复的删除：成功体给出 `deletedAt` 与 `restorableUntil`，期间标识符与名称仍被占用，过期后清除，恢复过期的模块回 404。撤回版本不可恢复，保留只读记录并归还存储配额。创建、删除与撤回按账号限流，超限回 429 `rate_limited`，`details.retryAfterSeconds` 给出等待秒数。

## 响应信封与错误

成功体是 `{ "data": … }`。错误体是 `{ "error": { "code", "message", "requestId", "details"? } }`，`requestId` 与响应头 `X-Request-Id` 一致，报告问题时带上它。`message` 面向人阅读，客户端只能按 `code` 分支。设备授权的两个接口也用这个信封：`deviceToken` 的轮询结果（`authorization_pending` 等）在 400 体的 `error.code` 里，不在 OAuth 风格的顶层字段。

## 设备授权登录

按 RFC 8628：

1. `deviceAuthorize` 带 `{ "clientKind": "cli" }`（可选 `suggestedName`，1..64 字符），得到 `deviceCode`、`userCode`、`verificationUri`、`verificationUriComplete`、`expiresIn`（固定 600 秒）与 `interval`（固定 5 秒）。把 `userCode` 与 `verificationUriComplete` 显示给用户，让用户在面板里确认。
2. 每隔 `interval` 秒用 `deviceToken` 带 `{ "deviceCode" }` 轮询。400 `authorization_pending`：继续等。400 `slow_down`：把间隔加 5 秒再继续。400 `expired_token`：设备码过期，停止并让用户重新登录。400 `access_denied`：用户拒绝，停止。400 `invalid_request`：请求本身有误，停止。
3. 成功体 `{ "data": { "kind": "cli", "credential": "ucc_…", "credentialId", "userId" } }`。凭据只显示这一次，客户端必须立刻存下（权限只给当前用户），之后的所有机器调用都用它。

429 `rate_limited` 的 `details.retryAfterSeconds` 是 1..86400 的整数秒数；`Retry-After` 响应头使用相同的十进制整数秒数，不是 HTTP 日期。客户端按该值等待后再试。`deviceAuthorize`、`deviceToken`、`modulesCreate`、`modulesDelete` 与 `versionsWithdraw` 的 429 都遵循此约定。本包只描述 CLI 用法。

## 发布一个版本

上传即发布，没有单独的「发布」调用：

1. `versionsCreate`：在 `POST /v1/developer/modules/:moduleRef/versions` 声明 `version`（`X.Y.Z`，可带预发布后缀）、`changelog`（字符串，最多 5000 字，没有就传 `null`）、`sha256`（JAR 的小写十六进制 SHA-256）与 `sizeBytes`（正整数，不超过 52428800）。成功体含 `version`（状态 `pending_upload`）与 `upload` 票据。
2. 对票据里的 `uploadUrl` 发 `PUT`，带票据 `headers` 里的全部头与 `Content-Type: application/java-archive`，请求体是 JAR 原文，**不带 Authorization**。成功体 `{ "data": { "version" } }`，版本状态变为 `published`，即已发布。
3. 服务端会核对：票据绑定的版本、声明的大小、SHA-256、存储配额，并读取 JAR 里 `plugin.yml` 的整数 `api-version`。

版本对象按 `status` 区分：`pending_upload` 的 `downloadUrl` 与 `withdrawnAt` 为 null、`isLatest` 为 false；`published` 的 `downloadUrl` 是 HTTPS URL（模块已下架时为 null，且 `isLatest` 为 false）、`withdrawnAt` 为 null；`withdrawn` 的 `downloadUrl` 为 null、`isLatest` 为 false，`withdrawnAt` 是带三位毫秒的 ISO 时间。不再输出旧状态值 `yanked`。输出的 `changelog` 最长 64000 字，创建入参仍限 5000 字。分页 `page` 范围为 1..1000000。

## 续传与重试

- 上传途中中断、票据过期或丢失：版本仍是 `pending_upload`。调用 `uploadToken`（`POST …/versions/:version/upload-token`）重签票据，旧票据立即失效，再重做第 2 步。
- 重新调用 `versionsCreate` 回 409 `version_exists`：版本已存在。改调 `uploadToken` 续传；若 `uploadToken` 回 409 `version_not_pending`，说明该版本已经发布，不要重传。
- 上传途中版本被删除，`uploadPut` 回 410 `upload_token_expired`：重新调用 `versionsCreate` 建版本。
- 429 `rate_limited`：按 `details.retryAfterSeconds` 等待，退避重试，不要立即重放。
- 网络错误（没有收到响应）：`versionsCreate` 与 `uploadToken` 可安全重试；`uploadPut` 先用 `uploadToken` 换新票据再重传。

## 错误码与客户端动作

| HTTP | `code` | 出现在 | 客户端该做什么 |
|---|---|---|---|
| 401 | `invalid_credential` | 所有需要凭据的接口 | 凭据缺失、无效、已撤销或过期：提示重新登录（CLI）或更换密钥，不要重试 |
| 403 | `role_required` | 开发者接口 | 账号还不是开发者：提示先到开发者中心开通 |
| 403 | `key_scope_insufficient` | 五个管理操作 | 密钥只有 `upload` 作用域：提示换用 `manage` 密钥或重新登录获取 CLI 凭据 |
| 404 | `not_found` | 带 `:moduleRef` 的接口 | 模块或版本不存在（或已过恢复期）：提示检查标识符，必要时指向开发者中心 |
| 409 | `version_exists` | `versionsCreate` | 版本已存在：调用 `uploadToken` 续传 |
| 409 | `version_not_greater` | `versionsCreate` | 版本号不大于已有最新版本：提示提高版本号 |
| 409 | `version_not_pending` | `uploadToken` | 版本已发布：无需再传 |
| 409 | `version_not_published` | `versionsWithdraw` | 版本还在等待上传，不能撤回 |
| 409 | `storage_quota_exceeded` | `versionsCreate`、`uploadToken`、`uploadPut` | 存储配额用完：提示撤回旧版本或联系支持 |
| 409 | `module_identifier_taken`、`module_name_taken` | `modulesCreate`、`modulesUpdate`、`modulesRestore` | 标识符或名称被占用：换一个 |
| 410 | `upload_token_expired` | `uploadPut` | 票据过期：`uploadToken` 重签，或重新 `versionsCreate` |
| 413 | `upload_too_large` | `uploadPut` | 文件超过声明大小或 50 MiB：检查构建产物 |
| 415 | `upload_invalid_file_type` | `uploadPut` | 只收 `application/java-archive` |
| 422 | `validation_failed` | 所有带参数的接口 | `details.fields` 逐字段给出原因：修正后重试（版本号格式错误也回此码） |
| 422 | `upload_checksum_mismatch` | `uploadPut` | SHA-256 与声明不一致：重新计算并重建版本 |
| 422 | `upload_api_level_missing` | `uploadPut` | `plugin.yml` 需要整数 `api-version`：补上后重新构建 |
| 422 | `upload_jar_invalid` | `uploadPut` | 上传文件不是有效的 ZIP/JAR：检查构建产物并上传有效 JAR |
| 422 | `icon_url_invalid` | `modulesUpdate` | 图标必须是可访问的 https 地址 |
| 429 | `rate_limited` | 所有接口 | 按 `details.retryAfterSeconds` 等待 |
| 400 | `authorization_pending`、`slow_down`、`expired_token`、`access_denied`、`invalid_request` | `deviceToken`（`invalid_request` 也见于 `deviceAuthorize`） | 见「设备授权登录」 |

## 主机

- 生产：`https://api.ultikits.com`
- 预发：`https://api-staging.ultikits.com`

所有路径以 `/v1/` 开头。上传票据里的 `uploadUrl` 是绝对 https 地址，原样使用，不要自己拼接或解析。

## 版本与冻结

客户端拷贝的是 1.1.0。该目录已冻结（`RELEASES.json` 记录 `MANIFEST.json` 的 SHA-256）：日后契约变化会另出 1.2.0 或 2.0.0，不改已冻结目录。
