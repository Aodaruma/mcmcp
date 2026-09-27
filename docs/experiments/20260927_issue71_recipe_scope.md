# Issue #71 レシピ参照範囲

対象: Minecraft 26.2 / NeoForge 26.2.0.59 / Java 25。ゲーム、Prism profile、配布先JAR、worldは変更していない。

## 実装

- `recipe_scope=UNLOCKED`: **解放済みのレシピのみ**。既存client recipe bookを既定として維持する。
- `recipe_scope=ALL_CRAFTABLE`: **すべての作成可能なレシピ**。既存MODの通常同期で届いた公開NeoForgeイベントのdisplayも検索する。追加経路は全件参照専用で、実行用resolveを拒否する。
- 固定5 Toolを維持し、`agent_get_state.recipe_query` に取得範囲、追加同期への依存、欠測・不完全性、材料／設備の未評価を明示する。0件は不存在の証拠にならない。
- 設定切替・公開同期更新・world/session境界・既知内容／結果tag変更で参照を失効する。接続instanceを跨いで追加情報源を流用しない。

## 調査根拠

ローカルの公式 `neoforge-26.2.0.59-sources.jar` にある `RecipesReceivedEvent` は、server側の既存MODが `OnDatapackSyncEvent.sendRecipes` で要求した型だけを通知すると明記している。`ClientPayloadHandler` は受信payloadからRecipeMapを作り、この公開イベントへ渡す。MCMCPはイベントのみ購読し、同期要求・server companion・非公開cache・RecipeManagerへのアクセスを追加していない。

ローカル26.2 clientクラスのpublic APIも `javap` で確認した。`ClientRecipeBook.getCollections()` は既存経路、`RecipeAccess` のproperty set／stonecutter accessorは汎用の全recipe列挙APIではない。

公式ソースを読み取り専用で確認した外部情報（取得コードは実行していない）:

- [JEI 26.2 / 6e9f6eff の通常同期登録](https://github.com/mezz/JustEnoughItems/blob/6e9f6effa3acb69c21f9c5a342a133d40d7bfd38/NeoForge/src/main/java/mezz/jei/neoforge/JustEnoughItems.java): crafting、stonecutting、smelting、smoking、blasting、campfire cooking、smithingを要求する。独自recipe型の網羅性、clientだけの導入時の取得、JEI plugin固有の合成表示を保証しない。今回JEI固有APIへの依存は追加しない。
- [SophisticatedCore 26.2 / e992ebcd のUpgradeNextTierRecipe](https://github.com/P3pp3rF1y/SophisticatedCore/blob/e992ebcdb822e38d294d68bd117c8c92605f5452/src/main/java/net/p3pp3rf1y/sophisticatedcore/crafting/UpgradeNextTierRecipe.java): `isSpecial()` はtrue、`display()` はcomposeへ委譲する。`assemble()` は元upgradeのcomponentsを引き継ぐ。MCMCPはassembleを呼ばず、自動craftを認可しない。このソース調査は利用者の導入artifactとの同一性確認ではない。

## 制限と配布前確認

同期がない環境では追加情報源を利用不能と返す。同期型の部分集合、displayなし、未解釈の結果表示、上限、情報源例外による欠落が残る。追加表示の材料表は空で、材料不要を意味しない。検索は現在の材料・設備を評価せず、既存craftのpreflightを代替しない。

利用者のMagnet upgradeを実環境で検索できること、JEI固有表示の取得、材料・設備不足の検索時診断は未確認／未実装。Issue全体の完了は主張せず、これらを残件として扱う。`release:verification-needed` を維持し、merge・Releaseは行わない。

## 検証

Java 25で対象テスト（ClientRecipeCatalogTest、ClientSyncedRecipesTest、McmcpClientConfigTest、McpToolCatalogTest）を実行。既定・名称、欠測／部分取得／0件、未解放・特殊displayの参照専用性、情報源例外、上限、再受信・設定切替・接続隔離を確認した。

全Gradleの初回は、disconnect時のlease停止より前に追加情報源を破棄した順序について既存contract testが失敗した。破棄を停止後へ移し、テストの安全assertionを維持したまま再実行した `test harnessTest adminBridgeTest verifyHarnessIsolation build` は成功した。`tools/check-source.ps1` も成功（Java全体1,338件、harness 13件、admin bridge 27件、Python 14件、building／capability mock）。追加schemaテスト後の対象59件も成功。公開catalogのfile hash／Tool surface hashを評価runnerと監査scriptへ同期した。評価監査SelfTestの最終結果はIssueの対象commit記録へ追記する。
