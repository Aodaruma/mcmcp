# 公開API v2：所持収納と使用中停止のローカル実機試験

Issue #84 / Draft PR #85。2026-09-29、このWindows PCで、前回の[基本smoke](20260928_public_api_v2_local.md)に残った所持収納と追加停止経路を確認した。製品側の既存実装は今回の範囲で正常に動作し、Javaコードの変更は不要だった。今回の実装は再実行用runner、fixture、誤合格を防ぐ回帰試験、手順と証拠の追加である。

## 対象と準備

- checkoutの基準: `2565dfec40b561f7cb7dec7697d11b7ac9db78d3`。製品コードは前回と同じ`e79f59888e2aece6d426437e0d4c545f8e27384a`。
- Minecraft 26.2 / NeoForge 26.2.0.59 / Zulu Java 25。専用Prism profile `MCMCP-V2-Local-20260928`、survival / peaceful。通常profileのsave・設定は使用していない。
- 製品JAR SHA-256: `6acde6beacbea21c812c390568332ccf5bcd8a21005204085ff3eda633dbdde5`。
- fixture-admin JAR SHA-256: `9b4aeb58e57c6ac3353fe8d553f55971cf11b50766506b4e9c2cd7dc6a584230`。
- Sophisticated Backpacks `26.2-3.25.90.2084`: `9b8b60c087937b141c8ed61c8fea357ac8931f86eda42a26198c231712eb4037`。
- Sophisticated Core `26.2-1.4.99.2265`: `f80b8868d15b59882c642ebaa020100e9d1f59cfbae8bdb6a584140b658fb10e`。

前回保存した試験用baselineを専用profileへ復元し、既存の独立検証instanceにある上記2 MODのJARを追加した。組込みprofileの要求hashと導入先を照合した。通常のlauncherログインで起動し、認証情報をコピーしていない。

UI経由の準備関数でhotbar slot 2へbackpack、slot 3へshieldを配置。新規backpackは通常UIで一度開閉して個体IDを初期化した。**未初期化の新品backpackを自動初期化する経路は今回の対応範囲外**。初期化後の空収納27枠と、snow blockの所持数16を確認した。simulation distanceは専用profileだけ4から5へ変更した。

WorldChange用にはnetherのchunkをロードし、25cellのstone足場と上部airをUIの準備関数で作成した。移動先を確認してから、試験プレイヤーだけをtagで選ぶ15秒後の次元移動を予約した。操作runnerは公開MCPだけを使い、admin tokenや任意commandを持たない。準備と再現手順は[fixture README](../../tools/eval/fixtures/public-api-v2/README.md)を参照。

## 実機結果

| フェーズ | 確認結果 | 証拠 |
|---|---|---|
| Storage | 空収納inspect → store3 → 再開封inspect → take2 → 再開封inspectが成功。収納内snowは0→3→1、プレイヤー側は16→13→15。両transferの確定数が要求数と一致し、`unconfirmed:false` | [結果JSON](artifacts/20260929-v2-storage-stop/storage.json) |
| ItemCancel | shield使用の受付と4tick稼働を確認してAPI取消。6tickで`cancelled / client_request`。続く1tickのshield使用も成功 | [結果JSON](artifacts/20260929-v2-storage-stop/item-cancel.json) |
| Escape | shield使用中に物理Escを入力。296tickで`failed / local_emergency_key`。実行中actionなし、control READY。ゲームへ戻った画面でshield使用解除と元の手持ちへの復帰を確認 | [結果JSON](artifacts/20260929-v2-storage-stop/escape.json) |
| UiOff | chat画面を開いてからshield使用を開始し、画面のMCPボタンをOFFにした。655tickで`failed / local_ui_disabled`、control OFF、実行中actionなし。画面でもshield解除を確認 | [結果JSON](artifacts/20260929-v2-storage-stop/ui-off.json) |
| WorldChange | shield使用中に予約済みfixtureでnetherへ移動。214tickで`failed / world_boundary`。sessionが変わり、control OFF、実行中actionなし | [結果JSON](artifacts/20260929-v2-storage-stop/world-change.json) |

使用中停止はすべて`hold_ticks:1000 / max_ticks:1200`で一度だけ開始した。開始受付だけでなく、同じactionの稼働・`dispatched:true / client_consumed:true`を停止前に確認した。自然終了や別の理由による停止を成功に数えていない。shieldの`effect_confirmed:false`はitem固有効果を照合していないという意味であり、使用解除や制御終了の判定とは分ける。

主要action ID:

- store3: `29ddcf18-4585-43bd-914d-f6e09221a251`、take2: `05633b2e-825e-479e-8fce-94afc8516b42`。
- ItemCancel: `d98d63bd-6b6a-4bba-86d4-10bfacb3aa50`、取消後の新規使用: `84d7b426-c096-4178-8ac1-876e7c2f6e02`。
- Escape: `764fdf16-3fb4-485c-ab08-0b32ce3438ee`。
- UiOff: `ba8e5534-74e8-4777-af7c-72f2637180f8`。
- WorldChange: `b51b75f3-9e59-4a41-b594-e9458df42c31`。

初期sessionは`ef8dcc0c-5f92-41fe-8054-c9da6298b295`、次元移動後は`0bcbe027-b81f-4157-935d-fd715835ad64`。移動後の[readback](artifacts/20260929-v2-storage-stop/world-change-readback.json)はnetherの`(200.5,201,200.5)`、体力20、空腹20、炎上・水没なし。OFF中の新規操作は`MCP_OPERATION_DISABLED`で拒否された。手動で再許可した後、[UI OFF後](artifacts/20260929-v2-storage-stop/after-ui-off-probe.json)と[次元移動後](artifacts/20260929-v2-storage-stop/after-world-change-probe.json)の新しいshield使用が正常終了した。次元移動後の選択枠はshieldのslot 3であり、旧プレイヤーの選択枠復元を新プレイヤーへ適用したとは扱わない。

## 回帰検査と復旧

`tools/check-source.ps1 -SkipJava`成功。MCP transport 15件、既存smoke mock 7件、新しい収納／停止mock 13件と、台帳・復旧・capability gateの検査が成功した。新mockでは未確定移送、数量不一致、早期終了、停止理由不一致、OFF未移行、session未変更・未生成を誤合格にしないことを確認した。mockはMinecraftの実機証拠とは別である。製品Javaとadmin bridgeは変更していないため、前回のJava 1,577件・harness 13件・admin bridge 28件の実施対象はそのままで、今回ローカルでは再実行していない。

ゲームを保存終了し、Minecraftとlauncherの停止後、変更後profile全体をローカルartifactsへ退避した。専用profileを試験前へ戻し、**12ファイルの個数と全SHA-256一致**を確認した。[復旧receipt](artifacts/20260929-v2-storage-stop/restoration.json)に相対pathとhashを記録した。追加MOD、試験world・nether、datapack、設定変更、生成tokenは稼働profileに残っていない。

[candidate metadata](artifacts/20260929-v2-storage-stop/candidate.json)と[証拠manifest](artifacts/20260929-v2-storage-stop/manifest.json)には導入物と公開JSONのhashを記録する。公開JSONはLFに正規化し、認証情報・profile本体・ゲームlogは含めない。

## 残る検証と機能

所持収納の応答待ち途中取消・通信遅延、装備枠やoffhandの収納、upgrade付き収納、他itemや他の行動種類での各停止、ログアウト／再接続は今回の範囲外。既知の単体試験だけで実機合格へ置き換えない。今回のWorldChangeは次元移動であり、すべてのworld境界経路を網羅していない。

menu操作、move中の障害物処理、遠方entityへの接近などの未実装機能とPR全体レビューは引き続き残る。現在の一覧は[進捗メモ](../PUBLIC_API_V2_STATUS_20260928.md)を参照。PR #85はDraftを維持する。
