# R11 feedback反映: internal hardening

## 状態

- 日付: 2026-08-29
- 種別: 実装・内部試験のみ
- 実ゲームrun: 未実施
- 次の実ゲーム試験: 変更点共有とユーザー確認後に実施する

## feedbackから確定した受入の意味

固定fixtureの攻略自体を目的にせず、ユーザーまたは評価計画が指定した畑区画について、次を完了条件とする。

1. 耕作可能な土をすべて耕す
2. 全区画へ小麦の種を植える
3. 成熟後に全区画を収穫する
4. 収穫区画へ再び種を植える
5. 上記cycleを反復し、player inventoryの小麦を64個以上にする
6. 64個へ到達した最後のcycleでも再播種を完了する

従来の短いpromptも、将来同程度に省略された依頼が来る可能性を検証する回帰trackとして残す。ただし、短いpromptの成功だけで明示的な全区画completionを代替しない。

## 実装した変更

### promptと説明書

- evaluatorを厳格な`full-cycle` / `short-regression`の2 profileに分離し、任意prompt入力を許可しない
- `PromptProfile`をrunnerの必須引数にし、T0・audit・manifestへprofileとprompt hashを記録する
- `agent_start_action`のTool descriptionへ頻出nodeの必須field signatureを追加する
- Action DSLクイックガイドを追加し、畑作業を観測・batch・再観測の段階へ分ける一般手順を記述する
- containerとmutation batchの保守的budget目安、およびcamera 720度を超える入力順はbatch分割することを公開する

### schema診断

- catalog順に最大4件、512文字以内でschema違反をまとめる
- public error shapeはexact `{code,message,recoverable}`のまま維持する
- 未知property名、提出値、node ID、秘密を診断へ反射しない
- budget不足は超過した`budget.max_*` component名を固定順ですべて返し、数値や座標を反射しない

### batch

- till / plant / harvest batchは提出されたtarget順を厳密に維持し、camera最適化で並べ替えない
- 入力順のworst-caseがpolicy上限を超える場合は入力前に`PROGRAM_BUDGET_UNPROVABLE`で拒否する
- `collect_visible_item_batch`を2〜8件の第一級AST nodeにする
- batch開始時にitem種別ごとのinventory絶対個数baselineを固定する
- 後続dropの付随pickupは、fresh policy-visible AABBとplayer pickup areaの実接触後に同itemの絶対個数が増えた場合だけcreditする
- witness消失、merge、移動、近接だけでは成功にせず、失敗時は未開始suffixを実行しない

### 観測filterと座標

- delivery-only `position_bounds={dimension,min_x,min_y,min_z,max_x,max_y,max_z}`を追加する
- 既存のblock / entity / displayed item / crop maturity filterと、record kindごとに適用可能な条件をANDする
- 既にpolicy-visibleな同一frameからrecordを除外するだけで、任意center/radius、hidden scan、Action認可拡張には使わない
- anchorはsurfaceのblock position、entity等の`floor(position)`、traversabilityの返却済み`navigation_target`とする
- `navigate_to_known.target`には`navigation_target`を無変換コピーし、continuous `from / to`をLLMに変換させない契約を維持する

高水準の「周辺block一覧を自動で処理するAction」やfixture固有routineは追加していない。

## 内部試験

- batch関連targeted tests: 116 PASS
- `gradlew check`: BUILD SUCCESSFUL（22 tasks）
- `gradlew harnessTest verifyHarnessIsolation build`: BUILD SUCCESSFUL
- evaluator trace audit self-test: 55 / 55 PASS（両prompt profileを含む）
- `git diff --check`: PASS
- catalog raw SHA-256: `75c70584b0b04cc59aebd6d78ff1d89ae7fc1f7dbebf19a032fbf3a312433955`
- catalog semantic surface SHA-256: `4afdacbad81ad958e4fd7b285b45f8dc802259560cea1cbbdc94817ce9482ecc`

## 次回の実ゲーム試験で確認する点

1. ユーザー指定区画をT0前に確定し、無関係な固定座標へ置き換えない
2. `full-cycle`を先に実行し、全耕耘・全播種・全収穫・最終再播種・小麦64個を別々に判定する
3. 同じbaselineから`short-regression`を別runで実行し、文脈推定の回帰性を独立判定する
4. collect batchで先行targetへの移動中に後続dropを拾った場合、接触・inventory delta・listed order traceを確認する
5. camera budget超過時にtargetをruntimeが並べ替えず、モデルが小さいbatchへ分割して回復できるか確認する
