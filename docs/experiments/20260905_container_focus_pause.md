# チェスト終了後の自動ポーズ画面の調査

2026-09-05。ゲーム操作・computer-use・設定変更は行わず、別タスクからの公開MCP結果と、ローカルの設定・NeoForge適用済みMinecraftコードを確認した。

## 修正版の配置

「くらふとぶ！-v01.2」の本体JARを旧r4から、チャット遷移とラージチェスト集計上限の修正を含む版へ差し替えた。通常AppDataとCodex仮想化先のパスは同一file IDであり、配置後の両経路のSHA-256は `958F9A26554903DC1BB3C8CAEDE41C41C255643717DD9FADC3342F7EE0C38128` に一致した。旧JARはmods外の `.codex-temp/chest-fix-backups/20260905-201322/` にバックアップ済み。Validationと配布ZIPは変更していない。

その後の起動ログで20:14:28.717のMCP開始を確認した。調査側からゲームを終了・起動していない。

## 再現報告

- `75768e59-6e33-4bec-9472-2bc23c175dba`、20:15:17 JST受付。overworld `(158,64,-305)` → `(158,65,-305)` の連続inspect。
- tick8に一段目のNODE_EVIDENCEとして `container_items=minecraft:cinnabar:14,minecraft:potent_sulfur:5,minecraft:sulfur:10,minecraft:sulfur_spike:1` が返り、NODE_COMPLETED。修正版で実際に公開MCPへ中身が返った。
- 二段目はtick11に `SERVER_DENIED_OR_DESYNC / inventory_screen_context_changed`。
- 続く単独inspect `50acf43c-3a65-444d-bf77-a16b23b49807` はtick1に `inventory_screen_not_clear`。READY、game_paused=false、known_menuなしが報告された。

## 標準設定による画面遷移

利用者から「フォーカスがない状態でMCPがインベントリを閉じると自動でメニューへ戻る」との観察があった。対象profileのoptions.txtでは `pauseOnLostFocus:true` を確認した。

対象バージョンのMinecraft.pauseIfInactive()は、ウィンドウ非focusかつ同設定が有効で、lastActiveTimeから500msを超えるとpauseGame(false)を呼ぶ。Gui.setPauseScreen()は現在の画面がnullのときにPauseScreenを開き、マルチプレイでもこの画面を開く。チャットやチェスト表示中は画面がnullでないため開かず、所有チェストの正常なcleanupで画面がnullになった直後に開き得る。

この経路は、最初のinspect成功後に次のnodeの画面条件が不一致になることと整合する。マルチプレイではPauseScreenでもゲーム自体は停止しないため、game_paused=falseは反証にならない。known_menuは任意GUIの有無を表す項目ではなく、同項目がないことも画面解放の証明にならない。ただし失敗時の画面種別を記録していないため、このActionの直接原因としての確定は設定変更後の追試を待つ。

## 対応方針

別タスクが利用者へ標準の設定切り替えを案内し、pauseOnLostFocus=falseでの実測を担当する。調査側では追加hookや設定書き換えを行わない。途中で着手した診断ログの編集も取り下げ、ソースは既存の検証済み版と同一に戻した。本体JARも上記ハッシュのまま。

公開MCPには任意の画面を強制的に閉じる汎用操作はない。所有権を失った画面を無条件に閉じる機能は追加しない。標準設定変更後の再現性と、単独・連続inspectのNODE_EVIDENCEを別タスクで確認する。
