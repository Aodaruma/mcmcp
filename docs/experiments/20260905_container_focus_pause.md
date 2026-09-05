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

## 利用案内と通知案

利用者からREADME案内・有効時の通知・自動無効化の改善提案を受領した。repository README、導入と接続ガイド、配布用READMEのMarkdownへF3＋Pによる切り替えと確認手順を追記した。設定が保存され通常プレイにも影響すること、トグル操作で戻せること、手動Escは維持されることを明記した。KeyboardHandlerの実装でも同設定の反転・options.save・on/offのdebug feedbackを確認した。PDF・配布ZIPはこの段階では再生成していないため、追加案内はMarkdown側の更新である。

MOD側は、MCPをONにした時点でpauseOnLostFocusが有効なら非モーダルの通知を1回表示し、同じ注意を操作ボタンのtooltipで再確認できる案を推奨する。既存AutomationIndicatorControllerにON操作、overlay、tooltipの表示経路があるため、新たな確認画面で操作を中断する必要はない。無効なら表示せず、OFF→ONや設定の変化を単位として重複通知を防ぐ。これは検討案で、UI実装はまだ追加していない。

設定の自動無効化は、通常プレイまで影響する永続設定の書き換えになる。MCP終了時に元の値を戻す方式でも、実行中の利用者によるF3＋P変更を上書きしない扱いや異常終了時の復元が必要になる。自動pause経路だけをMCP ON中に抑える方式なら永続設定を変更せずに済むが、待機中にも標準の挙動が変わる。まずREADMEと有効時の通知を優先し、自動変更は利用者が選択できる設計を別途検討する。どの方式でも手動Esc・明示pause・予期しない画面の安全停止は維持する。
