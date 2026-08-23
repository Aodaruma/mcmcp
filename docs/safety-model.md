# 安全モデル

## 信頼境界

MCP clientとLLMは、local実行であっても信頼済みとはみなしません。すべての引数を検証し、公開tool、local capability profile、routine boundsの外へ到達できない構造にします。

通常プレイヤーに可能な操作を抽象化することと、client内部で技術的に読める・書ける状態を公開することは別です。server-authoritativeな値を直接変更せず、観測にも通常プレイヤー境界を設けます。

## 多層停止

優先順位の高い順に、次の停止経路を設けます。

1. ユーザー専用のlocal emergency stop key
2. 実keyboard/mouse inputによるhuman override
3. client tickのSafety controller
4. `emergency_stop` MCP tool
5. internal action lease満了

local stopはHTTP、LLM、Voice Chat、routine状態に依存せず動作します。`emergency_stop`は通常command queue待ちにせず、次client tickで最初に確認するstop flagとして扱います。

## input releaseとterminal判定

次は即座にcurrent actionのinput、item-use、screen ownershipを解放し、routineもterminalへ移します。

- ユーザーの実input、emergency stop、local lock
- window focus喪失。ただし後段のbackground execution gateに合格し、local UIで明示opt-inした場合を除く
- world切断、respawn、死亡、client終了
- 予期しないdimension変更、screen/menu、screen identity不一致
- Voice Chatが意図せずunmute、または状態確認不能
- routine hard deadline
- 例外、queue飽和、重大なtick遅延、adapter互換性error
- 対象の同定喪失

次はcurrent actionをreleaseしてfresh observationとfailure分類を行います。既存bounds内で同じpostconditionへ収束できる場合だけ有限retryし、それ以外は`FAILED`へ終端します。

- internal action leaseまたはserver sync timeout
- 想定外block、inventory desync、一定時間進捗なし

terminal時は保持している場合だけVoice Chat所有状態を復元します。局所retry可能なreleaseとroutine全体の停止を混同しません。

外部MCP heartbeatを必須にはしません。LLMやpollingの一時停止で宣言済みroutineが不意に切れることを避け、local deadlineとleaseで安全を担保します。

開始時にroutineの`max_duration_seconds + 5秒のFINALIZING reserve`がunlock windowへ収まることを要求します。この5秒は独立したfinalization timerではありません。停止は既存routine deadlineとactive arming fenceに従い、`WAITING`中もSafety controllerとdeadlineは有効です。

## 技術停止とsurvival response

低体力、敵対Mob、空腹、夜間等は技術的な異常と分けて評価します。Phase 6 v1は自動food/sleep maintenance、safe anchor退避、自動防衛、通常disconnectを実行せず、安全checkpointを満たせなければ停止・lockします。

将来の自動survival handlerを追加する場合の優先順位:

1. 進行中stepのpostconditionを安全に確定できるなら確定
2. inputを解放して危険評価
3. `retreat_only`なら登録safe anchorへ退避
4. 明示opt-inの`defend_and_retreat`なら、退路を塞ぐ現在可視hostileだけへ有限防衛
5. recovery不能ならpolicyによりstopまたは通常disconnect

元routineの固定execution envelope、破壊許可、Entity対象、期限をsurvival handlerが広げてはいけません。Phase 6 v1のexecution envelopeは宣言済みwork regionに限り、safe anchorやtransit corridorを暗黙に追加しません。

将来`retreat_only`を追加する場合もsafe anchorとcorridorを開始前に固定し、anchor不在時は停止へfallbackします。player、passive、tamed、named/protected Entityへの自動攻撃を禁止します。敵対Mobを追跡したりloot目的で戦ったりしません。

## Local arming

- unlockはlocal UI/keyだけで行い、MCP toolを設けない
- current world sessionとlocal capability profileへ束縛する
- inactivity/max duration、切断、emergency stop、互換性異常でauto-lock
- 能動routine開始時に、`max_duration_seconds + 5秒のFINALIZING reserve`が現在のunlock expiry以前であることを確認する
- 全13 routineの`completion_intent`は省略可能な`finish_goal | continue_goal`で、省略時は`finish_goal`
- `continue_goal`は同じworld session・1回のlocal armにつき最大16回、unlockから最大15分に限定する
- `finish_goal`成功は`goal_finished`でlockし、失敗、cancel、emergency stop、world session変更はchainを破棄してlockする
- LLMはlocal policyを緩和できず、より狭いboundsだけを指定できる

## 能力境界

### 許可

- 通常inputで可能な移動、視点、jump、attack、use、hotbar選択
- 通常screen/menu上で可能なcraftとslot transfer
- 同期済みの自分の状態、inventory、crosshair target
- 現在観測できるblock/Entityと、出所・鮮度付きのlast-known memory
- allowlist済みの有限・型付きroutine
- 明示的な`sleep_at_bed`と、宣言済みbounds内の`navigate_to`
- 非公開local Creativeで、別capabilityとしてarmした上限付きのworld-read-only非同期region capture

### 禁止

- 任意packet、任意chat、任意command、任意Java呼び出し
- 座標、速度、Entity motion/AI、NBT、inventoryの直接変更
- 通常profileでのwall-through現在情報、hidden Boolean oracle、未ロード領域、seed/structure/POI取得
- reach、LOS、cooldown、採掘速度、移動速度、collisionの回避
- 自動login、認証情報取得、自動reconnect
- playerへの攻撃、窃盗、取引、蘇生等の対人影響を自動判断する操作
- 任意Mob捕獲、汎用Entity transport、無期限combat/guard

### Creative profile

Creativeの観測拡張はSurvival capabilityと分離します。permission gateは、このclientが所有する非公開integrated single-player、対応するserver playerのCreative GameType、cheats/GM permission、現在world sessionでのlocal armだけです。32 block、client preload、512 cellは権限条件ではありません。

資源上限として各辺256、4,194,304 block、64 chunk column、1 active job、1 chunk in flight、artifact展開後64 MiBを適用します。生成済みchunkはintegrated serverで一時loadして必ず解放し、未生成chunkは生成しません。WorldMemoryへ保存せず、world変更も行いませんが、gzip artifactを書き込むためMCP annotationは`readOnlyHint=false / destructiveHint=false`です。直接`setBlock`、Creative item生成、command、任意packet/NBT、Entity summon/kill/teleportは公開しません。Creativeでの能動操作は専用gateに合格するまで追加しません。

## 破壊元とblock planの境界

Phase 2〜4の`stationary_break`、`break_block`、`apply_block_plan`は、破壊元を次の5 IDへ閉じます。

- `minecraft:cobblestone`
- `minecraft:stone`
- `minecraft:dirt`
- `minecraft:obsidian`
- `minecraft:grass_block`

文字列がallowlistに入るだけでは不十分です。canonicalな`minecraft` registry entryであり、`EntityBlock`でもBlockEntity付きstateでもなく、liveなBlockEntityが存在せず、FluidStateが空であることを確認します。開始時のschema/admissionに加えて実際のattack/packet直前にもlive stateで再確認し、TNT、infested block、container、ice、未知・MOD block、流体を含むstateはfail closedにします。Phase 4の`break_to_air / replace`は`bounds.allow_break=true`を必須とし、破壊operationがないplanでは逆に`false`を要求します。

Phase 4 `apply_block_plan`は実装・受入完了しており、移動なしの1 phase・最大64 target、完全なbefore/after BlockState、current-onlyの再確認へ限定します。成功が示すのは、要求したtargetに対するprediction ACKとserver state、および最終same-tick current集合です。通常vanilla操作が引き起こす隣接block更新やgame eventは停止しないため、target外を含むworld全体の無変化保証ではありません。壊れ得るsupportや反応し得る隣接cellも守る必要がある場合、planへ明示してcurrent exact-state検証の対象にします。

通常の`useItemOn`はitem設置よりsupport block自身の操作を先に試すため、Phase 3/4の設置supportは`minecraft:cobblestone / dirt / grass_block / obsidian / smooth_stone / stone`の6 IDに限定します。canonical registry entry、BlockEntityなし、FluidState空をcandidate選定時とpacket直前に再確認し、container、lever、trapdoor等は通常useを呼ぶ前にfail closedにします。

## 観測境界

`loaded`と`observable`を分けます。

- camera/FOVと視覚的遮蔽を満たすlive observation
- 自分の通常interaction後にserver同期まで確認した結果
- 上記を時刻・出所付きで保存したlast-known memory

この3経路以外でclient内部のblock/Entity状態を公開しません。hidden packetやchunk dataでmemoryを更新せず、stale memoryだけを根拠に能動操作を開始しません。詳細は[観測・記憶モデル](observation-model.md)に従います。

## Screen safety

GUIは一律禁止せず、ownershipで制御します。

許可:

- routine自身が通常interactionで開いたallowlist済みscreen/menu
- screen identity、menu/sync ID、slot revisionが期待値と一致
- container clickにはpositive ACKがないため、click後にautomation-owned screenを閉じ、同じ宣言済みcontainerを再度開いてcontainer/player全slotをfull readbackする
- full readback後にsource/destinationのserver同期された絶対目標countを確認
- userが事前に開いたscreenはadoptしない

停止:

- chat、command、未知MOD画面、予期しないcontainer
- userが手動で開閉・操作
- slot revision不一致、二重click疑い、response timeout

`transfer_items`と`craft_items`は追加量をblind retryせず、毎回source/destinationの目標countとの差分を再計算します。click送信、slot revision増加、screen closeだけを成功根拠にしません。

## Entityとuser handoff

Phase 3 v1の`interact_entity`は、current world session/dimensionで現在可視な短寿命opaque refが指すadult cowを、crosshair・LOS・通常reach・declared bounds内でmain-hand bucketにより1回だけ搾乳する操作に限定します。dispatch後のfreshなselected-slot inventory syncと`minecraft:milk_bucket`の絶対目標countを確認し、retryしません。取引、餌やり、毛刈り、騎乗等の汎用interactionは後続phase、移動、捕獲、押し込み、攻撃、釣り竿は搬送用の個別experimental gateまで公開しません。

Entity搬送は、初版ではユーザーが次を準備した後だけ自動化候補にします。

- 対象がboat/minecart/sealed cellへ収容済み
- vehicle modeでは対象passengerがopaque refと一致
- routeとdestinationが観測・封鎖済み
- sourceとdestinationを同時に開けない手順がある
- targetをopaque refで再確認できる

不足時は`NEEDS_USER_HANDOFF`で安全に終了します。敵対Mobが脱走した場合は追跡せず、可能ならgateを閉じて退避し、`CONTAINMENT_BREACH_REQUIRES_USER`を返します。

餌・POI・aggro誘導、自動押し込みは成功率と副作用のversion別試験が必要なため、後期experimentalかつ既定OFFです。釣り竿pullを試験する場合もlocal test harness内部だけとし、MCP catalogへ公開しません。

## 睡眠

- bedは登録済み、またはtask中に観測・設置確認したものだけ
- bed-safe dimensionだけでuseする
- sleep状態と正常なwakeをserver同期後に確認する
- bed占有、時間外、近くのmonster、破壊、timeoutを構造化failureにする
- maintenance前にcheckpointし、帰還後にworld差分を再検証する

未知bedの検索、危険dimensionでの試行、right-click送信だけを成功扱いすることを禁止します。

## 湧き潰し

敵対Mobの壁越し探索ではなく、観測済み表面のblock state、light、shapeを使ってpotential spawn surfaceを評価します。

- 結果は`checked / possibly_spawnable / unknown / coverage`
- client-side評価は`predicted`でありserver保証ではない
- 未観測洞窟・壁裏を含む場合は完全湧き潰し済みと断定しない
- 指定した小さなregionを通常移動で調査する`survey_area`を使う

## 完了後policy（Phase 6、実装済み）

Phase 6 v1のlocal completion policyは固定`stay`です。全13 routineの`completion_intent`は省略可能な`finish_goal | continue_goal`で、省略時は`finish_goal`です。

- `continue_goal`でもroutine-local cleanup、全input/item-use/screen解放、Voice Chat復元、安全なstay checkpointを必須とし、その後はlocal armingを維持する
- `finish_goal`成功は同じcheckpointを確認して`goal_finished`でlockする
- checkpointはworld/player存在、alive、on-ground、非passenger、health 6以上、水平速度の二乗0.01以下、item-useなし、画面なし、screen ownership idle、現在可視hostileなしをすべて要求する
- `stay`確認後の無期限guardは行わない
- 失敗、cancel、emergency stop、world session変更はcontinuation chainを破棄してlockする
- safe anchorへの自動帰還、`ask`、自動disconnect、自動food/sleep maintenanceは実装しない。必要ならLLM/MCP clientが明示的な`navigate_to`や`sleep_at_bed`を中間routineとして実行する
- auto reconnect/loginは行わない
- cleanup対象のownershipを証明できない場合はworldへ触れず、goal完成とfinalization failureを分ける

Temporary shelterは初版に含めません。後段で実装する場合も、明示build region、allowlist素材、固定template、時間上限、撤去policyを必須にします。

## Network

- bind先はloopback addressとして`127.0.0.1`へ固定する
- `0.0.0.0`、LAN IP、IPv6全公開へのfallbackを禁止する
- `Origin`をallowlistと照合し、不正なら403
- 256bit以上のrandom Bearer tokenを生成し、log/MCP responseへ出さない
- request size、JSON depth、string length、同時数、毎秒rateを制限する
- `/mcp`以外の管理endpointを設けない
- MCP protocol versionを固定して不正headerを拒否する

## 監査log

残すもの:

- 時刻、routine ID、tool/kind、状態遷移、phase、event seq
- precondition/postcondition、retry、failure category/code、停止理由
- Minecraft/NeoForge/MOD/MCP/adapter version
- 操作時間、進捗、observation revision、memory provenanceの要約
- finalizationとcontinuation policyの実行結果

残さないもの:

- Bearer token、Microsoft認証情報、server address
- voice、chat本文、Discord内容
- player名/UUID、音声device名、不要な個人情報

## 運用上の注意

- unattended運転は検証gate合格前に有効化しない
- multiplayerでは利用者が接続先ごとのrule、負荷、公平性に合わせてlocal policyを設定する
- server切断や制限兆候で安全停止する
- 特定serverだけを許可する仕組みは実装せず、single playerでも同じ機能を使えるようにする
