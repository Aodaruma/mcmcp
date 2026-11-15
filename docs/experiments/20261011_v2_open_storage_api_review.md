# nav-r14：既存の収納画面と照準指定の公開API確認

2026-10-11、現場からの問い合わせに対するコード確認。対象製品のsourceは `fc349a82c1261d9780f0820d83e7dd2b45a90267`（nav-r14）。製品コード、通常ゲーム、JAR、設定は変更していない。

## 報告と確認範囲

上段チェストの `inspect_storage` が `container_target_not_observed` / `target_aim_unconfirmed` で終了した一方、端へ `agent_look` して対象条件付き `agent_click` を行うと収納が開いた。開いた後の `known_menu` は16件で打ち切られ、再度 `inspect_storage` を実行すると `screen_changed` で拒否された。

現場担当から、利用者がEscで閉じた後、残り9箱は「端へ `agent_look` → `inspect_storage`（`advance=false`）」で成功し、調査完了・ActionなしREADYとの続報を受領した。これは現場からの報告であり、本確認で実機試験を追加したものではない。今回の照準失敗の直接原因は、当時の観測rayの証拠がないため未確定。

## 現行公開APIで可能なこと・不足すること

| 用途 | nav-r14の状態 |
| --- | --- |
| 閉じた収納を開き、内容を読み、閉じる | `agent_interact target=block, operation=inspect_storage`。Vanilla収納は収納部分のitem別数量を返す。 |
| 閉じたmenuを開き、全スロットを読み、閉じる | `agent_interact target=menu` に座標・`block`・`menu_type`を指定し `clicks=[]`。最大256slotの`slot/item/count`を返す。プレイヤー側の枠も含む。通常ラージチェストの型は `minecraft:generic_9x6`。 |
| すでに開いている収納を引き継ぎ、全内容を読む | 公開v2では未対応。上記二つはいずれも自身で新規に開くActionであり、画面が開いた状態の新規受付は拒否される。 |
| すでに開いている収納を公開toolで閉じる | 専用operationなし。内部の終了処理は、そのActionが開いて所有したmenuを対象とする。Action取消を任意の既存GUIの終了手段にはできない。 |
| `known_menu`で内容を全件読む | 不可。`operations`は全スタックを移送できる枠の参照であり、最大16件。空枠・移送不可枠を省略する。ページ引数なし。`max_results`はレシピ検索用で、この上限を変更しない。 |
| `inspect_storage`へ照準点・面を指定 | 公開引数なし。`aim_point` / `face`等の追加キーは拒否される。事前に`agent_look`で端へ向く方法は利用可能。 |

`target=menu`にも同じ観測照準の条件があるため、`inspect_storage`の照準失敗を自動的に解消する代替ではない。内部DSLの `operate_known_menu` は公開v2 toolやscriptのコマンドではない。`agent_input_sequence`の入力種別にもEscやinventoryキーはない。

## コード上の根拠

- [V2InventoryContainerArguments](../../src/main/java/dev/aod/mcmcp/runtime/V2InventoryContainerArguments.java)：`inspect_storage`引数を収納用parserへ変換。照準点は内部生成であり、利用者の指定項目ではない。
- [MinecraftV2ContainerDriver](../../src/main/java/dev/aod/mcmcp/runtime/MinecraftV2ContainerDriver.java) と [V2BlockAimResolver](../../src/main/java/dev/aod/mcmcp/agent/action/V2BlockAimResolver.java)：最新frameの対象面rayを要求する。rayの観測から40tick以内、現在の眼位置と観測時のずれ0.125block以内、眼からray hitまで4.5block以内、対象座標・world revision等を検査する。現在のcrosshairだけでこれらを省略しない。
- [ActionWireMapper](../../src/main/java/dev/aod/mcmcp/runtime/ActionWireMapper.java) の `knownMenuPayload` と [KnownMenuOperationRefs](../../src/main/java/dev/aod/mcmcp/runtime/KnownMenuOperationRefs.java)：移送可能枠のみを参照化し、`MAX_LEASES=16`で打ち切る。
- [V2MenuArguments](../../src/main/java/dev/aod/mcmcp/runtime/V2MenuArguments.java) と [MinecraftV2MenuDriver](../../src/main/java/dev/aod/mcmcp/runtime/MinecraftV2MenuDriver.java)：空click列は読み取りのみ。server由来の全menu slotsを返し、自身のExpectedOpenTokenで所有したmenuを終了する。
- [McmcpRuntime](../../src/main/java/dev/aod/mcmcp/runtime/McmcpRuntime.java)：新規interactの `requireV2WorldSafe` と `v2InputWorldUnsafeReason` が既存画面を拒否する。scriptのコマンド集合にも既存menu引継ぎ・終了はない。
- [公開catalog](../MCMCP_MCP_Tool_Catalog.json)：公開16ツールと各引数を照合。nav-r14製品source以降、確認時点の変更は文書と隔離fixtureのみ。

## 残る開発課題

既存画面に対する読み取りと終了を `agent_interact` の明示operationとして追加する余地がある。読み取りは同期済みの全スロットと収納・プレイヤー枠の区分、打切りの有無を返し、終了は観測したmenuの参照・session・container同一性を確認して行う設計が必要。カーソルにアイテムがある場合や未知のMOD画面は別途契約を定める。今回これらを実装・検証したものではない。

照準については失敗理由の内訳を公開診断に加えること、指定した面・点を通常の観測・reach確認を通すhintとして受け付けることを候補とする。既存の成功例だけから原因や修正方針を確定しない。

## 検証

nav-r14の配布catalogと対応sourceを照合し、関連するparser・画面受付・照準・返却・終了経路を確認。文書リンクと差分の空白検査を実施。製品コードを変えていないため、自動試験・実機試験・JAR生成は追加実行していない。
