# 足場・石段への接近時の停止（2026-10-11）

14:44 JST追記：リモート担当の完了報告と、保存した最終receiptの検証成功出力を取得した。4ケース成功、元66ファイルの復元・Docker停止・lease解放を確認。接続断で取得できていなかっただけで、復元は10:12 JST台までに完了していた。現場の停止原因は未確定。

## 受領した事実

差し替え担当が通常profileへのnav-r14導入を確認し、地図アート担当へ操作権を返却した。非公開の導入記録では製品SHA-256 `cfa6961ecfec3b05117387fa21c2cdbd93eaf2baf190df48eed850af62d834e5`、新JVM・session、MCP READY、公開16ツール全定義一致、検証用MOD不在が確認されている。

地上の外側床から3block上の石段を目標にしたmoveが、別の足場上まで移動してから停止した。`clear_path:false`、距離14、360tick、`auto_replan:true`。Action `26e49d0e-f1eb-487c-887d-11db9c741398` は320操作／16.00秒で `route_replan_limit`、最後の再計画理由は `movement_stalled`、再計画8回、体力20、落下損傷0。

続く新観測と、足場の上面に沿った短い区間のmoveは成功し、色替え作業を継続できたとの報告がある。`auto_replan:false` の一段階では境界で `route_edge_changed` も記録された。通常ゲームの並行操作・緊急差し替えは依頼されていない。

非公開根拠は現場担当の `nav-r14-stair-approach-failure-20261011.json`。通常world・認証情報・設備座標は公開しない。旧nav-r13の同条件対照はなく、nav-r14による回帰とは断定しない。

### 10:38 JSTの現場完了報告

地図アート担当はnav-r14の公開MCMCPで収集を完了した。`completion-20261011.json` では全16色の必要147,456個を充当、完成品159,088個、残数0、余剰11,632個、体力20、収集スケジュールPAUSEDを確認した。補助根拠は `mixed-batch660-yield-20261011.json`。通常ゲームの所有は現場担当にあり、設備停止・Actionなしの報告を受領した。今回の収集のための追加交換・差し替え・操作は不要。

この収集完了を、残存する経路問題の解消や隔離環境の復元完了の証拠としては扱わない。復元は以下のリモート担当のreceipt検証出力で別途確認した。経路問題の原因は引き続き未確定。

## 調査の切り分け

`movement_stalled` は実行器がwaypointへの進捗を20tick確認できなかったことを示す。経路の選択、足場の支持・衝突判定、入力と実際の変位のどこで止まったかは、この理由だけでは分からない。保存された終了時の公開観測は表面情報が中心で、停止中の内部waypoint・経路edge・入力guardの判定は含まれていない。

前回の[上階観測後の不要な迂回](20261011_v2_scaffold_detour.md)とは別の事象として扱う。短区間で成功したことだけを根拠に、安全判定を緩めたり再計画上限を増やしたりしない。

## 隔離fixture

検証harness `97d0cf8ac9dcad2ee2d1dc8ab1339666cb74976d` に、単純なL字足場と3段の石段を持つ2つの固定caseを追加した。製品コードと公開APIは変更していない。必須Gradle task・build・製品隔離、自動1,849件、source checks、[CI](https://github.com/Aodaruma/mcmcp/actions/runs/38100345342)が成功。

- `scaffold_corner`：足場上端の中心からずれた `(311.82,104,304.42)` で開始。
- `scaffold_stair`：同じ地形の地上 `(301.51,101,304.28)` で開始。
- 後続harness `2219404fa4f0e8a9d698463ee044efd0903c344f` の `scaffold_step`：東側に1段低い足場を加え、`(314.82,103,304.42)` で開始。現地観測に異なる高さの足場があるため、この境界を切り分ける。harness14件・build・製品隔離の再確認が成功。
- 共通目標 `(307,104,301)`、距離24、400tick、`clear_path:false`、`auto_replan:true`。地形・開始位置は固定した合成条件で、現場のworldを複製しない。

新しいrun `20261011-v2-scaffold-stall` でnav-r14を確認した。停止を再現した場合のみ隔離専用の診断ビルドを使う計画だったが、4ケースとも成功したため診断ビルドは作成していない。

### 確認できた結果

| case | Action | 結果 | 操作数／時間 | 最終体力 |
|---|---|---|---|---|
| 既存 `scaffold_column` → 上端横移動まで | `60e99a6e-bef4-4e21-9d4d-e0594fd96e66` | succeeded | 89／4.493秒 | 20 |
| 既存 `scaffold_top` → 横移動 | `3bb25922-97e1-4a18-acef-4bfcc5991795` | succeeded | 33／1.709秒 | 20 |
| `scaffold_corner` | `a0f39a4e-7b8c-4219-9152-f27f1269d93f` | succeeded | 92／4.640秒 | 20 |
| `scaffold_stair` | `58c97204-8428-4fa5-a5c5-f8bdb8fa74c8` | succeeded | 159／7.992秒 | 20 |

最初の2件は保存された公開Actionの終了値を確認し、再計画はcolumnが1回、topが0回。後続2件を含む最終結果は、リモート担当が保存した `validation-summary.json` を検証するコマンドの終了0と `FINAL_RECEIPTS_VERIFIED` を確認した。4件とも開始条件が有効、終了stateがsucceeded、最終health20、終了後Actionなし。topの終了後に観測paginationの `SERVER_BUSY` が一度起きたが、移動を再送せず観測のみ取り直した。

`scaffold_step` は復元・Docker停止後に追加依頼が届いたため、「復元開始後は再開しない」の指定に従い未実施。harness追加の[CI](https://github.com/Aodaruma/mcmcp/actions/runs/38101010100)は成功している。確認した単純条件では現地の停止は再現しておらず、本件に対する製品修正・修正版JARはない。

## 接続断と復元の確認

リモート担当は開始前の66ファイルを退避し、前回復元時のhash一致を確認した。Dockerの起動時に既知の一時IPC障害が再発し、確認済みの可逆退避手順で復旧した。占有leaseの期限は10:48 JSTで、復元・container/Docker停止・lease解放まで行うよう依頼済み。

角の成功報告後、aod-mimoidのCodex hostに接続できなくなり、追加結果・復元receiptの取得が止まった。単独SSHも再認証待ちで初期応答timeout。この試行で起動したSSHと認証子プロセスは終了したことを確認し、既存の常駐サービスは終了していない。

既存手順に従う空lock退避と単独quiet loginのコマンドは、実行前の自動承認審査で拒否された。具体的な理由は返されず、退避・再loginは実行されていない。別の方法で同じ変更を強行しない。

14:44 JSTのCodex経由の読取りで、新runの最終receiptを検証した出力を取得した。10:11:33 JSTに元66ファイル（world61ファイル）・元JAR集合のhash一致とfixture撤去を確認し、10:12:54 JSTまでにcontainer停止、Docker Desktop・docker-desktop WSL停止、engine到達不可、Dockerプロセス0、lease lock不在を確認。leaseプロセスも `LEASE_COMPLETE` / exit 0で終了した。lease期限10:48:31 JST以前に完了している。SSHの認証回復は別件であり、今回の読取りで認証設定を変更していない。

根拠は隔離run内の `validation-summary.json`、`restoration.json`、`shutdown-final.json` と、これらを検証した担当チャットのコマンド出力。runは約1,774 MiB、うち再生成可能なGradleキャッシュ約1,640 MiB。baseline・証拠・退避IPCを含め、自動削除はしていない。

追加実機試験は再開していない。次の調査では未実施のstep境界と、停止時のwaypoint・入力・実変位の証拠を切り分ける。通常ゲームへの操作・追加差し替えは行わない。
