<#
.SYNOPSIS
    Install, upgrade, or uninstall Ollama on Windows.

.DESCRIPTION
    Downloads and installs Ollama.

    Quick install:

        irm https://ollama.com/install.ps1 | iex

    Specific version:

        $env:OLLAMA_VERSION="0.5.7"; irm https://ollama.com/install.ps1 | iex

    Custom install directory:

        $env:OLLAMA_INSTALL_DIR="D:\Ollama"; irm https://ollama.com/install.ps1 | iex

    Uninstall:

        $env:OLLAMA_UNINSTALL=1; irm https://ollama.com/install.ps1 | iex

    Environment variables:

        OLLAMA_VERSION       Target version (default: latest stable)
        OLLAMA_INSTALL_DIR   Custom install directory
        OLLAMA_UNINSTALL     Set to 1 to uninstall Ollama
        OLLAMA_DEBUG         Enable verbose output

.EXAMPLE
    irm https://ollama.com/install.ps1 | iex

.EXAMPLE
    $env:OLLAMA_VERSION = "0.5.7"; irm https://ollama.com/install.ps1 | iex

.LINK
    https://ollama.com
#>

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# --------------------------------------------------------------------------
# Configuration from environment variables
# --------------------------------------------------------------------------

$Version      = if ($env:OLLAMA_VERSION) { $env:OLLAMA_VERSION } else { "" }
$InstallDir   = if ($env:OLLAMA_INSTALL_DIR) { $env:OLLAMA_INSTALL_DIR } else { "" }
$Uninstall    = $env:OLLAMA_UNINSTALL -eq "1"
$DebugInstall = [bool]$env:OLLAMA_DEBUG

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

# OLLAMA_DOWNLOAD_URL for developer testing only
$DownloadBaseURL = if ($env:OLLAMA_DOWNLOAD_URL) { $env:OLLAMA_DOWNLOAD_URL.TrimEnd('/') } else { "https://ollama.com/download" }
$InnoSetupUninstallGuid = "{44E83376-CE68-45EB-8FC1-393500EB558C}_is1"

# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

function Write-Status {
    param([string]$Message)
    if ($DebugInstall) { Write-Host $Message }
}

function Write-Step {
    param([string]$Message)
    if ($DebugInstall) { Write-Host ">>> $Message" -ForegroundColor Cyan }
}

function Test-Signature {
    param([string]$FilePath)

    $sig = Get-AuthenticodeSignature -FilePath $FilePath
    if ($sig.Status -ne "Valid") {
        Write-Status "  Signature status: $($sig.Status)"
        return $false
    }

    # Verify it's signed by Ollama Inc. (check exact organization name)
    # Anchor with comma/boundary to prevent "O=Not Ollama Inc." from matching
    $subject = $sig.SignerCertificate.Subject
    if ($subject -notmatch "(^|, )O=Ollama Inc\.(,|$)") {
        Write-Status "  Unexpected signer: $subject"
        return $false
    }

    Write-Status "  Signature valid: $subject"
    return $true
}

function Find-InnoSetupInstall {
    # Check both HKCU (per-user) and HKLM (per-machine) locations
    $possibleKeys = @(
        "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$InnoSetupUninstallGuid",
        "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\$InnoSetupUninstallGuid",
        "HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\$InnoSetupUninstallGuid"
    )

    foreach ($key in $possibleKeys) {
        if (Test-Path $key) {
            Write-Status "  Found install at: $key"
            return $key
        }
    }
    return $null
}

function Update-SessionPath {
    # Update PATH in current session so 'ollama' works immediately
    if ($InstallDir) {
        $ollamaDir = $InstallDir
    } else {
        $ollamaDir = Join-Path $env:LOCALAPPDATA "Programs\Ollama"
    }

    # Add to PATH if not already present
    if (Test-Path $ollamaDir) {
        $currentPath = $env:PATH -split ';'
        if ($ollamaDir -notin $currentPath) {
            $env:PATH = "$ollamaDir;$env:PATH"
            Write-Status "  Added $ollamaDir to session PATH"
        }
    }
}

function Invoke-Download {
    param(
        [string]$Url,
        [string]$OutFile
    )

    Write-Status "  Downloading: $Url"
    try {
        $request = [System.Net.HttpWebRequest]::Create($Url)
        $request.AllowAutoRedirect = $true
        $response = $request.GetResponse()
        $totalBytes = $response.ContentLength
        $stream = $response.GetResponseStream()
        $fileStream = [System.IO.FileStream]::new($OutFile, [System.IO.FileMode]::Create)
        $buffer = [byte[]]::new(65536)
        $totalRead = 0
        $lastUpdate = [DateTime]::MinValue
        $barWidth = 40

        try {
            while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $fileStream.Write($buffer, 0, $read)
                $totalRead += $read

                $now = [DateTime]::UtcNow
                if (($now - $lastUpdate).TotalMilliseconds -ge 250) {
                    if ($totalBytes -gt 0) {
                        $pct = [math]::Min(100.0, ($totalRead / $totalBytes) * 100)
                        $filled = [math]::Floor($barWidth * $pct / 100)
                        $empty = $barWidth - $filled
                        $bar = ('#' * $filled) + (' ' * $empty)
                        $pctFmt = $pct.ToString("0.0")
                        Write-Host -NoNewline "`r$bar ${pctFmt}%"
                    } else {
                        $sizeMB = [math]::Round($totalRead / 1MB, 1)
                        Write-Host -NoNewline "`r${sizeMB} MB downloaded..."
                    }
                    $lastUpdate = $now
                }
            }

            # Final progress update
            if ($totalBytes -gt 0) {
                $bar = '#' * $barWidth
                Write-Host "`r$bar 100.0%"
            } else {
                $sizeMB = [math]::Round($totalRead / 1MB, 1)
                Write-Host "`r${sizeMB} MB downloaded.          "
            }
        } finally {
            $fileStream.Close()
            $stream.Close()
            $response.Close()
        }
    } catch {
        if ($_.Exception -is [System.Net.WebException]) {
            $webEx = [System.Net.WebException]$_.Exception
            if ($webEx.Response -and ([System.Net.HttpWebResponse]$webEx.Response).StatusCode -eq [System.Net.HttpStatusCode]::NotFound) {
                throw "Download failed: not found at $Url"
            }
        }
        if ($_.Exception.InnerException -is [System.Net.WebException]) {
            $webEx = [System.Net.WebException]$_.Exception.InnerException
            if ($webEx.Response -and ([System.Net.HttpWebResponse]$webEx.Response).StatusCode -eq [System.Net.HttpStatusCode]::NotFound) {
                throw "Download failed: not found at $Url"
            }
        }
        throw "Download failed for ${Url}: $($_.Exception.Message)"
    }
}

# --------------------------------------------------------------------------
# Uninstall
# --------------------------------------------------------------------------

function Invoke-Uninstall {
    Write-Step "Uninstalling Ollama"

    $regKey = Find-InnoSetupInstall
    if (-not $regKey) {
        Write-Host ">>> Ollama is not installed."
        return
    }

    $uninstallString = (Get-ItemProperty -Path $regKey).UninstallString
    if (-not $uninstallString) {
        Write-Warning "No uninstall string found in registry"
        return
    }

    # Strip quotes if present
    $uninstallExe = $uninstallString -replace '"', ''
    Write-Status "  Uninstaller: $uninstallExe"

    if (-not (Test-Path $uninstallExe)) {
        Write-Warning "Uninstaller not found at: $uninstallExe"
        return
    }

    Write-Host ">>> Launching uninstaller..."
    # Run with GUI so user can choose whether to keep models
    Start-Process -FilePath $uninstallExe -Wait

    # Verify removal
    if (Find-InnoSetupInstall) {
        Write-Warning "Uninstall may not have completed"
    } else {
        Write-Host ">>> Ollama has been uninstalled."
    }
}

# --------------------------------------------------------------------------
# Install
# --------------------------------------------------------------------------

function Invoke-Install {
    # Determine installer URL
    if ($Version) {
        $installerUrl = "$DownloadBaseURL/OllamaSetup.exe?version=$Version"
    } else {
        $installerUrl = "$DownloadBaseURL/OllamaSetup.exe"
    }

    # Download installer
    Write-Step "Downloading Ollama"
    if (-not $DebugInstall) {
        Write-Host ">>> Downloading Ollama for Windows..."
    }

    $tempInstaller = Join-Path $env:TEMP "OllamaSetup.exe"
    Invoke-Download -Url $installerUrl -OutFile $tempInstaller

    # Verify signature
    Write-Step "Verifying signature"
    if (-not (Test-Signature -FilePath $tempInstaller)) {
        Remove-Item $tempInstaller -Force -ErrorAction SilentlyContinue
        throw "Installer signature verification failed"
    }

    # Build installer arguments
    $installerArgs = "/VERYSILENT /NORESTART /SUPPRESSMSGBOXES"
    if ($InstallDir) {
        $installerArgs += " /DIR=`"$InstallDir`""
    }
    Write-Status "  Installer args: $installerArgs"

    # Run installer
    Write-Step "Installing Ollama"
    if (-not $DebugInstall) {
        Write-Host ">>> Installing Ollama..."
    }

    # Create upgrade marker so the app starts hidden
    # The app checks for this file on startup and removes it after
    $markerDir = Join-Path $env:LOCALAPPDATA "Ollama"
    $markerFile = Join-Path $markerDir "upgraded"
    if (-not (Test-Path $markerDir)) {
        New-Item -ItemType Directory -Path $markerDir -Force | Out-Null
    }
    New-Item -ItemType File -Path $markerFile -Force | Out-Null
    Write-Status "  Created upgrade marker: $markerFile"

    # Start installer and wait for just the installer process (not children)
    # Using -Wait would wait for Ollama to exit too, which we don't want
    $proc = Start-Process -FilePath $tempInstaller `
        -ArgumentList $installerArgs `
        -PassThru
    $proc.WaitForExit()

    if ($proc.ExitCode -ne 0) {
        Remove-Item $tempInstaller -Force -ErrorAction SilentlyContinue
        throw "Installation failed with exit code $($proc.ExitCode)"
    }

    # Cleanup
    Remove-Item $tempInstaller -Force -ErrorAction SilentlyContinue

    # Update PATH in current session so 'ollama' works immediately
    Write-Step "Updating session PATH"
    Update-SessionPath

    Write-Host ">>> Install complete. Run 'ollama' from the command line."
}

# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

if ($Uninstall) {
    Invoke-Uninstall
} else {
    Invoke-Install
}

# SIG # Begin signature block
# MIIflwYJKoZIhvcNAQcCoIIfiDCCH4QCAQExDzANBglghkgBZQMEAgEFADB5Bgor
# BgEEAYI3AgEEoGswaTA0BgorBgEEAYI3AgEeMCYCAwEAAAQQH8w7YFlLCE63JNLG
# KX7zUQIBAAIBAAIBAAIBAAIBADAxMA0GCWCGSAFlAwQCAQUABCCe3FbDUbvNhZK5
# uhNZsu2YLO52YqmtBV85AN5fIurEZ6CCGm8wggM5MIICwKADAgECAhANkmgYrd08
# R3WPCs6TebLnMAoGCCqGSM49BAMDME0xCzAJBgNVBAYTAlVTMRcwFQYDVQQKEw5E
# aWdpQ2VydCwgSW5jLjElMCMGA1UEAxMcRGlnaUNlcnQgQ1MgRUNDIFAzODQgUm9v
# dCBHNTAeFw0yMTAyMTEwMDAwMDBaFw0zNjAyMTAyMzU5NTlaMFMxCzAJBgNVBAYT
# AlVTMRcwFQYDVQQKEw5EaWdpQ2VydCwgSW5jLjErMCkGA1UEAxMiRGlnaUNlcnQg
# RzUgQ1MgRUNDIFNIQTM4NCAyMDIxIENBMTB2MBAGByqGSM49AgEGBSuBBAAiA2IA
# BL/O8ofiwsu7/PN7f/68fJ9ETk5w7vtM3DcwIwCVp3Zj+NrnQpOigZKZeF/Axels
# FOhvuGyjGm8hT3ZboOEWf2WDilwp/lQcsSLSIN0dD3DSGkUmIwAHArrSeT47CwZN
# lKOCAV0wggFZMBIGA1UdEwEB/wQIMAYBAf8CAQAwHQYDVR0OBBYEFNcdx/rG+oJ0
# IEVxNBIvVdWYQZJeMB8GA1UdIwQYMBaAFPCMmHE5OGXCOhumF2YdyO1l3pI2MA4G
# A1UdDwEB/wQEAwIBhjATBgNVHSUEDDAKBggrBgEFBQcDAzB5BggrBgEFBQcBAQRt
# MGswJAYIKwYBBQUHMAGGGGh0dHA6Ly9vY3NwLmRpZ2ljZXJ0LmNvbTBDBggrBgEF
# BQcwAoY3aHR0cDovL2NhY2VydHMuZGlnaWNlcnQuY29tL0RpZ2lDZXJ0Q1NFQ0NQ
# Mzg0Um9vdEc1LmNydDBFBgNVHR8EPjA8MDqgOKA2hjRodHRwOi8vY3JsMy5kaWdp
# Y2VydC5jb20vRGlnaUNlcnRDU0VDQ1AzODRSb290RzUuY3JsMBwGA1UdIAQVMBMw
# BwYFZ4EMAQMwCAYGZ4EMAQQBMAoGCCqGSM49BAMDA2cAMGQCMHIJaKNEKcmiBl/3
# hTkcb/lLhAUzEQ4FIDLEQJsXGCtcwpeHmpruhLYX6FPYM/PFPAIwIi2ErRf/XhvF
# m+s02I1JbN6vZMNTJHDoc0h+eQeCpoYVBUArJLkjvumu/ney0rRdMIID9DCCA3qg
# AwIBAgIQCewMazrPL8u9C7dInjiZmTAKBggqhkjOPQQDAzBTMQswCQYDVQQGEwJV
# UzEXMBUGA1UEChMORGlnaUNlcnQsIEluYy4xKzApBgNVBAMTIkRpZ2lDZXJ0IEc1
# IENTIEVDQyBTSEEzODQgMjAyMSBDQTEwHhcNMjYwMjEyMDAwMDAwWhcNMjkwMjEz
# MjM1OTU5WjCBvTETMBEGCysGAQQBgjc8AgEDEwJDQTEYMBYGCysGAQQBgjc8AgEC
# EwdPbnRhcmlvMR0wGwYDVQQPDBRQcml2YXRlIE9yZ2FuaXphdGlvbjEQMA4GA1UE
# BRMHMjcxMzM1NTELMAkGA1UEBhMCQ0ExEDAOBgNVBAgTB09udGFyaW8xEDAOBgNV
# BAcTB1Rvcm9udG8xFDASBgNVBAoTC09sbGFtYSBJbmMuMRQwEgYDVQQDEwtPbGxh
# bWEgSW5jLjBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABOu+JJvByTtTQ93eCUqG
# t67dpHkGv6bdjy+Dxsd1ds42j+4lN65CdoRVohSIk+ZRe3rAjHhMLsY/JctPjUNZ
# liyjggHDMIIBvzAfBgNVHSMEGDAWgBTXHcf6xvqCdCBFcTQSL1XVmEGSXjAdBgNV
# HQ4EFgQUcZHxF/t8wVp8BfYi5Ql/kB+v11cwPQYDVR0gBDYwNDAyBgVngQwBAzAp
# MCcGCCsGAQUFBwIBFhtodHRwOi8vd3d3LmRpZ2ljZXJ0LmNvbS9DUFMwDgYDVR0P
# AQH/BAQDAgeAMBMGA1UdJQQMMAoGCCsGAQUFBwMDMIGNBgNVHR8EgYUwgYIwP6A9
# oDuGOWh0dHA6Ly9jcmwzLmRpZ2ljZXJ0LmNvbS9EaWdpQ2VydEc1Q1NFQ0NTSEEz
# ODQyMDIxQ0ExLmNybDA/oD2gO4Y5aHR0cDovL2NybDQuZGlnaWNlcnQuY29tL0Rp
# Z2lDZXJ0RzVDU0VDQ1NIQTM4NDIwMjFDQTEuY3JsMH4GCCsGAQUFBwEBBHIwcDAk
# BggrBgEFBQcwAYYYaHR0cDovL29jc3AuZGlnaWNlcnQuY29tMEgGCCsGAQUFBzAC
# hjxodHRwOi8vY2FjZXJ0cy5kaWdpY2VydC5jb20vRGlnaUNlcnRHNUNTRUNDU0hB
# Mzg0MjAyMUNBMS5jcnQwCQYDVR0TBAIwADAKBggqhkjOPQQDAwNoADBlAjB12Dbe
# HuL82JX96A/owZ0BqDNsE+dxygmPJzk2kRD1SaTuPvYNoHkpAObH/aVqI1YCMQD2
# YFytRXQZb3EO00FX8RWX3HbEcUiBxFiO5jp1FRhOfvxqAPrzD+U+V+Mr3cwWXa8w
# ggWNMIIEdaADAgECAhAOmxiO+dAt5+/bUOIIQBhaMA0GCSqGSIb3DQEBDAUAMGUx
# CzAJBgNVBAYTAlVTMRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3
# dy5kaWdpY2VydC5jb20xJDAiBgNVBAMTG0RpZ2lDZXJ0IEFzc3VyZWQgSUQgUm9v
# dCBDQTAeFw0yMjA4MDEwMDAwMDBaFw0zMTExMDkyMzU5NTlaMGIxCzAJBgNVBAYT
# AlVTMRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2Vy
# dC5jb20xITAfBgNVBAMTGERpZ2lDZXJ0IFRydXN0ZWQgUm9vdCBHNDCCAiIwDQYJ
# KoZIhvcNAQEBBQADggIPADCCAgoCggIBAL/mkHNo3rvkXUo8MCIwaTPswqclLskh
# PfKK2FnC4SmnPVirdprNrnsbhA3EMB/zG6Q4FutWxpdtHauyefLKEdLkX9YFPFIP
# Uh/GnhWlfr6fqVcWWVVyr2iTcMKyunWZanMylNEQRBAu34LzB4TmdDttceItDBvu
# INXJIB1jKS3O7F5OyJP4IWGbNOsFxl7sWxq868nPzaw0QF+xembud8hIqGZXV59U
# WI4MK7dPpzDZVu7Ke13jrclPXuU15zHL2pNe3I6PgNq2kZhAkHnDeMe2scS1ahg4
# AxCN2NQ3pC4FfYj1gj4QkXCrVYJBMtfbBHMqbpEBfCFM1LyuGwN1XXhm2ToxRJoz
# QL8I11pJpMLmqaBn3aQnvKFPObURWBf3JFxGj2T3wWmIdph2PVldQnaHiZdpekjw
# 4KISG2aadMreSx7nDmOu5tTvkpI6nj3cAORFJYm2mkQZK37AlLTSYW3rM9nF30sE
# AMx9HJXDj/chsrIRt7t/8tWMcCxBYKqxYxhElRp2Yn72gLD76GSmM9GJB+G9t+ZD
# pBi4pncB4Q+UDCEdslQpJYls5Q5SUUd0viastkF13nqsX40/ybzTQRESW+UQUOsx
# xcpyFiIJ33xMdT9j7CFfxCBRa2+xq4aLT8LWRV+dIPyhHsXAj6KxfgommfXkaS+Y
# HS312amyHeUbAgMBAAGjggE6MIIBNjAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQW
# BBTs1+OC0nFdZEzfLmc/57qYrhwPTzAfBgNVHSMEGDAWgBRF66Kv9JLLgjEtUYun
# pyGd823IDzAOBgNVHQ8BAf8EBAMCAYYweQYIKwYBBQUHAQEEbTBrMCQGCCsGAQUF
# BzABhhhodHRwOi8vb2NzcC5kaWdpY2VydC5jb20wQwYIKwYBBQUHMAKGN2h0dHA6
# Ly9jYWNlcnRzLmRpZ2ljZXJ0LmNvbS9EaWdpQ2VydEFzc3VyZWRJRFJvb3RDQS5j
# cnQwRQYDVR0fBD4wPDA6oDigNoY0aHR0cDovL2NybDMuZGlnaWNlcnQuY29tL0Rp
# Z2lDZXJ0QXNzdXJlZElEUm9vdENBLmNybDARBgNVHSAECjAIMAYGBFUdIAAwDQYJ
# KoZIhvcNAQEMBQADggEBAHCgv0NcVec4X6CjdBs9thbX979XB72arKGHLOyFXqka
# uyL4hxppVCLtpIh3bb0aFPQTSnovLbc47/T/gLn4offyct4kvFIDyE7QKt76LVbP
# +fT3rDB6mouyXtTP0UNEm0Mh65ZyoUi0mcudT6cGAxN3J0TU53/oWajwvy8Lpuny
# NDzs9wPHh6jSTEAZNUZqaVSwuKFWjuyk1T3osdz9HNj0d1pcVIxv76FQPfx2CWiE
# n2/K2yCNNWAcAgPLILCsWKAOQGPFmCLBsln1VWvPJ6tsds5vIy30fnFqI2si/xK4
# VC0nftg62fC2h5b9W9FcrBjDTZ9ztwGpn1eqXijiuZQwgga0MIIEnKADAgECAhAN
# x6xXBf8hmS5AQyIMOkmGMA0GCSqGSIb3DQEBCwUAMGIxCzAJBgNVBAYTAlVTMRUw
# EwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2VydC5jb20x
# ITAfBgNVBAMTGERpZ2lDZXJ0IFRydXN0ZWQgUm9vdCBHNDAeFw0yNTA1MDcwMDAw
# MDBaFw0zODAxMTQyMzU5NTlaMGkxCzAJBgNVBAYTAlVTMRcwFQYDVQQKEw5EaWdp
# Q2VydCwgSW5jLjFBMD8GA1UEAxM4RGlnaUNlcnQgVHJ1c3RlZCBHNCBUaW1lU3Rh
# bXBpbmcgUlNBNDA5NiBTSEEyNTYgMjAyNSBDQTEwggIiMA0GCSqGSIb3DQEBAQUA
# A4ICDwAwggIKAoICAQC0eDHTCphBcr48RsAcrHXbo0ZodLRRF51NrY0NlLWZloMs
# VO1DahGPNRcybEKq+RuwOnPhof6pvF4uGjwjqNjfEvUi6wuim5bap+0lgloM2zX4
# kftn5B1IpYzTqpyFQ/4Bt0mAxAHeHYNnQxqXmRinvuNgxVBdJkf77S2uPoCj7GH8
# BLuxBG5AvftBdsOECS1UkxBvMgEdgkFiDNYiOTx4OtiFcMSkqTtF2hfQz3zQSku2
# Ws3IfDReb6e3mmdglTcaarps0wjUjsZvkgFkriK9tUKJm/s80FiocSk1VYLZlDwF
# t+cVFBURJg6zMUjZa/zbCclF83bRVFLeGkuAhHiGPMvSGmhgaTzVyhYn4p0+8y9o
# HRaQT/aofEnS5xLrfxnGpTXiUOeSLsJygoLPp66bkDX1ZlAeSpQl92QOMeRxykvq
# 6gbylsXQskBBBnGy3tW/AMOMCZIVNSaz7BX8VtYGqLt9MmeOreGPRdtBx3yGOP+r
# x3rKWDEJlIqLXvJWnY0v5ydPpOjL6s36czwzsucuoKs7Yk/ehb//Wx+5kMqIMRvU
# BDx6z1ev+7psNOdgJMoiwOrUG2ZdSoQbU2rMkpLiQ6bGRinZbI4OLu9BMIFm1UUl
# 9VnePs6BaaeEWvjJSjNm2qA+sdFUeEY0qVjPKOWug/G6X5uAiynM7Bu2ayBjUwID
# AQABo4IBXTCCAVkwEgYDVR0TAQH/BAgwBgEB/wIBADAdBgNVHQ4EFgQU729TSunk
# Bnx6yuKQVvYv1Ensy04wHwYDVR0jBBgwFoAU7NfjgtJxXWRM3y5nP+e6mK4cD08w
# DgYDVR0PAQH/BAQDAgGGMBMGA1UdJQQMMAoGCCsGAQUFBwMIMHcGCCsGAQUFBwEB
# BGswaTAkBggrBgEFBQcwAYYYaHR0cDovL29jc3AuZGlnaWNlcnQuY29tMEEGCCsG
# AQUFBzAChjVodHRwOi8vY2FjZXJ0cy5kaWdpY2VydC5jb20vRGlnaUNlcnRUcnVz
# dGVkUm9vdEc0LmNydDBDBgNVHR8EPDA6MDigNqA0hjJodHRwOi8vY3JsMy5kaWdp
# Y2VydC5jb20vRGlnaUNlcnRUcnVzdGVkUm9vdEc0LmNybDAgBgNVHSAEGTAXMAgG
# BmeBDAEEAjALBglghkgBhv1sBwEwDQYJKoZIhvcNAQELBQADggIBABfO+xaAHP4H
# PRF2cTC9vgvItTSmf83Qh8WIGjB/T8ObXAZz8OjuhUxjaaFdleMM0lBryPTQM2qE
# JPe36zwbSI/mS83afsl3YTj+IQhQE7jU/kXjjytJgnn0hvrV6hqWGd3rLAUt6vJy
# 9lMDPjTLxLgXf9r5nWMQwr8Myb9rEVKChHyfpzee5kH0F8HABBgr0UdqirZ7bowe
# 9Vj2AIMD8liyrukZ2iA/wdG2th9y1IsA0QF8dTXqvcnTmpfeQh35k5zOCPmSNq1U
# H410ANVko43+Cdmu4y81hjajV/gxdEkMx1NKU4uHQcKfZxAvBAKqMVuqte69M9J6
# A47OvgRaPs+2ykgcGV00TYr2Lr3ty9qIijanrUR3anzEwlvzZiiyfTPjLbnFRsjs
# Yg39OlV8cipDoq7+qNNjqFzeGxcytL5TTLL4ZaoBdqbhOhZ3ZRDUphPvSRmMThi0
# vw9vODRzW6AxnJll38F0cuJG7uEBYTptMSbhdhGQDpOXgpIUsWTjd6xpR6oaQf/D
# Jbg3s6KCLPAlZ66RzIg9sC+NJpud/v4+7RWsWCiKi9EOLLHfMR2ZyJ/+xhCx9yHb
# xtl5TPau1j/1MIDpMPx0LckTetiSuEtQvLsNz3Qbp7wGWqbIiOWCnb5WqxL3/BAP
# vIXKUjPSxyZsq8WhbaM2tszWkPZPubdcMIIG7TCCBNWgAwIBAgIQCE/cM09+RU7b
# ww+P+ZIYNTANBgkqhkiG9w0BAQsFADBpMQswCQYDVQQGEwJVUzEXMBUGA1UEChMO
# RGlnaUNlcnQsIEluYy4xQTA/BgNVBAMTOERpZ2lDZXJ0IFRydXN0ZWQgRzQgVGlt
# ZVN0YW1waW5nIFJTQTQwOTYgU0hBMjU2IDIwMjUgQ0ExMB4XDTI2MDgwNTAwMDAw
# MFoXDTM3MTEwNDIzNTk1OVowYzELMAkGA1UEBhMCVVMxFzAVBgNVBAoTDkRpZ2lD
# ZXJ0LCBJbmMuMTswOQYDVQQDEzJEaWdpQ2VydCBTSEEyNTYgUlNBNDA5NiBUaW1l
# c3RhbXAgUmVzcG9uZGVyIDIwMjYgMTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCC
# AgoCggIBALZ7pvLJ/s1K+NSbTGWz/TjGMPh8CQ6RucZCLv5anHzWJjF/NWJrFIhy
# 24fcpKXlgRiky4WAawDfU3YP0BMxt9l3Dm5oCG5Z69AqEN1kgHg2epx+l+lZBcmJ
# CcN0ASURML5uFIS80sZsDwO3BSkUxDjLJhBI+qiZP3aixAC/qEGLjsBNlLol9VZ7
# pfGEXiMlneJIC5/YKuizVzNFKZZEeoy/0B8Zm+nzKBgSWG52lCO1w+nCg6XpCtkl
# TJXeIg283hw7TmmsZXR+SMbjbrEOvZ3fP2VxIgeR28Y90ZStd3F9VuA5RVynb/wh
# ITPAo9b75Zr4Ta6Mj3URm26QZYMn/FnbuTegcoRcFEZ9FOqM5T6MTdtr/n74lIT/
# ug0eeOzmZ6QTFg33otX+bFRsIolvykE1jive4PuESaT8zzVeFWDAMDtozNgLctkG
# D1ZjkEyZtJrLl5ya0m5doH/ScpaZCZVl6pNUOCybMc/kxC6EAmSJY24L0yYKD1Nk
# ddsnb/ItVKi/2nXpQNMu1PT5prW83vV8d67WowuUs0HdY4H8AMLGvdL/WHEj3Znq
# MqAQQP9u3Ai9t+5eQ02GDwy0ODjdzi0xlp70W+ow63/0++YDEX1M0iwgUHwbrJvf
# pklkZQvw3+kv3vUPItdwroczk9icflf55W1zOEKAcJVAIXpcMCU9AgMBAAGjggGV
# MIIBkTAMBgNVHRMBAf8EAjAAMB0GA1UdDgQWBBQUyWOKMC7USvtulPPm40B+9ezN
# 4jAfBgNVHSMEGDAWgBTvb1NK6eQGfHrK4pBW9i/USezLTjAOBgNVHQ8BAf8EBAMC
# B4AwFgYDVR0lAQH/BAwwCgYIKwYBBQUHAwgwgZUGCCsGAQUFBwEBBIGIMIGFMCQG
# CCsGAQUFBzABhhhodHRwOi8vb2NzcC5kaWdpY2VydC5jb20wXQYIKwYBBQUHMAKG
# UWh0dHA6Ly9jYWNlcnRzLmRpZ2ljZXJ0LmNvbS9EaWdpQ2VydFRydXN0ZWRHNFRp
# bWVTdGFtcGluZ1JTQTQwOTZTSEEyNTYyMDI1Q0ExLmNydDBfBgNVHR8EWDBWMFSg
# UqBQhk5odHRwOi8vY3JsMy5kaWdpY2VydC5jb20vRGlnaUNlcnRUcnVzdGVkRzRU
# aW1lU3RhbXBpbmdSU0E0MDk2U0hBMjU2MjAyNUNBMS5jcmwwIAYDVR0gBBkwFzAI
# BgZngQwBBAIwCwYJYIZIAYb9bAcBMA0GCSqGSIb3DQEBCwUAA4ICAQCNxTphHp1S
# Ct+ZrAmAfn0oQLFr0mLywSLaDXQIENoyKqxrFbJblzCVP/pkXmwXOdrOpWygLzlT
# 12os5ipDCy35RBCg2UMeApEtrfGhz45F4Wt4WGdNdIbRWt3YTYJmpR+b7lr4d7Uw
# n+H600u4D7RnOGf8Wj4UNgAdZkfHhHv1mx9EVh71SJelcEN/oORSjXzdjfw1iZH9
# d8Nh/thn6hH23d+VsPAr6GAYyzSA02nXD1nYLI7Ijmiv+xLCiYC41DSFYL3GhTiy
# 0PxpawPtGRyaBVGzq+UiTfM8pD7KVyF5aQyWP4KhVGUUTnmm/RlYJoW3TiXA/+t0
# YcT2oRVBm3JETjajHug2AL+v5jhtKVnd3D0rbHXEu27o+Q8p4sEWPMqKDB+qbceb
# 6T/6WcwTwXmQ9lOCLLYcsQeSWmvKqzpAec9etE14jOQAzLKWdE3w/TCaKtLRaRT7
# LCkRYVnhA2D73FLje1O5b3HR5eHs0NzU/+xX7NbEdcofy0W3Wdwd1XOqtlpg/Jgw
# tKfZM5dqO94lbUveOiJBI+xZEbGRsMNbXmMREUTgu+Oca7Y73MPWcslIx2VhkSKS
# XjDbD6rgg39H5Mh7QfieAIjWagkJNt68Yfim6cjEzVSiLSeZfdkr5dtFPTW6jATl
# WJdYeeDRGCyatf8R1hSjzSvdN8yWQPT9gzGCBH4wggR6AgEBMGcwUzELMAkGA1UE
# BhMCVVMxFzAVBgNVBAoTDkRpZ2lDZXJ0LCBJbmMuMSswKQYDVQQDEyJEaWdpQ2Vy
# dCBHNSBDUyBFQ0MgU0hBMzg0IDIwMjEgQ0ExAhAJ7AxrOs8vy70Lt0ieOJmZMA0G
# CWCGSAFlAwQCAQUAoHwwEAYKKwYBBAGCNwIBDDECMAAwGQYJKoZIhvcNAQkDMQwG
# CisGAQQBgjcCAQQwHAYKKwYBBAGCNwIBCzEOMAwGCisGAQQBgjcCARUwLwYJKoZI
# hvcNAQkEMSIEIMtCLZtW8+mLjjDOdsmX/KETJTAK1jZJd5rzW+9HjTMDMAsGByqG
# SM49AgEFAARIMEYCIQDGBthHrfdapux4NIdZAAez3KBPDDGOKbM0KWEYMfsguwIh
# AOXReG7DDhiqKfuBXFVJ/Jg8SZBP5ZXLtoZkNF1dYEE9oYIDJjCCAyIGCSqGSIb3
# DQEJBjGCAxMwggMPAgEBMH0waTELMAkGA1UEBhMCVVMxFzAVBgNVBAoTDkRpZ2lD
# ZXJ0LCBJbmMuMUEwPwYDVQQDEzhEaWdpQ2VydCBUcnVzdGVkIEc0IFRpbWVTdGFt
# cGluZyBSU0E0MDk2IFNIQTI1NiAyMDI1IENBMQIQCE/cM09+RU7bww+P+ZIYNTAN
# BglghkgBZQMEAgEFAKBpMBgGCSqGSIb3DQEJAzELBgkqhkiG9w0BBwEwHAYJKoZI
# hvcNAQkFMQ8XDTI2MDkyNDA0MTIxNlowLwYJKoZIhvcNAQkEMSIEICdbk46+05ag
# 5NsFSryhX44PhpmqFWq4nGfnWRVTP3+JMA0GCSqGSIb3DQEBAQUABIICADGu27Hk
# 9Jru+Dxylzwd+xXgUZ8/znKtxS7eLVJ9hjOigKYmKr3RPHhxhDgWGNiZqskLIeor
# OlVD7D0PQs95G13RArx+V67oAW0mQ4dugGl8ArgvZhKT86Yrf14/cQppeyrgN20H
# ddCTsXqaIwpHLBnpdzqjEdJqacAtGAemKyLwdINRxxy936UwEWkHJf0LNdvq9LKD
# JhmmKmIsrHgeOi3dlmwoWsATMomlYBdnzEyxw/2dRheT4YOHGA3ANzGa6Gi1B81Z
# CMtWbOZezB6tyv2oPV7K3P7ndXZHCGoE0N71TqOLbx7gZujB8pIdtyCI0UbKsIzo
# ms2DnwQa8ti98jwIl3iDQGNPEy3bpmTD1wNbuK5KNgYZNX1CggZ/dEkAV6OUu8Qj
# MvDUmAEslqEOAU8J8kUg1bbnCQFZveyl684LRjUJhNxKDkMmLnq4dSdUslEkKqxj
# P9b3In9w0t15/XKqbKVIh5S1/LNPOy6BMnHHF3WZ8IbfU2pfXKKCHs89OsnLfdBo
# ZrZ6K564uMSS/00D+3X+cbwHzF+XePVRNFkQ2tWp1QUUiGugybwKzqtpO17p8Gox
# zZhqRfSHr/isWvQhseM0UnoMmmPwNoFe+SHGRS8eZxqJOYswpxvCF/YaKjparenj
# 0u7wonmPyx2PVVVW8r30SODCjp5If7ViO7cT
# SIG # End signature block
