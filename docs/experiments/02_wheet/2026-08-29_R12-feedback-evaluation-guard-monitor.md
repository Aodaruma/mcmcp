# R12 feedback反映: evaluation-turn入力隔離と可視monitor

## 状態

- 日付: 2026-08-29
- 種別: 実装・内部試験のみ
- 実ゲーム再試験: 未実施
- 次の実ゲーム試験: 変更点と内部試験結果をユーザーへ共有した後に実施する

## feedbackから確定した要件

- window focus喪失またはVanilla mouse grab解除だけでActionのuniversal safetyを失敗させない
- モデルが推論している間もMinecraftの物理入力を隔離し、Action中の黄色とは異なるcyan外縁を表示する
- Escは評価を失敗させ、Action停止と入力解放を確認してから隔離を解除する。安全に解除できた通常EscではMCP操作ONを維持する
- 別の可視Terminalには、Codexが公開したcommentary / preambleとcompleted reasoning summaryを意味的に加工せず表示する
- Terminalへ表示した公開進捗は、時刻・種別labelを含む同じ本文・同じ順序で`live-monitor.log`へ保存する
- raw private chain-of-thought、reasoning delta、raw Tool引数・結果は表示対象へ入れない
- runner、monitor、visible childは周期pollingせず、control stream、app-server event、process終了を待つ

## 実装した変更

### universal safety

- semantic action、stationary break、block plan、Phase 5 adapterから`isWindowActive`と`isMouseGrabbed`だけを除外した
- pause、Screen / overlay、Survival、生存・health・threat、位置・向き・slot・item use、server reconciliationは維持した
- control context変化と対象block precondition変化の診断を分離した

### evaluation-turn lease

- 固定5 Toolへ追加しないBearer認証済みloopback内部endpointを追加した
- leaseをworld session、UUID、runner PID + process start、monotonic deadline、control streamへ束縛した
- preliminary readiness後にleaseを獲得し、lease header付きauthoritative readinessを再確認してからT0を記録する
- leaseなし／exact lease IDをHTTP受付時とMinecraft client thread実行時に再検証し、acquire / releaseとの競合をfail closedにした
- runner終了、stream切断、Esc、UI OFF、player / world境界、endpoint fault、shutdown、deadlineをpriority stopへ接続した
- 入力解放または全Action terminalを確認できない間はguardをactiveのままOFFへlockし、最初のterminal intentを保持して再試行する
- terminal receiptは`inputs_released`、`input_owner_none`、`all_actions_terminal`、`process_identity_bound`を別fieldで返す

### UIと入力

- 推論中はcyan、Action / recovery中はyellowの2 px外縁とした
- evaluation-turn中はAction間もkeyboard、mouse button / wheel、camera、text / IME入力を隔離する
- Escと状態buttonだけを例外とし、Escは緊急停止後にVanillaへも渡す

### 可視monitor

- `Start-McmcpFreshEvalMonitor.ps1`から可視`pwsh`を起動し、public commentary / preamble、completed reasoning summary、固定Tool進行を表示する
- 公開本文は座標、ID、path / URL、JSONを含めて無加工で通し、複数行も保持する
- 実credential完全一致とTerminal制御文字だけを遮断する
- raw / summary deltaはapp-server初期化時にopt-outし、reasoning itemのprivate `content`が空であることをraw artifact書込前と監査時の双方で確認する
- opt-out対象のprivate reasoning notificationが到達した場合はraw writer前に拒否し、実credentialの完全一致もraw / bridge等の各artifact書込み前に拒否する
- Terminalへ表示した安全な公開行は、時刻・種別label込みの同じ本文・同じ順序で`live-monitor.log`へ逐次保存する
- `live-monitor.log`にもprivate reasoning、delta、raw Tool引数・結果、実credentialは書き込まず、表示とlogの完全一致をself-testする
- app-server stdoutとlease terminalが同時に届いた場合はleaseを優先し、異常terminalを正常`turn_completed`として扱えないようにした

## 内部試験

- Java `test`: 723 / 723、failure 0、error 0
- Java `adminBridgeTest`: 21 / 21、failure 0、error 0
- Java `harnessTest`: 20 / 20、failure 0、error 0
- `gradlew test harnessTest verifyHarnessIsolation build`: `BUILD SUCCESSFUL`
- PowerShell構文解析: 6 / 6 PASS
- public monitor / evaluation lease self-test: 65 / 65 PASS（表示と`live-monitor.log`の正常・異常終了時の一致、private reasoning notificationと実credentialのraw / bridge artifact書込み前拒否を含む）
- app-server trace audit self-test: 60 / 60 PASS
- reasoning artifact書込み前契約: 3 / 3 PASS
- lease receipt契約: 2 / 2 PASS
- `git diff --check`: PASS

これらは実装と静的・内部結合契約の確認であり、実Minecraft JVMとHTTP / control stream / visible Terminalを繋ぐ結合は次回の実ゲーム試験で確認する。

## 次回の実ゲーム試験で確認する点

1. 可視Terminalを開いてMinecraftのfocus / mouse grabが変化しても、それだけでmutationが失敗しない
2. 推論中はcyan、Action開始時はyellow、Action terminal後の推論はcyanへ戻る
3. 推論区間の物理入力がMinecraftへ混入しない
4. EscでAction停止、入力ownerなし、lease terminal、READY復帰の順になる
5. 正常完了または異常終了でrunnerとvisible childが終了し、入力隔離が残らない
6. public commentary / completed reasoning summaryがTerminalへ本文どおり表示される
7. `live-monitor.log`がTerminal表示と行単位・順序とも一致し、private reasoning、raw Tool入出力、実credentialを含まない
