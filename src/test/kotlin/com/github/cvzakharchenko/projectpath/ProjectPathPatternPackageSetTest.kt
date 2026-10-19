// Ported from com.intellij.psi.search.scope.packageSet.ProjectPathPatternPackageSetTest of IntelliJ Platform 2026.3
// (JetBrains/intellij-community commit 8d10dbb2f609). Licensed under the Apache License 2.0.
package com.github.cvzakharchenko.projectpath

import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.scope.packageSet.FilePatternPackageSet
import com.intellij.psi.search.scope.packageSet.PackageSetBase
import com.intellij.psi.search.scope.packageSet.PackageSetFactory
import com.intellij.psi.search.scope.packageSet.PackageSetParserExtension
import com.intellij.psi.search.scope.packageSet.ParsingException
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.testFramework.rules.TempDirectoryExtension
import com.intellij.workspaceModel.ide.registerProjectRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import com.intellij.psi.search.scope.packageSet.ProjectPathPackageSetParserExtension as FakeNativeParser

@TestApplication
@Timeout(30)
internal class ProjectPathPatternPackageSetTest {
    @JvmField
    @RegisterExtension
    val projectModel: ProjectModelExtension = ProjectModelExtension()

    @JvmField
    @RegisterExtension
    val tempDir: TempDirectoryExtension = TempDirectoryExtension()

    // Cases from the native 2026.3 test

    @Test
    fun `pattern uses the base project directory instead of the content root`(): Unit = timeoutRunBlocking {
        val file = createNestedModuleFile()

        assertTrue(contains(compile("projectPath:language-server//*"), file))
        assertTrue(contains(compile("projectPath:/language-server//*"), file))
        assertFalse(contains(FilePatternPackageSet("", "language-server//*"), file))
    }

    @Test
    fun `file outside the base project directories does not match`(): Unit = timeoutRunBlocking {
        val file = tempDir.newVirtualFile("outside/src/Foo.kt")

        assertFalse(contains(compile("projectPath:**"), file))
    }

    @Test
    fun `pattern uses the base directory that contains the file`(): Unit = timeoutRunBlocking {
        val file = tempDir.newVirtualFile("attached/src/Foo.kt")
        registerProjectRoot(projectModel.project, file.parent.parent.toNioPath())

        assertTrue(contains(compile("projectPath:src//*"), file))
    }

    @Test
    fun `recursive pattern matches a directory`(): Unit = timeoutRunBlocking {
        val directory = createNestedModuleFile().parent

        assertTrue(contains(compile("projectPath:language-server//*"), directory))
    }

    // Layout of a CLion 2026.2 CMake project: each CMake directory is its own content root inside the project

    @Test
    fun `sources and unit tests under a nested CMake directory match by full project path`(): Unit = timeoutRunBlocking {
        registerProjectRoot(projectModel.project, projectModel.projectRootDir)
        val cmakeDir = projectModel.baseProjectDir.newVirtualDirectory("libs/net/src")
        PsiTestUtil.addContentRoot(projectModel.createModule("net"), cmakeDir)
        val source = projectModel.baseProjectDir.newVirtualFile("libs/net/src/net/socket.cpp")
        val test = projectModel.baseProjectDir.newVirtualFile("libs/net/src/tests/socket_test.cpp")
        val pch = projectModel.baseProjectDir.newVirtualFile("libs/net/src/pch.hpp")

        val net = compile("projectPath:libs/net/src//*")
        assertTrue(contains(net, source))
        assertTrue(contains(net, test))
        assertTrue(contains(net, pch))
        assertFalse(contains(FilePatternPackageSet("", "libs/net/src//*"), test))

        assertTrue(contains(compile("projectPath:libs/net/src/*"), pch))
        assertFalse(contains(compile("projectPath:libs/net/src/*"), source))
        assertTrue(contains(compile("projectPath:libs/**/src//*"), source))
        assertFalse(contains(compile("projectPath:projects//*"), source))
    }

    // Parsing

    @Test
    fun `text round-trips through parsing`() {
        assertEquals("projectPath:libs//*", compile("projectPath:libs//*").text)
        assertEquals("projectPath:/libs//*", compile("projectPath:/libs//*").text)
        assertEquals("projectPath:a-b/c~d/#e/1.2//*", compile("projectPath:a-b/c~d/#e/1.2//*").text)
        assertEquals(
            "projectPath:libs//*||!projectPath:libs/docs//*",
            PackageSetFactory.getInstance().compile("projectPath:libs//*||!projectPath:libs/docs//*").text,
        )
    }

    @Test
    fun `pattern combines with other scope terms`(): Unit = timeoutRunBlocking {
        val file = createNestedModuleFile()

        assertTrue(contains(compile("projectPath:language-server//*&&!projectPath:language-server/docs//*"), file))
        assertFalse(contains(compile("projectPath:language-server//*&&!projectPath:language-server/analyzer//*"), file))
        assertTrue(contains(compile("file:nothing//*||projectPath:language-server//*"), file))
    }

    @Test
    fun `module qualifier and empty pattern are rejected`() {
        assertThrows(ParsingException::class.java) { PackageSetFactory.getInstance().compile("projectPath[module]:libs//*") }
        assertThrows(ParsingException::class.java) { PackageSetFactory.getInstance().compile("projectPath:") }
        assertThrows(ParsingException::class.java) { PackageSetFactory.getInstance().compile("projectPath") }
    }

    @Test
    fun `backport steps aside when the native parser is registered`() {
        val disposable = Disposer.newDisposable()
        try {
            val extensions = PackageSetParserExtension.EP_NAME.extensionList
            assertTrue(extensions.any { it is ProjectPathPackageSetParserExtension })
            // Keep the backport first, so it would win if it didn't check for the native parser
            ExtensionTestUtil.maskExtensions(PackageSetParserExtension.EP_NAME, extensions + FakeNativeParser(), disposable)

            assertInstanceOf(FakeNativeParser.Marker::class.java, PackageSetFactory.getInstance().compile("projectPath:libs//*"))
        }
        finally {
            Disposer.dispose(disposable)
        }
        assertInstanceOf(ProjectPathPatternPackageSet::class.java, compile("projectPath:libs//*"))
    }

    private suspend fun createNestedModuleFile(): VirtualFile {
        registerProjectRoot(projectModel.project, projectModel.projectRootDir)
        val contentRoot = projectModel.baseProjectDir.newVirtualDirectory("language-server/analyzer")
        val module = projectModel.createModule("language-server.analyzer")
        PsiTestUtil.addContentRoot(module, contentRoot)
        return projectModel.baseProjectDir.newVirtualFile("language-server/analyzer/src/Foo.kt")
    }

    private fun compile(pattern: String): PackageSetBase {
        return assertInstanceOf(PackageSetBase::class.java, PackageSetFactory.getInstance().compile(pattern))
    }

    private suspend fun contains(packageSet: PackageSetBase, file: VirtualFile): Boolean {
        return readAction { packageSet.contains(file, projectModel.project, null) }
    }
}
