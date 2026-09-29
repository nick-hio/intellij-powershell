<!-- SPDX-FileCopyrightText: 2026 intellij-powershell contributors <https://github.com/intellij-powershell/intellij-powershell>
     SPDX-License-Identifier: Apache-2.0 -->

# Type Insight fork

Based on [intellij-powershell](https://github.com/intellij-powershell/intellij-powershell), upstream
commit `f168cfd`. This is an experimental fork for WebStorm 2026.2.3 on macOS and other IDEs on
IntelliJ Platform 262. The platform-independent ZIP works on Intel and Apple Silicon Macs.
Native macOS execution/debugging still uses your installed PowerShell.

## Install in WebStorm

1. Download a successful **Type Insight test build** workflow's `PowerShell-Type-Insight` artifact
   from this fork's Actions tab. Extract the downloaded artifact once to get the plugin ZIP inside.
   For a local build, use `build/distributions/intellij-powershell-2.12.0-type-insight.1.zip` directly.
2. Open **Settings → Plugins → gear menu → Install Plugin from Disk**.
3. Select the plugin ZIP (keep that ZIP compressed), accept replacement of the existing PowerShell
   plugin if prompted, and restart WebStorm.
4. Check that the installed plugin is **PowerShell (Type Insight Fork)**, version
   `2.12.0-type-insight.1`.

The fork keeps plugin ID `com.intellij.plugin.adernov.powershell`. It replaces the original
installation so two plugins cannot register competing PowerShell parsers and editor extensions.
Disable automatic updates for this plugin while testing, since a Marketplace update can replace
the fork. To roll back, uninstall the fork and reinstall **PowerShell** by Andrey Dernov from
Marketplace, then restart. The fork uses the original settings format.

JetBrains documents [installation from disk](https://www.jetbrains.com/help/webstorm/managing-plugins.html#install_plugin_from_disk).

## Try it

Open [examples/type-insight.ps1](../examples/type-insight.ps1). Nothing needs to be executed.

- Put the caret on a variable, value, member access or local function name and use
  **View → Quick Documentation** (use Find Action if needed). Hover documentation uses the same provider.
- Inferred shapes include nested properties, for example
  `PSCustomObject { Name: string; Age: int; Address: hashtable { City: string } }`.
- After `$user.` invoke completion to see known properties and their inferred types. Nested
  objects and local function results participate. Property names requiring quotes are quoted
  when inserted.
- Variables read before assignment receive a yellow warning, including the original case:

  ```powershell
  function Get-VariableExample {
      param()
      return $NonInitializedVariable # warning: may be read before assignment
  }
  ```

Configure the warning under **Settings → Editor → Inspections → PowerShell →
Variable may be read before assignment**. It is separate from PSScriptAnalyzer.
The new features also work with the PowerShell language server disabled in PowerShell settings.

## Coverage

- Strings, common numeric literals, booleans, null, explicit casts, typed parameters,
  typed assignments and arrays.
- Hashtable and `PSCustomObject` properties with constant names, nested shapes, aliases and
  property/index reads. Assignments update the inferred type at subsequent read sites.
- Local function parameter types and both explicit `return` and implicit pipeline output.
  `[OutputType(...)]` is shown separately as declared metadata; PowerShell does not enforce it.
  Several possible output values are represented conservatively as a union and/or an array.
- Case-insensitive variable names, braced variables, local/private assignment aliases,
  parameters, automatic variables and known names captured from enclosing scopes.
- Conditional initialization through `if`/`elseif`/`else`, loops, switch clauses and
  `try`/`catch`/`finally`. Terminating branches do not count as paths reaching a later read.
- Reads on the right side of assignments, compound updates, interpolation, arguments and
  pipelines. A later assignment does not suppress an earlier read's warning.

Analysis is cached per PSI file, recomputed after edits, checks IDE cancellation, and bounds
expression/function recursion. It never executes the script.

## Limits

This is static editing assistance, not a complete type checker or runtime guarantee.
Unknown information is displayed as `unknown`.

- Inference is file-local. External commands, modules, imported functions, arbitrary .NET
  members, reflection, generated code and complex pipelines remain unknown. Existing
  PowerShell Editor Services completion remains available for these cases.
- Functions use declared parameter types; call arguments do not specialize untyped parameters.
  Runtime function redefinitions and command aliases are not modeled.
- Captured outer names may exist when a function eventually runs; their types are unknown.
  Explicit scope/provider references (`$script:`, `$global:`, `$using:`, `$env:`, etc.) are
  exempt from missing-assignment warnings. Cross-scope writes are not tracked.
- Variables created by dot-sourcing, `Set-Variable`, `-OutVariable`, profiles, modules or
  dynamic callers can produce false positives for unqualified reads. Prefer explicit parameters
  or disable the inspection where this dynamic behavior is intentional.
- Loop and exception analysis is conservative and does not compute a full control-flow fixed
  point. It can warn after a `do` loop even when a human can prove assignment always runs.
  Class methods/properties remain handled by the original plugin and language server.
- Property/index writes and method calls invalidate inferred shapes, including aliases, to
  avoid retaining known-stale properties. Dynamic mutation by commands cannot be tracked.
  Only members shared by all inferred alternatives are suggested.
- Numeric promotion/overflow, all numeric literal suffixes, implicit conversions and exact
  pipeline cardinality are not fully modeled. No incompatible-assignment errors are emitted,
  since PowerShell often converts values at runtime.

PowerShell's [return semantics](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_return)
and [OutputType metadata](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_functions_outputtypeattribute)
explain why output inference includes more than explicit return statements.

## Build and verify

The wrapper downloads the project's JDK. The full upstream test suite also needs `pwsh` on
`PATH`; Linux editor tests require fontconfig and at least one font.

```sh
./gradlew test --tests '*PowerShellStaticAnalysisTest'
./gradlew build -PwebStormVerificationVersion=2026.2.3
```

The second command runs upstream tests, builds the installable ZIP, checks its size and runs
JetBrains Plugin Verifier against WebStorm 2026.2.3. Reports are under `build/reports/`.
Binary verification runs on the build host; it does not replace macOS UI, console and debugger testing.
