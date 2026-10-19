# projectPath Scopes Backport

Backports the `projectPath:` scope pattern of IntelliJ Platform 2026.3 to CLion 2026.2.

<!-- Plugin description -->
Adds the `projectPath:` scope pattern from IntelliJ Platform 2026.3 to CLion 2026.2.

`projectPath:path//*` matches files by their path from the project root directory, while `file:path//*` measures the path from the closest content root. In CLion 2026.2, nested CMake directories of a loaded project can become such roots, so `file:` patterns stop matching full paths (IJPL-252273). `projectPath:` patterns keep working.

The syntax and behavior are the same as the native 2026.3 implementation, so scopes written with it keep working after the upgrade. The plugin is limited to CLion 2026.2 builds and steps aside if the IDE already has native support.
<!-- Plugin description end -->

## Why

CLion 2026.2 breaks `file:` scope patterns once a CMake project is loaded: files below CMake directories that are mirrored in the build tree are measured from those directories instead of the project root (IJPL-252273), and the Scopes settings tree becomes a flat list of folders (IJPL-254094). JetBrains added `projectPath:` in 2026.3 (IJPL-22590, intellij-community commit `8d10dbb2f609`, first in build 263.4732). This plugin registers the same prefix in 2026.2.

## Syntax

`projectPath:<pattern>`, where `<pattern>` uses the same wildcards as `file:`, measured from the base project directory that contains the file:

| Pattern | Matches |
|---|---|
| `projectPath:libs/net/src//*` | everything under `libs/net/src`, at any depth |
| `projectPath:libs/net/src/*` | files directly in that folder |
| `projectPath:/libs//*` | a leading `/` is ignored |
| `projectPath:*.cpp` | `.cpp` files at any depth (a `*` followed by more pattern also crosses folders) |
| `projectPath:libs/**/src//*` | `**` matches within a single folder name |

Combine terms with `||`, `&&`, `!` and parentheses, as with any scope pattern. A module qualifier (`projectPath[module]:`) isn't supported, the same as in 2026.3.

The base project directory is the outermost project or content root containing the file (`BaseProjectDirectories.getBaseDirectoryFor`), normally the opened project folder. Keep the repository root registered in the project model: if only `libs` were a base directory, paths would start inside `libs`. A separately attached root gets paths relative to itself. Files outside every base directory never match.

## Notes

- Unlike `file:`, `projectPath:` doesn't require the file to be in project content, so it can match excluded files. A scope doesn't change what the IDE indexes or searches: Find in Files still leaves excluded files out, and other features may apply their own filters.
- Without this plugin (or 2026.3), a scope that uses `projectPath:` stays in the settings as text but matches nothing. Keep the plugin installed, or change those patterns, while you stay on a build without native support.
- After upgrading to 2026.3 the plugin no longer loads (`until-build` is `262.*`); uninstall it. The scopes keep working with the native implementation. If a later 2026.2 update ever ships native support, the plugin detects the native parser and stays inactive.
- The Scopes settings tree and its Include/Exclude buttons still generate `file:` patterns. Type `projectPath:` patterns into the Pattern field manually.
- The parser reads the scope lexer's token types (`ScopeTokenTypes`), which the Plugin Verifier reports as 11 internal API usages. The native implementation reads the same tokens through the internal file parser, and calling that parser from a plugin has no supported contract. The usage is validated on CL-262.10968.176 and bounded by `until-build` `262.*`; `verifyPlugin` fails on every other problem category.

## Tests

`./gradlew test` runs real platform tests on the target CLion:
- the native 2026.3 test cases
- CMake-like nested content roots, with `tests`, `pch.hpp`, `/*` vs `//*` and `**`
- attached and detached roots, excluded files, files outside every root
- search scope filtering, `$Name` references, scope XML save and load
- parsing: text round-trip, malformed input, and the same accepted and rejected input as `file:`
- the native-parser guard

CLion's Nova engine logs an error when its backend freeze monitor is enabled in tests, which fails every test, so the test task sets `patch.engine.backend.freeze.timeout=0`.

`./gradlew verifyPlugin` runs the Plugin Verifier against the target IDE.

## Build and install

Requires JDK 25 for the Gradle toolchain (the 2026.2 platform is compiled to Java 25).

```bash
./gradlew buildPlugin -PplatformLocalPath=C:/Users/<user>/AppData/Local/Programs/CLion
```

Without `platformLocalPath`, the build downloads CLion 2026.2.3.1 (`platformVersion` in `gradle.properties`).

Install `build/distributions/projectPath-<version>.zip` with <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install Plugin from Disk...</kbd>, then restart the IDE. Don't extract the ZIP.

If Java on Windows fails with `Unable to establish loopback connection` / `Invalid argument: connect` (seen when Gradle is started from some packaged apps), run Gradle from a regular terminal, or point Java's Unix domain sockets at a local folder for that shell only:

```powershell
New-Item -ItemType Directory -Force build/tmp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path build/tmp).Path)"
```

## License

The implementation is ported from JetBrains/intellij-community, licensed under the Apache License 2.0. See `NOTICE` and `LICENSE`; both are also bundled in the plugin JAR.
