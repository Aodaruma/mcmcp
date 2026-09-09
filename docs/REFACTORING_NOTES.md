# 責務分割の作業記録

第1段階（Issue #30 / PR #31）の記録を保持し、末尾に第2段階（Issue #32）の結果を追記した。各段階の規模と未確認条件は、その時点のもの。

## 目的と契約

人間・LLMが機能を理解・編集する際の読取範囲を減らす。公開5 ToolとDSL、観測lease、受付/再検証、budget、server ACK、入力解放、fixture隔離を維持する。単なる巨大クラスの移動、継承階層、巨大な共通Contextを避ける。

開始commit: 805cd5321f907932bf9f53a172c8975dd7014698。Issue: #30。
本体Java 252ファイル/86,859行。Runtime 13,805行、Planner 3,918行、Construction gate 4,108行。

## 作業分担

- 統合worktree / refactor/30-runtime-modules: McmcpRuntimeの分割、関連テスト、案内・最終統合。
- Codex CLI / refactor/30-planner: AgentPrimitivePlannerと直接関連テスト。public APIを維持する。
- Codex CLI / refactor/30-eval-gates: capability gateの共通処理・建築シナリオ分割とmockテスト。
- subagentは使用しない。CLIの各作業は独立worktreeで実施し、完了後に統合担当が確認する。

## 段階

1. 現行runtimeのメソッドと依存を把握し、payload/引数解析/DSL証拠計算等の純粋な責務を抽出する。
2. 観測・個別操作・受付・終了処理を、状態所有権とthread境界を保って分割する。
3. CLI実装を統合し、公開API・既存テストを維持して全体を検証する。
4. 独立CLIレビューを反映し、コード案内と前後の規模・未確認条件を記録する。

## 並行変更・検証

- PR #26の坑道掘削は別作業。既存worktreeへ書き込まず、base更新時の競合を確認する。
- Java25 + Gradle wrapperを使う。test/harnessTest/adminBridgeTest/verifyHarnessIsolation/buildと関係するPowerShell/Python試験を実行する。
- baselineの前回監査では本体1245件、harness13件、通信14件成功。adminBridge21件はUP-TO-DATEだった。今回の改修後に改めて検証する。
- 実機検証・Release公開は未実施。完了と混同しない。

## 第1段階完了時の状態

2026-09-09:

- runtimeの計算・証拠・要求・応答・引数・失敗処理を16モジュールへ抽出した後、CLI（high）が受付・評価lease・観測・旧routine・menu/釣り/攻撃の状態所有と進行結果を9モジュールへ分割。本体13,805→5,488行。抽出先は最大919行で、runtime全体参照、継承による分割、万能Contextは追加していない。
- PlannerはCodex CLI（high）で3,918→897行の公開入口と8機能別helperへ分割し、統合済み。CLI sandboxではJDK/dependencyアクセスが拒否されたが、親環境のGradle compileと専用57テストは成功。
- capability gateはCLI（medium）の成果を統合。入口4,108→151行、共有支援4ファイル・建築7ファイルへ分割。他9gateは建築入口の読込と引数退避を不要にした。既存92関数の本体・引数は保持。
- 参照のない旧schema49メンバーを削除し、McpToolSchemasは1,681→592行。publicな既存入力schemaとそこから到達するhelperは保持。現在の5 Tool catalogは変更しない。
- inventory/furnace/brewingの完全一致するPlayerBaselineを1レコードに統合。位置許容差0.01²、session一致、health基準を保持。
- `tools/check-source.ps1`を追加。通信14件＋capability mock10本＋読込/scope試験が統合先でも成功。全Java検証も以下のとおり成功。
- コード案内を追加し、AGENTS/CONTRIBUTINGから導線を作成。旧build gateの現在のTool surfaceとの非互換と、実装済みqueue容量を文書へ反映。
- 静的抽出の229メソッドを機械照合し、所有クラス修飾・可視性・空白以外の一致を確認。stateful分割は別途、全体検証と独立レビューの対象にする。

## 規模の比較

空行・コメントを含むソース行数。生成物・game dataは集計しない。

| 対象 | 改修前 | 改修後 |
| --- | ---: | ---: |
| McmcpRuntime | 13,805 | 5,488 |
| AgentPrimitivePlanner | 3,918 | 897 |
| McpToolSchemas | 1,681 | 592 |
| Construction capability gate入口 | 4,108 | 151 |
| 本体Javaファイル数 | 252 | 286 |
| 本体Java総行数 | 86,859 | 87,498 |
| 本体Java上位10ファイルの合計行数 | 35,231（40.6%） | 23,996（27.4%） |
| 1,000行を超える本体Javaファイル数 | 19 | 17 |

import、既存public APIの委譲、状態を明示する接続により総行数は約0.7%増えた。目的は機能削除ではなく、変更時に読まなければならない範囲と責務の混在を減らすことにある。

## 検証結果

Java25で `gradlew.bat test harnessTest adminBridgeTest verifyHarnessIsolation build --offline --console=plain --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1G` を実行。

- 本体JUnit: **1,250件成功**、失敗・skipなし。
- harnessTest: **13件成功**。adminBridgeTest: **21件成功**。
- verifyHarnessIsolationとproduction JARのbuild: 成功。
- PythonのMCP transport: **14件成功**。
- capability gate mock: **10スクリプト成功**。読込・scope・引数保持の追加試験も成功。
- 親側でも建築/共有支援92関数の完全一致を検証。新しい案内の相対リンクと、基点からの全差分に対する `git diff --check 805cd5321f907932bf9f53a172c8975dd7014698 HEAD` を確認。
- 初回のJava試験で検出した旧owner参照は、移動先を検査するよう修正した。入力解放、再計画期限、session tick、placement admissionのassertionは維持し、委譲先への接続も追加確認した。
- 公開Tool Catalog、McpRuntimePort、EvaluationTurnControlには差分なし。
- CLI sandbox内ではJDKファイルのアクセス拒否があったため、コンパイル・JUnitの合否は親環境の実行結果で確認した。ACL・認証設定は変更していない。

実機のMinecraft、実server ACK、fog・入力解放の実機評価は未実施。配布用タグは公開しない。

## 独立レビュー

Codex CLI（read-only、reasoning high）でponytail-reviewと安全性レビューを実施。対象 `7000c383e90be284a49b314a0c2615816cde1b58` では修正必須の回帰・安全条件の変更なし。参考指摘の2点を反映した。

- KillZoneExecutionの `operation = this` と自己引数を除去。所有者自身のfieldを直接参照する。
- 抽出したPowerShell 10ファイルの末尾空行を除去。working treeだけでなく基点からの全差分を検査する。

変更後の差分レビュー、対象HEAD、GitHub必須CIの結果は [PR #31](https://github.com/Aodaruma/mcmcp/pull/31) へ記録する。CLIの記録はGitHubの自己approvalとは区別する。

## 第1段階終了時の分割候補と順序

1. **McmcpRuntimeのAction進行部分**: 約5,500行と、まだ最大のファイル。`tickAgentAction`、`beginAgentPrimitive`、破壊・mutation・bounded inputの実行が残る。次は1 Action内の進行状態と予算・再計画の関係を明示し、入力解放の順序を保って1種類ずつ分離する。全runtime参照を受け取る巨大controllerへ移すだけにはしない。
2. **Minecraft操作port**: Inventory 2,635行、Brewing 2,453行、ApplyBlockPlan 2,416行、Furnace 2,252行。候補選択・slot計画・readback/ACK・cleanupを個別に扱う。今回共有したPlayerBaseline以上に安全gateをまとめると、操作固有の差を消す可能性があるため、意味の一致を確認してから抽出する。
3. **局所観測と評価runner**: LocalObservationVolume 2,307行、EvalTrace/Fresh runnerの大きさも残る。観測時刻・revision・fixture境界のテストを基点に次の単位を選ぶ。建築WallScenariosも1関数が長く、引数のscopeを保った次段階の整理が候補。

今後の機能追加は[コード案内](CODE_MAP.md)から該当モジュールを選ぶ。分割先へ処理を追加し、runtimeには必要な進行・終了の接続を残す。

## 第2段階（Issue #32）

基点: `eec1f46c2390dd3a0cff76b7b893aa4dc51ccccc`。ユーザーからの継続依頼に対し、残るAction tick・primitive状態とInventory portの方針の混在を対象にする。subagentは使用せず、Runtimeの実装と独立レビューをCodex CLIで行う。

### Inventoryの境界

- InventoryParameters: 要求解析、immutableな条件、menu種別・aim検証。Brewing/Furnaceの同じaim検証の呼出先も更新した。
- InventorySlotPlanning: server snapshotからのslot選択・個数・readback計算。
- InventoryOpenHandPolicy: MAIN_HANDの選択と、既知containerに先行するNeoForge hookの安全証明。
- InventoryTransferBatch: 初期source集合・click baseline・確認済みprefixの所有。mutable fieldをprivateに保ち、portへは確認済み個数・immutableなslot snapshotを返す。
- portはclient thread上の開封・click・同期・readback・cleanupを保持する。public APIと診断、上限、clickの順序を変えない。

既存テスト16件をslot計画とbatch契約の小さなファイルへ移動した。port試験には各方針への接続、入力・画面所有と処理順の検査を残す。移動した70メンバーを旧commitと照合し、可視性・indent・constructorの所有クラス以外に本体差分がないことを確認した。

### Runtimeの境界

KnownBreakExecution、CobblestoneExecution、BlockMutationExecution、FrameItemExecution、BoundedInputExecution、MovementExecution、WaitExecutionへ分割した。抽出先は123〜543行で、各操作の状態とtick・cleanupを同じownerが扱う。RuntimeはAction開始・DSL進行・入力解放・terminalの調整を保持する。時間予算の純粋計算はActionBudgetsへ移し、各実行クラスからRuntime本体への逆参照を除いた。

長いtickは開始確認、制御境界、回復、予算・pickup確認、semantic dispatch、移動結果に分けた。tick加算位置、早期return、元の配送期限、ACK、effect回収、入力解放後のterminal公開を維持する。準備出力の古いslot・pickupが後続操作へ残らないよう初期化し、失敗時にも前の操作の証拠を返さない回帰試験を加えた。

### 規模と検証

| 対象 | 第1段階終了時 | 第2段階終了時 |
| --- | ---: | ---: |
| McmcpRuntime | 5,488行 | 4,192行 |
| tickAgentActionの入口 | 778行 | 59行 |
| MinecraftPhaseFiveInventoryPort | 2,635行 | 1,864行 |
| MinecraftPhaseFiveInventoryPortTest | 1,238行 | 876行 |
| 本体Javaファイル数 | 286 | 297 |
| 本体Java総行数 | 87,498行 | 88,089行 |
| 上位10ファイルの合計 | 23,996行（27.4%） | 21,929行（24.9%） |

ファイルは空行・コメントを含む。tick入口は宣言から閉じ括弧までで集計する。クラス間の依存・結果の受渡しを明示したため総行数は約0.7%増えた。行数だけでなく、1操作を理解するときに読む責務の範囲が小さくなったことを成果とする。

- Java25の本体テスト1,256件、harness13件、admin bridge21件が成功。失敗・skipなし。buildとverifyHarnessIsolationも成功。
- Inventoryの関連74件と移動メンバー70件の一致を確認。既存assertionを保ち、ASMは移動先とruntimeからの接続・実行順を検査するよう更新した。
- 独立Codex CLIの対象SHA・結論、最終CI結果はIssue #32に対応するPRへ記録する。
- CLI sandbox内ではJDKアクセスが拒否されたため、Java検証は統合担当の環境で実行した。権限・ACL・認証設定は変更していない。

実機Minecraft、実server ACK、fog、入力解放の実機確認は未実施。Releaseタグは公開しない。

### 今回の区切りと次の候補

Runtimeはまだ約4,200行あるが、操作固有の実装と主要な判断段階には入口ができた。残るAction進行と停止の調整を一括で別controllerへ移すと、入力解放・最初のterminal intent・評価leaseの順序を複数のowner間で管理することになる。今回はこの調整を1か所に保持して区切る。

今後は、機能変更に合わせてpickupの接触・在庫確認状態、recovery調整、Brewing/Furnace等の個別portを1つずつ扱う。既に短く責務が明確なmoduleのさらなる細分化は優先しない。次の大きな状態分割や配布の前には実機評価も挟み、ソース試験だけで安全性やLLMの修正成功率が証明されたとは扱わない。
