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

- runtimeの計算・証拠・要求・応答・引数・失敗処理を16モジュール（116〜919行）へ抽出。本体13,805→8,995行、production compile成功。状態所有をさらに分割する段階をCodex CLI（high）で実施中。
- PlannerはCodex CLI（high）で3,918→897行の公開入口と8機能別helperへ分割し、統合済み。CLI sandboxではJDK/dependencyアクセスが拒否されたが、親環境のGradle compileと専用57テストは成功。
- capability gateはCLI（medium）の成果を統合。入口4,108→151行、共有支援4ファイル・建築7ファイルへ分割。他9gateは建築入口の読込と引数退避を不要にした。既存92関数の本体・引数は保持。
- 参照のない旧schema49メンバーを削除し、McpToolSchemasは1,681→592行。publicな既存入力schemaとそこから到達するhelperは保持。現在の5 Tool catalogは変更しない。
- inventory/furnace/brewingの完全一致するPlayerBaselineを1レコードに統合。位置許容差0.01²、session一致、health基準を保持。
- `tools/check-source.ps1`を追加。通信14件＋capability mock10本＋読込/scope試験が統合先でも成功。全Java検証は状態分割後に実施する。
- コード案内を追加し、AGENTS/CONTRIBUTINGから導線を作成。旧build gateの現在のTool surfaceとの非互換と、実装済みqueue容量を文書へ反映。
- 静的抽出の229メソッドを機械照合し、所有クラス修飾・可視性・空白以外の一致を確認。stateful分割は別途、全体検証と独立レビューの対象にする。
