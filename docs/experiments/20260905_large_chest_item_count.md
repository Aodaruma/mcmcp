# ラージチェスト集計個数の上限修正

2026-09-05。computer-use・gameplay Actionを使わず、実行ログとコードを調査した。

## 再現情報と原因

- `694ec9f3-6f13-4410-87d5-17db82a2ac41`、overworld `(158,66,-304)` のinspectで `INTERNAL_ERROR / runtime_exception`。17 ticks、interaction1、NODE_EVIDENCEなしとして報告された。
- 最新ログの19:58:56.619に `IllegalArgumentException: item count is outside the bounded range` を確認。`KnownContainerAttempt.ItemCount` → `itemCounts` → `tick` → `McmcpRuntime.tickAgentContainer` で発生した。
- server同期snapshotの同一アイテム合計を、ItemCountが2,304個で拒否していた。これはプレイヤー36枠×64の上限で、対応済みgeneric_9x6の54枠×64=3,456個と不整合だった。ログには対象アイテム名・正確な個数は出ておらず、そこまでは断定していない。
- 先行Action `ec10a5f5-f279-4806-affd-77814a92c8fb` の `unexpected_screen_closed` は別件。当初は利用者から操作が重なったとの補足があったが、その後、操作を止めた再試行でも報告された。後述のとおり、インストール済みJARはチャット遷移修正前の版だった。

## 修正と検証

- 読み取り結果ItemCountの上限を54×64へ修正。server full-content・画面所有権・cleanup後に結果を返す条件、公開DSLの転送目標上限は変更していない。
- 2,305、3,000、3,456個でserver-confirmed結果がcleanup後に成功として返る回帰テスト。interaction1、effectsなし、release/retire各1回を確認した。
- 0と3,457個は引き続き拒否する境界テスト。
- unit1,075件、harness13件、admin21件、buildとharness isolation成功。
- JAR SHA-256: 958F9A26554903DC1BB3C8CAEDE41C41C255643717DD9FADC3342F7EE0C38128
- 実行中ゲームと配布ZIPは変更していない。他タスクで再試行中のため、ゲーム操作を追加していない。修正版を反映して公開MCPのNODE_EVIDENCEに内容が返る実機追試は未実施。

## 追加報告とインストール済みJARの照合

- `393635c2-dc55-4e35-b0d4-95bf47e85904` は `(158,64,-304)` で13 ticks、`unexpected_screen_closed` と報告された。
- その後の `bf37c606-c3e9-4140-a231-9a1d42673e5f` と `6b90c685-923b-409d-ad7a-0b5500864ff1` はz=-305の棚で、1 tickの `inventory_screen_not_clear`。READY・known_menuなしも報告された。
- 利用者がチャット画面へ戻した後の `faab5db7-f08d-42d3-967c-e27ec5900e58`（20:04:15 JST受付）も17 ticks、interaction1で `unexpected_screen_closed`。NODE_EVIDENCEには到達していない。
- 通常AppDataとCodex仮想化先の両方で、「くらふとぶ！-v01.2」「MCMCP-Validation」の本体JARをSHA-256照合した。4か所すべて `FEBE730A4436C58F558080DBDA763F213D61EC470A46C967D22BE967C9732F58`（r4）で、チャット遷移修正4454810も個数上限修正6e3ccd9も含まない。javawの起動は19:50:13、元profileログのMCP起動は19:50:29だった。これらの再試行を追加修正版の実機検証とは数えない。

## 例外後の終了処理の確認

コード上、runtime例外も `failAgentAction` → `releaseAgentControl` → `KnownContainerAttempt.close` から通常の終了処理へ進む。所有画面のID・menu種別・server cursorを確認して閉じ、解放未確認ならterminal公開を保留する。今回確認したログには `known-container release failed` やterminal cleanup失敗の記録はなかった。

`inventory_screen_not_clear` は画面種別だけでなく、playerのmenuがinventoryへ戻っていること・所有権がIDLEであることも要求する。公開known_menuがないだけではこの3条件を満たすとは限らず、この記録だけから個数例外による画面残留とは断定できない。旧版のchat Closingで失敗した際の残留も候補だが、イベント単位の実測記録はないため未確定とする。所有権のない画面を強制的に閉じる変更は加えていない。

次の実機検証では、追加修正を含むJARのハッシュを確認してから再起動し、チャットからのinspectと3,456個以内のラージチェスト内容が公開NODE_EVIDENCEへ返ること、続くinspectも実行できることを確認する。調査側はゲーム操作・再起動を行わず、別タスクの操作と競合させていない。

## 後続の実機結果

修正版958F9A…の配置・再起動後、利用者がフォーカス喪失時の一時停止設定を切り替えた状態で、検証担当タスクから単独inspect 19件連続成功の報告を受けた。エンドストーン3,456個・安山岩3,456個も公開NODE_EVIDENCEで取得でき、2,304個を超える集計の実機確認に到達した。詳細と検証範囲は[フォーカス調査記録](20260905_container_focus_pause.md)を参照。配布ZIPへの反映とは別の結果である。
