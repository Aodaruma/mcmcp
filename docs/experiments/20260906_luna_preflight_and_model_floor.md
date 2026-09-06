# Lunaの表面判定失敗と、低コストモデルに向けた設計検討

対象: `43f3a13` / `0.1.0-rc.3-SNAPSHOT`。2026-09-06の倉庫観測タスクの保存済みTool出力とコードを調べた。今回の調査ではMinecraft、MCP経由のゲーム操作、computer-useを実行していない。

## 結論

**今回の失敗だけを理由にLunaを推奨対象から外す根拠はない。モデルに要求する能力を下げる設計改善を先行する。**

- 保存記録ではチェスト1個のinspectが成功し、完全な5品目の結果も取得できていた。すべての内容確認が失敗したわけではない。
- `known_surface_changed` は、現在の条件を満たす表面の証拠がなくなったという判定である。ブロックの実変化を確認したという意味ではない。
- 描画由来のfog sampleが一時的に欠測すると、同じブロックでもこの判定に失敗し得るコード経路がある。今回の実機でその経路を通ったかは保存項目が足りず未確定。
- 接続用PowerShellのエラー処理や結果の読み違いも混在していた。これらをモデルのゲーム操作能力と分離する必要がある。
- 現段階の候補は、低コストで成立させたい対象が **Luna / Haiku 4.5**、汎用作業の比較基準が **Terra / Sonnet 5**。動作保証や検証済みの最低要件ではない。

## 1. 保存記録で確認できたこと

倉庫観測の1ターンは18分12秒。command execution 44件、file change 16件、context compaction 1回を含む。ソースや設計書の読み取り、接続スクリプトの作成・修正も行われており、[fresh MCP-only評価protocol](MCMCP_fresh_MCP-only_評価protocol.md)による比較実験には該当しない。実トークン数、有効コンテキスト長、モデル設定を揃えた対照結果は取得していない。

| 記録 | 確認できること | 断定できないこと |
| --- | --- | --- |
| inspectの`state=succeeded`、実行1 node、10 ticks、1 interaction | チェスト1個の正規inspectが成功した | 倉庫全体の整理能力・安定成功率 |
| 同じActionを`include_container_results=true`で再取得。5品目、`truncated=false` | 全品目を結果として取得できた。最初の省略時の`null`は空チェストを意味しない | 現在も同じ在庫であること |
| 開始IDなしの待機timeoutが5件、各約63秒、合計317.659秒 | エラー後の待機で5分以上を消費した | その待機時間をモデルの推論時間とみなすこと |
| `INVALID_ARGUMENT`、`InvalidToolCall`、`TARGET_UNKNOWN`、`known_surface_changed` | 引数・証拠不足・preflight拒否が混在する | すべてが同じMOD不具合であること |
| 最後のapproachは同じスクリプト内でstate→observation→start、全体3.450秒 | モデルの長考だけではこの拒否を説明できない | captureとcommitの間に何も変わらなかったこと |

最後のdebug出力は、表面の`observed_tick=61341`、`surface_world_revision=475780`、stateの`world_revision=475835`だった。差の55はglobal revisionの差であり、対象表面に適用されたrevision barrierではない。これだけで「55回チェストが変化した」「古い観測なので必ず失敗する」と判断してはいけない。

接続スクリプトには、Windows PowerShellでの日本語パス文字化け、HttpClient未ロード、`isError`を確認せず成功用の`structuredContent`を読む処理があった。調査時の近接スクリプトにも、positionだけを渡してblock種別を落とす、`container_results.results`を参照しない、失敗した対象を検査済みとして扱う処理が残る。これらは保存済みスクリプトの実装上の問題であり、実行済みのすべての試行に同じ版が使われたとは限らない。

## 2. 実変化と観測欠測の切り分け

根拠となる実装は次のとおり。

- [McmcpRuntime.agentPlanningFrame](../../src/main/java/dev/aod/mcmcp/runtime/McmcpRuntime.java): current fogがあれば配送済み表面を実再観測する。欠測時は元のrecordを保持する`augment`へ戻る。
- [DeliveredPolicyEvidenceStore.reobserveForPlanning](../../src/main/java/dev/aod/mcmcp/agent/observation/DeliveredPolicyEvidenceStore.java): 新しいrecordは今回の内部planning view限りで、公開frameや配送期限を更新しない。
- [ClientFogDistanceSignals.currentIdentity](../../src/main/java/dev/aod/mcmcp/agent/observation/ClientFogDistanceSignals.java): level・camera・entity tickが一致したrenderer sampleだけを返す。
- [AgentPrimitivePlanner.knownSurfaceRecord](../../src/main/java/dev/aod/mcmcp/agent/action/AgentPrimitivePlanner.java): 位置・面・blockが同じでも、recordのrevisionが現在のbarrier未満なら利用できない。

この組み合わせでは、次の経路が成立する。

1. 配送済み表面のrevisionは`r`。すでに存在するbarrierは`R > r`。
2. Action準備時の実再観測が成功し、内部の表面だけが`R`以降になる。
3. 予約までにtickが進み、新しい描画がないとcurrent fogが欠測する。
4. 予約側が元の`r`を読むため、位置・面・blockが同じでも`known_surface_changed`になる。
5. 新しいfogが得られ、元の期限内で同じ面を実再観測できれば、再び条件を満たし得る。

証拠不足で操作を止めること自体は安全上必要である。一方、これを「world等が変わった」とだけ説明し、描画の短い欠測からの回復をモデルへ丸投げするのは改善余地がある。保存記録にはcapture/commitそれぞれのfog有無、対象barrier、元の配送期限、再観測結果がないため、今回の直接原因は未確定のままとする。配送期限切れ、遮蔽、距離、面の観測失敗なども同じ拒否へ集約され得る。

### オフラインの再現確認

[LunaSurfacePreflightRegressionTest](../../src/test/java/dev/aod/mcmcp/agent/observation/LunaSurfacePreflightRegressionTest.java)は、実装のfog identity・配送ACK store・表面再観測・plannerを組み合わせた現行挙動の確認である。

- 同じmap/session/eye/block/barrierでfog有→欠測→復帰を作り、表面判定のtrue→false→trueを確認する。
- 公開frameと配送storeの原本は古いrevisionのままであり、欠測中には再raycastしないことも確認する。
- global revision差55だけでは無効にならず、対象に適用するbarrierが上がった場合に失効する対照を置く。

Minecraftの描画・inbox実行順序そのものを起動するテストではなく、rayの結果は変化しないチェストのfixtureである。Lunaの実機でこの順序が起きたことや、修正済みであることを証明するテストではない。

追加した2件は成功。`test` 1,201件、`harnessTest` 13件、`adminBridgeTest` 21件の計1,235件は失敗・skipなしで、`verifyHarnessIsolation`と`build`も成功した。既存harness/adminBridgeは変更がなくGradleのup-to-date結果を使用した。本体コードは変更しておらず、JARの差し替え・再起動はこの調査では不要である。

## 3. モデルに要求する能力を下げる改善案

以下は提案であり、この調査では本体の動作や公開schemaを変更していない。

| 優先 | 変更案 | 減らせる負担・維持する条件 |
| --- | --- | --- |
| P0 | 正規MCP接続を安定させ、fallbackは固定の汎用bridgeにする | モデルが毎回HTTP・認証・文字コード・エラー判別を実装しない。既存評価runnerの輸送検証を再利用し、引数・座標・Actionの補完や自動再送はしない |
| P0 | preflightで「描画情報待ち」「期限切れ」「現在の面の不一致」を区別する | 固定reasonと、開始前/送信後の区別を返す。描画欠測は元の期限と総budget内で待ち、復帰後に全条件を再検証する設計を先に試す |
| P0 | inspectの説明を統一する | catalogの`inspectContainerNode`に残る「27種類をNODE_EVIDENCEへ」を、正本の最大54種類・`container_results.results[].items`へ合わせる。空・未取得・失敗を混同しにくくする |
| P1 | stateの静的DSL説明、Actionの監査source/templateを必要時だけ取得できる表示を追加する | 同じ長文の再読を減らす。完全な仕様と監査情報の取得経路は保持し、既存クライアント互換を確認する |
| P1 | 初回観測で最新の公開済みframeを原子的に選べるようにする | state→frame ID転記の1往復を減らす。例として`frame_id:null, cursor:null`を許し、続きページは返却された具体IDに固定する。古いframeを新鮮と偽らない |
| P1 | エラーに固定の回復分類を返す | 開始前なら再観測、送信後UNKNOWNなら結果確認、と次の安全な分岐を明確にする。`recoverable`を同一要求の再送許可と混同させない |
| P2 | ページcursorの明示解放と、不足budgetの短い診断 | 未完了leaseを放棄して待たされる状況や、既にcompilerが計算できる数値の推測を減らす。budgetを自動拡大しない |

固定bridgeでは、HTTP・JSON-RPC・Tool domain errorを区別し、成功応答で得たAction IDだけを待つ。待機には既存の`wait_timeout_ms`を使用できる。ゲーム判断はモデルに残し、輸送だけをコードで保証する。[既存runner](../../tools/eval/Invoke-McmcpFreshEval.ps1)にも`isError`と成功schemaの厳密な分岐があり、同じ処理を一から作る必要はない。

現catalogをPythonの`json.dumps(ensure_ascii=False, separators=(',', ':'))`で数えると、5 Toolのdescription＋inputSchemaは**106,073文字**、うちstart_actionは94,400文字。outputSchema等を含む5 Tool配列は155,157文字、外側のmetadataを含むcatalog全体は155,306文字。start_actionには39 opcode、69の`$defs`、20 examplesがある。これはトークン数ではなく、実際にモデルへ投入される量はクライアントのschema処理・重複除去でも変わる。

安全上の説明をただ削ると、小さいモデルにはかえって使いにくくなる。まず繰り返しの静的情報を減らし、必須引数、正規opcode、最小例、失敗後の手順を短く一貫させる。閉じた合成DSLと固定5 Toolは維持する。OpenAIも、コードで確定できる引数や処理をモデルに負担させない設計を推奨している。[Function callingの設計指針](https://developers.openai.com/api/docs/guides/function-calling#best-practices-for-defining-functions)

## 4. Codex・Claudeの暫定的な選び方

公式資料の位置付けをもとにした**MCMCP向けの検証候補**であり、公式のMinecraft対応保証でも、今回測った性能順位でもない。CodexではLunaは明確で反復的な作業、Terraは日常の推論・Tool利用、Solは複雑で曖昧な作業向けとされている。ClaudeではHaiku 4.5、Sonnet 5、Opus 5はいずれもTool利用に対応している。[Codexのモデル選択](https://learn.chatgpt.com/docs/models)、[Claudeモデル一覧](https://platform.claude.com/docs/en/models/overview)

| 目的 | Codex | Claude | 現時点の扱い |
| --- | --- | --- | --- |
| 低コストで観測・単純な内容確認・短い反復を成立させる | GPT-5.6 Luna | Haiku 4.5 | 設計改善の目標。複数条件の再現試験前に「最低保証」とは書かない |
| 倉庫の分類など複数段階の汎用作業 | GPT-5.6 Terra | Sonnet 5 | 暫定的な試用・比較基準。今回のMOD側の拒否がモデル変更で直るとは限らない |
| 曖昧な分類・長い復旧・複雑な計画の比較 | GPT-5.6 Sol | Opus 5 | 上位モデルとの差分を調べる候補。常時必須にはしない |

低コストであることと、コンテキスト上限が小さいことは別問題である。APIの公称コンテキスト長はLunaとTerraがともに1,050,000、Claude Haiku 4.5が200K、Sonnet 5が1M。ただしCodexやClaude Codeが実際に使える履歴量、Tool定義の扱い、圧縮設定とは区別する。今回のcompaction 1回だけからLunaの限界を導くことはできない。[Luna API仕様](https://developers.openai.com/api/docs/models/gpt-5.6-luna)、[Terra API仕様](https://developers.openai.com/api/docs/models/gpt-5.6-terra)、[Claudeモデル一覧](https://platform.claude.com/docs/en/models/overview)

READMEに下限を載せる段階では、モデル名だけでなく「単純な操作」「複数チェストの整理」など機能別に、実際に試したmodel ID・推論設定・MOD版・完了率を併記する。現在は固定のコンテキスト最小値やハードなモデル制限を設けない。

## 5. 改善を判断する評価

1. まずオフラインで、domain error→空ID待機、検査失敗→検査済み扱い、fixtureのfog有→欠測→復帰を検証する。実変化・遮蔽・期限切れを許可しない対照も維持する。
2. 個人操作と他プレイヤーの変更が混ざらない復元可能な環境で、観測のみ、1チェストinspect、複数チェストの結果一覧を別課題にする。fog欠測の有無と対象barrierを固定形式で記録し、画面の目視判断に依存しない。
3. 同じbaseline・依頼・deadline・Tool契約で各モデルを別runにする。最初は各条件3回の切り分け、推奨下限の公開前には条件と回数を増やす。3回成功だけを安定性の証明としない。
4. 完了率、schema違反、開始拒否、無効ID待機、同一失敗の反復、結果の読み違い、再観測からの復帰、時間、実入力トークン量、compactionを分けて記録する。安全停止と入力所有権の解放、未確認結果を成功と報告しないことは全モデルで必須とする。
5. 現行runnerの許可pairはSol/highとLuna/high・xhighである。Terra・Claudeの比較にはproviderと有効設定の検証を先に追加する。この調査で新しいモデル比較runを実施したとは扱わない。

## English summary

The saved run includes one successful chest inspection, preflight rejections, and errors in an ad hoc PowerShell client. It is not a controlled model benchmark. A missing current renderer sample can invalidate a temporary planning witness without a block change; the actual run lacks the diagnostics needed to confirm that cause. Stabilize transport, distinguish evidence failures, and reduce repeated context before setting a model floor. Luna / Haiku 4.5 are low-cost evaluation targets; Terra / Sonnet 5 are provisional comparison baselines, not verified minimum requirements.
