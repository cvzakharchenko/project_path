# projectPath Scopes Backport

Backports the `projectPath:` scope pattern of IntelliJ Platform 2026.3 to 2026.2 IDEs, e.g. CLion 2026.2.

<!-- Plugin description -->
Adds the `projectPath:` scope pattern from IntelliJ Platform 2026.3 to 2026.2 IDEs.

`projectPath:path//*` matches files by their path from the project root directory, while `file:path//*` measures the path from the closest content root. In CLion 2026.2, every CMake directory of a loaded project becomes such a root, so `file:` patterns stop matching full paths (IJPL-252273). `projectPath:` patterns keep working.

The syntax and behavior are the same as the native 2026.3 implementation, so scopes written with it keep working after the upgrade. The plugin is limited to 2026.2 builds and steps aside if the IDE already has native support.
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

The base project directory is the outermost project or content root containing the file (`BaseProjectDirectories.getBaseDirectoryFor`), normally the opened project folder. Files outside every base directory never match.

## Notes

- Unlike `file:`, `projectPath:` doesn't require the file to be in project content, so it can match excluded files. Find in Files still searches only files in the project scope.
- Without this plugin (or 2026.3), a scope that uses `projectPath:` stays in the settings as text but matches nothing.
- After upgrading to 2026.3 the plugin no longer loads (`until-build` is `262.*`); uninstall it. The scopes keep working with the native implementation.
- The Scopes settings tree and its Include/Exclude buttons still generate `file:` patterns. Type `projectPath:` patterns into the Pattern field manually.
- The parser reads the scope lexer's token types (`ScopeTokenTypes`), which the Plugin Verifier reports as internal API, the same as the native implementation does. Every parser extension receives these tokens, and the plugin only loads on 2026.2 builds, where they don't change.

## Tests

`./gradlew test` runs the native 2026.3 test cases plus CMake-like nested content roots, parsing, and the native-parser guard. The test sandbox disables CLion's Nova engine (`org.jetbrains.plugins.clion.radler`), which logs an error on startup in tests and fails every test otherwise.

`./gradlew verifyPlugin` checks compatibility with the target IDE.

## Build and install

Requires JDK 25 for the Gradle toolchain (the 2026.2 platform is compiled to Java 25).

```bash
./gradlew buildPlugin -PplatformLocalPath=C:/Users/<user>/AppData/Local/Programs/CLion
```

Without `platformLocalPath`, the build downloads CLion 2026.2.3.1 (`platformVersion` in `gradle.properties`).

Install `build/distributions/projectPath-<version>.zip` with <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install Plugin from Disk...</kbd>, then restart the IDE.

## License

The implementation is ported from JetBrains/intellij-community, licensed under the Apache License 2.0.
