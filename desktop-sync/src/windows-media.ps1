$ErrorActionPreference = 'Stop'
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $managerType = [type]::GetType('Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime')
  $propertiesType = [type]::GetType('Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime')
  if ($null -eq $managerType -or $null -eq $propertiesType) { exit 0 }
  function Wait-WinRT([object]$operation, [type]$resultType) {
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
      $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetGenericArguments().Length -eq 1 -and
      $_.GetParameters().Length -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1
    if ($null -eq $asTask) { throw 'Could not create a WinRT task bridge.' }
    $task = $asTask.MakeGenericMethod($resultType).Invoke($null, @($operation))
    return $task.GetAwaiter().GetResult()
  }
  $request = $managerType.GetMethod('RequestAsync').Invoke($null, @())
  $manager = Wait-WinRT $request $managerType
  foreach ($session in $manager.GetSessions()) {
    if ($session.GetPlaybackInfo().PlaybackStatus.ToString() -ne 'Playing') { continue }
    $properties = Wait-WinRT $session.TryGetMediaPropertiesAsync() $propertiesType
    $fields = @($session.SourceAppUserModelId, $properties.Title, $properties.Artist) | ForEach-Object { ([string]$_).Replace('|', ' ').Replace("`r", ' ').Replace("`n", ' ') }
    $fields -join '|'
  }
} catch { exit 0 }
