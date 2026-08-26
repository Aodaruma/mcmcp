# ADR 0002: 低レベルinputを非公開にし、意味的routineを公開する

- 状態: 採用
- 日付: 2026-08-19
- 更新: 2026-08-20

## 決定

本番MCPでは`hold_forward`、`hold_attack`、任意yaw delta等の生inputを公開しません。MCPへ公開する能動操作は、対象、期限、bounds、固定postconditionを持つ型付きroutineだけです。

短い`place_block`や`interact_entity`も、公開上は`start_routine(kind=...)`で開始する有限routineとして扱います。長い建築や農林業と同じsupervisor、安全停止、failure schemaを共有します。

## 理由

- LLM応答遅延とMinecraft 20 TPS制御を分離できる
- 時間、対象、region、停止条件をroutine単位で検証できる
- 通信断やLLM停止時にもinternal action leaseで安全にreleaseできる
- click送信ではなくserver-confirmed postconditionを成功条件にできる
- MCP tool surfaceを小さくし、`list_routines`でdomain kindを拡張できる
- 同じtarget stateへのreconcileで中断後に再実行できる

## 許可するlocal回復

「失敗後に自動で別作戦を試さない」は、高水準の別作戦を意味します。同じpostconditionへ収束するため、宣言済みbounds内で次は許可します。

- fresh observation後の有限retry
- 再照準、別の有効な設置face
- 再接近、hotbar再選択
- 許可済みregion内のrepath
- already-satisfiedのskip

次はLLMの再計画かuser handoffを必要とします。

- 素材・recipe方針の変更
- blueprint/anchorの変更
- Entityの再同定・捕獲
- region、破壊、combat、時間上限の拡張

## 影響

- 新しい作業ごとに型付きschema、executor、postcondition、fault testが必要
- 汎用性はLLMによるroutine compositionで得る
- `tools/list`は固定されたMCP toolを返し、`list_routines`がdomain catalogを返す
- routine kindはclosed discriminated schemaにし、任意parameter bagにしない
- low-level primitiveはdeveloper modeでもsingle player、local opt-in、短いTTLに限定する

## 明示的に作らないもの

- 自由文`run_goal`
- raw input ownershipをLLMへ渡すtool
- 巨大なcommand batch language
- JSON workflow/if/loop DSL
- 任意postcondition predicate
- rollback transaction engine
- 無期限routine/guard
