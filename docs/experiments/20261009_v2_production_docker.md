# 公開API v2 生産系の隔離Docker検証

2026-10-09 JST。Issue #84／Draft PR #85。生産系の最初の公開実装をaod-mimoidの既存隔離Dockerで検証した。通常の「くらふとぶ！」profileや、このPCの画面は操作していない。

## 候補と結果

最終候補は `production-r6`、`mcmcp-neoforge-26.2-0.1.0-SNAPSHOT.jar`。

SHA-256: `21040e897ec4dbf237d726aaa90ae414cb767909089eaa208870b69c3add78ae`

ローカルbuild、受渡し候補、隔離profileから回収した実JARのhashが一致。公開15ツールとcatalogの照合を各runnerの開始時に通過した。[fixtureと再実行手順](../../tools/eval/fixtures/public-api-v2-production/README.md)、[利用ガイド](../PUBLIC_API_V2_PRODUCTION_20261009.md)を参照。

最終候補で9ケース・24件の公開job（planと事後inventory照会を含む）が期待した終端へ到達した。script内の子jobはこの24件へ重複加算していない。

| ケース | 確認結果 | 主なaction ID |
| --- | --- | --- |
| Craft | 2×2で板材8個・棒8個、3×3でかまど2個。材料消費と所持品数量が一致 | `583aba73-3a92-4899-acba-372b4545000d`、`624c8377-090c-484f-bcfe-f5e5bea635c5`、`90e4f834-3415-41d7-9ac2-a1e8fdf59678` |
| Cake | hotbarを自動整理して空手を確保、ケーキ2個・空バケツ6個を回収。牛乳バケツは0個 | `1da9921f-b3e6-4480-8dd8-caa0f25ad5a6` |
| Smelt | 原鉄4個→鉄インゴット4個、石炭1個消費。未使用燃料を回収し、設備slot空を確認 | `63df9a6d-6d1b-485b-be61-eb021eb4abc6` |
| Brew | 水入り瓶3本→奇妙なポーション3本、回収・設備解放を確認 | `9ab915c2-8258-4eb7-9948-d8bfc12d0ed8` |
| BrewFilled | 燃料を20回分充填した台で、材料のブレイズパウダー1個だけを所持。力のポーション3本を回収 | `92d3581e-65c3-457b-89f4-e9962505c728` |
| BrewCancel | 投入の再開封確認後に取消。`processing_may_continue:true`、残留瓶・材料、画面解放を報告 | `8092fb14-d9a2-497a-a4a3-4d15337c9bd4` |
| Cancel | 原木64個で板材製作を取消。確定した板材4個と残原木63個、材料保存と次job受付を確認 | `6a1d0681-255b-4855-8a91-19f093d76ec5` |
| MoveStop | 64個のstackから1個を移送、3tick期限で停止。移送済み1個・返却63個・cursor解放を確認 | `0473eb34-95e2-407c-b316-c0d0f545e10c` |
| Script | `craft(...)`→`inventory(...)`を同じscriptで逐次実行。板材4個と原木15個を確認 | `69c645cb-aa39-4727-b52f-72f34f6ef251` |

公開job結果の `confirmed_count` 等だけでなく、次のinventory jobで数量と操作再開を確認した。ポーション成分は生産adapterのserver同期・再開封照合で確認し、item IDだけのinventory表示を種類の証明には使っていない。

## 自動検査と並行レビュー

- `gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build` 合格。Java 1,653件、harness 13件、admin bridge 28件、計1,694件。failure/error/skipは0。
- `tools/check-source.ps1 -SkipJava` 合格。Python transport 15件、施工台帳・回復、capability mocks、v2 smoke 7ケース、storage/stop 13ケース、新規production runner検査を含む。
- 利用者の許可に従いCodex CLIを別processで使用した。subagentは使用していない。CLIのread-only環境ではファイル読取りが拒否されたため、親processで選んだソースを渡し、ツール操作なしのレビューを3回行った。動作試験とは別のソースレビューである。
- 指摘から、材料配置の未確認取消、入力解放の独立実行、部分移送量、session切替時の解放、実際の製錬材料の報告、複数回の材料割当を修正し、対応する回帰試験を追加した。設備releaseの非同期進行に関する1件は、既存portの毎回の終了処理と実機取消により問題なしと確認した。

## 実機で修正した点

初期候補では醸造adapterへ照準情報が渡らず開始できなかった。受信した可視面のaimを渡すよう修正した。戻り容器試験ではhotbar全占有時に作業台を開けなかったため、所持品の空き枠へ正確に移送して空手を準備するようにした。

力のポーションでは、通常Shiftクリックのブレイズパウダーが材料枠ではなく燃料枠へ入った。材料singletonをhotbarへ準備し、通常の数字キー交換で材料枠へ投入する。cursorを保持せず、再開封のserver差分で照合する。

新runnerにはレシピ候補の絞込み不足と0個集計の不備があった。明示したfixture材料で選び、欠品は0として検査するよう修正した。失敗した実行記録も保持し、fixtureを再準備してから最終合格を記録した。

## 証拠と復旧

ローカルの証拠は `mcmcp/artifacts/v2-production-20261009/acceptance-r6.json`、`candidate-r6.json`、`restoration.json`。remoteの対応ディレクトリには各ケースの `result.json` と先行候補のJSONLを保持する。認証情報や元worldは公開資料へ含めない。

終了後に検証ゲームを保存終了し、containerを停止。元world 61ファイルを含む65ファイルをすべてSHA-256一致で復旧した。元のJARと設定を戻し、追加datapackが残らないことも確認。復旧receiptは2026-10-09 04:06:45 JST。

## 未実装・未確認

データ付き材料・特殊レシピ・連鎖クラフト、複数工程醸造、取引・金床・エンチャントはこの候補の対応外。MOD破壊、水中・乗り物、装備整理等は[後続計画](../PUBLIC_API_V2_PRODUCTION_PLAN_20261009.md)に残る。

新しいv2入口からの溶鉱炉・燻製器、全標準ポーション、全MODレシピ、通信拒否・遅延・切断の全組合せ、creativeでの生産、最終候補でのEsc／UI OFF／world変更の実機網羅は未確認。応答不明時は解放待ちとして所有を保持し、次jobを開始させない。通常profileへの導入、main統合、タグ、Release公開は行っていない。
