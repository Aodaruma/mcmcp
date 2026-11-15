# 観測カーソル解放・最新frame取得の実機確認（2026-11-15）

PR #88 / #89、Issue #10 / #7 の公開HTTP経路を、許可済みの隔離Dockerで確認した。通常profile・元ワールド・常用認証設定には操作していない。

## 候補と前提

- 製品source: `846cb934b57fd9167533be27cc6a149f2c446e2a`（#89、#88を含む）。
- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25、既存の隔離Prism profileを使用。
- 製品JAR SHA-256: `c0e1b3938c515cc6e9c1f285f74053a3e0e0b6eff4e7382b37f5a48961554206`。
- dev-only admin JAR SHA-256: `9b4aeb58e57c6ac3353fe8d553f55971cf11b50766506b4e9c2cd7dc6a584230`。試験中にfixture mutationは呼んでいない。
- 起動前にworld 61ファイルを含む66ファイルとJAR・設定をhash付きで保全し、45分の排他leaseと期限時の復元処理を用意した。
- 観測試験は読み取り専用のためMCP OFFのまま実施。各試験フェーズの実行中は画面観測・物理入力・admin補助を行わず、公開MCPだけを呼んだ。

## 実機結果

基本17チェック、world未接続時3チェック、同一worldへの再login後4チェックの合計24チェックが成功した。合格フェーズの公開MCPは合計26要求。

| 確認 | 結果 |
| --- | --- |
| `frame_id:null,cursor:null` | 具体的なimmutable frame IDとcursorを返した |
| 明示frame IDによる別query | 同じframeを選択した |
| 未完了lease 2本で3本目を開始 | `SERVER_BUSY`で拒否した |
| 1本のcursorを解放して別queryを開始 | `released`の後、3本目を取得できた |
| 解放済みcursorの再使用 | `INVALID_CURSOR`で拒否した |
| 解放していない別queryの継続 | 元frame ID・元completed tickを維持した |
| 異なるqueryへのcursor流用 | `INVALID_CURSOR`で拒否した |
| 続きページで完了したcursorの解放 | `completed`を返し、配送receiptを新規生成しない実装と整合した |
| completedを30秒後に再度解放し、その後に元のidle期限を超過 | 最終的に`INVALID_CURSOR`。66.59秒の経過を記録し、解放が元の60秒期限を延長しないことを確認した |
| 古いframeの再指定 | `FRAME_EXPIRED`。cursor失効と区別した |
| 古いframe失効後の最新指定 | 新しい具体frameを取得できた |
| SaveQuit後 | worldなし・Actionなし。観測とcursor操作は上位の`NO_WORLD` gateで拒否した |
| 再login後 | world sessionが変わり、旧cursorは`INVALID_CURSOR`。最新frameの取得と新cursorの解放は成功した |
| 読み取りフェーズの終端 | Actionなし、HP20、session境界以外でsession変更なし |

world未接続時の初回試験は`NO_FRAME`を期待したが、実装どおり上位の`NO_WORLD`が先に返ることを確認し、試験の期待値を修正した。これは製品の失敗・修正ではない。実機でworldが存在しframeだけが未公開の瞬間は強制していない。`NO_FRAME` / `FRAME_EXPIRED` / `INVALID_CURSOR`の個別条件は、同じsourceの`ObservationFrameStoreTest.latestSelectionDistinguishesNoFrameExpiredFrameAndInvalidCursor`を含む単体試験で確認した。

## ソース検証

Java 25で`test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。通常1,341件、harness 13件、admin bridge 27件、合計1,381件で失敗・error・skipは0。製品コードは実機確認後も変更していない。

## 終了と復旧

最後に通常のSaveQuitを行い、公開stateでworldなし・Actionなしを確認した。終了helperの関数名がcallee内のDocker CLI名を隠す問題により、対象container停止後・world移動前で一度停止した。cleanup-requiredと排他markerを保持し、関数名だけを修正して同じbaselineから復旧した。

復旧後は元66ファイルすべてが元SHA-256に一致し、worldの61ファイルの個数と内容、元の製品・開発JAR集合、options・instance・client設定も一致した。対象containerは停止、leaseは解放済み。Docker Desktopは試験前から稼働していたため、その稼働状態を保持した。

公開MCP記録・各フェーズ結果・保存確認・復元receiptを含む8ファイルの証拠ZIPは、送受信側ともSHA-256 `581C57D70199376AB2700F3A2EE63128A82FF43FDD75477132299AE51E093FF3`で一致した。秘密値・認証設定・元/試験後worldはこの証拠ZIPへ含めていない。

この記録は#88/#89の観測API受入を対象とする。world mutation、Action安全性、v2への統合、最終Release JARの確認は別の検証で判断する。
