# 公開API v2：長時間・多回数指定の自動・隔離実機検証（2026-09-29）

利用者の追加指定により、右クリック専用案から全行動の明示的な上限拡張へ変更した。既存`feat/public-api-v2`／Draft PR #85で実装し、このWindows PCで自動検査した。続いて利用者の開始許可と担当間のGUI引き渡し後、同じ候補JARで短い隔離実機試験を行った。通常サーバーの粉硬化装置をfixtureには使用していない。

- 製品source: `633caff0d69562a63b187e7aa22bb8fe5060cf1a`
- 候補JAR SHA-256: `f787171893dff9ea3027fb9139c28ad28f8dad0b05ffcc375b8fa304bc9b2e7b`
- Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25。公開13ツールを維持。
- [機械可読の候補・検査記録](artifacts/20260929-v2-long-execution/candidate.json)
- [上限・既定値・使用例・停止契約](../PUBLIC_API_V2_LONG_EXECUTION_20260929.md)

## 実装した契約

共通job、各引数、click/input、script interpreter、収納の内部bounds、公開input/output schemaを同期した。明示tick予算は最大1,728,000、scriptは最大24実時間、calls 1,728,000、iterations 10,000,000、work 100,000,000。通常の既定値を維持した。総移動距離の明示上限は4,096 block。

全jobは受付時に単調時計の期限を設定し、scriptの子は親の残り期限を共有する。新しい子を開始しても総期限を更新しない。jobのdispatchと、移動／attack／use／pickの入力消費側で期限を検査し、tick停止・pause後の期限切れ入力再発行を防ぐ。取消等の停止と解放確認後のterminal公開は既存経路を共有する。

inputはstepsに加えて全論理入力の秒指定を受け付ける。材料を明示したuseだけには静止・同slot・同item・空offhandのguardを追加し、空手中はuseを解放して最大1..300秒の補充待ちを行う。block state、個数、stack identityの固定は不要。総期限は待ち時間分延長しない。

## 検査結果

`gradlew test harnessTest adminBridgeTest verifyHarnessIsolation build`成功。製品Java **1,612件**、harness **13件**、admin bridge **28件**でfailure/errorなし。`tools/check-source.ps1 -SkipJava -PythonExecutable python`も成功。今回17件の回帰試験を追加し、以前の上限超過を拒否する試験は新しい境界へ更新した。

| 検査 | 確認結果 |
|---|---|
| 旧上限を超える入力 | 1,500 tickを継続し、終了時に解放 |
| 旧上限を超えるscript | 1,500 callsを完了。有限のwork/iteration/call上限は継続 |
| 旧実時間上限を超える移動・施工 | 偽の経過時刻121秒で移動到達・block変更の確認を完了。従来の固定2分で誤停止しない |
| 24時間境界 | 境界直前のみdispatch可。到達後は不可、残り時間0、release未完了ではterminal不可 |
| tick停止後 | 時刻を24時間進めた次のtickで、次の入力より先に解放。保持tick数は増えない |
| 親子の期限 | 連続する子jobが同じ親deadlineを継承。偽時計でscript期限に達した後の次のcommandを発行しない |
| 取消・解放 | 秒指定inputの正常終了が解放待ちの間に取消された場合、cancelledを優先。既存Esc/OFF/world/安全境界の回帰も通過 |
| pauseと入力消費側 | pause後の全入力を期限で抑止。leaseを再発行しても期限切れ入力を復活させない |
| 補充 | 空の間は解放、同item復帰のみ再開、期限後の遅い補充・別item・slot・offhand・姿勢違いを拒否 |
| 公開契約 | 全行動の拡張引数、秒形式とstepsの排他、材料guard制限、旧上限を超える進捗と実時間情報がschemaに一致 |

検査中に、別variantの不足項目が優先される入力診断の退行と、進捗output schemaに残った旧上限を修正した。oneOfの妥当性判定は変更せず、診断は必須項目が揃っているvariantを優先する。入力のlease heartbeatが通常再publishすることも試験の期待値へ反映した。

## 隔離試験の準備

[準備手順・API引数・datapack](../../tools/eval/fixtures/public-api-v2-long/README.md)を追加した。既存の専用profile/worldと単発CLIを再利用し、期限、CLI終了後の継続、取消、材料の枯渇と正常/遅延/別材料補充、非アクティブ化、旧1,200tick超えを別runで確認する。補助fixtureは有限のserver tick処理で、公開MCPや製品JARの権限を拡張しない。

準備時にリクエスト8件の公開input schema適合、function 7ファイル間の参照解決、予約処理の1,200tick停止条件、自動load/tick tag不在、手順中11リンクを静的確認した。候補JARのhashは上記のまま。この準備時点ではゲーム接続・画面操作・profile変更を行わず、以下の引き渡し後の実機段階でdatapackの読込と配置rayを確認した。

## 隔離実機の結果

差し替え担当がネイティブPrism側の専用profileへ候補を配置し、物理パス・JAR hash・製品JARが1個であることを確認した。fixture-admin JAR/flagは使用していない。元の仮想化側profileは保持し、今回のbaselineと専用worldの復元を別記録にした。Minecraft 26.2 / NeoForge 26.2.0.59 / Zulu 25のsingleplayer、session `af76fd17-818e-42ff-a4a5-a78cb34f9e41`、体力20、空offhand、開始slot 5で実施。

これは決定的な機能smokeで、24時間運転やfresh LLMの自律評価ではない。API条件を満たす8試験を確認し、製品コード・JARの追加変更は不要だった。[一覧](artifacts/20260929-v2-long-live/summary.json)と[証拠hash manifest](artifacts/20260929-v2-long-live/manifest.json)を参照。

| 試験・証拠 | 確認結果 |
|---|---|
| [A 秒指定終了](artifacts/20260929-v2-long-live/duration.json) | 60tick、3.033秒、`succeeded / duration_elapsed`、remaining 0、control解放 |
| [B 開始CLIから独立](artifacts/20260929-v2-long-live/detached.json) | 開始CLI終了後の同一Actionが、5秒の無通信区間を含め400tick・20.046秒まで継続し終了 |
| [C 取消](artifacts/20260929-v2-long-live/cancel.json) | 実入力9tick後に取消、`cancelled / client_request`、終了後も同じ結果とcontrol解放 |
| [D 枯渇・補充](artifacts/20260929-v2-long-live/refill.json) | 粉1個消費後、空slotと`waiting_for_item`、入力数1のまま待機。同slotへ32個補充後に`holding`と消費再開。取消後28個で停止。GUIのfixture設置数は計5 |
| [E 遅い補充](artifacts/20260929-v2-long-live/late.json) | 入力1tick後、約3.103秒で`refill_timeout`。その後の粉32個が消費されず、terminal不変。GUIの設置数は1 |
| [F 別材料](artifacts/20260929-v2-long-live/wrong.json) | 入力1tick後、dirt到着で`item_changed`。32個を消費せず、GUIの設置数は1 |
| [G 非アクティブ](artifacts/20260929-v2-long-live/background.json) | 開始直後にPrismを前面へ。Minecraftの背景中に入力数166→397、`game_paused:false`。20.001秒・399tickで期限終了、復帰後もterminal不変。既存`pauseOnLostFocus:false`を変更せず確認 |
| [H 旧上限超え](artifacts/20260929-v2-long-live/extended.json) | 1,452tick時点のrunningを記録し、1,500tick・75.060秒で`completed`。終了後のcontrol解放 |

Gの[初回](artifacts/20260929-v2-long-live/background-invalid.json)は画面切り替えが20秒の期限後だったため無効。開始処理とfocus移行を連続実行した別Actionを合格証拠とし、初回を背景継続の証拠に含めていない。

画面観測はAPI判定と分けた。A/B/Cは終了後の通常画面と待機表示、D/E/Fは上記のslot個数とfixture設置数を目視したが、それぞれの画像ファイルは未保存。Gの復帰後の通常立ち姿、Hの開始前・保持中・終了後の姿勢はprivate画像に保存した。H終了直後のcaptureは古い保持姿勢・青表示が残ったため解放証拠から除外し、F3で描画更新後の通常立ち姿・待機表示を確認した。Shift/Escで解除して成功に見せる操作はしていない。公開APIに物理キー内部状態はなく、その直接測定を合格条件として偽装しない。runner既定の`gui_release_verified:false`を書き換えず、[一覧のGUI観測欄](artifacts/20260929-v2-long-live/summary.json)へ別記した。アバター等を含む画像は自動公開しない。

全Action終了後、`/function v2_long:stop`を実行し、元の一人称・F3非表示へ戻してMCP OFF。14:47:48 JSTの[引き渡し記録](artifacts/20260929-v2-long-live/final-handoff.json)は同じsession、`off / game_paused:true / running_action_id:null`、体力20・空のhotbar。GUI操作権を依頼元へ返した。ゲーム保存終了、今回baseline復旧、通常profile導入は差し替え担当の後続工程であり、この引き渡しだけでは完了としない。

## 残る確認・導入

**短い隔離入力試験は確認済み。通常profile導入・そのMOD構成での運用・24時間連続実測は未確認。** 拡張した移動距離上限・長い移動/施工・scriptの最大予算を実ゲームで全て走らせたものではない。OSスリープ中の稼働や任意MODによる背景tick抑制を保証しない。局所安全・短い確認待ち等で最大時間より早く停止することがある。

候補JARとhashは用意済み。現場の状態確認・実機の時間帯・JAR差し替えは担当間で調整する。前回のAppData仮想化による導入誤判定は[試用メモ](../PUBLIC_API_V2_TRIAL_20260929.md)と[保守手順](../MAINTENANCE.md)を訂正した。今後は実体配置確認と再起動後の公開schema確認まで揃えて導入完了とする。main統合・タグ・Release公開は行わない。
