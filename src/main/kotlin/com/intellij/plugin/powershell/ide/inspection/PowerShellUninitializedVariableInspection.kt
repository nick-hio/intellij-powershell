// SPDX-FileCopyrightText: 2026 intellij-powershell contributors <https://github.com/intellij-powershell/intellij-powershell>
// SPDX-License-Identifier: Apache-2.0

package com.intellij.plugin.powershell.ide.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbAware
import com.intellij.plugin.powershell.lang.analysis.PowerShellStaticAnalysis
import com.intellij.plugin.powershell.psi.PowerShellTargetVariableExpression
import com.intellij.plugin.powershell.psi.PowerShellVisitor
import com.intellij.psi.PsiElementVisitor

class PowerShellUninitializedVariableInspection : LocalInspectionTool(), DumbAware {
  override fun runForWholeFile(): Boolean = true

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor = object : PowerShellVisitor() {
    override fun visitTargetVariableExpression(element: PowerShellTargetVariableExpression) {
      if (element in PowerShellStaticAnalysis.forFile(holder.file).uninitialized) {
        holder.registerProblem(element, "Variable '${element.text}' may be read before assignment")
      }
    }
  }
}
