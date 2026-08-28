# wheat-original-v1

ユーザーが`tester (1)`に用意した既存のoak-fenced小麦畑を対象とするcold-start baselineである。固定空中arenaを新設しない。

現在のsaveは過去試験後であり、完全なpristine状態は残っていない。そのため本fixtureは「復元」ではなく、既知の施設を次の明示状態へ初期化する。

- player: Survival、空inventory、既存記録に基づく畑東側の開始pose
- chest `(-10,56,-14)`: netherite hoe 1、wheat seeds 64
- gate `(-11,56,-15)`: closed
- tillable 72 cells: dirt、cropなし
- central water/trapdoor support row: fixtureでは変更せず、既存構造を維持
- workspace item entities: なし
- `randomTickSpeed`: 3000、最大22分の復元付きlease

fixtureは評価T0前だけにapplyし、T0からturn terminalまではadmin bridgeを評価モデルへ公開しない。
