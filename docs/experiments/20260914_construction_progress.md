# 平面施工の進捗保存・補充・再開（Issue #38）

利用者の依頼に基づき、最大128×128の行列を蛇行順に施工する外部runnerを追加した。固定5 Tool、既存のschema検証付きtransport、有限な`extend_known_floor`、通常container移送と床置きtorchを使う。MCP Toolの追加や無制限Actionは行っていない。使用方法と制限は[施工runner](../../tools/building/README.md)を参照する。

## 実装と自動検証

- 設計図ハッシュ、向き・高さ、セルのconfirmed/observed/pending、各確認のAction ID、材料収支を原子的に保存。排他lock、checksum、prefixとpending情報の検証を備える。
- 開始前intent→受付ID→terminal effectの順で保存。応答喪失は同sessionの最新Actionのnonceを照合し、別Action・履歴失効・通信不明から推測で再送しない。明確な受付前拒否は区別する。
- failed/cancelledのconfirmed effectを一度だけ反映して停止。unknown後の目視一致はobservedに分け、材料ACKを捏造しない。材料の不変なplacement-state identityだけを同session内で保持し、支持・座標・経路は再観測する。
- 補充の往復は確認済み床だけを使う。予算停止を挟んでも現在地点から補充を続ける。world session変更は既定で拒否し、pendingのない明示的な元saveへの結び直しだけを許可する。
- 初期在庫＋確認済み補充−消費を現在在庫と照合し、外部拾得などの差と未精算を表示する。全セル施工済みでも収支不明なら`built_with_unknown_balance`にする。
- Java `test harnessTest adminBridgeTest verifyHarnessIsolation build` PASS。追加fixtureのadminBridgeTest PASS。
- source checks: transport 14件、全capability mock、building ledger/recovery試験PASS。128×128の4回転、16,505件の確認receiptを含む8MiB未満の台帳、応答喪失・二重計上防止・破損・排他・不正pending・材料収支を確認。
- Codex CLIによる独立レビューを実施。診断保持、pending検証、observed表示、移動予算超過を採用できる条件を修正し、最終差分はブロッカーなし。subagentは使用していない。

## 指定Dockerの短い実機試験

SSH先の既存Prism/Minecraft Docker、削除可能なclone save、開発用fixture `construction-progress`を使用した。固定5 Toolの決定的runnerによるcapability試験であり、fresh LLM受入評価ではない。T0前にfixtureを適用し、run中に画面やadmin APIで操作していない。

- product JAR SHA-256: `5cda350ad77e7172f145a2fef069fbdf382c62aecd3f875760f09cc2fc9ae762`。
- 最終JARは`8cf05db25d1a71eddd6c5b7edd17589a4ea68e2ed4fea69be04fa3b3d0502a1f`。実機読込み版との全ZIP entry比較ではcatalog JSONの整形だけが異なり、JSONを解析した内容と全class byte列は一致する。最終版でもJava build・test・isolationを再確認した。
- 最終fixture SHA-256: `9162cbd75769fd361047b7199aca14dbdbce3254a5b015ab4613868ce609db6e`。16 command、宣言713 block、上限1000。
- 設計図は床Y=60、anchor=(-4,60,6)、回転0、行列`AB/BA`。A=snow block、B=black wool。床4セルの後、先頭セルの上にtorch1本を置く。
- chestに最初のsnow/black各1個と後続64個ずつ、torch16個を用意。すべて通常container操作で取得する。

初期試験では、移動の0.1到着許容を満たす前にnavigationが予算超過になった。失敗を一般的に無視せず、純粋な移動のみ・effectなし・`replanned_route_remaining_occurrence`と`REPLANNING/unverified_actual_movement`・health非低下・現在の中心範囲0.15と支持の再観測が揃う場合だけ到着姿勢を採用した。床とtorchの施工完了を2回確認した。

fixture再適用で既存torchから生じた落下itemを拾うケースも検出した。最終fixtureは事前に宣言範囲のitemだけを除去し、runnerにも外部在庫差の記録を追加した。

最終版は新しいcheckpointで、次の**別々のPowerShellプロセス**を起動した。途中のfixture再適用・追加gameplay入力は行っていない。

| run | 操作 | 結果 |
| --- | --- | --- |
| run08 | `MaxCells=2, Refill` | 床2/4でpaused。snow/black各1個取得・消費、在庫0。pendingなし。 |
| run09 | 同checkpoint、補充指定なし | `needs_material`。床2/4と収支を保持。追加消費なし。 |
| run10 | 同checkpoint、`Refill` | 元chestへの往復、残りの床2セル、torch1本を完了。`complete`、observed=0、unknown=false。 |

最終在庫はsnow63・black63・torch15。確認済み補充65/65/16、消費2/2/1で全て一致し、外部在庫差は各0。床4セル＋torch1本のconfirmed receiptを永続化した。

| 対象 | Action ID |
| --- | --- |
| floor 0 | `b53a205a-8c32-4378-9af5-cbcbab3b12a2` |
| floor 1 | `4a6aab6f-69f3-406f-a7b7-21ebd1d69615` |
| floor 2 | `c1e85bb7-b6af-41d1-a7f9-c36a725ac3ce` |
| floor 3 | `0cd8fda4-1513-4db1-a795-f296beee75c5` |
| torch 0 | `1e14c3ed-3807-414b-90d9-62536b47cb24` |

最終checkpointのファイルSHA-256は`fbc6700a11e58441e4197eedb8bd2b2ba9ed2445b8ee2f565ff507bd0d41335e`。terminal後に公開Toolで在庫一致・READY・全Action terminalを確認した。入力ownerは公開Toolで直接露出していない。古いAction詳細は履歴の保持期限で失効し得るため、各Action終了時に保存したreceiptと、事後の公開状態確認を分けている。

## 復旧と残る範囲

remote artifact `20260914-construction-phase3-r1`にrun結果、`job-audited/checkpoint.json`、`post-run-proof.json`、施工後saveを保存した。元saveの全61ファイル・元product/harness JAR・instance.cfgをバックアップとのSHA一致で復旧。元Docker container名も戻し、検証containerは停止した。通常の「くらふとぶ！」プロフィールはこの試験に使用していない。

128×128全施工、実QRの読み取り・地図描画、全Vanilla特殊ブロックへの対応、Minecraft自体のcrashを伴う未知Actionの自動復旧は、この軽い試験の合格範囲ではない。runner再起動・補充・保存の実機PASSと、最大サイズ台帳の自動試験を区別する。
