# レシピ参照範囲の実機受入（2026-11-15、#71 / PR #87）

状態: **既定の解放済み範囲と、追加の公開同期データを参照する範囲の代表検索が合格**。材料・設備の判定、Magnet の取得など #71 全体の完了を意味しない。製品コード変更はない。

## 対象と境界

- source: `575a55e460f78354d7293a04e1c4efa1d4aed4d1`。
- 製品 SHA-256: `507651b969c86ab0c8ed586b9c04850b6a225071b871f4cf67e245bf60b6d0ff`。
- dev harness SHA-256: `355db948dc2686e49683d4e68a0768c56ab984874dda28888f9fa65a20b07ceb`、同梱 fixture-admin: `cd8a04b8b9ef96aeec0215bb00224485837f03ffe216d78979338dfb57399c9c`。
- 隔離 Docker clone、Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25。新しい server companion、JEI、同期用 MOD は追加していない。
- 排他的な有限 lease を取得し、world・MOD・設定の 66 ファイルを事前に hash 付きで保全した。live の公開 Tool 5 本の完全な定義を source catalog と照合した。
- 最初の T0 前に固定コマンドで recipe unlock を消去し、`minecraft:stick` と `minecraft:oak_planks` だけを明示解放した。T0 後の検索は公開 MCP のみで、Action を開始していない。

## 既定 UNLOCKED

`recipe_scope="UNLOCKED"` で起動し、棒・oak planks・planks tag・MOD backpack・nether star を検索した。既知 2 件、追加 source は `not_selected`。明示解放した棒と板材は返り、未解放のバッグを含め、返らない結果を不存在と断定していない。

**7 request / 41 check が合格**。`complete=false` / `absence_proven=false`、各レシピの source、`materials_status=not_assessed` / `equipment_status=not_assessed` を確認した。Tool 引数に scope を追加してローカル設定を上書きする試みは `INVALID_ARGUMENT` で拒否された。

## ALL_CRAFTABLE と実同期

MCP OFF 中にローカル設定を変更した。Docker bind 上のファイルを実行中に編集しただけでは client へ反映されず、最初の scope 確認は不合格として保持した。設定変更の即時反映や hot reload の成功は主張しない。world を保存して同じ lease 内で container / client を再起動し、`recipe_scope="ALL_CRAFTABLE"` の起動状態で再試験した。

既存環境の公開 NeoForge `RecipesReceivedEvent` から、既知 **1709 件**、7 recipe types、display を持たず省略した **26 件**を取得し、追加 source は `partial` / `limited=false` / `failed=false` を返した。

| 検索 | 実測 |
| --- | --- |
| `minecraft:stick` | 2 件 |
| `minecraft:oak_planks` | 1 件 |
| `minecraft:planks` tag、limit 1 | 12 件に一致、1 件返却、`truncated=true` |
| `sophisticatedbackpacks:backpack` | 1 件 |
| `minecraft:nether_star` | 0 件、`absence_proven=false` |

この範囲の返却レシピは `source=neoforge_recipes_received`、`supported=false`、`unsupported_reason=client_synced_lookup_only` を維持し、材料・設備も `not_assessed` だった。追加 source の参照を、自動 craft の許可や、現在作成できるという判定へ変換していない。

**7 request / 52 check が合格**。再起動後の新 world session で古い opaque reference を再使用していないことも確認した。最初の試験スクリプトは跨 session で解放済み source と追加 source の fingerprint が同一であると誤って仮定したため、訂正前の判定も保存した。同じ session 内での scope / 同期更新による参照失効と、追加 source の craft 拒否は、この実機検索から断定せず既存の単体・契約試験と区別する。

合格した 2 段階の合計は **14 request / 93 check**。設定反映前の不一致確認と、誤った fingerprint 仮定の判定は、この合格数へ含めていない。各段階を通して Action は不在、HP20 / READY を維持した。

## 保存と復旧

再起動前と最終終了時に通常 UI で保存して world を抜け、公開 state で world / Action 不在を確認した。container を停止し、**66 ファイル / world 61 ファイルすべてを元の SHA-256 と復旧照合、不一致 0**。scope 設定も元へ戻し、lease を解放した。Docker Desktop は試験前から稼働していたため保持した。通常 profile は変更していない。

完全なレシピ網羅、display のない特殊レシピ、現在の材料・設備の充足、Magnet の取得経路、同期欠落・更新の故障注入は未完のまま残す。#71 は `Refs` として扱い、この受入だけで close しない。
