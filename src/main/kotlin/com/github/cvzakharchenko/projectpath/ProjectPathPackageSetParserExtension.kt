// Backport of com.intellij.psi.search.scope.packageSet.ProjectPathPackageSetParserExtension from IntelliJ Platform 2026.3
// (JetBrains/intellij-community commit 8d10dbb2f609, IJPL-22590). Licensed under the Apache License 2.0.
package com.github.cvzakharchenko.projectpath

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.lexer.Lexer
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.TokenType
import com.intellij.psi.search.scope.packageSet.PackageSet
import com.intellij.psi.search.scope.packageSet.PackageSetParserExtension
import com.intellij.psi.search.scope.packageSet.ParsingException
import com.intellij.psi.search.scope.packageSet.lexer.ScopeTokenTypes

/**
 * Parses `projectPath:<pattern>` scope terms. The pattern uses the same wildcards as `file:` patterns.
 *
 * The native 2026.3 implementation registers the same prefix, so this extension steps aside when it finds the
 * native parser. Otherwise both would claim `projectPath`, and the first registered one would win.
 */
internal class ProjectPathPackageSetParserExtension : PackageSetParserExtension {
    override fun parseScope(lexer: Lexer): String? {
        if (lexer.tokenType != ScopeTokenTypes.IDENTIFIER) return null
        if (ProjectPathPatternPackageSet.SCOPE_PROJECT_PATH != lexer.tokenText()) return null

        // A module qualifier, as in file[module]:, isn't supported: the prefix must be followed by ':' directly
        val end = lexer.tokenEnd
        if (end >= lexer.bufferEnd || lexer.bufferSequence[end] != ':') return null

        if (isNativeParserRegistered()) return null

        lexer.advance()
        return ProjectPathPatternPackageSet.SCOPE_PROJECT_PATH
    }

    override fun parsePackageSet(lexer: Lexer, scope: String, modulePattern: String?): PackageSet? {
        if (ProjectPathPatternPackageSet.SCOPE_PROJECT_PATH != scope) return null
        if (modulePattern != null) return null
        return ProjectPathPatternPackageSet(parseFilePattern(lexer))
    }

    private fun isNativeParserRegistered(): Boolean {
        val registered = PackageSetParserExtension.EP_NAME.extensionList.any { it.javaClass.name == NATIVE_PARSER_CLASS }
        if (registered && !nativeParserReported) {
            nativeParserReported = true
            LOG.info("Native projectPath: scope support found ($NATIVE_PARSER_CLASS), the backport is inactive")
        }
        return registered
    }

    private companion object {
        const val NATIVE_PARSER_CLASS = "com.intellij.psi.search.scope.packageSet.ProjectPathPackageSetParserExtension"

        val LOG = logger<ProjectPathPackageSetParserExtension>()

        @Volatile
        var nativeParserReported = false

        // Same grammar as FilePackageSetParserExtension.parseFilePattern(), which is internal API in 2026.2
        @Throws(ParsingException::class)
        fun parseFilePattern(lexer: Lexer): String {
            val pattern = StringBuilder()
            var wasIdentifier = false
            while (true) {
                when (lexer.tokenType) {
                    ScopeTokenTypes.DIV -> {
                        wasIdentifier = false
                        pattern.append("/")
                    }
                    ScopeTokenTypes.IDENTIFIER, ScopeTokenTypes.INTEGER_LITERAL -> {
                        if (wasIdentifier) parsingError(lexer, CodeInsightBundle.message("error.package.set.token.expectations", lexer.tokenText()))
                        wasIdentifier = lexer.tokenType == ScopeTokenTypes.IDENTIFIER
                        pattern.append(lexer.tokenText())
                    }
                    ScopeTokenTypes.ASTERISK -> {
                        wasIdentifier = false
                        pattern.append("*")
                    }
                    ScopeTokenTypes.DOT -> {
                        wasIdentifier = false
                        pattern.append(".")
                    }
                    TokenType.WHITE_SPACE -> {
                        wasIdentifier = false
                        pattern.append(" ")
                    }
                    ScopeTokenTypes.MINUS -> {
                        wasIdentifier = false
                        pattern.append("-")
                    }
                    ScopeTokenTypes.TILDE -> {
                        wasIdentifier = false
                        pattern.append("~")
                    }
                    ScopeTokenTypes.SHARP -> {
                        wasIdentifier = false
                        pattern.append("#")
                    }
                    else -> break
                }
                lexer.advance()
            }

            if (pattern.isEmpty()) {
                parsingError(lexer, CodeInsightBundle.message("error.package.set.pattern.expectations"))
            }

            return pattern.toString()
        }

        private fun parsingError(lexer: Lexer, message: String): Nothing {
            throw ParsingException(CodeInsightBundle.message("error.package.set.position.parsing.error", message, lexer.tokenStart + 1))
        }

        private fun Lexer.tokenText(): String = bufferSequence.subSequence(tokenStart, tokenEnd).toString()
    }
}
