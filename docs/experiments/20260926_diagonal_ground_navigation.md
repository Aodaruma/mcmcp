# 角だけで接する足場の斜め移動

Issue #74。rc.4には含めず、PRで統合判断を待つ。
対象はMinecraft 26.2 / NeoForge 26.2.0.59 / Java 25。

## 実装と安全条件

従来は斜めedgeに加え、出発cellの両直交edgeがCONFIRMEDであることを必要としていた。
直交先が空気でも対角のfull-cubeへ身体の支持を保って渡れる配置を、経路候補として評価できるようにした。

- 同じ高さの対角隣接full-cube、余裕付きの連続支持、実端点とcell中心の立位corridorの衝突・fluid・危険物・loadを局所確認する。
- 内部の追加証拠をsession/current revisionへ束縛し、候補は実通過までPROBE_ALLOWEDのままにする。
- planner、予約の依存edge、実行時検査で同じ判定を使う。追加証拠がない場合は従来の直交edge条件を維持する。
- この候補はcrouchで移動し、40tick/edgeを追加予約する。移動開始後に新たな低速条件へ切り替えて予算を暗黙に増やさない。
- 各tickのVanilla最終collision/support gateと有限停止を維持する。公開Tool/schemaは変更しない。

## ソース検証

通常Java1,333件、harness13件、admin bridge27件、計1,373件とisolation/buildが成功。
四方向・負座標・開始位置の中心ずれ・支持間隔・段差・低い身体の立位clearance・部分CONTACTの全corridor、
追加証拠の伝搬/撤回/同tick優先順・revision失効・従来corner条件・経路予算を検査した。
製品差分の独立Codex CLI静的レビューは必須修正なし。対象の凍結差分と結論をPRへ記録する。
レビュー後のmain追従はrc.4の配布記録とgateだけで、製品コードに追加変更はない。

## 隔離Dockerでの軽い実機試験

ユーザー指定SSH先の削除可能な検証cloneを使用した。
[fixture](../../tools/eval/fixtures/diagonal74/fixture.json)と[setup](../../tools/eval/fixtures/diagonal74/setup.mcfunction)で、
高さ60のfull-cubeを菱形に4個配置し、直交先の同じ高さには床を置かない。
下方に回収用床を設け、通常profile・本番ゲームには操作していない。

- 候補JAR SHA-256: `28c3a8331a54411c464ab37f2baa8756ce13ae35ba9d5ce03327907064acd05e`
- fixture-admin JAR SHA-256: `9b4aeb58e57c6ac3353fe8d553f55971cf11b50766506b4e9c2cd7dc6a584230`
- fixture SHA-256: `7cf6c45ef3669dc63bbf35504fa257bd9907d3b1556beb40b6fa36a35ddfdaeb`

T0前にfixture適用とUIによるMCP ONを完了し、以後は[公開MCPのgate](../../tools/eval/Invoke-McmcpDiagonalNavigationGate.ps1)だけを実行。
目標はfresh traversabilityから配送されたnavigation_targetを無変換で使用した。

| 方向 | Action ID | 判定 |
| --- | --- | --- |
| X+, Z- | `111949cd-27be-4824-ad97-4d1224ebd76e` | succeeded |
| X+, Z+ | `55e93f12-8ca5-42ae-83ea-758649ec00ef` | succeeded |
| X-, Z+ | `f9130702-3d7d-4474-b577-17e187573400` | succeeded |
| X-, Z- | `6fcaeb0f-a139-43bc-950d-8578e58ecb91` | succeeded |

全到着後にY=61と目標中心から0.25 block以内をreadbackし、health20を維持した。
全Action terminal、control ready、cancel不要を確認した。公開Toolはinput ownerを直接公開しない。
結果JSONのSHA-256は `d2468e3721d37a5d73eeaccff40b460ef50e0ef67cec267ab899399a5e130e02`。
監査は `20260926-release-diagonal/diagonal-run01` に保持する。

Minecraftを保存終了し、追加fixtureの2ファイル一致を確認して削除した。
元ワールド61ファイルの全hash・個数、MOD、instance設定、optionsを復旧し、検証コンテナを停止した。
元コンテナも停止状態を維持した。

これは決定的な機能smoke testで、freshモデルの自律完遂認定やユーザーのsand duper現場そのものの検証ではない。
段差、slab等の部分支持、角に障害がある配置、動的piston、MOD独自形状への新規対応を実機で認定しない。
実装は保守的な条件を満たさない場合、従来条件または安全停止へ戻る。
