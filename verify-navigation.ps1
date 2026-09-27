$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-26.0.1'
& 'D:\Tools\apache-maven-3.9.16\bin\mvn.cmd' '-Dmaven.repo.local=D:\BOTFARM\.m2\repository' '-Dexec.mainClass=NavigationPolicyTest' '-Dexec.classpathScope=test' clean test-compile exec:java
exit $LASTEXITCODE
