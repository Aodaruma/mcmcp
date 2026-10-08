# v2生産系の隔離fixture

通常profileでは使わない。許可済みの検証world・MOD・options・instance設定を停止中に退避し、相対path・file数・SHA-256を記録する。利用者は現在、aod-mimoidの隔離Dockerを時間帯にかかわらず許可している。

`datapack` を検証saveの `datapacks/v2production` に配置してworldへ入る。fixture commandはゲーム側の準備担当が実行する。MCPや評価runnerから任意commandを公開・実行しない。座標300～320／高さ100～108の範囲とinventoryを書き換える。

| 試験 | 最初に実行するfunction | 確認 |
| --- | --- | --- |
| Craft | `v2production:craft` | 板材・棒・かまどを2回ずつ製作、数量の保存 |
| Script | `v2production:craft` | scriptから製作→inventoryを逐次実行 |
| Cake | `v2production:cake` | hotbarの空き枠準備、ケーキ2個、バケツ6個の返却 |
| Smelt | `v2production:craft` | 鉄4個、石炭1個、残燃料回収 |
| Brew | `v2production:brew` | 水入り瓶3本→奇妙なポーション3本 |
| BrewFilled | `v2production:brew_filled` | 台の残燃料、材料のブレイズパウダー1個→力3本 |
| BrewCancel | `v2production:brew` | 投入確認後の取消、残留物・加工継続可能性・画面解放 |
| MoveStop／Cancel | `v2production:cancel` | 原木64個で部分移送の期限停止／クラフト取消、cursor・材料の保存 |

各ケースの準備後にMCPをREADYにし、sessionを取得して実行する。ケースごとに未使用のartifact directoryを指定する。

```powershell
pwsh -File tools/eval/Invoke-McmcpV2ProductionSmoke.ps1 `
  -TokenPath '<隔離profileのtoken path>' -Endpoint 'http://127.0.0.1:8765/mcp' `
  -ExpectedWorldSession '<現在のsession>' -ArtifactDirectory '<新規保存先>' -ProductionCase Cake
```

runnerは既存のv2 smokeと同じ配送・照会・取消処理を共有する。tokenは記録せず、引数・action ID・terminal・inventoryを `result.json` に保存する。応答を失った開始は再送しない。復旧未確認のjobがある間は次のfixtureを適用しない。

終了後は全job停止を確認し、保存終了・検証JVM/container停止後、save全体・MOD・設定を復元する。追加datapackも元に戻し、file数と全hash一致を記録する。runner単体試験と実ゲーム合格は区別する。
