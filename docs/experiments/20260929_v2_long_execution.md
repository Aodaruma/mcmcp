# 公開API v2：長時間・多回数指定の自動検証（2026-09-29）

利用者の追加指定により、右クリック専用案から全行動の明示的な上限拡張へ変更した。既存`feat/public-api-v2`／Draft PR #85で実装し、このWindows PCで自動検査した。GUI・現場ゲーム・通常profileへの操作は今回行っていない。

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

準備時にリクエスト8件の公開input schema適合、function 7ファイル間の参照解決、予約処理の1,200tick停止条件、自動load/tick tag不在、手順中11リンクを静的確認した。候補JARのhashは上記のまま。ゲームへの接続・画面操作・profile変更・functionのゲーム内実行は行っていないため、datapackの実機読込と配置rayの確認も残る。

## 残る実機確認・導入

**新候補の実機確認・通常profile導入・24時間連続実測は未実施。** 短い隔離試験で粉設置、同slotへの補充、空待ち、別item、取消、期限、CLI終了後とゲーム非アクティブ時の継続・停止を確認する。OSスリープ中の稼働や任意MODによる背景tick抑制を保証しない。局所安全・短い確認待ち等で最大時間より早く停止することがある。

候補JARとhashは用意済み。現場の状態確認・実機の時間帯・JAR差し替えは担当間で調整する。前回のAppData仮想化による導入誤判定は[試用メモ](../PUBLIC_API_V2_TRIAL_20260929.md)と[保守手順](../MAINTENANCE.md)を訂正した。今後は実体配置確認と再起動後の公開schema確認まで揃えて導入完了とする。main統合・タグ・Release公開は行わない。
