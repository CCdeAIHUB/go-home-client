param(
    [string]$Repository = "CCdeAIHUB/go-home-client",
    [string]$OutputDirectory = "artifacts/latest"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "GitHub CLI is required. Install gh and run 'gh auth login' first."
}

$runJson = gh run list --repo $Repository --workflow CI --branch main --status success --limit 1 --json databaseId | ConvertFrom-Json
if (-not $runJson -or -not $runJson[0].databaseId) {
    throw "No successful CI run was found for $Repository."
}

$runId = $runJson[0].databaseId
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
gh run download $runId --repo $Repository --dir $OutputDirectory

Write-Host "Downloaded latest successful CI artifacts from run $runId to $OutputDirectory"
