// SPDX-FileCopyrightText: 2018 Andrey Dernov <https://github.com/ant-druha/>
//
// SPDX-License-Identifier: Apache-2.0

package com.intellij.plugin.powershell.ide.editor.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.plugin.powershell.lang.analysis.InferredType
import com.intellij.plugin.powershell.lang.analysis.PowerShellStaticAnalysis
import com.intellij.plugin.powershell.lang.lsp.psi.LSPWrapperPsiElement
import com.intellij.plugin.powershell.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class PowerShellDocumentationProvider : AbstractDocumentationProvider() {
  override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
    val local = localDocumentation(originalElement ?: element)
    val server = (element as? LSPWrapperPsiElement)?.getDocumentation()
    return if (local != null) local + (server?.let { "<hr>$it" } ?: "")
    else server ?: super.generateDoc(element, originalElement)
  }

  override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
    return localDocumentation(originalElement ?: element)
      ?: (element as? LSPWrapperPsiElement)?.getDocumentation()
      ?: super.getQuickNavigateInfo(element, originalElement)
  }

  override fun getCustomDocumentationElement(editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int): PsiElement? {
    val context = contextElement ?: file.findElementAt(targetOffset) ?: return null
    return documentationTarget(context)
  }

  private fun documentationTarget(element: PsiElement): PsiElement? =
    if (element is PowerShellExpression || element is PowerShellFunctionStatement) element
    else PsiTreeUtil.getParentOfType(element, PowerShellExpression::class.java, PowerShellFunctionStatement::class.java)

  private fun localDocumentation(element: PsiElement?): String? {
    if (element == null) return null
    val target = if (element is PowerShellExpression || element is PowerShellFunctionStatement) element
      else documentationTarget(element) ?: return null
    val file = target.containingFile ?: return null
    val analysis = PowerShellStaticAnalysis.forFile(file)
    val function = if (target is PowerShellFunctionStatement) target else if (target is PowerShellCommandCallExpression) analysis.functionAt(target) else null
    if (function != null) {
      val info = analysis.functionInfo(function) ?: return null
      val signature = "function ${function.name}(" + info.parameters.joinToString(", ") { (name, type) -> "$name: ${type.display()}" } + ")"
      val declared = info.declaredOutput?.let { "<p>Declared OutputType (metadata): <code>${escape(it.display())}</code></p>" } ?: ""
      return "<div class='definition'><pre>${escape(signature)}</pre></div>" +
        "<div class='content'>Inferred output: <code>${escape(info.output.display())}</code>$declared" +
        "<p>Includes implicit pipeline output and explicit return values.</p></div>"
    }
    val type = analysis.typeOf(target)
    if (type == InferredType.UNKNOWN && target !is PowerShellVariable) return null
    val warning = if (target in analysis.uninitialized) "<p>May be read before assignment.</p>" else ""
    return "<div class='definition'><pre>${escape(target.text.take(120))}: ${escape(type.display())}</pre></div>" +
      "<div class='content'>Inferred statically from this file.$warning</div>"
  }

  private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}
