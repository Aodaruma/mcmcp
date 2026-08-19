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

routineのwork soft deadlineでは新しい作業stepを止め、予約済み時間でFINALIZINGへ移ります。maintenance/FINALIZING reserveまで使い切るhard deadlineでは即時releaseします。`WAITING`中もSafety controller、survival policy、両deadlineは有効です。

## 技術停止とsurvival response

低体力、敵対Mob、空腹、夜間等は、即座に全制御を放棄するとその場で死亡する可能性があります。技術的な異常とは分け、local survival policyの範囲で固定handlerが対処します。

優先順位:

1. 進行中stepのpostconditionを安全に確定できるなら確定
2. inputを解放して危険評価
3. `retreat_only`なら登録safe anchorへ退避
4. 明示opt-inの`defend_and_retreat`なら、退路を塞ぐ現在可視hostileだけへ有限防衛
5. recovery不能ならpolicyによりstopまたは通常disconnect

元routineの固定execution envelope、破壊許可、Entity対象、期限をsurvival handlerが広げてはいけません。execution envelopeは開始時にwork regionと、同じdimensionの事前承認済みbed/safe anchor/transit corridorから確定します。

初版の既定は`stop_and_notify`です。`retreat_only`はsafe anchorとcorridorが開始前に固定できる場合だけ選べ、anchor不在時は`stop_and_notify`へ固定fallbackします。player、passive、tamed、named/protected Entityへの自動攻撃を禁止します。敵対Mobを追跡したりloot目的で戦ったりしません。

## Local arming

- unlockはlocal UI/keyだけで行い、MCP toolを設けない
- current world sessionとlocal capability profileへ束縛する
- inactivity/max duration、切断、emergency stop、互換性異常でauto-lock
- 能動routine開始時に、routine hard deadlineが現在のunlock expiry以前であることを確認する
- `ask` timeout、fallback、FINALIZING reserveもunlock残時間内に収める
- `continue_goal`はlocal UIが許可したsessionだけで受理し、回数・総時間・expiryで延期を制限する
- LLMはlocal policyを緩和できず、より狭いboundsだけを指定できる

## 能力境界

### 許可

- 通常inputで可能な移動、視点、jump、attack、use、hotbar選択
- 通常screen/menu上で可能なcraftとslot transfer
- 同期済みの自分の状態、inventory、crosshair target
- 現在観測できるblock/Entityと、出所・鮮度付きのlast-known memory
- allowlist済みの有限・型付きroutine
- user policyに基づく食事、睡眠、安全退避、完了後の通常切断

### 禁止

- 任意packet、任意chat、任意command、任意Java呼び出し
- 座標、速度、Entity motion/AI、NBT、inventoryの直接変更
- wall-throughの現在情報、hidden Boolean oracle、未ロード領域、seed/structure/POI取得
- reach、LOS、cooldown、採掘速度、移動速度、collisionの回避
- 自動login、認証情報取得、自動reconnect
- playerへの攻撃、窃盗、取引、蘇生等の対人影響を自動判断する操作
- 任意Mob捕獲、汎用Entity transport、無期限combat/guard

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
- 各click後にserver同期されたslot countを確認
- userが事前に開いたscreenはadoptしない

停止:

- chat、command、未知MOD画面、予期しないcontainer
- userが手動で開閉・操作
- slot revision不一致、二重click疑い、response timeout

`transfer_items`と`craft_items`は追加量をblind retryせず、毎回source/destinationの目標countとの差分を再計算します。

## Entityとuser handoff

`interact_entity`は可視、LOS、通常reach内への有限右clickだけです。移動、捕獲、押し込み、攻撃、釣り竿を含めません。

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

## 完了後policy

ユーザーがlocal UIで選びます。

```text
after_completion = ask | stay | return_to_safe_anchor | disconnect
on_unrecoverable_danger = stop_and_notify | retreat | disconnect
ask_timeout_seconds = bounded local value
ask_fallback = stay | return_to_safe_anchor | disconnect
```

- LLMはpolicyを緩和・変更できない
- `completion_intent=continue_goal`はroutine-local cleanupとstable checkpointで止め、`finish_goal`だけがこの`after_completion`を実行する。省略時は`finish_goal`
- `ask`は全input解放済みの期限付き`WAITING`でlocal UIに問い、timeout時は固定fallbackを実行する
- `stay`はその場の瞬間的safe-in-place検証後にreleaseし、以後の無期限guardを意味しない
- `stop_and_notify`はlocal UIとevent/audit logへ通知し、MCP pushを必須にしない
- disconnectは全input解放、screen cleanup、監査event、Voice Chat復元後に通常経路で行う
- auto reconnect/loginは行わない
- safe anchor要件があるのに未登録なら長時間routine開始を拒否
- safe anchorへ戻れない場合、goal完成とfinalization失敗を分けて返す
- cleanupはroutineが作成・変更したserver-confirmed ownership recordのあるtemporary effectだけに限定する

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
- finalizationとdisconnect policyの実行結果

残さないもの:

- Bearer token、Microsoft認証情報、server address
- voice、chat本文、Discord内容
- player名/UUID、音声device名、不要な個人情報

## 運用上の注意

- unattended運転は検証gate合格前に有効化しない
- multiplayerでは利用者が接続先ごとのrule、負荷、公平性に合わせてlocal policyを設定する
- server切断や制限兆候で安全停止する
- 特定serverだけを許可する仕組みは実装せず、single playerでも同じ機能を使えるようにする
