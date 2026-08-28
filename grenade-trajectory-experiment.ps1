[CmdletBinding()]
param(
    [double]$Pitch = -32.5,
    [double]$Yaw = 0.0,
    [double]$Speed = 1.1,
    [double]$Gravity = 0.07,
    [double]$Drag = 0.99,
    [int]$Ticks = 75,
    [double]$OriginX = 0.0,
    [double]$OriginY = 0.0,
    [double]$OriginZ = 0.0,
    [Nullable[double]]$Range = $null,
    [double]$TargetDeltaY = 0.0,
    [Nullable[double]]$TargetY = $null,
    [ValidateSet("low", "high")]
    [string]$Branch = "low",
    [double]$PitchStep = 0.1,
    [string]$CsvPath = "grenade-trajectory.csv"
)

$ErrorActionPreference = "Stop"

function New-Vector([double]$x, [double]$y, [double]$z) {
    return [pscustomobject]@{ X = $x; Y = $y; Z = $z }
}

function Add-Vector($left, $right) {
    return New-Vector ($left.X + $right.X) ($left.Y + $right.Y) ($left.Z + $right.Z)
}

function Scale-Vector($value, [double]$scale) {
    return New-Vector ($value.X * $scale) ($value.Y * $scale) ($value.Z * $scale)
}

function Get-Length($value) {
    return [math]::Sqrt($value.X * $value.X + $value.Y * $value.Y + $value.Z * $value.Z)
}

function Get-InitialVelocity([double]$pitchDegrees, [double]$yawDegrees, [double]$launchSpeed) {
    $pitchRadians = $pitchDegrees * [math]::PI / 180.0
    $yawRadians = $yawDegrees * [math]::PI / 180.0
    $horizontal = [math]::Cos($pitchRadians) * $launchSpeed
    return New-Vector `
        (-[math]::Sin($yawRadians) * $horizontal) `
        (-[math]::Sin($pitchRadians) * $launchSpeed) `
        ([math]::Cos($yawRadians) * $horizontal)
}

function Get-GeometricSum([double]$factor, [int]$count) {
    if ($count -le 0) { return 0.0 }
    if ([math]::Abs(1.0 - $factor) -lt 1.0e-12) { return [double]$count }
    return (1.0 - [math]::Pow($factor, $count)) / (1.0 - $factor)
}

function Get-GravitySum([double]$factor, [int]$count) {
    if ($count -le 0) { return 0.0 }
    if ([math]::Abs(1.0 - $factor) -lt 1.0e-12) {
        return 0.5 * $count * ($count - 1)
    }
    return ($count - (Get-GeometricSum $factor $count)) / (1.0 - $factor)
}

function Get-NoDragPitchCandidates([double]$range, [double]$targetDeltaY,
                                    [double]$speed, [double]$gravity) {
    if ($range -le 0.0) { return @() }
    if ($gravity -lt 1.0e-12) {
        return @(-([math]::Atan2($targetDeltaY, $range) * 180.0 / [math]::PI))
    }

    $speedSquared = $speed * $speed
    $discriminant = $speedSquared * $speedSquared - $gravity * (
        $gravity * $range * $range + 2.0 * $targetDeltaY * $speedSquared)
    if ($discriminant -lt 0.0) { return @() }

    $root = [math]::Sqrt($discriminant)
    $lowElevation = [math]::Atan(($speedSquared - $root) / ($gravity * $range))
    $highElevation = [math]::Atan(($speedSquared + $root) / ($gravity * $range))
    return @(
        -($lowElevation * 180.0 / [math]::PI)
        -($highElevation * 180.0 / [math]::PI)
    )
}

function Get-EstimatedFlightTicks([double]$range, [double]$horizontalSpeed,
                                   [double]$drag, [int]$lifetime) {
    if ($horizontalSpeed -le 1.0e-12) { return $lifetime }
    for ($tick = 1; $tick -le $lifetime; $tick++) {
        if ($horizontalSpeed * (Get-GeometricSum $drag $tick) -ge $range) {
            return $tick
        }
    }
    return $lifetime
}

function Get-DragAwarePitch([string]$branch, [double]$range,
                            [double]$targetDeltaY, [double]$yaw,
                            [double]$speed, [double]$gravity,
                            [double]$drag, [int]$lifetime) {
    $firstElevation = if ($branch -eq "low") { 10.0 } else { 45.0 }
    $lastElevation = if ($branch -eq "low") { 45.0 } else { 80.0 }
    $best = $null
    # Scan the closed-form drag recurrence instead of iterating an unstable
    # pitch correction. This keeps the estimate bounded and reproducible.
    for ($elevation = $firstElevation; $elevation -le $lastElevation + 1.0e-9; $elevation += 0.1) {
        $pitch = -$elevation
        $velocity = Get-InitialVelocity $pitch $yaw $speed
        $trace = Get-DragAwareEstimate (New-Vector 0.0 0.0 0.0) $velocity $gravity $drag $lifetime
        $crossing = Get-PointAtHorizontalRange $trace $range
        if (-not $crossing.Reached) { continue }
        $verticalError = $crossing.Position.Y - $targetDeltaY
        $horizontalError = $crossing.HorizontalRange - $range
        $error = [math]::Sqrt($horizontalError * $horizontalError + $verticalError * $verticalError)
        if ($null -eq $best -or $error -lt $best.Error) {
            $best = [pscustomobject]@{ Pitch = $pitch; Error = $error }
        }
    }
    return $best.Pitch
}

function Get-ExactTrace($origin, $initialVelocity, [double]$gravity, [double]$drag, [int]$ticks) {
    $position = $origin
    $velocity = $initialVelocity
    $trace = New-Object System.Collections.Generic.List[object]
    [void]$trace.Add([pscustomobject]@{
        Tick = 0; Model = "native-discrete"; X = $position.X; Y = $position.Y; Z = $position.Z
        Vx = $velocity.X; Vy = $velocity.Y; Vz = $velocity.Z
    })

    for ($tick = 1; $tick -le $ticks; $tick++) {
        # ThrowableItemEntity.tick with no block collision: move, drag, gravity.
        $position = Add-Vector $position $velocity
        $velocity = Add-Vector (Scale-Vector $velocity $drag) (New-Vector 0.0 (-$gravity) 0.0)
        [void]$trace.Add([pscustomobject]@{
            Tick = $tick; Model = "native-discrete"; X = $position.X; Y = $position.Y; Z = $position.Z
            Vx = $velocity.X; Vy = $velocity.Y; Vz = $velocity.Z
        })
    }
    return $trace
}

function Get-DragAwareEstimate($origin, $initialVelocity, [double]$gravity, [double]$drag, [int]$ticks) {
    $trace = New-Object System.Collections.Generic.List[object]
    for ($tick = 0; $tick -le $ticks; $tick++) {
        $positionSum = Get-GeometricSum $drag $tick
        $gravitySum = Get-GravitySum $drag $tick
        $position = Add-Vector $origin (New-Vector `
            ($initialVelocity.X * $positionSum) `
            ($initialVelocity.Y * $positionSum - $gravity * $gravitySum) `
            ($initialVelocity.Z * $positionSum))
        $velocitySum = Get-GeometricSum $drag $tick
        $velocity = Add-Vector (Scale-Vector $initialVelocity ([math]::Pow($drag, $tick))) `
            (New-Vector 0.0 (-$gravity * $velocitySum) 0.0)
        [void]$trace.Add([pscustomobject]@{
            Tick = $tick; Model = "drag-aware-estimate"; X = $position.X; Y = $position.Y; Z = $position.Z
            Vx = $velocity.X; Vy = $velocity.Y; Vz = $velocity.Z
        })
    }
    return $trace
}

function Get-NoDragEstimate($origin, $initialVelocity, [double]$gravity, [int]$ticks) {
    $trace = New-Object System.Collections.Generic.List[object]
    for ($tick = 0; $tick -le $ticks; $tick++) {
        # This represents the common continuous ballistic approximation.
        $position = Add-Vector $origin (New-Vector `
            ($initialVelocity.X * $tick) `
            ($initialVelocity.Y * $tick - 0.5 * $gravity * $tick * $tick) `
            ($initialVelocity.Z * $tick))
        $velocity = New-Vector $initialVelocity.X $initialVelocity.Y $initialVelocity.Z
        $velocity = New-Vector $velocity.X ($velocity.Y - $gravity * $tick) $velocity.Z
        [void]$trace.Add([pscustomobject]@{
            Tick = $tick; Model = "no-drag-estimate"; X = $position.X; Y = $position.Y; Z = $position.Z
            Vx = $velocity.X; Vy = $velocity.Y; Vz = $velocity.Z
        })
    }
    return $trace
}

function Get-PointAtHorizontalRange($trace, [double]$range) {
    for ($index = 0; $index -lt $trace.Count - 1; $index++) {
        $from = $trace[$index]
        $to = $trace[$index + 1]
        $fromRange = [math]::Sqrt($from.X * $from.X + $from.Z * $from.Z)
        $toRange = [math]::Sqrt($to.X * $to.X + $to.Z * $to.Z)
        if ($toRange -ge $range) {
            $span = $toRange - $fromRange
            $fraction = if ($span -le 1.0e-12) { 0.0 } else { ($range - $fromRange) / $span }
            $fraction = [math]::Max(0.0, [math]::Min(1.0, $fraction))
            $position = New-Vector `
                ($from.X + ($to.X - $from.X) * $fraction) `
                ($from.Y + ($to.Y - $from.Y) * $fraction) `
                ($from.Z + ($to.Z - $from.Z) * $fraction)
            return [pscustomobject]@{
                Position = $position
                Tick = $index + $fraction
                HorizontalRange = $fromRange + ($toRange - $fromRange) * $fraction
                Reached = $true
            }
        }
    }

    $last = $trace[$trace.Count - 1]
    return [pscustomobject]@{
        Position = New-Vector $last.X $last.Y $last.Z
        Tick = $trace.Count - 1
        HorizontalRange = [math]::Sqrt($last.X * $last.X + $last.Z * $last.Z)
        Reached = $false
    }
}

function Get-ExactPitchResult([double]$pitch, [double]$range, [double]$targetDeltaY,
                              [double]$yaw, [double]$speed, [double]$gravity,
                              [double]$drag, [int]$lifetime) {
    $velocity = Get-InitialVelocity $pitch $yaw $speed
    $trace = Get-ExactTrace (New-Vector 0.0 0.0 0.0) $velocity $gravity $drag $lifetime
    $crossing = Get-PointAtHorizontalRange $trace $range
    $position = $crossing.Position
    $horizontalError = $crossing.HorizontalRange - $range
    $verticalError = $position.Y - $targetDeltaY
    $error = [math]::Sqrt($horizontalError * $horizontalError + $verticalError * $verticalError)
    return [pscustomobject]@{
        Pitch = $pitch
        Elevation = -$pitch
        Position = $position
        Tick = $crossing.Tick
        HorizontalRange = $crossing.HorizontalRange
        HorizontalError = $horizontalError
        VerticalError = $verticalError
        Error = $error
        Reached = $crossing.Reached
    }
}

function Find-ExactPitch([string]$branch, [double]$range, [double]$targetDeltaY,
                         [double]$yaw, [double]$speed, [double]$gravity,
                         [double]$drag, [int]$lifetime, [double]$pitchStep) {
    $firstElevation = if ($branch -eq "low") { 10.0 } else { 45.0 }
    $lastElevation = if ($branch -eq "low") { 45.0 } else { 80.0 }
    $best = $null
    for ($elevation = $firstElevation; $elevation -le $lastElevation + 1.0e-9; $elevation += $pitchStep) {
        $candidate = Get-ExactPitchResult (-$elevation) $range $targetDeltaY $yaw $speed $gravity $drag $lifetime
        if ($candidate.Reached -and ($null -eq $best -or $candidate.Error -lt $best.Error)) {
            $best = $candidate
        }
    }
    return $best
}

if ($Ticks -lt 1) { throw "Ticks must be at least 1." }
if ($Speed -le 0.0) { throw "Speed must be greater than zero." }
if ($Drag -le 0.0 -or $Drag -gt 1.0) { throw "Drag must be greater than zero and no greater than one." }
if ($Gravity -lt 0.0) { throw "Gravity cannot be negative." }
if ($null -ne $Range -and $Range -le 0.0) { throw "Range must be greater than zero." }
if ($PitchStep -le 0.0) { throw "PitchStep must be greater than zero." }
if ($null -ne $TargetY) { $TargetDeltaY = $TargetY - $OriginY }

$selectedPitch = $Pitch
if ($null -ne $Range) {
    $noDragPitches = @(Get-NoDragPitchCandidates $Range $TargetDeltaY $Speed $Gravity)
    $dragPitches = @()
    foreach ($noDragPitch in $noDragPitches) {
        $label = if ($dragPitches.Count -eq 0) { "low" } else { "high" }
        $dragPitches += Get-DragAwarePitch $label $Range $TargetDeltaY $Yaw $Speed $Gravity $Drag $Ticks
    }

    $exactLow = Find-ExactPitch "low" $Range $TargetDeltaY $Yaw $Speed $Gravity $Drag $Ticks $PitchStep
    $exactHigh = Find-ExactPitch "high" $Range $TargetDeltaY $Yaw $Speed $Gravity $Drag $Ticks $PitchStep
    $selectedExact = if ($Branch -eq "low") { $exactLow } else { $exactHigh }
    if ($null -ne $selectedExact) {
        $selectedPitch = $selectedExact.Pitch
        $Pitch = $selectedPitch
    }

    Write-Output ("Pitch solve: range={0:F3}, targetDeltaY={1:F3}, branch={2}" -f $Range, $TargetDeltaY, $Branch)
    if ($noDragPitches.Count -eq 0) {
        Write-Output "No-drag analytical estimate: no real solution at this speed/range/height."
    } else {
        for ($index = 0; $index -lt $noDragPitches.Count; $index++) {
            $label = if ($index -eq 0) { "low" } else { "high" }
            $estimateResult = Get-ExactPitchResult $noDragPitches[$index] $Range $TargetDeltaY $Yaw $Speed $Gravity $Drag $Ticks
            $dragResult = Get-ExactPitchResult $dragPitches[$index] $Range $TargetDeltaY $Yaw $Speed $Gravity $Drag $Ticks
            Write-Output ("{0} estimate: no-drag pitch={1:F3}, exact-at-range-y-error={2:F4}; drag-aware pitch={3:F3}, exact-at-range-y-error={4:F4}" -f `
                $label, $noDragPitches[$index], $estimateResult.VerticalError, $dragPitches[$index], $dragResult.VerticalError)
        }
    }
    $exactResults = @($exactLow, $exactHigh)
    for ($index = 0; $index -lt $exactResults.Count; $index++) {
        $result = $exactResults[$index]
        $label = if ($index -eq 0) { "low" } else { "high" }
        if ($null -eq $result) {
            Write-Output ("{0} exact discrete pitch: no solution within the lifetime/range." -f $label)
        } else {
            $solutionLabel = if ($result.Error -le 0.1) { "solution" } else { "closest pitch" }
            Write-Output ("{0} {1}: {2:F3}, atTick={3:F3}, verticalError={4:F4}, totalError={5:F4}" -f `
                $label, $solutionLabel, $result.Pitch, $result.Tick, $result.VerticalError, $result.Error)
        }
    }
    if ($null -eq $selectedExact) {
        Write-Output ("No exact discrete {0} pitch exists; using requested pitch {1:F3} for the detailed trace." -f $Branch, $Pitch)
    } else {
        Write-Output ("Using exact discrete {0} pitch {1:F3} for the detailed trace." -f $Branch, $selectedPitch)
    }
}

$origin = New-Vector $OriginX $OriginY $OriginZ
$initialVelocity = Get-InitialVelocity $Pitch $Yaw $Speed
$exact = Get-ExactTrace $origin $initialVelocity $Gravity $Drag $Ticks
$dragEstimate = Get-DragAwareEstimate $origin $initialVelocity $Gravity $Drag $Ticks
$noDragEstimate = Get-NoDragEstimate $origin $initialVelocity $Gravity $Ticks

$rows = New-Object System.Collections.Generic.List[object]
$maxDragError = 0.0
$maxNoDragError = 0.0
$firstNoDragDivergence = $null

for ($tick = 0; $tick -le $Ticks; $tick++) {
    $exactPoint = $exact[$tick]
    $dragPoint = $dragEstimate[$tick]
    $noDragPoint = $noDragEstimate[$tick]
    $dragError = [math]::Sqrt(
        [math]::Pow($exactPoint.X - $dragPoint.X, 2) +
        [math]::Pow($exactPoint.Y - $dragPoint.Y, 2) +
        [math]::Pow($exactPoint.Z - $dragPoint.Z, 2))
    $noDragError = [math]::Sqrt(
        [math]::Pow($exactPoint.X - $noDragPoint.X, 2) +
        [math]::Pow($exactPoint.Y - $noDragPoint.Y, 2) +
        [math]::Pow($exactPoint.Z - $noDragPoint.Z, 2))
    $maxDragError = [math]::Max($maxDragError, $dragError)
    $maxNoDragError = [math]::Max($maxNoDragError, $noDragError)
    if ($null -eq $firstNoDragDivergence -and $noDragError -gt 0.1) {
        $firstNoDragDivergence = $tick
    }
    [void]$rows.Add([pscustomobject]@{
        Tick = $tick
        ExactX = $exactPoint.X; ExactY = $exactPoint.Y; ExactZ = $exactPoint.Z
        DragEstimateX = $dragPoint.X; DragEstimateY = $dragPoint.Y; DragEstimateZ = $dragPoint.Z
        NoDragEstimateX = $noDragPoint.X; NoDragEstimateY = $noDragPoint.Y; NoDragEstimateZ = $noDragPoint.Z
        DragAwareError = $dragError; NoDragError = $noDragError
    })
}

$rows | Export-Csv -LiteralPath $CsvPath -NoTypeInformation
$lastExact = $exact[$Ticks]
$lastDrag = $dragEstimate[$Ticks]
$lastNoDrag = $noDragEstimate[$Ticks]

Write-Output ("Initial velocity: ({0:F6}, {1:F6}, {2:F6}), speed={3:F6}" -f `
    $initialVelocity.X, $initialVelocity.Y, $initialVelocity.Z, (Get-Length $initialVelocity))
Write-Output ("Tick {0} native:       ({1:F6}, {2:F6}, {3:F6})" -f $Ticks, $lastExact.X, $lastExact.Y, $lastExact.Z)
Write-Output ("Tick {0} drag-aware:   ({1:F6}, {2:F6}, {3:F6}), max error={4:E3}" -f `
    $Ticks, $lastDrag.X, $lastDrag.Y, $lastDrag.Z, $maxDragError)
Write-Output ("Tick {0} no-drag:       ({1:F6}, {2:F6}, {3:F6}), max error={4:F6}" -f `
    $Ticks, $lastNoDrag.X, $lastNoDrag.Y, $lastNoDrag.Z, $maxNoDragError)
if ($null -ne $Range) {
    $selectedCrossing = Get-PointAtHorizontalRange $exact $Range
    $selectedTargetY = $OriginY + $TargetDeltaY
    $selectedVerticalError = $selectedCrossing.Position.Y - $selectedTargetY
    Write-Output ("Selected native trajectory at range {0:F3}: tick={1:F3}, position=({2:F6}, {3:F6}, {4:F6}), targetY={5:F6}, verticalError={6:F6}" -f `
        $Range, $selectedCrossing.Tick, $selectedCrossing.Position.X, $selectedCrossing.Position.Y,
        $selectedCrossing.Position.Z, $selectedTargetY, $selectedVerticalError)
}
if ($null -eq $firstNoDragDivergence) {
    Write-Output "No-drag estimate stayed within 0.1 blocks for the whole trace."
} else {
    Write-Output ("No-drag estimate first exceeds 0.1 blocks at tick {0}." -f $firstNoDragDivergence)
}
Write-Output ("CSV written to {0}" -f (Resolve-Path -LiteralPath $CsvPath))
