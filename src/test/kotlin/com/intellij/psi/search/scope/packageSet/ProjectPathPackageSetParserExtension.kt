package com.intellij.psi.search.scope.packageSet

import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.scope.packageSet.lexer.ScopeTokenTypes

/**
 * Stands in for the native 2026.3 parser, which the backport recognizes by this exact class name.
 * Claims every `projectPath:` term and returns a [Marker], so a test can see which parser handled it.
 */
internal class ProjectPathPackageSetParserExtension : PackageSetParserExtension {
    override fun parseScope(lexer: Lexer): String? {
        if (lexer.tokenType != ScopeTokenTypes.IDENTIFIER) return null
        if (lexer.bufferSequence.subSequence(lexer.tokenStart, lexer.tokenEnd).toString() != SCOPE) return null
        lexer.advance()
        return SCOPE
    }

    override fun parsePackageSet(lexer: Lexer, scope: String, modulePattern: String?): PackageSet? {
        if (scope != SCOPE) return null
        while (lexer.tokenType != null) lexer.advance()
        return Marker()
    }

    class Marker : PackageSetBase() {
        override fun contains(file: VirtualFile, project: Project, holder: NamedScopesHolder?): Boolean = false

        override fun createCopy(): PackageSet = Marker()

        override fun getNodePriority(): Int = 0

        override fun getText(): String = "$SCOPE:native"
    }

    private companion object {
        const val SCOPE = "projectPath"
    }
}
