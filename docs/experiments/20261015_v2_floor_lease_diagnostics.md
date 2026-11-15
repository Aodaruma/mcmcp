# 床延長の入力期限切れ診断と確定済み設置の保持

2026-10-15。R30候補は、床延長が入力期限切れで停止した際に、内部で生成した診断5件がv2の返却時に失われる不具合を修正する。最大2秒の入力期限、停止条件、施工予算、再開位置の意味は変更しない。通常環境のR29cで起きた遅延の原因そのものを解消した候補ではない。

## 通常環境で確認した事象

施工担当の保存結果 `blueprint-085020-086556.result.json` では、親Action `083af746-746d-465c-a3b7-a21b47f16796` が約3,242秒で `script_command_failed` となった。停止した子 `de5c3af1-9889-43f6-9548-f058fb874034` は25操作・約4.890秒で `floor_extension_input_lease_expired`。子の120秒予算や親の6,264秒予算の使い切りではない。

子の結果には、空気からpink concreteへ変わった `(-615,79,414)` の確定1床が残る。親の `placed_blocks=1370` と設計図の `completed_entries=1369 / next_index=86389` の差は、設置確認後の前進で失敗した子を、完全成功した子のカーソルへ加算しない既存契約によるもの。二重計上の証拠ではない。施工担当が成功prefix1369＋確定1を一度だけ記帳し、再観測後に未施工6床を完了したとの報告を受領した。通常ゲーム・worker・台帳は施工担当の所有を維持した。

内部の床延長処理は各tickの開始時に既存期限を検査し、終了時にheartbeatを行っている。保存された経過時間と操作数だけでは、GC・OSのスケジュール・tick内の処理など、遅延源までは区別できない。

## 修正と返却契約

`MinecraftV2PlaceDriver` が床延長の `Result.diagnostics()` を保持し、`V2BlockJobExecution` が入力解放前に既存の確定セル結果へ追加する。解放が一度失敗して次tickへ持ち越されても、診断や確定済み床を失わず、設置を再送しない。

`agent_place_block`／`agent_get_action` の `result.failure_diagnostics` に、次の最大5件の短い文字列を返す。

- `floor_extension_phase`：失効したtickの開始時の段階。
- `floor_extension_lease_check`：`tick_entry` または `heartbeat`。
- `floor_extension_lease_overdue_ms`：入力期限からの超過ms。
- `floor_extension_lease_tick_gap`：直近の更新からのclient tick差。
- `floor_extension_executor_ms`：当該tickの実行器内で経過したms。

script／設計図の親では `last_command.action.result.failure_diagnostics` に同じ診断を保持する。子の診断コピーの既存上限（256 nodes・depth 8・8 KiB）内で、巨大なセル一覧より先に保持する。1件の文字列は内部結果の既存上限128文字に収まる。

`next_index` を失敗した子の確定数だけ無条件に進める変更はしない。確定セルと新しい現場観測を照合し、利用側が次の有限Actionを指定する。[設計図の部分結果契約](../PUBLIC_API_V2_BLUEPRINT_TOOL.md#大きな設計図と再開位置)を参照。

## 自動試験

`test harnessTest adminBridgeTest verifyHarnessIsolation build` 成功。Javaは1,936件（通常1,894・harness14・admin28）、失敗／skipは0。`tools/check-source.ps1 -SkipJava -PythonExecutable python` も成功した。

追加した回帰試験は、設置0／1の停止と解放の一時失敗、診断の保持、二重計上・次セルの再送防止、失敗子の確定床と保守的な設計図カーソル、巨大receipt内での診断優先保持、公開schemaへの適合を検証する。

## aod-mimoidの隔離実機試験

許可済みDockerのprivate single-player fixtureを使用した。追加のfault injectionは検証JARだけに含め、`mcmcp.testHarness=true`、非公開のintegrated server、明示的なfixture開始とallowlistのmode指定をすべて満たす場合に、1回だけ3秒停止する。製品JARからの隔離はbuildで確認済み。

| ケース | 結果 | 確定設置と診断 |
| --- | --- | --- |
| 遅延なしの2色2床 | 成功、3.745秒 | 新規2床・next_index 2 |
| 設置前にtick間で3秒停止 | 期限切れ停止 | 新規0床・next_index 0、tick_entry・超過1,065ms・tick差1・実行0ms |
| 設置確認後にtick間で3秒停止 | 期限切れ停止 | 確定1床を親と子に保持・next_index 0、tick_entry・超過1,066ms・実行0ms |
| 設置確認後にtick内で3秒停止 | 期限切れ停止 | 確定1床を親と子に保持・next_index 0、heartbeat・超過1,033ms・実行3,000ms |
| 直前ケースから再観測して再開 | 成功 | 既存一致1＋新規1・next_index 2。黒／灰の所持数は各63で、消費各1を確認 |
| 16ticksの親予算 | script_timeout | 新規0床・次の子へ進まない |
| 床端への接近中に取消 | cancelled | 新規0床・次の子へ進まない |

全7ケースでHP20・接地・終端Actionなしを確認し、fixtureの入力所有なしの検査を通してserver側の床と数量を照合した。再開では公開 `agent_look` → `agent_get_observation` により既設床を新たに確認し、`agent_move(sneak=true)` で中央へ寄せてから、同じ設計の新しい有限jobを実行した。既設床を再設置せず、未施工の床だけを設置した。

設置前の遅延試験では、停止中の `agent_get_state` が `SERVER_BUSY` となったため、保存済みAction IDから結果を後で回収した。施工命令は再送していない。以後の遅延試験は停止期間を待って同じIDを照会した。意図的なclient停止中にも状態取得が即応することを保証する試験ではない。

04:36 JSTに保存終了後、退避した66ファイル（world61）をhash一致で復元した。追加fixtureを撤去し、Dockerプロセス0・WSL停止・検証lease解放を確認済み。

## 配布物と適用範囲

候補は作業ルートの `artifacts/v2-floor-lease-20261015-r30/package/` に凍結した。

- 製品 `mcmcp-neoforge-26.2-0.1.0-SNAPSHOT.jar`：SHA-256 `215077e1a7b926291f4a8b25b636e882c493dbf38945a242a729989f6f951a1e`。
- 検証専用 `mcmcp-neoforge-26.2-0.1.0-SNAPSHOT-test-harness.jar`：SHA-256 `d6e21630c55bdc1b6b090e3bb56b47366165ec0c1ec9f4a622dd8e2e9133407c`。通常profileへ入れない。
- `candidate.json`、`source-manifest.json`、`source-overlay.zip` にbase commit、変更ソース、hashを保持。R29c製品との差は診断配送に関わる3クラス群とcatalogのみ。

通常profileはR29cのまま、この開発チャットからの差し替えは行わない。候補は再発時の切り分けを改善するもので、元の遅延原因や再発率の改善を証明しない。松明境界の速度改善は別の[worker I/O変更](20261014_v2_torch_worker_speed.md)であり、このJARによる高速化とは区別する。
