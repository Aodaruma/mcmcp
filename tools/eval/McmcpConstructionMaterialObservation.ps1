# Definitions only; current delivery-backed construction surfaces.
function Get-MaterialCellBounds {
    param([int]$X, [int]$Y, [int]$Z)
    [ordered]@{ dimension = 'minecraft:overworld'; min_x = $X; max_x = $X
        min_y = $Y; max_y = $Y; min_z = $Z; max_z = $Z }
}

function Get-MaterialSurface {
    param([string]$Block, [int]$X, [int]$Y, [int]$Z, [AllowNull()][string[]]$Faces)
    $current = Wait-ForCurrentVisibleSurfaceRecords -InitialState (Get-FreshState) `
        -Block $Block -Bounds (Get-MaterialCellBounds $X $Y $Z) -Faces $Faces
    return $current.records[0]
}

