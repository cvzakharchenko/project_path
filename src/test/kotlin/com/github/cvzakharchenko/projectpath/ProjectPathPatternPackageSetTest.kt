// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Ported from com.intellij.psi.search.scope.packageSet.ProjectPathPatternPackageSetTest of IntelliJ Platform 2026.3
// (JetBrains/intellij-community commit 8d10dbb2f609). See NOTICE.
package com.github.cvzakharchenko.projectpath

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.search.scope.packageSet.FilePatternPackageSet
import com.intellij.psi.search.scope.packageSet.NamedScope
import com.intellij.psi.search.scope.packageSet.NamedScopeManager
import com.intellij.psi.search.scope.packageSet.PackageSetBase
import com.intellij.psi.search.scope.packageSet.PackageSetFactory
import com.intellij.psi.search.scope.packageSet.PackageSetParserExtension
import com.intellij.psi.search.scope.packageSet.ParsingException
import com.intellij.psi.search.scope.packageSet.lexer.ScopesLexer
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.testFramework.rules.TempDirectoryExtension
import com.intellij.workspaceModel.ide.registerProjectRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Duration.Companion.seconds
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

    @Test
    fun `exactly one backport parser is registered`() {
        assertEquals(1, PackageSetParserExtension.EP_NAME.extensionList.count { it is ProjectPathPackageSetParserExtension })
    }

    @Test
    fun `unrecognized prefixes do not consume the lexer`() {
        val parser = ProjectPathPackageSetParserExtension()
        for (text in listOf("file:libs//*", "projectPathOther:libs//*", "projectPath[module]:libs//*", "projectPath", "projectPath :libs//*")) {
            val lexer = ScopesLexer()
            lexer.start(text)
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            assertNull(parser.parseScope(lexer), text)
            assertEquals(start, lexer.tokenStart, text)
            assertEquals(end, lexer.tokenEnd, text)
        }
    }

    @Test
    fun `malformed patterns are rejected`() {
        for (text in listOf("projectPath:", "projectPath", "projectPath :libs//*", "projectPath:libs//*||", "projectPath:libs/[", "projectPath:libs//*)")) {
            val error = assertThrows(ParsingException::class.java, { PackageSetFactory.getInstance().compile(text) }, text)
            assertFalse(error.message.isNullOrEmpty(), text)
        }
    }

    // The pattern grammar is the platform's file: grammar, so both prefixes must accept and reject the same input
    @Test
    fun `pattern grammar matches the file grammar`() {
        for (pattern in listOf("libs//*", "/libs//*", "a-b/c~d/#e/1.2//*", "libs/*.cpp", "libs/**/src//*", "a b//*", "*test.cpp")) {
            val fileSet = assertInstanceOf(FilePatternPackageSet::class.java, PackageSetFactory.getInstance().compile("file:$pattern"))
            assertEquals("projectPath:${fileSet.pattern}", compile("projectPath:$pattern").text, pattern)
        }
        for (pattern in listOf("", "[", "libs/[", "libs//*)", "libs//*||")) {
            val fileError = assertThrows(ParsingException::class.java) { PackageSetFactory.getInstance().compile("file:$pattern") }
            val projectPathError = assertThrows(ParsingException::class.java) { PackageSetFactory.getInstance().compile("projectPath:$pattern") }
            val prefixShift = "projectPath:".length - "file:".length
            assertEquals(shiftPosition(fileError.message!!, prefixShift), projectPathError.message, pattern)
        }
    }

    @Test
    fun `text and copy round-trip`() {
        val text = "projectPath:/libs/source-2.1~backup#1//*"
        val set = compile(text)
        assertInstanceOf(ProjectPathPatternPackageSet::class.java, set)
        assertEquals(text, set.text)
        assertEquals(text, set.createCopy().text)
        assertNotSame(set, set.createCopy())
        assertEquals(text, compile(set.text).text)
        assertEquals(0, set.nodePriority)
    }

    @Test
    fun `files outside every base directory never match`(): Unit = timeoutRunBlocking {
        val outside = LightVirtualFile("outside.cpp")
        assertFalse(contains(compile("projectPath:**"), outside))
        assertFalse(contains(compile("projectPath:*.cpp"), outside))
    }

    @Test
    fun `excluded files match like the native implementation`(): Unit = timeoutRunBlocking {
        registerProjectRoot(projectModel.project, projectModel.projectRootDir)
        val contentRoot = projectModel.baseProjectDir.newVirtualDirectory("language-server/analyzer")
        val module = projectModel.createModule("language-server.analyzer")
        PsiTestUtil.addContentRoot(module, contentRoot)
        val excluded = projectModel.baseProjectDir.newVirtualFile("language-server/analyzer/generated/Out.kt")
        PsiTestUtil.addExcludedRoot(module, excluded.parent)

        assertTrue(contains(compile("projectPath:language-server//*"), excluded))
        assertFalse(contains(compile("file:generated//*"), excluded))
    }

    @Test
    fun `attached content root becomes a base directory and detaching it removes the match`(): Unit = timeoutRunBlocking {
        val file = tempDir.newVirtualFile("attached-root/src/attached.cpp")
        val root = file.parent.parent
        assertFalse(contains(compile("projectPath:src//*"), file))

        val module = projectModel.createModule("attached")
        PsiTestUtil.addContentRoot(module, root)
        waitForBaseDirectory(file, root)
        assertTrue(contains(compile("projectPath:src//*"), file))
        assertFalse(contains(compile("projectPath:attached-root/src//*"), file))

        PsiTestUtil.removeContentEntry(module, root)
        waitForBaseDirectory(file, null)
        assertFalse(contains(compile("projectPath:src//*"), file))
    }

    @Test
    fun `search scope keeps the project content filter`(): Unit = timeoutRunBlocking {
        registerProjectRoot(projectModel.project, projectModel.projectRootDir)
        val contentRoot = projectModel.baseProjectDir.newVirtualDirectory("language-server/analyzer")
        val module = projectModel.createModule("language-server.analyzer")
        PsiTestUtil.addContentRoot(module, contentRoot)
        val inContent = projectModel.baseProjectDir.newVirtualFile("language-server/analyzer/src/Foo.kt")
        val excluded = projectModel.baseProjectDir.newVirtualFile("language-server/analyzer/generated/Out.kt")
        PsiTestUtil.addExcludedRoot(module, excluded.parent)
        val pattern = compile("projectPath:language-server//*")
        assertTrue(contains(pattern, excluded))

        // The pattern matches the excluded file, but a search scope built from it still leaves excluded files out
        val searchScope = GlobalSearchScopesCore.filterScope(projectModel.project, NamedScope("Net", pattern))
        assertTrue(readAction { searchScope.contains(inContent) })
        assertFalse(readAction { searchScope.contains(excluded) })
    }

    @Test
    fun `named scope references and stored scopes resolve`(): Unit = timeoutRunBlocking {
        val file = createNestedModuleFile()
        val test = projectModel.baseProjectDir.newVirtualFile("language-server/analyzer/src/FooTest.kt")
        val manager = NamedScopeManager.getInstance(projectModel.project)
        manager.setScopes(arrayOf(NamedScope("Net", compile("projectPath:language-server//*"))))

        val expression = compile("\$Net&&!projectPath:*Test.kt")
        assertTrue(readAction { expression.contains(file, projectModel.project, manager) })
        assertFalse(readAction { expression.contains(test, projectModel.project, manager) })

        val state = manager.state
        manager.setScopes(arrayOf())
        manager.loadState(state)
        val restoredScope = manager.getScope("Net")
        assertNotNull(restoredScope)
        val restored = restoredScope!!.value
        assertEquals("projectPath:language-server//*", restored?.text)
        assertTrue(readAction { (restored as PackageSetBase).contains(file, projectModel.project, manager) })
    }

    private suspend fun waitForBaseDirectory(file: VirtualFile, expected: VirtualFile?) {
        val directories = BaseProjectDirectories.getInstance(projectModel.project)
        withTimeout(10.seconds) {
            while (directories.getBaseDirectoryFor(file) != expected) delay(20)
        }
    }

    private fun shiftPosition(message: String, shift: Int): String =
        Regex("(\\d+)(\\D*)$").replace(message) { "${it.groupValues[1].toInt() + shift}${it.groupValues[2]}" }

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
