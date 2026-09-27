# 所持建材からの設置用参照

Issue #48 / PR #49。製品処理の検証対象は`9ba493171871d3a29fe0df59cc3cdb057d3ee2c3`、比較元は`0047c2460005c8b7c35b87eecdd6eb09a8709bdd`。Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25を使用した。

## 問題と変更

従来は所持品に黒羊毛があっても、ワールド内の可視見本からしか設置用参照を取得できず、再起動で参照を失うと初設置を開始できなかった。`agent_get_state.placement_materials`は、実所持の通常BlockItem、default components、正常stack数、既存建築policy、propertyなし・状態一意のfull-cubeを検証してitem/count/state/placement_state_refを返す。

参照はHTTP配送成功後だけ有効になる。可視見本と同じ最大512 identityの記憶を使い、座標TTLでは失効せず、world session変更で消去する。所持品は座標証拠を生成せず、支持・目標・JIT・通常設置・server block/inventory確認を維持する。公開5 Toolの数は変えず、catalogと参照更新情報の`alternative_sources`を同期した。外部施工runnerは所持建材の参照を優先する。

## ソース検証

`tools/check-source.ps1`全件合格。最終buildでJava unit 1,292件が成功し、harness/adminBridge、配布物の分離、Python transport 14件、建築台帳・回復、capability mockを確認した。追加のGetState配送transport試験も成功した。

所持黒羊毛の見本なしprojectionと通常stackだけの個数集計、配送前/破棄後のref拒否、session消去、60秒の座標TTLとの分離、非標準components・過大stack・方向/部分形状/動的材料の除外を確認した。独立したCodex CLIレビューは上記製品commitを読み取り専用で確認し、具体的で再現可能な問題なし。レビューは実機合格の代用としていない。

## Docker実機

利用者が許可したremote Dockerの既存検証cloneを使用し、元containerは停止を維持した。開始前にsave・MOD・instance.cfg・options.txtを保全した。新しい`construction-inventory` fixtureは既存の端設置arenaを使い、雪・黒羊毛の見本を置かず、チェスト内に各64個を用意する。正規fixture適用は各runのT0前のみ。T0からterminalまでは公開MCPによる有限capability gateを実行し、画面観測・追加入力・admin操作を行っていない。

`Invoke-McmcpFloorExtensionCapabilityGate -InventorySources`でチェストから材料を取り、`agent_get_state.placement_materials`を設置元に使用した。maxFps=10、Vsync無効。run01終了後にMinecraftを通常終了・再起動し、fixtureを再適用してrun02を実行した。

| 試験 | run01 | 再起動後run02 |
| --- | --- | --- |
| 東・東・北・西への床4枚 | 成功、44/37/50/50 tick | 成功、44/37/50/50 tick |
| 各設置の材料消費 | 1個ずつ | 1個ずつ |
| block server確認・対象再観測・着地 | 全4枚確認 | 全4枚確認 |
| crouch実移動後の取消 | 19 tick、材料不変 | 19 tick、材料不変 |
| 全Action terminal / READY | 確認 | 確認 |

最初の黒羊毛設置Actionはrun01が`0ff1ce5d-adf7-4afe-9708-eb3faaf4b81b`、run02が`7dda7d36-a7cf-4a7f-9934-513cc71e0d22`。いずれも`floor_extension_complete=1,server_confirmed=1`を記録した。run02は所持品projectionの履歴も保存し、黒羊毛64個と完全stateから得たrefの利用を確認した。run02終端のhealth/hungerは20/20。input owner自体は公開APIに直接露出していないため、直接検査したとは扱わない。

復旧ではsave 61ファイルのhash、MOD群、instance.cfg、options.txtの一致を確認し、追加fixtureを削除、検証containerを停止した。元containerは停止のまま。検証は定型capability gateであり、初見LLMによる自律施工全体の合格とは区別する。旧版との比較実機runは行っていない。

## Artifactと限界

- 製品JAR SHA-256: `fa9b1f59a0f34bc1ffd2cae204b83f73d0228bb20611dcf60c452f38dbf330cb`
- catalog SHA-256: `923d67bd0a0f96f258248727fa0e0b0b148e8c5f54cff37cf39d9b492fd95f24`
- 保管単位: `artifacts/20260914-inventory-placement`（build receipt、unit XML、独立レビュー、各runのresult/events、run02の所持品履歴、fixture/復旧receipt）。元save・認証情報は公開物へ含めない。

実機で設置した材料は雪と黒羊毛。方向を持つ木材、階段、松明等へ所持品経路を拡大しておらず、既存対応stateは引き続き可視見本を使う。元プロファイルへの導入は施工担当の帰還・全Action terminal確認後に行い、このDocker結果を元現場での導入・改善確認と混同しない。
