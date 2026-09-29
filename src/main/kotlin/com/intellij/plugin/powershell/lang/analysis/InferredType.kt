// SPDX-FileCopyrightText: 2026 intellij-powershell contributors <https://github.com/intellij-powershell/intellij-powershell>
// SPDX-License-Identifier: Apache-2.0

package com.intellij.plugin.powershell.lang.analysis

/** A static description, never a promise about values supplied by a running PowerShell session. */
data class InferredType(
  val name: String,
  val properties: Map<String, InferredType> = emptyMap(),
  val elementType: InferredType? = null,
  val alternatives: Set<InferredType> = emptySet()
) {
  fun display(depth: Int = 0): String {
    if (depth > 4) return name
    if (alternatives.isNotEmpty()) return alternatives.joinToString(" | ") { it.display(depth + 1) }
    if (elementType != null) {
      val item = elementType.display(depth + 1)
      return (if (elementType.alternatives.isEmpty()) item else "($item)") + "[]"
    }
    if (properties.isEmpty()) return name
    return "$name { " + properties.entries.take(20).joinToString("; ") { (key, value) ->
      "$key: ${value.display(depth + 1)}"
    } + (if (properties.size > 20) "; …" else "") + " }"
  }

  fun property(name: String): InferredType = members().entries.firstOrNull { it.key.equals(name, true) }?.value ?: UNKNOWN

  /** Only suggest members available on every alternative. */
  fun members(): Map<String, InferredType> {
    if (elementType != null) return elementType.members().mapValues { (_, type) -> array(type) }
    if (alternatives.isEmpty()) return properties
    val first = alternatives.first().members()
    return first.mapNotNull { (key, _) ->
      val matches = alternatives.map { type -> type.members().entries.firstOrNull { it.key.equals(key, true) }?.value }
      if (matches.any { it == null }) null else key to union(matches.filterNotNull())
    }.toMap()
  }

  fun itemType(): InferredType = when {
    elementType != null -> elementType
    alternatives.isNotEmpty() -> union(alternatives.map { it.itemType() })
    else -> this
  }

  companion object {
    val UNKNOWN = InferredType("unknown")
    val NONE = InferredType("no output")
    val NULL = InferredType("null")
    val STRING = InferredType("string")
    val INT = InferredType("int")
    val BOOL = InferredType("bool")

    fun array(item: InferredType) = InferredType("array", elementType = item)

    fun union(types: Iterable<InferredType>): InferredType {
      val all = types.flatMap { it.alternatives.ifEmpty { setOf(it) } }.filter { it != NONE }.toSet()
      return when {
        all.isEmpty() -> NONE
        UNKNOWN in all || all.size > 8 -> UNKNOWN
        all.size == 1 -> all.first()
        else -> InferredType("union", alternatives = all)
      }
    }

    fun named(text: String): InferredType {
      val name = text.trim().removePrefix("[").removeSuffix("]").trim()
      if (name.endsWith("[]")) return array(named(name.dropLast(2)))
      return when (name.lowercase()) {
        "system.string", "string" -> STRING
        "system.int32", "int32", "int" -> INT
        "system.int64", "int64", "long" -> InferredType("long")
        "system.boolean", "boolean", "bool", "switch" -> BOOL
        "system.double", "double" -> InferredType("double")
        "system.decimal", "decimal" -> InferredType("decimal")
        "system.management.automation.pscustomobject", "pscustomobject" -> InferredType("PSCustomObject")
        "system.collections.hashtable", "hashtable" -> InferredType("hashtable")
        "system.object[]", "array" -> array(UNKNOWN)
        "void", "system.void" -> NONE
        else -> InferredType(name)
      }
    }
  }
}
