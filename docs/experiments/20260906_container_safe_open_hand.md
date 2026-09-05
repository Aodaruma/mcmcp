# 非空ホットバーでの安全なcontainer開封（2026-09-06）

## 報告と原因

rc.2の整理再開時、3件のinspectが開封前に`inventory_safe_open_hand_required`で停止した。報告時点で、この新しい格納作業による移送は0件。空きhotbar以外を選ぶ経路はすでにあったが、`MinecraftKnownBrewingPort.safeNormalUseStack`が`Item.class`完全一致に加えてdefault stackとの全components一致を要求していたため、名前・損傷・enchantment付きの安全な道具も候補から外れていた。

一方、defaultとの一致だけでは安全性を確認できない。26.2では食料・装備も`Item`本体であり、使用開始はcomponentsから決まる。

## 修正と根拠

- 空きhotbarを優先する既存選択を維持し、空きがなければ通常の素材・剣・ピッケル等の`Item`本体を選ぶ。名前・損傷・enchantment差分だけで拒否しない。
- 実stackの`CONSUMABLE`、`EQUIPPABLE`、`BLOCKS_ATTACKS`、`KINETIC_WEAPON`を拒否する。明示patchに限らずprototypeから継承する成分も検査する。
- 斧・クワ・シャベル・設置block・bucket・着火具等の独自useを持つsubclassは許可しない。空きoffhandへのfallbackも追加しない。
- 候補がないcontainer失敗へ固定の原因・対処診断を追加する。1枠の通常素材やピッケルでよく、インベントリ全体を空にする必要はない。
- exact target crosshair、非sneak、安全状態、選択slot所有権、normal `useItemOn`、interaction結果、server menu/full-content ACK、cleanupを維持する。送信済みOPENING中に補充された持ち物でreadbackを打ち切らない既存修正も維持する。

ローカルのMC 26.2 / NeoForge 26.2.0.59のsourceとpatched bytecodeを照合した。`Item.useOn`と標準`onItemUseFirst`はPASS、`Item.use`の使用開始分岐は上記4componentsを参照する。26.2の剣・ピッケルは`Item`本体である。client/serverの通常block-useはhook、block interaction、MAIN_HANDのcontainer interaction、未消費時のitem useの順であり、正常なchest/barrel/醸造台の開封はcontainer側で消費される。

この判定は通常右クリックの副作用を対象とする。任意MODのglobal interaction hookや、custom enchantmentの受動的なtick/装備効果全般を無効化するものではない。名前空間による擬似的な安全保証や、通常interactionを迂回する専用packetは導入していない。

## 検証

Java 25で`test harnessTest adminBridgeTest verifyHarnessIsolation build -Pmod_version=0.1.0-rc.3-SNAPSHOT`が成功。

- unit 1,199件、harness 13件、admin 21件、計1,233件。失敗・error・skipは0件。
- 実ItemStackで名前・損傷・非空enchantment付きの剣/ピッケルを許可。
- 各危険componentの追加、およびpatchなしの食料/装備prototypeを拒否。
- 特殊useの7種、空stack、個数0を拒否。
- 候補不足の開始前後で固定診断を返し、無関係な失敗の任意情報を反射しない。
- 従来の送信直前確認・送信後readback・slot解放・server同期・schema/catalogとhash同期の回帰試験も成功。

JAR: `mcmcp-neoforge-26.2-0.1.0-rc.3-SNAPSHOT.jar`  
SHA-256: `183f2c2e38c0d755968bd49a82af519042ceab3069ea1af9984f11a851cbeb82`

公開rc.2を上書きせず、別の開発版としてゲーム担当へ渡す。実ゲームでの非空hotbar開封は差し替え・再起動後に確認する。現時点の自動テスト成功を、格納・整理の完了や実ゲーム確認済みとは扱わない。
