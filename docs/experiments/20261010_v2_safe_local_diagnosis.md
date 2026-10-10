# R18 扉端の安全判定拒否と診断改善（2026-10-10）

## 現場の記録と結論

通常R18で、Endの上段扉付近へのscript内moveが5.61秒で `script_command_failed` となった。その後、観測だけのscriptと新規moveが `SAFETY_PRECONDITION` / `requires a safe local world state` で受付前に拒否された。保存証拠は作業用ディレクトリの `_PLAYING/map-art-MFwtpM21wWc-3x3/duper-operation/r18-upper-door-safety-20261010.json`。通常ゲームへの再現入力は行っていない。

- 拒否時は概ね `(97.991373,49,-1.505068)`、体力20・空腹20、炎上・水没・状態効果なし、READY、actionなし。直前のairは300。装置OFF。
- 保存された観測summaryはhazard 162件を示すが、当時の各hazardの明細は保存されていない。体力が満タンでも、衝突・窒息・足場不明・画面状態などの受付条件を満たすとは限らない。
- 地図アート担当の後続報告では、手動操作後に `(100.432887,49,0.967578)` へ移り、黒曜石上で再開可能となった。これは**手動復旧**であり、公開APIによる退避成功として数えない。

コード上、新規moveはlocalSafetyがCONTINUEでない場合に拒否される。実行中moveはREPLANに対する再観測を許すため、扉との衝突で止まった後、新規moveも開始できない組合せは存在する。ただし今回の直接原因は**未確定**。画面・overlay・観測不足なども、R18の同じ汎用エラーになる。

## ソースで確認した境界

`LocalObservationVolume.evaluateCurrent` は現在のプレイヤーAABBをVanillaの衝突・窒息・液体・支持判定で評価する。`LocalObservationProjector.currentSafety` はCOLLISION／UNKNOWNをREPLAN、FALL／LAVA／FIRE_DAMAGE／CONTACT_DAMAGE／FREEZING／SUFFOCATIONをRECOVERへ写す。支持不足などのneutralize条件もREPLANとなる。

`McmcpRuntime.v2InputWorldUnsafeReason` はRECOVERを `local_hazard`、新規受付時のREPLANを `local_safety_unconfirmed` とする。R18の各commitはこの理由を捨て、同じ文言を返していた。scriptは内容がobserveのみでも同じ受付を通る。look・click・input・interact・Waystonesも局所安全条件を通るので、この拒否を回避する公開退避APIはない。内部の旧Action DSL用recovery governorは、新規v2移動の公開退避手段ではない。

`visible_surface.state:null` はblock stateがその観測で提供されていないことを表す。空気や衝突の確定証拠として扱わない。視覚frameと毎tickの局所安全観測は更新経路が異なり、視覚frameの新しさだけでも安全判定を確定できない。

## 診断修正（ソース候補・実機未確認）

共通受付の判定順序と許可条件を維持して、拒否理由を公開エラーの `message` に残す。公開エラーは内部detailsを返さないため、detailsへの追加だけでは解決しない。公開tool数・入出力schema・error code／recoverableは変更しない。

例（衝突を判定した場合の形式であり、現場原因の断定ではない）:

```text
Move requires a safe local world state. Reason: local_safety_unconfirmed. Local hazard: collision; observed_tick: 3859; world_revision: 42.
```

局所安全が拒否理由なら、保持している現在AABBのhazard分類と観測tick・revisionも示す。観測がない場合は `Local observation: unavailable` とする。これは最新の保持観測の診断であり、安全な退避経路を証明するものではない。画面・pause・policy等の拒否には局所hazardを付けない。失敗操作の再送、REPLANからの無条件入力、読み取りscriptの特別許可は追加していない。

R18／R19／R20の固定済み配布artifactは変更しない。通常導入・追加JAR差し替えは依頼していない。

## 次回の公開観測と隔離試験

再発した所有チャットで、入力を伴う再試行の前に以下を直接呼ぶ。scriptには包まない。

1. `agent_get_state({"sections":["control","world","policy","observation","action"]})` と `agent_get_mcp_status({})` を保存する。
2. 最新summaryのframe IDを使い、`agent_get_observation` で現在位置周囲の `hazard` と `traversability` を取得する。例は下記。frame IDは実際の最新値へ置換し、座標範囲も現在位置に合わせる。これは配送対象の絞り込みであり、新しい場所を透視する指定ではない。
3. 必要なら同じ範囲の `visible_surface` を別に取得する。next cursorがある場合は最大4ページまで取得し、打切りも保存する。時間を置いた新しいframeをもう1回取得し、古いframeの再配送を再観測として数えない。
4. hazardのpositionとobserver_position、observed_tick／world_revisionを保存する。現在AABB由来のhazardはpositionがobserver_positionと一致する。周囲の移動候補のhazardと区別する。明細なし／打切り／frame更新なしを安全とは判定しない。

```json
{
  "schema_version": 1,
  "frame_id": "obs-1202c8b5023f01f5",
  "kinds": ["hazard", "traversability"],
  "filter": {"position_bounds": {
    "dimension": "minecraft:the_end",
    "min_x": 96, "min_y": 48, "min_z": -4,
    "max_x": 100, "max_y": 52, "max_z": 1
  }},
  "cursor": null,
  "limit": 128
}
```

隔離Dockerは23:00～12:00 JSTの許可枠で、新しいbaselineとleaseを作って検証する。復元済みの過去baselineを再利用しない。通常ゲームの扉や設備には触れない。

- 静止した扉脇／感圧板による開閉／閉じた扉とAABBが重なる姿勢を個別に再現し、R18と診断修正の判定・無入力拒否を比較する。
- 衝突、真の窒息・落下、観測不足、画面表示を区別して理由が出ること、拒否中も直接のstate/status/observationを取得できることを確認する。
- 安全な姿勢へ戻した後の受付再開を確認し、管理fixture・手動による位置変更を公開API退避と混同しない。
- 自律退避は別の未実装項目。開閉待ちの有限再観測や、現在AABBから新しい衝突を増やさず既知安全地点へ戻る移動を検討するが、危険下の入力許可を緩める前にfixtureで経路・tickごとの証明、予算、取消を確認する。

自動試験はJava 1,732件、harness 14件、admin bridge 28件、計1,774件が失敗・skipなしで成功。buildとverifyHarnessIsolationも成功。現在AABBの衝突をREPLANとして公開hazardへ写す試験、診断本文と公開エラーへの写像、観測がない場合の診断を含む。現場の根本原因の特定・自律退避・隔離実機確認は未完了。

製品ソース `dd032ecfbc4ee0e00fdc5d40a32d5fd57255d74f` の[CI](https://github.com/Aodaruma/mcmcp/actions/runs/38026267817)も成功し、Releaseはskip。共通 `tools/check-source.ps1 -SkipJava` の通信15件・blueprint 4件と保存再開／capability／各v2 runnerのmock試験も成功した。
