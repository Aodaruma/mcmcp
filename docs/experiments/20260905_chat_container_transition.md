# チャットとチェスト画面遷移の追加調査

2026-09-05。computer-useを使わず、公開MCPの失敗記録・ローカルのNeoForge実装・回帰テストを確認した。今回の調査ではgameplay Actionを追加実行していない。

## 受領した再現情報

- overworld `(158,64,-300)`、Action `ac49c829-f5d9-447b-8ce7-d2f8ec255e39`。公開get_actionでも `EMERGENCY_STOP / unexpected_screen_closed`、12 ticks、interaction1、effectsなし、NODE_EVIDENCEなしを確認した。
- 同じ列のy65、Action `7b6cc77c-de00-4025-a841-4d8e3171d8ed`も同様の失敗として報告された。両方ともREADYは維持。
- 報告時のplayerは `(160.5856081838354,64,-301.2537241516379)`。ユーザーの画面操作・chatの有無は未確認であり、この2件の直接原因は断定していない。

## コードから確認した不具合

NeoForge 26.2.0.59適用済みGui.javaでは、期待するコンテナのOpeningの後、元の画面のClosingを送る。MCMCPはOpeningでEXPECTING_FULL_CONTENTへ進むが、元のChatScreenにはmenuがないため、そのClosingをunexpected_screen_closedとして停止していた。共通の非pause chat許可と整合しない経路である。

通常world入力を許可するChatScreenのClosingだけを、正しいOpenScreenとOpeningを受けた同じtickに1回許可する。full-content packet前には所有権を与えず、コンテナ自体の予期しないcloseは維持する。期待前・別tick・2回目のchat closeも拒否する。

## 中身の取得経路

前回の成功2件 `82bc705f-0b7e-4fc8-a3dd-484f95819931` と `3e7f04f5-0eee-4c5b-ae27-15f2ef826065` では、公開get_actionのtrace/NODE_EVIDENCEに `container_items=minecraft:birch_log:17,minecraft:dark_oak_log:38,minecraft:oak_log:267,minecraft:oak_sapling:3` が実際に返っていた。GUI開閉だけを成功と数えたものではない。

inspectは転送しないためeffectsが空であること、終了時に所有画面を閉じるためknown_menuがないことは正常。今回の失敗2件は中身を返す前に停止した。取得手順と、最大27種類・256文字の要約で完全な棚卸しを保証しない制限をクイックガイドへ追記した。公開schemaは変更していない。

## 検証と反映状況

- 同tickのchat置換からfull-contentで所有権を得られること、full-content前は未所有であること、所有コンテナの予期しないcloseを拒否する回帰テスト。
- 期待前・別tick・重複のchat closeを拒否する回帰テスト。
- unit 1,073件、harness 13件、admin 21件、build・harness isolation成功。
- JAR SHA-256: `C6142231CB93F11B2A27D84EC42ED20142651D10B918F737AC18D900186E555C`。
- 実行中ゲームとr4配布ZIPは前の検証済み版のまま。今回の追加修正版の差し替え・再起動・chatを開いた実ゲーム追試は未実施。computer-use禁止の条件に従い、画面操作や再起動は行っていない。
