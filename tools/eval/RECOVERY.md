# 観測欠測からの回復試験 / Renderer recovery regression

[Issue #16](https://github.com/Aodaruma/mcmcp/issues/16) は [#4](https://github.com/Aodaruma/mcmcp/issues/4) の実機検証を支える評価ツールです。実装は開発担当、環境準備・実行・正式合否は保守・検証担当が受け持ちます。これは機能回帰試験であり、低コストモデルの能力比較とは分けます。

This tooling supports game acceptance for #4. Development owns the implementation; maintenance/validation owns setup, execution and acceptance. It is a functional regression, separate from model capability comparisons.

## 固定条件 / Fixed conditions

`container-inspect-recovery` は5分の固定deadlineで、次の文だけを新しいモデルのturnへ渡します。実行中の追加入力、再送、deadline延長は行いません。

The profile has a fixed five-minute deadline and supplies only this prompt to a fresh turn. Do not add hints, replay an uncertain operation or extend its deadline.

> 近くにあるチェスト1つの中身を一度だけ確認し、結果を教えてください。アイテムの出し入れはしないでください。

公開5 Tool、観測からの対象選択、入力隔離lease、秘密の除外、terminal後の解放は[評価protocol](../../docs/experiments/MCMCP_fresh_MCP-only_評価protocol.md)を継承します。runnerは正規の隔離評価環境で未登録を確認してから既存の固定bridgeを使います。普段のCodex設定の上書きや認証失敗の回避には使いません。登録済みクライアントの接続そのものを検証した結果とも区別してください。

The existing five-tool, observation, input-lease, secret-filter and terminal-release contracts remain in force. The runner uses its established bridge only after proving that the isolated evaluation configuration has no MCP registration. This does not replace normal client settings, bypass failed authentication, or establish acceptance of a native registered connection.

## T0前の準備 / Before T0

1. 削除可能な検証環境で、同じbaselineを毎回復元します。通常操作の範囲内にチェスト1つがある配置を用意し、既存readiness条件（空inventory、可視entity 0、MCP READY等）を満たします。環境の座標や回答はモデルへ渡しません。
2. 使用する製品commitとbuild JARのSHA-256を、レビュー済みのbuild記録から取得します。設置後にMinecraftを起動し、起動記録で使用JARを照合します。通常条件のFPS上限は10より大きい値を明示し、低FPS条件は10にします。
3. FPS設定を保存してからrunを開始します。`options.txt` の `maxFps` と実際に起動中の設定の一致を事前に確認します。T0からterminalまで、ゲーム・画面・設定ファイルへ介入しません。

Restore the same baseline before every run. Place one chest within ordinary interaction range and satisfy existing readiness checks without providing coordinates to the model. Obtain the product commit and JAR hash from the reviewed build record, verify the launched JAR, and save/check the FPS setting before starting. Use an explicit normal cap greater than 10 and exactly 10 for the low-FPS condition. Do not intervene between T0 and terminal.

PowerShell 7.5以上で、同一host上のbuild JARと設置JAR、設置先ゲームの `options.txt` を指定します。remote/Dockerではその検証host/container内で実行します。パスは下記placeholderを置き換えてください。token本文をコマンドへ書かないでください。

Use PowerShell 7.5 or later on the validation host/container. Supply the build JAR, a separate installed copy, and that game's options file. Replace the placeholders below; never put the token value in a command.

```powershell
pwsh -NoProfile -File tools/eval/Invoke-McmcpFreshEval.ps1 `
  -Model gpt-5.6-sol -ReasoningEffort high `
  -PromptProfile container-inspect-recovery `
  -BaselineId '<restored-baseline-id>' `
  -ProductCommit '<40-lowercase-hex-commit-from-build-record>' `
  -ExpectedBuildJarSha256 '<64-lowercase-hex-sha256-from-build-record>' `
  -BuildJarPath '<build-output>/mcmcp.jar' `
  -InstalledJarPath '<validation-game>/mods/mcmcp.jar' `
  -OptionsPath '<validation-game>/options.txt' `
  -ExpectedMaxFps 120 `
  -TokenPath '<validation-game>/config/mcmcp/mcp-token' `
  -ArtifactDirectory '<new-empty-directory-outside-repo>'
```

別の表示用Terminalが必要なら、同じ引数を `Start-McmcpFreshEvalMonitor.ps1` に渡します。低FPSのrunでは設定保存後に `-ExpectedMaxFps 10` と新しいartifact directoryを指定します。runnerはFPSを変更しません。

For a visible monitor, pass the same arguments to `Start-McmcpFreshEvalMonitor.ps1`. For each low-FPS run, save the setting first, use `-ExpectedMaxFps 10` and a new artifact directory. The runner never changes FPS.

## 証跡と判定 / Evidence and decisions

T0直前に `recovery_preflight` を `bridge.jsonl` のT0 recordと `manifest.json` へ記録します。build・設置JARのSHA-256が期待hashと一致し、同じゲームdirectoryの `options.txt` に `maxFps` が1件だけ存在して期待値と一致しなければT0へ進みません。個人パスや他の設定内容は記録しません。

The runner records `recovery_preflight` in the T0 bridge record and manifest. It rejects mismatched JAR hashes, an unrelated options directory, duplicate/missing FPS entries and a cap that differs from the expected value before T0. Personal paths and unrelated settings are excluded.

| 記録 / Field | 証明範囲 / Evidence scope |
| --- | --- |
| `product_commit` | build記録に基づく実行者の申告。JARからの自動抽出ではありません。 / Operator declaration from the build record; not extracted from the JAR. |
| `build_jar_sha256`, `installed_jar_sha256` | T0前の実ファイルを読み取ったhash。 / Hashes read from actual files before T0. |
| `baseline_id` | 復元記録に基づく実行者の申告。ゲーム内部の自動oracleではありません。 / Operator declaration from the restoration record, not an in-game oracle. |
| `max_fps` | T0前に保存済みのoptions値。実測FPSではありません。 / Persisted options value, not measured rendering FPS. |
| `runtime_jar_and_fps_verified` | 常にfalse。起動中のJAR・FPSの一致は別途起動前記録と照合します。 / Always false; runtime identity needs separate setup evidence. |

`audit.json` の `passed` は既存のtrace契約と新profileの証跡の整合性です。回復経路の通過は **`recovery_witness.status`** で別に確認します。

`audit.json.passed` checks trace and profile evidence integrity. Inspect **`recovery_witness.status`** separately to determine whether the recovery path was exercised.

| Status | 意味 / Meaning |
| --- | --- |
| `witnessed` | 同一terminal Actionに1件の回復要約と同stageのmissing/revalidatedがあり、単独inspectの成功と完全なcontainer結果を確認。 / One terminal action proves a recovered stage and a successful standalone inspection with complete container results. |
| `not_exercised` | inspectは成功したが欠測が発生していません。回復PASSには数えません。 / Inspection succeeded without a renderer gap; not recovery acceptance. |
| `invalid` | 不一致・不足した証拠、複数Action、失敗、未完了など。trace auditも不合格です。 / Inconsistent or missing evidence, multiple actions, failure or nonterminal work; the trace audit fails. |

監査はstart receiptのAction IDとget_actionを照合し、同じterminal応答にある `state=succeeded`、`failure=null`、`progress.interactions>0`、最大256件のtrace、cleanup後の完全な単独inspect結果を要求します。途中snapshotや別Actionから回復履歴を借用しません。完全な結果をモデルが取得していない場合も不合格です。lease解放・入力ownerなし・全Action terminalの既存証跡も必要です。

The auditor correlates the start receipt with get_action and requires a successful terminal response, null failure, positive interactions, a bounded trace and the complete post-cleanup inspect result. It never borrows recovery flags from another action or an earlier snapshot. Missing full container-result retrieval also fails. Existing lease release, input-owner and all-actions-terminal evidence remains mandatory.

正式比較は**通常FPSで1回＋同一baselineを復元したmaxFps=10で1〜3回**に限定します。製品commit、build/設置hash、baseline ID、prompt、model/effortが一致するrunを並べ、少なくとも低FPSの1回が `witnessed` であることを確認します。3回とも `not_exercised` なら「未通過」で止め、成功するまで無制限に繰り返しません。対象stageだけの肯定証拠であり、全stageやモデル能力の証明にはしません。terminal後にFPSと環境を復旧し、保守・検証担当が比較結果をIssue #4へ記録します。

Run exactly one normal condition and one to three independently restored low-FPS attempts. Match product commit, both JAR hashes, baseline, prompt and model/effort; require at least one low-FPS `witnessed` result. If all three runs are `not_exercised`, stop and record the unexercised path. This proves only the observed stage, not every stage or general model capability. Restore settings after terminal and record acceptance in #4.

## 自動回帰 / Automated regression

```powershell
pwsh -NoProfile -File tools/eval/Test-McmcpRecoveryPreflight.ps1
pwsh -NoProfile -File tools/eval/Test-McmcpRecoveryWitness.ps1
pwsh -NoProfile -File tools/eval/Test-McmcpEvalTrace.ps1 -SelfTest
pwsh -NoProfile -File tools/eval/Test-McmcpLiveMonitor.ps1
```

この自己テストは一時ファイルと合成Tool応答を使用し、Minecraftや常用の認証設定へ接続しません。合格しても実機受入やRelease公開を意味しません。

These tests use temporary files and synthetic tool responses, without connecting to Minecraft or normal authentication settings. Passing them does not establish game acceptance or authorize a release.
