# Issue #30: 責務分割の作業記録

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

## 現在の状態

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
| 本体Java総行数 | 86,859 | 87,504 |
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
- 親側でも建築/共有支援92関数の完全一致を検証。新しい案内の相対リンクと `git diff --check` を確認。
- 初回のJava試験で検出した旧owner参照は、移動先を検査するよう修正した。入力解放、再計画期限、session tick、placement admissionのassertionは維持し、委譲先への接続も追加確認した。
- 公開Tool Catalog、McpRuntimePort、EvaluationTurnControlには差分なし。
- CLI sandbox内ではJDKファイルのアクセス拒否があったため、コンパイル・JUnitの合否は親環境の実行結果で確認した。ACL・認証設定は変更していない。

実機のMinecraft、実server ACK、fog・入力解放の実機評価は未実施。配布用タグは公開しない。独立Codex CLIのponytailレビューとPR上のCI結果はレビュー記録へ追記する。

## 残る分割候補と順序

1. **McmcpRuntimeのAction進行部分**: 約5,500行と、まだ最大のファイル。`tickAgentAction`、`beginAgentPrimitive`、破壊・mutation・bounded inputの実行が残る。次は1 Action内の進行状態と予算・再計画の関係を明示し、入力解放の順序を保って1種類ずつ分離する。全runtime参照を受け取る巨大controllerへ移すだけにはしない。
2. **Minecraft操作port**: Inventory 2,635行、Brewing 2,453行、ApplyBlockPlan 2,416行、Furnace 2,252行。候補選択・slot計画・readback/ACK・cleanupを個別に扱う。今回共有したPlayerBaseline以上に安全gateをまとめると、操作固有の差を消す可能性があるため、意味の一致を確認してから抽出する。
3. **局所観測と評価runner**: LocalObservationVolume 2,307行、EvalTrace/Fresh runnerの大きさも残る。観測時刻・revision・fixture境界のテストを基点に次の単位を選ぶ。建築WallScenariosも1関数が長く、引数のscopeを保った次段階の整理が候補。

今後の機能追加は[コード案内](CODE_MAP.md)から該当モジュールを選ぶ。分割先へ処理を追加し、runtimeには必要な進行・終了の接続を残す。
