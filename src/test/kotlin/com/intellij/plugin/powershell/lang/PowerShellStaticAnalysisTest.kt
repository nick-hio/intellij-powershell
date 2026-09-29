// SPDX-FileCopyrightText: 2026 intellij-powershell contributors <https://github.com/intellij-powershell/intellij-powershell>
// SPDX-License-Identifier: Apache-2.0

package com.intellij.plugin.powershell.lang

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.plugin.powershell.ide.editor.documentation.PowerShellDocumentationProvider
import com.intellij.plugin.powershell.ide.inspection.PowerShellUninitializedVariableInspection
import com.intellij.plugin.powershell.lang.analysis.InferredType
import com.intellij.plugin.powershell.lang.analysis.PowerShellStaticAnalysis
import com.intellij.plugin.powershell.lang.lsp.PowerShellSettings
import com.intellij.plugin.powershell.psi.*
import com.intellij.plugin.powershell.testFramework.PowerShellCodeInsightTestBase
import com.intellij.plugin.powershell.testFramework.RunInEdt
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

@TestApplication
@RunInEdt
class PowerShellStaticAnalysisTest : PowerShellCodeInsightTestBase() {
  private var languageServerEnabled = true

  @BeforeEach
  fun disableLanguageServer() {
    val settings = PowerShellSettings.getInstance().state
    languageServerEnabled = settings.isUseLanguageServer
    settings.isUseLanguageServer = false
  }

  @AfterEach
  fun restoreLanguageServer() {
    PowerShellSettings.getInstance().state.isUseLanguageServer = languageServerEnabled
  }

  private fun configure(script: String): PsiFile {
    val file = codeInsightTestFixture.configureByText("analysis.ps1", script.trimIndent().replace('§', '$'))
    assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java), "Unexpected parse error in ${file.text}")
    return file
  }

  private fun warnings(script: String): List<String> {
    val file = configure(script)
    return PowerShellStaticAnalysis.forFile(file).uninitialized.map { it.text }
  }

  private fun lastType(script: String): InferredType {
    val file = configure(script)
    val variable = PsiTreeUtil.findChildrenOfType(file, PowerShellVariable::class.java).last()
    return PowerShellStaticAnalysis.forFile(file).typeOf(variable)
  }

  @Test
  fun `user example warns inside return with no language server`() {
    codeInsightTestFixture.enableInspections(PowerShellUninitializedVariableInspection())
    configure("""
      function Get-VariableExample {
          param()
          return <warning descr="Variable '§NonInitializedVariable' may be read before assignment">§NonInitializedVariable</warning>
      }
    """)
    codeInsightTestFixture.checkHighlighting(true, false, false)
  }

  @Test
  fun `later assignment does not initialize earlier read`() {
    assertEquals(listOf("\$value"), warnings("§value; §value = 1; §value"))
    assertEquals(listOf("\$value"), warnings("§value = §value + 1; §value"))
  }

  @Test
  fun `branch-only assignment remains uncertain`() {
    assertEquals(listOf("\$value"), warnings("if (§true) { §value = 1 }; §value"))
    assertEquals(emptyList(), warnings("if (§true) { §value = 1 } else { §value = 'a' }; §value"))
    assertEquals(setOf("int", "string"), lastType("if (§true) { §value = 1 } else { §value = 'a' }; §value").alternatives.map { it.name }.toSet())
  }

  @Test
  fun `terminating branch does not weaken surviving assignments`() {
    assertEquals(emptyList(), warnings("function Test { if (§true) { return }; §x = 1; §x }"))
    assertEquals(emptyList(), warnings("function Test { if (§true) { §x = 1 } else { return }; §x }"))
  }

  @Test
  fun `elseif and loop bodies preserve definite assignment rules`() {
    assertEquals(emptyList(), warnings("if (§true) { §x = 1 } elseif (§false) { §x = 2 } else { §x = 3 }; §x"))
    assertEquals(listOf("\$x"), warnings("while (§false) { §x = 1 }; §x"))
    assertEquals(listOf("\$item", "\$x"), warnings("foreach (§item in 1,2) { §item; §x = 1 }; §item; §x"))
    assertEquals(emptyList(), warnings("for (§i = 0; §i -lt 2; §i++) { §i }; §i"))
  }

  @Test
  fun `parameters and builtins are initialized`() {
    assertEquals(emptyList(), warnings("""
      param([string]§Name, [int]§Count = 1)
      §name; §COUNT; §null; §true; §false; §_; §PSItem; §args; §PSScriptRoot; §env:PATH; §global:Shared
      function Test([int]§value) { §value }
    """))
    assertEquals(InferredType.INT, lastType("function Test { param([int]§count); §count }"))
  }

  @Test
  fun `function locals and scriptblock locals do not leak`() {
    assertEquals(listOf("\$secret"), warnings("function Test { §secret = 1 }; §secret"))
    assertEquals(listOf("\$secret"), warnings("§block = { §secret = 1 }; §secret"))
    assertEquals(listOf("\$secret"), warnings("function One { §secret = 1 }; function Two { §secret }"))
    assertEquals(emptyList(), warnings("function Test { §outer }; §outer = 1"))
  }

  @Test
  fun `case-insensitive and braced variables share assignments`() {
    assertEquals(emptyList(), warnings("§Name = 1; §name; §{NAME}; §local:Name"))
    assertEquals(InferredType.INT, lastType("§{my value} = 1; §{MY VALUE}"))
  }

  @Test
  fun `compound updates and property writes read their targets`() {
    assertEquals(listOf("\$count"), warnings("§count += 1"))
    assertEquals(listOf("\$count"), warnings("§count++"))
    assertEquals(listOf("\$object"), warnings("§object.Name = 'a'"))
    assertEquals(emptyList(), warnings("§count = 0; §count += 1; §count++"))
  }

  @Test
  fun `interpolated strings and command arguments are checked`() {
    assertEquals(listOf("\$missing", "\$other"), warnings("Write-Output \"hello §missing\" §other"))
    assertEquals(emptyList(), warnings("'§notAVariable' # §alsoNotAVariable"))
  }

  @Test
  fun `pipeline reads are checked at top level in returns and in conditions`() {
    assertEquals(listOf("\$missing"), warnings("Write-Output §missing | Out-Null"))
    assertEquals(listOf("\$missing"), warnings("function Test { return Write-Output §missing | Out-String }"))
    assertEquals(listOf("\$missing"), warnings("if (Write-Output §missing | Out-String) { }"))
  }

  @Test
  fun `switch branches do not leak assignments into other branches`() {
    assertEquals(listOf("\$missing", "\$x", "\$x"), warnings("switch (1) { 1 { §missing; §x = 1 } 2 { §x } }; §x"))
  }

  @Test
  fun `short circuit assignments are conditional`() {
    assertEquals(listOf("\$x"), warnings("§false -and (§x = 1); §x"))
    assertEquals(listOf("\$x"), warnings("1 ?? (§x = 1); §x"))
  }

  @Test
  fun `try catch and finally have separate paths`() {
    assertEquals(listOf("\$x"), warnings("try { §x = 1 } catch { }; §x"))
    assertEquals(emptyList(), warnings("try { §x = 1 } catch { §x = 2 }; §x"))
    assertEquals(emptyList(), warnings("try { } finally { §x = 1 }; §x"))
  }

  @Test
  fun `types follow reassignment and persistent type constraints`() {
    assertEquals(InferredType.STRING, lastType("§value = 1; §value = 'text'; §value"))
    assertEquals(InferredType.INT, lastType("[int]§value = 1; §value = '2'; §value"))
    assertEquals(InferredType.BOOL, lastType("§value = §true; §value"))
    assertEquals(InferredType.array(InferredType.INT), lastType("§value = 1,2,3; §value"))
  }

  @Test
  fun `nested object shapes and property access are inferred`() {
    val script = "§user = [pscustomobject]@{ Name = 'Ada'; Age = 37; Address = @{ City = 'London' } }; §user"
    val type = lastType(script)
    assertEquals("PSCustomObject", type.name)
    assertEquals(InferredType.STRING, type.property("name"))
    assertEquals(InferredType.INT, type.property("AGE"))
    assertEquals(InferredType.STRING, type.property("Address").property("city"))
    assertEquals(InferredType.STRING, lastType("$script; §city = §user.Address.City; §city"))
    assertEquals(InferredType.STRING, lastType("$script; §name = §user['Name']; §name"))
  }

  @Test
  fun `array wrappers and foreach infer element shapes`() {
    assertEquals(InferredType.array(InferredType.INT), lastType("§values = @(1; 2); §values"))
    assertEquals(InferredType.STRING, lastType("§users = @([pscustomobject]@{ Name = 'Ada' }); foreach (§user in §users) { §name = §user.Name; §name }"))
  }

  @Test
  fun `arrays expose enumerated object properties`() {
    val type = lastType("§users = @([pscustomobject]@{ Name = 'Ada' }); §users")
    assertEquals(InferredType.array(InferredType.STRING), type.property("Name"))
  }

  @Test
  fun `function scopes select the nearest local declaration`() {
    assertEquals(InferredType.STRING, lastType("function Get-Value { 1 }; function Test { function Get-Value { 'a' }; §v = Get-Value; §v }"))
  }

  @Test
  fun `for iterator runs after the first body and method calls invalidate shapes`() {
    assertEquals(emptyList(), warnings("for (§i = 0; §i -lt 2; §i += §step) { §step = 1 }"))
    assertTrue(lastType("§h = @{Name = 'Ada'}; §h.Remove('Name'); §h").properties.isEmpty())
  }

  @Test
  fun `quoted and computed hash keys are handled independently`() {
    val type = lastType("§key = 'Dynamic'; §h = @{ 'First Name' = 'Ada'; §key = 42; Other = §true }; §h")
    assertEquals(InferredType.STRING, type.property("First Name"))
    assertEquals(InferredType.BOOL, type.property("Other"))
    assertEquals(InferredType.UNKNOWN, type.property("Dynamic"))
  }

  @Test
  fun `editing an earlier assignment removes a later warning`() {
    codeInsightTestFixture.enableInspections(PowerShellUninitializedVariableInspection())
    val file = configure("function Test { return §missing }")
    assertEquals(1, PowerShellStaticAnalysis.forFile(file).uninitialized.size)
    WriteCommandAction.runWriteCommandAction(project) {
      codeInsightTestFixture.editor.document.setText("function Test { \$missing = 1; return \$missing }")
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
    assertTrue(PowerShellStaticAnalysis.forFile(file).uninitialized.isEmpty())
    codeInsightTestFixture.checkHighlighting(true, false, false)
  }

  @Test
  fun `local functions propagate explicit and implicit output`() {
    assertEquals(InferredType.INT, lastType("function Get-Value { return 42 }; §v = Get-Value; §v"))
    val type = lastType("function Get-User { [pscustomobject]@{ Name = 'Ada' } }; §v = Get-User; §v")
    assertEquals(InferredType.STRING, type.property("Name"))
    val mixed = lastType("function Get-Value { 'message'; return 42 }; §v = Get-Value; §v")
    assertTrue(mixed.display().contains("string"))
    assertTrue(mixed.display().contains("int"))
    assertTrue(mixed.display().contains("[]"))
  }

  @Test
  fun `recursive functions and unknown commands terminate conservatively`() {
    assertEquals(InferredType.UNKNOWN, lastType("function Get-Value { Get-Value }; §v = Get-Value; §v"))
    assertEquals(InferredType.UNKNOWN, lastType("§v = Get-SomethingExternal; §v"))
    assertEquals(InferredType.UNKNOWN, lastType("§v = Get-Process | Select-Object Name; §v"))
  }

  @Test
  fun `shape mutation invalidates aliases`() {
    assertTrue(lastType("§a = @{Name = 'Ada'}; §b = §a; §a.Name = 1; §b").properties.isEmpty())
  }

  @Test
  fun `quick documentation renders shape at the read site and escapes html`() {
    val file = configure("§v = @{ '<Name>' = '<Ada>' }; §v<caret>")
    val variable = PsiTreeUtil.findChildrenOfType(file, PowerShellVariable::class.java).last()
    val doc = PowerShellDocumentationProvider().generateDoc(variable, variable)!!
    assertContains(doc, "&lt;Name&gt;: string")
    assertFalse(doc.contains("<Name>"))
  }

  @Test
  fun `function documentation distinguishes declared output metadata`() {
    val file = configure("function Get-Value { [OutputType([string])] param([int]§id); return §id }")
    val function = PsiTreeUtil.findChildOfType(file, PowerShellFunctionStatement::class.java)!!
    val doc = PowerShellDocumentationProvider().generateDoc(function, function)!!
    assertContains(doc, "§id: int".replace('§', '$'))
    assertContains(doc, "Inferred output: <code>int</code>")
    assertContains(doc, "Declared OutputType (metadata): <code>string</code>")
  }

  @Test
  fun `member completion works without the language server`() {
    codeInsightTestFixture.configureByText("completion.ps1", "§user = [pscustomobject]@{ Name = 'Ada'; Age = 37 }; §user.<caret>".replace('§', '$'))
    codeInsightTestFixture.complete(CompletionType.BASIC)
    val items = codeInsightTestFixture.lookupElementStrings.orEmpty()
    assertContains(items, "Name")
    assertContains(items, "Age")
  }

  @Test
  fun `analysis is invalidated after editing`() {
    val file = configure("§value = 1; §value")
    val before = PowerShellStaticAnalysis.forFile(file)
    assertSame(before, PowerShellStaticAnalysis.forFile(file))
    WriteCommandAction.runWriteCommandAction(project) {
      codeInsightTestFixture.editor.document.setText("\$value = 'new'; \$value")
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
    val after = PowerShellStaticAnalysis.forFile(file)
    assertNotSame(before, after)
    assertEquals(InferredType.STRING, after.typeOf(PsiTreeUtil.findChildrenOfType(file, PowerShellVariable::class.java).last()))
  }

}
