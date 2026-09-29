# SPDX-FileCopyrightText: 2026 intellij-powershell contributors <https://github.com/intellij-powershell/intellij-powershell>
# SPDX-License-Identifier: Apache-2.0

# Open this file in WebStorm; execution is unnecessary.
# Quick Documentation on values and variables shows inferred types/shapes.
$user = [pscustomobject]@{
    Name = 'Ada'
    Age = 37
    Active = $true
    Address = @{ City = 'London'; PostalCode = 'N1' }
}

$user
$city = $user.Address.City
$city # string
# On a new line, type $user. or $user.Address. and invoke completion.

function Get-ExampleUser {
    param([string]$Name)
    return [pscustomobject]@{ Name = $Name; Enabled = $true }
}

$result = Get-ExampleUser -Name 'Grace'
$result # PSCustomObject { Name: string; Enabled: bool }

function Get-VariableExample {
    param()
    return $NonInitializedVariable # warning: may be read before assignment
}

$later # warning, despite the assignment below
$later = 42
$later # int; no warning

if ($user.Active) {
    $conditional = 'only one branch'
}
$conditional # warning: not assigned on every path

if ($user.Active) {
    $complete = 'yes'
} else {
    $complete = 'no'
}
$complete # string; no warning

$count = 1
$count = 'one'
$count # string at this point

foreach ($item in @($user)) {
    $item.Name # string
}

$env:PATH # supplied externally; no warning
$PSScriptRoot # automatic variable; string
