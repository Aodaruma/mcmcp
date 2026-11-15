# Issue #91 r6: 施工ジョブの飛行救助・復帰（2026-11-15）

対象sourceは `0eaba1bc2fbecf885df7abdb95baa6991c44e23a`、候補versionは `0.2.0-rc.1`。隔離Dockerの固定fixtureによる決定的なMCP-only回帰試験であり、fresh LLM評価ではない。r6全体や狭いpoolへの着地を合格とする記録ではない。

| artifact | SHA-256 |
| --- | --- |
| product.jar | `552e7af4c83d9cc1258f79f23cb8f86d37b52fb7be73d71c68357e93278015f7` |
| test-harness.jar | `a6ec51bd01bb32ecef55d874424555b933d14454e1310516034f776761828bf1` |
| fixture-admin.jar | `feb649f2557e395b8dea976869df6857ca1d0e7151318cea1bf05ee39f3c9fb2` |

sourceとcatalogを同commitから凍結し、配置JARのhashを照合した。固定fixtureの準備はT0前だけ、private oracleは公開jobのterminal後だけに使用した。実行中の画面観測・外部入力・world操作は行っていない。

## 行動と2つの結果

各runは次の公開Toolを1回だけ開始し、返された同じIDをterminalまで照会した。開始の再送や、別jobによる残り施工の補完はない。

```json
{"x":315,"y":120,"z":310,"dx":9,"block":"minecraft:black_concrete","advance":true,"max_ticks":1800,"max_distance":128,"wait_timeout_ms":0}
```

使用Toolは `agent_place_block`。既存 `recovery_build_flight` の発火は開始点付近のmovementOwnedであり、最初の1ブロック確定待ちではない。したがって実証したのは、設置ジョブの接近中に確認済みprefix 0で落下し、救助後に同じjobで0→10へ進む範囲である。送信済み設置のACK待ち中に落下するケースとは区別する。

| run | 回復設定 | 結果 |
| --- | --- | --- |
| 元設定の予算不足run | 200tick / 16m / camera360 / safe100 | `8455a367-97b5-4eed-afed-f1f1e0a3db3b`: 実落下・実飛行後、距離16.4179m・35tickで `failed/recovery_exhausted`、確認済み設置0。 |
| 設定を実機側へ統一した追加run | 600tick / 128m / camera360 / safe100 / flightObservation96 | `abd171b7-591d-42c6-a218-048f48188926`: 74.81秒、`succeeded`、同IDで確認数0→10。 |

16m runは製品の安全予算による停止の負例として保持した。終端時はHP20だがまだ飛行中で、放した後に通常の滑空で下床へ着地した。施工は再開していない。終端後のinventory inspectはunsupported_locomotionで拒否され、追加のAction IDは発行されなかった。600/128 runでは失敗後に新Actionを開始しないdriverへ修正した。

追加runは親担当の許可後に、保存してタイトルへ戻る→player=null/jobなしを確認→container停止→隔離コピーの設定変更→同JARをhash再照合→再起動の順で行った。hot reloadは使っていない。元設定・worldの保全は最初のbackupを保持し、試験設定の統一を製品の予算上限変更とは扱わない。

## 追加runの確認

- 公開結果でfall→実際のfall_flying、飛行deployed/observed ticks、`resumed`・stable_ticks100を確認。最初の飛行時の確認済みprefixは0、その後も同じIDで1～10へ増えた。
- terminal oracleはhazard_injected=true、actual flying112tick、乾いた接地のprofile sample9件、flight_return.landed=trueを記録した。profileは134samples・非truncated。
- 下床から上方の施工場所へ戻った後、fixtureのzombieが発火した。公開combat結果は通常攻撃3回、最低体力17、終端体力20。無傷の成功とはしない。
- 10個のconfirmed_cellsはx315～324/y120/z310の固有座標で、afterはすべてminecraft:black_concrete。材料は64→54、oracleの実block10個とも一致した。
- 終端はsucceeded、READY、running_action_id=null、空cursor。公開driverは87requests・共通assert92件だった。

## 判定器の訂正

初版driverは完了後のrecovery statusへ `stable` だけを要求し、暫定public_passed=falseとした。製品は施工再開後の正規status `resumed` とstable_ticks100を返しており、raw snapshotへ繰り返し残っている。親担当の確認後、正規の `stable` または `resumed` とstable_ticks100を、実落下・飛行・同IDでの施工再開・10セル・材料・終端状態・private oracleと結合して **PASS** と判定した。再実行していない。

元のfalseは `construction-flight-summary.json` と `construction-flight-oracle-summary.json` に保持した。訂正は別fileの `construction-flight-adjudicated-summary.json` にあり、raw公開応答は `final-public.jsonl`、private終端oracleは `fixture-TerminalReport.json` に残した。16m runは `negative-budget16-*.json` へ分離した。

## 復旧

2026-11-15 11:28:57 JSTに、元66ファイルの全hashとworld61ファイルを照合して復旧した。追加のfixture request/receipt2項目は元々不存在であり、その不存在も再確認した。合計68状態項目を復旧、container OFF、排他lease解除、元から稼働していたDocker Desktopは保持した。通常profileは変更していない。

evidence.zip SHA-256: `ecdf487af811870478fa893b30f19eeb460a814ae10169e658cfd72a6bd60496`。
