// SPDX-FileCopyrightText: 2026 intellij-powershell contributors <https://github.com/intellij-powershell/intellij-powershell>
// SPDX-License-Identifier: Apache-2.0

package com.intellij.plugin.powershell.lang.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.plugin.powershell.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import java.util.Locale

/** File-local, flow-sensitive analysis. No script execution, reflection or language server is needed. */
class PowerShellStaticAnalysis private constructor(private val file: PsiFile) {
  data class FunctionInfo(val parameters: List<Pair<String, InferredType>>, val output: InferredType, val declaredOutput: InferredType?)
  private data class Binding(val type: InferredType, val definite: Boolean = true, val constraint: InferredType? = null)
  private class Flow(val variables: MutableMap<String, Binding> = linkedMapOf()) {
    var stopped = false
    fun copy() = Flow(variables.toMutableMap())
  }

  private val types = mutableMapOf<PsiElement, InferredType>()
  val uninitialized = linkedSetOf<PowerShellVariable>()
  private val functions = mutableMapOf<PowerShellFunctionStatement, FunctionInfo>()
  private val activeFunctions = mutableSetOf<PowerShellFunctionStatement>()
  private val declarations = PsiTreeUtil.findChildrenOfType(file, PowerShellFunctionStatement::class.java)
  private val allVariables = PsiTreeUtil.findChildrenOfType(file, PowerShellTargetVariableExpression::class.java)
  private var depth = 0

  fun typeOf(element: PsiElement?): InferredType = types[element] ?: InferredType.UNKNOWN
  fun functionInfo(function: PowerShellFunctionStatement): FunctionInfo? = functions[function]

  fun functionAt(element: PsiElement): PowerShellFunctionStatement? {
    val call = PsiTreeUtil.getParentOfType(element, PowerShellCommandCallExpression::class.java, false)
    return if (call != null) findFunction(call) else PsiTreeUtil.getParentOfType(element, PowerShellFunctionStatement::class.java, false)
  }

  private fun run(): PowerShellStaticAnalysis {
    statements(file, Flow(), mutableListOf())
    declarations.forEach { summarize(it) }
    return this
  }

  private fun statements(container: PsiElement, flow: Flow, output: MutableList<InferredType>) {
    for (child in container.syntaxChildren) {
      ProgressManager.checkCanceled()
      if (flow.stopped) break
      statement(child, flow, output)
    }
  }

  private fun statement(e: PsiElement, flow: Flow, output: MutableList<InferredType>) {
    when (e) {
      is PowerShellFunctionStatement -> summarize(e)
      is PowerShellClassDeclarationStatement, is PowerShellEnumDeclarationStatement -> Unit
      is PowerShellBlockParameterClause, is PowerShellParameterClause -> parameters(e, flow)
      is PowerShellIfStatement -> branches(e, flow, output)
      is PowerShellForeachStatement -> foreach(e, flow, output)
      is PowerShellWhileStatement, is PowerShellForStatement, is PowerShellDoStatement -> loop(e, flow, output)
      is PowerShellTryStatement -> tryStatement(e, flow, output)
      is PowerShellSwitchStatement -> switchStatement(e, flow, output)
      is PowerShellFlowControlStatement -> {
        val keyword = e.firstChild.text.lowercase(Locale.ROOT)
        values(e).forEach {
          val type = expression(it, flow)
          if (keyword == "return") output.add(type)
        }
        flow.stopped = true
      }
      is PowerShellBlockBody -> statements(e, flow, output)
      is PowerShellPipelineTail -> output.add(expression(e, flow))
      is PowerShellExpression -> {
        val type = expression(e, flow)
        if (e !is PowerShellAssignmentExpression && type != InferredType.NONE) output.add(type)
      }
    }
  }

  private fun branches(e: PowerShellIfStatement, flow: Flow, output: MutableList<InferredType>) {
    val remaining = flow.copy()
    val paths = mutableListOf<Flow>()
    for (child in e.syntaxChildren) {
      when (child) {
        is PowerShellExpression, is PowerShellPipelineTail -> expression(child, remaining)
        is PowerShellBlockBody -> paths.add(remaining.copy().also { statements(child, it, output) })
        is PowerShellElseifClause -> {
          values(child).forEach { expression(it, remaining) }
          paths.add(remaining.copy().also { branch -> child.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { statements(it, branch, output) } })
        }
        is PowerShellElseClause -> paths.add(remaining.copy().also { branch ->
          child.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { statements(it, branch, output) }
        })
      }
    }
    if (e.syntaxChildren.none { it is PowerShellElseClause }) paths.add(remaining)
    merge(flow, paths)
  }

  private fun foreach(e: PowerShellForeachStatement, flow: Flow, output: MutableList<InferredType>) {
    val expressions = values(e)
    val variable = expressions.firstOrNull() as? PowerShellVariable
    val input = expressions.drop(1).map { expression(it, flow) }.lastOrNull() ?: InferredType.UNKNOWN
    val body = flow.copy()
    if (variable != null) write(variable, input.itemType(), body)
    val loopOutput = mutableListOf<InferredType>()
    e.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { statements(it, body, loopOutput) }
    output.addAll(loopOutput.map { InferredType.array(it.itemType()) })
    merge(flow, listOf(flow.copy(), body.copy()))
  }

  private fun loop(e: PsiElement, flow: Flow, output: MutableList<InferredType>) {
    val body = flow.copy()
    val loopOutput = mutableListOf<InferredType>()
    // for clauses have initializer / condition / iterator separated by semicolons.
    val clause = e.syntaxChildren.filterIsInstance<PowerShellForClause>().firstOrNull()
    var iterator: PsiElement? = null
    if (clause != null) {
      var section = 0
      for (child in clause.syntaxChildren) {
        if (child.text == ";") section++
        if (child is PowerShellExpression || child is PowerShellPipelineTail) {
          if (section == 2) iterator = child else expression(child, body)
        }
      }
      flow.variables.putAll(body.variables)
    } else if (e !is PowerShellDoStatement) {
      values(e).forEach { expression(it, body) }
    }
    e.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { statements(it, body, loopOutput) }
    if (iterator != null && !body.stopped) expression(iterator, body)
    if (e is PowerShellDoStatement) values(e).forEach { expression(it, body) }
    output.addAll(loopOutput.map { InferredType.array(it.itemType()) })
    // Even a do loop may break before a later assignment in the body.
    merge(flow, listOf(flow.copy(), body.copy()))
  }

  private fun tryStatement(e: PowerShellTryStatement, flow: Flow, output: MutableList<InferredType>) {
    val entry = flow.copy()
    val normal = flow.copy()
    e.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { statements(it, normal, output) }
    val paths = mutableListOf(normal)
    e.syntaxChildren.filterIsInstance<PowerShellCatchClause>().forEach { clause ->
      paths.add(entry.copy().also { branch ->
        clause.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { statements(it, branch, output) }
      })
    }
    merge(flow, paths)
    e.syntaxChildren.filterIsInstance<PowerShellFinallyClause>().forEach { clause ->
      // finally also executes on a return/throw path.
      val stopped = flow.stopped
      flow.stopped = false
      clause.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { statements(it, flow, output) }
      flow.stopped = flow.stopped || stopped
    }
  }

  private fun switchStatement(e: PowerShellSwitchStatement, flow: Flow, output: MutableList<InferredType>) {
    values(e).forEach { expression(it, flow) }
    val paths = mutableListOf(flow.copy()) // no case is guaranteed to match
    e.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { body ->
      for (child in body.syntaxChildren) {
        if (child is PowerShellExpression) expression(child, flow.copy())
        if (child is PowerShellSwitchClauseBlock) {
          val branch = flow.copy()
          val emitted = mutableListOf<InferredType>()
          child.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { statements(it, branch, emitted) }
          paths.add(branch.copy())
          output.addAll(emitted.map { InferredType.array(it.itemType()) })
        }
      }
    }
    merge(flow, paths)
  }

  private fun merge(target: Flow, paths: List<Flow>) {
    val live = paths.filterNot { it.stopped }
    target.stopped = live.isEmpty()
    if (live.isEmpty()) return
    val merged = linkedMapOf<String, Binding>()
    live.flatMap { it.variables.keys }.distinct().forEach { key ->
      val bindings = live.map { it.variables[key] }
      val present = bindings.filterNotNull()
      merged[key] = Binding(InferredType.union(present.map { it.type }), bindings.all { it?.definite == true },
        present.map { it.constraint }.distinct().singleOrNull())
    }
    target.variables.clear()
    target.variables.putAll(merged)
  }

  private fun expression(e: PsiElement, flow: Flow): InferredType {
    ProgressManager.checkCanceled()
    // Malformed or deeply nested editor input must not overflow the stack.
    if (++depth > 100) {
      depth--
      return InferredType.UNKNOWN
    }
    try {
      val children = values(e)
      val type = when (e) {
        is PowerShellVariable -> read(e, flow)
        is PowerShellAssignmentExpression -> assign(e, flow)
        is PowerShellStringLiteralExpression -> {
          children.forEach { expression(it, flow) }
          InferredType.STRING
        }
        is PowerShellIntegerLiteralExpression -> integerType(e.text)
        is PowerShellRealLiteralExpression -> InferredType.named(if (e.text.endsWith("d", true)) "decimal" else "double")
        is PowerShellCastExpression -> {
          val value = children.lastOrNull()?.let { expression(it, flow) } ?: InferredType.UNKNOWN
          val cast = children.filterIsInstance<PowerShellTypeLiteralExpression>().firstOrNull()?.text ?: "unknown"
          val named = InferredType.named(cast)
          if (named.name == "PSCustomObject" || named.name == "hashtable" || named.name.equals("ordered", true))
            InferredType(if (named.name.equals("ordered", true)) "OrderedDictionary" else named.name, value.properties)
          else named
        }
        is PowerShellHashLiteralExpression -> hash(e, flow)
        is PowerShellArrayLiteralExpression -> InferredType.array(InferredType.union(children.map { expression(it, flow) }))
        is PowerShellArrayExpression -> {
          val output = mutableListOf<InferredType>()
          statements(e, flow, output)
          InferredType.array(InferredType.union(output.map { it.itemType() }).takeUnless { it == InferredType.NONE } ?: InferredType.UNKNOWN)
        }
        is PowerShellMemberAccessExpression -> {
          val receiver = children.firstOrNull()?.let { expression(it, flow) } ?: InferredType.UNKNOWN
          children.drop(1).forEach { expression(it, flow) }
          val name = e.referenceIdentifier?.text ?: children.drop(1).singleOrNull()?.let { constantKey(it) }
          when {
            name == null -> InferredType.UNKNOWN
            name.equals("Length", true) && (receiver == InferredType.STRING || receiver.elementType != null) -> InferredType.INT
            name.equals("Count", true) && receiver.elementType != null -> InferredType.INT
            else -> receiver.property(name)
          }
        }
        is PowerShellElementAccessExpression -> {
          val receiver = children.firstOrNull()?.let { expression(it, flow) } ?: InferredType.UNKNOWN
          children.drop(1).forEach { expression(it, flow) }
          val key = children.getOrNull(1)?.let { constantKey(it) }
          if (receiver.elementType != null) receiver.elementType else if (key != null) receiver.property(key) else InferredType.UNKNOWN
        }
        is PowerShellCommandCallExpression -> command(e, flow)
        is PowerShellInvocationExpression -> {
          e.syntaxChildren.filterIsInstance<PowerShellPsiElement>().forEach { expression(it, flow) }
          flow.variables.replaceAll { _, binding -> binding.copy(type = eraseShape(binding.type)) }
          InferredType.UNKNOWN
        }
        is PowerShellScriptBlockExpression -> {
          e.syntaxChildren.filterIsInstance<PowerShellBlockBody>().forEach { statements(it, flow.copy(), mutableListOf()) }
          InferredType("scriptblock")
        }
        is PowerShellParenthesizedExpression -> children.singleOrNull()?.let { expression(it, flow) } ?: InferredType.UNKNOWN
        is PowerShellSubExpression -> {
          val output = mutableListOf<InferredType>()
          statements(e, flow, output)
          capturedOutput(output)
        }
        is PowerShellRangeExpression -> { children.forEach { expression(it, flow) }; InferredType.array(InferredType.INT) }
        is PowerShellLogicalExpression -> {
          children.firstOrNull()?.let { expression(it, flow) }
          val conditional = flow.copy()
          children.drop(1).forEach { expression(it, conditional) }
          merge(flow, listOf(flow.copy(), conditional))
          InferredType.BOOL
        }
        is PowerShellComparisonExpression -> {
          val operands = children.map { expression(it, flow) }
          // Collection comparisons can filter the input rather than return bool.
          val operator = e.syntaxChildren.firstOrNull { it.node.elementType == PowerShellTypes.OP_C }?.text?.lowercase(Locale.ROOT)
          when {
            operator?.endsWith("replace") == true -> InferredType.STRING
            operator?.endsWith("split") == true -> InferredType.array(InferredType.STRING)
            operator == "-as" -> InferredType.UNKNOWN
            operands.firstOrNull()?.elementType != null -> InferredType.UNKNOWN
            else -> InferredType.BOOL
          }
        }
        is PowerShellFormatExpression -> { children.forEach { expression(it, flow) }; InferredType.STRING }
        is PowerShellAdditiveExpression, is PowerShellMultiplicativeExpression -> {
          val operands = children.map { expression(it, flow) }
          when {
            operands.firstOrNull() == InferredType.STRING -> InferredType.STRING
            operands.isNotEmpty() && operands.all { it == InferredType.INT } && e.syntaxChildren.none { it.text == "/" } -> InferredType.INT
            else -> InferredType.UNKNOWN
          }
        }
        is PowerShellUnaryExpression, is PowerShellPostIncrementExpression, is PowerShellPostDecrementExpression -> {
          val operand = children.firstOrNull()?.let { expression(it, flow) } ?: InferredType.UNKNOWN
          if (e.syntaxChildren.any { it.node.elementType == PowerShellTypes.PP || it.node.elementType == PowerShellTypes.MM }) {
            (children.firstOrNull() as? PowerShellVariable)?.let { write(it, operand, flow) }
          }
          when (e.firstChild.text.lowercase(Locale.ROOT)) {
            "!", "-not" -> InferredType.BOOL
            "," -> InferredType.array(operand)
            "-join" -> InferredType.STRING
            "-split" -> InferredType.array(InferredType.STRING)
            else -> operand
          }
        }
        is PowerShellNullCoalesceExpression -> {
          val first = children.firstOrNull()?.let { expression(it, flow) } ?: InferredType.UNKNOWN
          val conditional = flow.copy()
          val rest = children.drop(1).map { expression(it, conditional) }
          merge(flow, listOf(flow.copy(), conditional))
          InferredType.union((listOf(first) + rest).filter { it != InferredType.NULL })
        }
        is PowerShellTypeLiteralExpression -> InferredType("type")
        else -> {
          // Traverse wrappers, pipelines, method arguments and unsupported expressions for reads.
          e.syntaxChildren.forEach { child ->
            if (child is PowerShellExpression) expression(child, flow)
            else if (child is PowerShellPsiElement) expression(child, flow)
          }
          InferredType.UNKNOWN
        }
      }
      types[e] = type
      return type
    } finally {
      depth--
    }
  }

  private fun assign(e: PowerShellAssignmentExpression, flow: Flow): InferredType {
    val children = e.syntaxChildren.filterIsInstance<PowerShellExpression>()
    val lhs = children.firstOrNull() ?: return InferredType.UNKNOWN
    val rhs = e.syntaxChildren.lastOrNull { it is PowerShellPsiElement && it !== lhs }
    val value = rhs?.let {
      if (it is PowerShellExpression || it is PowerShellPipelineTail) expression(it, flow)
      else mutableListOf<InferredType>().let { output -> statement(it, flow, output); capturedOutput(output) }
    } ?: InferredType.UNKNOWN
    val compound = e.syntaxChildren.any { it.node.elementType in setOf(PowerShellTypes.EQ_PLUS, PowerShellTypes.EQ_DASH,
      PowerShellTypes.EQ_STAR, PowerShellTypes.EQ_DIV, PowerShellTypes.EQ_PERS) }
    if (compound) expression(lhs, flow)
    fun target(element: PsiElement, type: InferredType) {
      when (element) {
        is PowerShellVariable -> write(element, if (compound) InferredType.UNKNOWN else type, flow)
        is PowerShellCastExpression -> {
          val variable = element.syntaxChildren.filterIsInstance<PowerShellVariable>().lastOrNull()
          val constraint = element.syntaxChildren.filterIsInstance<PowerShellTypeLiteralExpression>().firstOrNull()?.text?.let { InferredType.named(it) }
          if (variable != null) write(variable, constraint ?: type, flow, constraint)
        }
        is PowerShellArrayLiteralExpression -> element.syntaxChildren.filterIsInstance<PowerShellExpression>().forEach { target(it, type.itemType()) }
        else -> {
          expression(element, flow)
          // Mutation can affect aliases, including nested objects: discard previously inferred shapes.
          flow.variables.replaceAll { _, binding -> binding.copy(type = eraseShape(binding.type)) }
        }
      }
      if (element !is PowerShellVariable) types[element] = type
    }
    target(lhs, value)
    return value
  }

  private fun eraseShape(type: InferredType): InferredType = type.copy(
    properties = emptyMap(), elementType = type.elementType?.let { eraseShape(it) }, alternatives = type.alternatives.map { eraseShape(it) }.toSet())

  private fun hash(e: PowerShellHashLiteralExpression, flow: Flow): InferredType {
    val body = e.syntaxChildren.filterIsInstance<PowerShellBlockBody>().firstOrNull() ?: return InferredType("hashtable")
    val properties = linkedMapOf<String, InferredType>()
    var key: String? = null
    var expectingKey = true
    for (child in body.syntaxChildren) {
      if (child !is PowerShellPsiElement || child is PowerShellComment) continue
      // The parser inlines literal keys, while bare names have a KeyExpression wrapper.
      if (expectingKey) {
        key = constantKey(child)
        if (key == null) expression(child, flow)
        expectingKey = false
      } else {
        val value = if (child is PowerShellExpression || child is PowerShellPipelineTail) expression(child, flow)
          else mutableListOf<InferredType>().let { output -> statement(child, flow, output); capturedOutput(output) }
        key?.let { name ->
          properties.keys.firstOrNull { it.equals(name, true) }?.let { properties.remove(it) }
          properties[name] = value
        }
        key = null
        expectingKey = true
      }
    }
    return InferredType("hashtable", properties)
  }

  private fun constantKey(e: PsiElement): String? {
    if (e is PowerShellKeyExpression) return e.syntaxChildren.firstOrNull { it is PowerShellPsiElement }?.let { constantKey(it) }
    if (e is PowerShellIdentifier || e is PowerShellReferenceIdentifier) return e.text
    if (e is PowerShellStringLiteralExpression && e.syntaxChildren.none { it is PowerShellExpression }) return e.getStringContent()
    return null
  }

  private fun read(variable: PowerShellVariable, flow: Flow): InferredType {
    val key = variableKey(variable)
    val builtin = builtinType(key)
    val binding = flow.variables[key]
    if (binding?.definite != true && builtin == null && !external(variable)) uninitialized.add(variable)
    return binding?.type ?: builtin ?: InferredType.UNKNOWN
  }

  private fun write(variable: PowerShellVariable, type: InferredType, flow: Flow, constraint: InferredType? = null) {
    val key = variableKey(variable)
    val declared = constraint ?: flow.variables[key]?.constraint
    val actual = declared ?: type.takeUnless { it == InferredType.NONE } ?: InferredType.NULL
    flow.variables[key] = Binding(actual, constraint = declared)
    types[variable] = actual
  }

  private fun parameters(clause: PsiElement, flow: Flow): List<Pair<String, InferredType>> {
    val result = mutableListOf<Pair<String, InferredType>>()
    var declared: InferredType? = null
    var expectingDefault = false
    for (child in clause.syntaxChildren) {
      when {
        child.text == "," -> { declared = null; expectingDefault = false }
        child is PowerShellAttribute -> {
          child.syntaxChildren.filterIsInstance<PowerShellTypeLiteralExpression>().firstOrNull()?.let { declared = InferredType.named(it.text) }
        }
        child is PowerShellVariable && !expectingDefault -> {
          write(child, declared ?: InferredType.UNKNOWN, flow, declared)
          result.add(child.text to (declared ?: InferredType.UNKNOWN))
          expectingDefault = true
        }
        child is PowerShellExpression -> expression(child, flow)
      }
    }
    return result
  }

  private fun command(e: PowerShellCommandCallExpression, flow: Flow): InferredType {
    e.syntaxChildren.filter { it !is PowerShellCommandName && it is PowerShellPsiElement }.forEach { expression(it, flow) }
    val function = findFunction(e)
    if (function != null) return summarize(function).output
    // External commands/cmdlets may be shadowed, so do not guess their result from the command name.
    return InferredType.UNKNOWN
  }

  private fun findFunction(call: PowerShellCommandCallExpression): PowerShellFunctionStatement? {
    val name = call.syntaxChildren.filterIsInstance<PowerShellCommandName>().firstOrNull()?.text ?: return null
    var scope: PsiElement? = scopeOf(call)
    while (scope != null) {
      val owner = scope
      val matches = declarations.filter { it.name.equals(name, true) && scopeOf(it.parent) == owner }
      if (matches.size == 1) return matches.single()
      scope = if (scope == file) null else scopeOf(scope.parent)
    }
    return null
  }

  private fun summarize(function: PowerShellFunctionStatement): FunctionInfo {
    functions[function]?.let { return it }
    if (function in activeFunctions || activeFunctions.size >= 20) return FunctionInfo(emptyList(), InferredType.UNKNOWN, null)
    activeFunctions.add(function)
    try {
      val flow = Flow()
      // Functions can be called after later outer assignments. Captures are known names, but their
      // runtime types and call order are not assumed. Sibling function locals never become captures.
      allVariables.filter { variable ->
        val owner = scopeOf(variable)
        owner != function && PsiTreeUtil.isAncestor(owner, function, true) && isDefinition(variable) && !variable.text.startsWith("\$private:", true)
      }.forEach { flow.variables[variableKey(it)] = Binding(InferredType.UNKNOWN) }
      val parameters = mutableListOf<Pair<String, InferredType>>()
      function.syntaxChildren.filterIsInstance<PowerShellParameterClause>().forEach { parameters.addAll(parameters(it, flow)) }
      val body = function.syntaxChildren.filterIsInstance<PowerShellBlockBody>().firstOrNull()
      body?.syntaxChildren?.filterIsInstance<PowerShellBlockParameterClause>()?.forEach { parameters.addAll(parameters(it, flow)) }
      val output = mutableListOf<InferredType>()
      if (body != null) statements(body, flow, output)
      val attributes = body?.syntaxChildren.orEmpty().filterIsInstance<PowerShellAttribute>() +
        body?.syntaxChildren.orEmpty().filterIsInstance<PowerShellBlockParameterClause>().flatMap { it.syntaxChildren.filterIsInstance<PowerShellAttribute>() }
      val declared = attributes.firstOrNull { it.text.startsWith("[OutputType(", true) }?.let { attribute ->
        InferredType.union(PsiTreeUtil.findChildrenOfType(attribute, PowerShellTypeLiteralExpression::class.java).map { InferredType.named(it.text) })
      }?.takeUnless { it == InferredType.NONE }
      return FunctionInfo(parameters, capturedOutput(output), declared).also { functions[function] = it }
    } finally {
      activeFunctions.remove(function)
    }
  }

  private fun capturedOutput(output: List<InferredType>): InferredType {
    if (output.isEmpty()) return InferredType.NONE
    val item = InferredType.union(output.map { it.itemType() })
    return if (output.size == 1 && output.single().elementType == null) item else InferredType.union(listOf(item, InferredType.array(item)))
  }

  private fun scopeOf(e: PsiElement): PsiElement = PsiTreeUtil.getParentOfType(e,
    PowerShellFunctionStatement::class.java, PowerShellScriptBlockExpression::class.java) ?: file

  private fun values(e: PsiElement): List<PsiElement> = e.syntaxChildren.filter { it is PowerShellExpression || it is PowerShellPipelineTail }

  private fun isDefinition(variable: PowerShellVariable): Boolean {
    val parent = variable.parent
    return parent is PowerShellParameterClause || parent is PowerShellBlockParameterClause ||
      (parent is PowerShellAssignmentExpression && parent.firstChild == variable) ||
      (parent is PowerShellCastExpression && parent.parent is PowerShellAssignmentExpression && parent.parent.firstChild == parent)
  }

  // PsiElement.children omits leaf tokens (operators and parameter separators).
  private val PsiElement.syntaxChildren: List<PsiElement>
    get() = generateSequence(firstChild) { it.nextSibling }.toList()

  companion object {
    fun forFile(file: PsiFile): PowerShellStaticAnalysis = CachedValuesManager.getCachedValue(file) {
      CachedValueProvider.Result.create(PowerShellStaticAnalysis(file).run(), file)
    }

    private fun variableKey(variable: PowerShellVariable): String {
      val text = variable.text.removePrefix("$").removePrefix("@").removeSurrounding("{", "}").lowercase(Locale.ROOT)
      return text.removePrefix("local:").removePrefix("private:")
    }

    private fun external(variable: PowerShellVariable): Boolean {
      val text = variableKey(variable)
      return ':' in text // Explicit external scopes, providers and using: are supplied by the runtime.
    }

    private fun integerType(text: String): InferredType {
      val value = text.lowercase(Locale.ROOT)
      if (value.endsWith("l")) return InferredType.named("long")
      if (value.endsWith("d")) return InferredType.named("decimal")
      val parsed = if (value.startsWith("0x")) value.drop(2).toLongOrNull(16) else value.toLongOrNull()
      return when {
        parsed == null -> InferredType.UNKNOWN
        parsed in Int.MIN_VALUE..Int.MAX_VALUE -> InferredType.INT
        else -> InferredType.named("long")
      }
    }

    private val automatic = ("_ psitem args error event eventargs eventsubscriber executioncontext false foreach home host input " +
      "iscoreclr islinux ismacos iswindows lastexitcode matches myinvocation nestedpromptlevel null pid profile pscmdlet " +
      "psboundparameters pscommandpath psculture psdebugcontext psedition pshome psscriptroot pssenderinfo psuiculture " +
      "psversiontable pwd sender shellid stacktrace switch this true ? ^ $ consolefilename enabledexperimentalfeatures " +
      "psstyle erroractionpreference warningpreference informationpreference verbosepreference debugpreference " +
      "progresspreference confirmpreference whatifpreference maximumhistorycount ofS outputencoding formatEnumerationLimit " +
      "psdefaultparametervalues psmoduleautoloadingpreference psnativecommandargumentpassing psnativecommanduseerroractionpreference " +
      "pssessionapplicationname pssessionconfigurationname pssessionoption psnativecommandargumentpassing psloguserdatapreference " +
      "psmailserver pscommandnotfoundaction logcommandhealthevent logcommandlifecycleevent logenginehealthevent " +
      "logenginelifecycleevent logproviderhealthevent logproviderlifecycleevent logsettingsEvent").lowercase(Locale.ROOT).split(' ').toSet()

    private fun builtinType(key: String): InferredType? = when (key) {
      "true", "false", "?", "iswindows", "islinux", "ismacos", "iscoreclr" -> InferredType.BOOL
      "null" -> InferredType.NULL
      "pid", "lastexitcode" -> InferredType.INT
      "psscriptroot", "pscommandpath", "home", "pshome" -> InferredType.STRING
      in automatic -> InferredType.UNKNOWN
      else -> null
    }
  }
}
