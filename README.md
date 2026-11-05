# ultitools-maven-plugin

Maven plugin for UltiTools module developers.

- `ultitools:deploy` publishes the built module JAR to **UltiCloud** with a developer key.
- `ultitools:withdraw` and `ultitools:delete-module` withdraw a version or delete a module; explicit goals only, never part of a build lifecycle.
- `ultitools:install` copies the built JAR into a local test server's plugin folder (unchanged).

## Publishing with `ultitools:deploy`

```xml
<build>
  <plugins>
    <plugin>
      <groupId>com.ultikits</groupId>
      <artifactId>ultitools-maven-plugin</artifactId>
      <version><!-- plugin version --></version>
      <configuration>
        <!-- Required: the module identifier on UltiCloud -->
        <identifyString>my-module</identifyString>
        <!-- Optional: create a missing module / keep its name and short description in sync -->
        <name>My Module</name>
        <shortDescription>What the module does.</shortDescription>
        <!-- Optional: only for CI-less local use; ULTIKITS_TOKEN takes precedence -->
        <accessKeyFile>ultikits.key</accessKeyFile>
      </configuration>
    </plugin>
  </plugins>
</build>
```

```bash
mvn package ultitools:deploy -Dultitools.changelog="Fixes kit cooldowns."
```

| Parameter | Property | Default | Meaning |
|---|---|---|---|
| `identifyString` | | (required) | Module identifier on UltiCloud |
| `apiUrl` | `ultitools.apiUrl` | `https://api.ultikits.com` | API origin. Must be `https://`; only `http://127.0.0.1` and `http://localhost` are allowed over plain http (test stubs). Staging: `https://api-staging.ultikits.com` |
| `changelog` | `ultitools.changelog` | none (sent as `null`) | Release notes, at most 5000 characters |
| `allowSnapshot` | `ultitools.allowSnapshot` | `false` | `-SNAPSHOT` versions are refused unless this is `true`. Published versions cannot be deleted, only withdrawn |
| `accessKeyFile` | | none | File whose first line is the developer key, relative to the project directory |
| `name` | | none | Module name; used to create a missing module and synchronised to an existing one |
| `shortDescription` | | none | Short description; synchronised like `name` |
| `version` | | `${project.version}` | Version to publish (`X.Y.Z`, optional pre-release suffix) |
| `outputDirectory`, `fileName` | | `${project.build.directory}`, `${project.build.finalName}.jar` | The JAR to publish |

### Developer key

Create a developer key in the UltiCloud developer center (`https://panel.ultikits.com`). The plugin reads it from:

1. the `ULTIKITS_TOKEN` environment variable, else
2. the first line of `accessKeyFile`.

Developer keys look like `ucd_` followed by 43 characters; a CLI credential (`ucc_…`) is accepted as well. The build log only ever shows a masked form such as `ucd_AbCd••••WxYz`. Never commit the key file: add it to `.gitignore`.

Keys have a scope. An **upload** key (the default) can publish versions of modules that already exist. A **manage** key can also create modules and change their name and short description. Use an upload key in CI.

### What `deploy` does

1. Checks the configuration before any network request: identifier, key format, `https` API address, snapshot guard, changelog length, JAR present and at most 50 MiB.
2. `POST /v1/developer/modules/<identifyString>/versions` declaring the version, changelog, SHA-256 and size of the JAR.
   - Module missing and `name` configured: creates it (`POST /v1/developer/modules`, needs a manage key), then declares the version. Without `name` the build fails and says so.
   - Version already declared but not uploaded (an interrupted earlier run): fetches a new upload ticket and continues.
   - Module exists and `name`/`shortDescription` are configured: if they differ from UltiCloud, they are updated (`PATCH`, needs a manage key; with an upload key this is skipped with a warning and the upload continues).
3. `PUT` of the same JAR bytes to the upload ticket URL, without the developer key. The plugin refuses a ticket URL on a different origin from `apiUrl`. A successful upload publishes the version.

Every failure ends the build with a specific message (invalid or revoked key, not a developer, version not greater than the latest, version already published, storage quota, validation errors, upload checksum mismatch, missing integer `api-version` in `plugin.yml`, rate limit with its wait time) and the server's request id.

### GitHub Actions

```yaml
- name: Publish to UltiCloud
  env:
    ULTIKITS_TOKEN: ${{ secrets.ULTIKITS_TOKEN }}
    # Passed through the environment, never interpolated into the shell script.
    CHANGELOG: ${{ github.event.release.body }}
  run: mvn -B package ultitools:deploy -Dultitools.changelog="$CHANGELOG"
```

Store an **upload**-scoped developer key as the repository secret `ULTIKITS_TOKEN`, and create the module in the developer center first.

## Withdraw a version / delete a module

Two destructive goals exist for module developers. Neither is bound to a lifecycle phase, so `mvn package`, `mvn deploy` or any other phase never runs them: they run only when named on the command line, and only with an exact confirmation string.

```bash
# Withdraw one published version. Cannot be undone.
mvn ultitools:withdraw -Dultitools.withdrawVersion=1.2.3 -Dultitools.confirm=my-module@1.2.3

# Delete the module. Recoverable for 30 days.
mvn ultitools:delete-module -Dultitools.confirm=my-module
```

- **Manage-scoped key required.** Both goals need a developer key with the **manage** scope (from `ULTIKITS_TOKEN` or `accessKeyFile`, as for `deploy`). Use an **upload** key in CI: with it the server refuses both operations (`key_scope_insufficient`), so a CI job cannot withdraw or delete anything.
- **Confirmation.** `-Dultitools.confirm` must equal `<identifyString>@<version>` for `withdraw` and `<identifyString>` for `delete-module`, character for character (case-sensitive, no surrounding spaces). A missing or different string fails the build before any request is sent. A confirmation written for one target never matches another: a module confirmation does not confirm a version withdrawal, and the reverse.
- **Version.** `withdraw` takes the version only from `-Dultitools.withdrawVersion` (never from `${project.version}`), and only a single `X.Y.Z` version with an optional pre-release suffix; ranges and keywords are refused. The parameter has its own name on purpose: deploy's `<version>` in the plugin's shared `<configuration>` and an `ultitools.version` property in the POM (often the framework version) are both ignored by `withdraw`, so neither can stand in for the version you typed.
- **Withdrawing is permanent.** A withdrawn version is marked withdrawn, can no longer be downloaded and cannot be restored. Only published versions can be withdrawn (`version_not_published` otherwise).
- **Deleting is recoverable for 30 days.** The build log prints the restore deadline (`restorableUntil`). Restore with `ultikits modules restore <identifyString>` or in the developer center; this plugin has no restore goal. Until the deadline the identifier and name stay reserved.
- Rate limits answer with the wait time (`Retry-After`); the plugin does not retry. The module owner is emailed about every withdrawal and deletion.
- Do not add these goals to an `<executions>` block of your `pom.xml`: that would bind them to a phase again.

| Parameter | Property | Default | Meaning |
|---|---|---|---|
| `identifyString` | | (required) | Module identifier on UltiCloud |
| `withdrawVersion` (`withdraw` only) | `ultitools.withdrawVersion` | none (required) | The version to withdraw |
| `confirm` | `ultitools.confirm` | none (required) | Exact confirmation string, see above |
| `apiUrl` | `ultitools.apiUrl` | `https://api.ultikits.com` | As for `deploy` |
| `accessKeyFile` | | none | As for `deploy` |

## Upgrading from 1.x

- The old access tokens no longer work. Create a developer key in the developer center and put it in `ULTIKITS_TOKEN` (or the first line of `accessKeyFile`).
- `name` and `shortDescription` are now optional. Creating a module and synchronising its name and short description need a **manage**-scoped developer key.
- When `name` or `shortDescription` is configured, every deploy sets the values on UltiCloud to the ones in `pom.xml`, overwriting edits made in the web developer center (as 1.x did). Leave both out if you do not want this.
- `-SNAPSHOT` versions are no longer published unless `-Dultitools.allowSnapshot=true` is given.
- Withdrawing a version and deleting a module are separate, explicit goals and are never part of `deploy`.

## Building this plugin

```bash
mvn -B -ntp -Dgpg.skip=true verify
```

HTTP goes through Apache HttpClient 5 (`HttpURLConnection` cannot send PATCH). The tests replay the UltiCloud developer-client contract fixtures copied into `src/test/resources/ulticloud/developer-client/1.1.0/` against a local stub server; no network access is needed. Refresh the copy with `scripts/sync-contract.sh <ulticloud>/packages/contracts/generated/rest-v1/developer-client/1.1.0`, which verifies every file against `MANIFEST.json`.
