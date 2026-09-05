# 2026-09-05 配布自動化と追加観測課題

## 配布のローカル検証

- コンテナ全品目取得はcommit `0421ec1`で実装済み。確認担当の別タスクへ固定コピーのJARを渡し、交換・再起動後の再検証を依頼した。
- Java 25で`test harnessTest adminBridgeTest verifyHarnessIsolation build`を実行。unit 1082件、harness 13件、admin bridge 21件が成功した。
- `mod_version=0.1.0-rc3`を指定したJARと配布ZIPをローカル生成し、JAR内のバージョン・MPL-2.0・開発用class非混入を検証した。タグの作成やRelease公開はしていない。
- 標準の`0.1.0-SNAPSHOT`もbuildし直し、配布ZIPを生成した。READMEはTyporaを使わず、Markdown・独自CSS・Noto Sans JP・headless Chromiumから8ページのPDFを生成。全ページを画像化し、注記、目次、見出し、画像、アイコン表、改ページを確認した。
- リリースツールのテスト3件、actionlint、ZIP CRC・JAR内容一致・PDF内容検証が成功した。CI結果はGitHub Actionsに記録する。
- 初回Linux CIでは`modernFormElicitationCarriesKillZoneApprovalWithoutMinecraftPrompt`の改変拒否検証が失敗した。署名末尾をAからBへ変えた場合にBase64の未使用bitだけが変わり、同じ署名byte列へ復号されるケースが原因だった。署名の正規表現・長さと再encode一致を要求し、同じbyte列になる別表記とpadding追加を必ず拒否する回帰検証を追加した。承認内容やHMAC比較の条件は緩めていない。

## 実ゲーム確認担当からの追加報告（未解決）

本項は別タスクからの報告とコードの読み取りによる仮説であり、この配布変更ではゲーム操作・修正・JAR再交換を行っていない。

### 額縁付近でコンテナ操作が開始しない

- `(164,67,-300)`の対象では再配置後もinteraction count 0で期限切れ。隣接するx=165の対象は成功した。ほかにも高さ違いで成功・失敗が分かれたとの報告がある。
- 現状の`MinecraftPhaseFiveInventoryPort.maintainAim`と`exactHit`は、通常crosshairの`BlockHitResult`が対象blockと一致することを要求する。額縁等の`EntityHitResult`が手前にある場合、固定照準点では操作開始に至らない可能性がある。
- 額縁が直接原因であることは未確認。検討する場合は、通常可視判定と到達距離を維持した有限個の照準点候補を使い、遮蔽無視・entity攻撃・額縁除去で代替しない。

### fog_limitにより観測が約1ブロックに狭まる

- 31件取得後、位置`(169.4305,64,-301.5936)`、READY、game_paused=false、health=20、status_effects=[]で、visible_surface=0、visible_entity=0、unknown_boundary=2048、traversability=259との報告があった。
- 全方向のunknown boundary reasonが`fog_limit`で、eyeから約1ブロックだった。直前にもSAFETY_PRECONDITIONが頻発したとの報告がある。
- 追加のコード確認では`ClientFogDistanceSignals.currentOrIdentity`がentity tickの完全一致を要求し、`McmcpRuntime`が不一致時のfallbackに`1.0D`を渡している。描画更新から新鮮なsampleを得られなかった場合もこの結果が起こり得るが、今回の実機原因とはまだ確定していない。
- 描画・focus・menuとfog snapshotの鮮度の関係を次回確認する。観測が狭まった原因とSAFETY_PRECONDITIONとの因果関係は未確定で、安全境界を緩和して解決したことにはしない。
