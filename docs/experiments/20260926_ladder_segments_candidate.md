# はしご区間移動候補（2026-09-26、#81）

状態: 候補実装、単体・契約試験合格。2026-09-28に隔離Dockerで主要な昇降・停止・乗移りを確認。未試験の境界があり、main統合・配布とは区別する。公開Toolは5本を維持し、既存の`navigate_to_known`を拡張する。

| 項目 | main | 候補 |
| --- | --- | --- |
| 段数 | 観測入口から上下4段 | 局所観測の範囲まで。各Actionを分割し、全体高さに固定上限を設けない |
| 目的地 | 床付きlanding | 観測済みladder中間段も公開 |
| 停止 | 全Action入力を解放 | Action入力・速度を解放し、安全なladder上では別所有者がSHIFTのみ保持 |
| 再開 | 床へ着地してから再計画 | `ladder_hold`確認→再観測→次の区間をnavigate |
| 乗移り | 同じ高さの隣接床 | 途中の横/対岸の隣接床に加え、1 block高い上端へ上昇→横移動 |

未観測の最終着地点は、初めに座標を認可する必要はない。観測済み区間を進んでから次を選ぶ。半径6 blockの局所観測、読込・取付・身体経路の検証、各Actionの32 block距離上限と時間予算は維持する。欠損段、gap jump、斜め角抜けは認可しない。scaffoldingの4段・床終点の仕様は変更しない。

`ladder_hold`はActionそのものではない。成功・取消・失敗時の停止地点が安全なladderなら保持し、新しいmovement leaseが優先する。手動移動/JUMP、MCP OFF、ladder離脱・破壊・危険化、死亡、player/world変更で解除する。待機中の戦闘や自動救助は行わない。ladder始点との接触だけでは空中の終点を公開せず、現局所観測で確認した段だけを停止地点にする。

単体試験は長い昇降の区間分割、未観測の次区間拒否、各方位・上端の経路、SHIFT保持と再開・手動復帰・OFF/境界、空中contactを停止地点にしないこと、上端の初動と水・未知床の拒否を含む。Java 25で`test harnessTest adminBridgeTest verifyHarnessIsolation build`が成功した。独立レビューは未実施（同セッションのCodex CLIでローカル読取基盤の欠落を確認）。自身の差分確認と独立レビューは区別する。

2026-09-28、SSH `aod-mimoid`の隔離Docker `mcmcp-hard-building-20260902-construction-validated`で、PR HEAD `d7c8b21`の製品JAR SHA-256 `3A43516C1C3906AA224A62AF708D12D4E4FDE43D2F87D19E9C7D99F0C3717595`を試験した。試験用の10段のladderを作り、元world・MOD・設定を事前に89ファイルのSHA-256付きで退避した。

- 初回観測に上端が入らない状態で、`202,204,200`へ34 tick、再観測後`202,209,200`へ58 tickで区間上昇した。両Actionは`succeeded`、停止後の`ladder_hold=true`で位置の高さが約9秒維持された。
- 上端床`201,211,200`へ40 tickで通常乗移りし、床上で`ladder_hold=false`となった。途中で2 tickの安全再計画が入ったが完了した。
- 途中床からladder段`202,205,200`へ15 tickで移り、`202,202,200`へ31 tickで下降した。そこから途中横床`201,205,200`へ51 tickで乗移った。各Actionは`succeeded`で、段停止時だけ`ladder_hold=true`だった。
- 観測外の`202,220,200`は`TARGET_UNKNOWN`でAction開始前に拒否された。各成功Actionの破壊・設置・interactionは0、体力は20のままだった。
- 終了後にDocker containerを停止し、world・MOD・設定の89ファイルを退避時のSHA-256と全件照合して復旧した。Docker serviceも停止した。通常プロファイルや現場ゲームは触れていない。

次の境界は未試験のため`release:verification-needed`を維持する。

1. 下降方向も、全体が最初の観測に入らない長いはしごで複数Actionに分けて完遂する。
2. 途中の対岸床への乗移りを試す。
3. cancel・Esc、手動移動、明示OFF、死亡・disconnect、欠損・水・低天井で所有権と結果を確認する。

バッグの共通収納対応は別の[PR #82](https://github.com/Aodaruma/mcmcp/pull/82)。後日の仕様見直しでは、こちらの表と#82の比較表を合わせて扱う。
