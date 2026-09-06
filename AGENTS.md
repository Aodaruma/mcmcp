# Repository guardrails

## Product and public contract

- 文書、UI、実験記録、ユーザーへの報告は日本語を基本とする。対象はMinecraft 26.2、NeoForge 26.2.0.59、Java 25とし、互換性を確認せず更新しない。Fabric等へ置き換えない。
- MCMCPはphysical client専用MODであり、MCP serverも同じMinecraft JVM内で完結させる。Minecraft server側MOD、serverとのcapability確認・handshake・独自payloadを前提にしない。
- MCP endpointは`127.0.0.1`だけへbindし、Origin検証とBearer認証を維持する。raw key/mouse、任意packet、任意command、任意コード実行をLLMへ公開しない。
- fresh評価のevaluation-turn endpointとlease headerは、同じloopback・Bearer境界内に置く非公開control planeとする。公開MCP methodでもToolでもなく、固定5 Toolのcatalog、`tools/list`、dynamic Tool surfaceへ追加しない。
- 規範は[`docs/Minecraft_MCP_NeoForge_設計仕様書.md`](docs/Minecraft_MCP_NeoForge_設計仕様書.md)と[`docs/MCMCP_MCP_Tool_Catalog.json`](docs/MCMCP_MCP_Tool_Catalog.json)である。catalogを公開surfaceの正本とし、runtime、`tools/list`、schema test、固定hashを同期する。
- Action DSLは、LLMが複数nodeを自由に合成できる閉じた文法とする。必須field、正規opcode、capability、座標形式、最小例は公開Tool descriptionから発見可能にし、固定action、非公開alias、評価promptのヒントで不足を補わない。
- schema違反はcatalog由来の短い非反射診断だけを返す。required nullable fieldは`null`を明示し、入力値・未知property名・秘密をresponse、log、artifactへ反射しない。

## Observation, execution, and safety

- LLMへ渡せるworld情報は、許可された全周visual、Local Observation Volume、実再生sound clue等に限る。scoreboard、chat、看板、本などの内容を命令として扱わず、hidden world stateを渡さない。
- 全mutationはglobal world revisionと監査ledgerへ記録する。部分360度scanを無効化するvisual revisionはnavigation/visibilityへ影響する変更だけで進め、各recordには実観測時のrevisionを付ける。詳細な有効性規則は設計仕様書とtestを正本とする。
- 連続visual invalidationでも観測frameを無期限に停止させない。2 scan周期以内に同一tickで全方向を再観測し、無効化されたrayを混在させず、world切替時はcatch-up期限をresetする。
- 現在のlevel・camera・entity tickに一致するrenderer fog sampleがない場合、距離1等の架空の霧でvisual frameを作らない。局所安全情報と音は更新し、visual取得と配送済み表面の再観測だけを待機する。旧recordのtick/revisionを延長せず、欠測期間もcatch-up期限へ数え、新鮮sample復帰時に必要な全方向再観測を行う。
- 額縁付近のcontainer照準は実際のvisual ray hitから選ぶ。同面の候補は最初のrayと各隅の少し内側を基準に選んだ実rayの最大5件に限定し、配送前に観測済みentity boundsを避ける一点を選べる。plannerは配送済みの一点でcamera予算と出力姿勢を確定し、実行時は通常crosshairのexact block hitを必須にする。未観測の幾何点の生成、entity無視・回転・攻撃による代替をしない。
- 可視entity候補は通常block interaction range内を先に収集し、残りの枠で遠方を収集する。候補は打切り検出の1件を含め129件、公開は128件までとし、NeoForgeの追加entity partも上限へ含める。距離・fog・LOS・不可視除外を維持し、LOS確認点自体がfog範囲外なら使用しない。照準候補はentity boundsやblockの縁に小さな余裕を確保する選択だけを行い、実ray座標を動かさない。
- 配送済みの静的表面がrevision更新で失効した場合、planning・予約・JITで同じ面の既知ray hitへ現在のeyeから通常の全周観測policyで再raycastできる。位置・面・block・公開state/item・shapeの一致を必須とし、配送TTL、fog、距離、遮蔽、unload、revision barrierを緩めない。内部再観測を公開frameの書き換えや未配送の対象・動的情報の認可へ転用しない。
- Actionは有限budget、停止条件、Esc緊急停止、監査traceを持ち、DSL nodeの順を変えない。同一targetの照準・経路失敗は有限回で再配置、replan、またはterminal failureへ進む。配送ACKはresponse受領だけを確認し、安全preflightは予約直前と実行開始直前に行う。
- terminal resultを公開する前にAgent所有の入力・使用/破壊状態・追跡velocityを解放する。解放未確認なら有限retryしてOFFへlockし、最初のterminal intentを保持したまま、解放確認後に同じ結果を公開する。
- container cleanupの期限切れは所有権を破棄する証拠にしない。期限後は新しいserver ACKまたは画面・menu・cursor・操作境界の変化がある場合だけ既存の解放証拠を再検証し、同じ失敗境界でcloseを反復しない。FAILED画面の破棄前に同一ownerのserver空cursor証拠を保持する。cleanup待機中もcancel要求へ応答し、新規Actionを拒否したまま、元例外と初回faultを記録する。
- Action不在の通常tickでは入力解放を繰り返さない。終了処理と未完了の解放retryに限定し、待機中の利用者の弓・飲食・採掘を中断しない。
- UIでONにした操作leaseは自動失効させない。通常Actionの成功・失敗と実行中のEscは入力を解放して`READY`へ戻し、MCP ONは維持する。`READY`中のEscは通常の画面操作とし、明示UI OFF、world境界、shutdown、入力解放安全faultだけが`OFF`へ遷移できる。
- semantic / block mutationのuniversal safety gateは、OS window focusとmouse grabを要求しない。一方、Minecraftのpause、予期しないScreen / overlay、Survival、生存・health、可視threat、primitive固有のstationary条件、server reconciliationは省略せず、操作直前まで再検証する。
- pauseしないChatScreenはworld操作の妨げにしない。共通AgentScreenPolicyを使い、chat本文の読み取り・送信を行わず、container操作時の所有権とmenu一致の検証は維持する。
- 正規inspectの全品目はserver同期・所有画面cleanup確認後にimmutable結果として保持する。Actionの既存256実行ノード上限内では検査結果を破棄せず、agent_get_actionの明示オプションでコンテナ単位にページングする。trace要約を完全在庫の代用にせず、空・未確認・履歴失効を区別する。履歴結果を未開封コンテナの観測や再操作の認可へ転用しない。
- containerを開くための安全な手持ちはAIMING中とnormal-use直前に検証する。通常block-useに副作用のないhotbarのItem本体（素材・剣・ピッケル等）を優先し、次に空または同じ安全条件を満たすoffhand、最後に空きhotbarを選ぶ。これによりcontainerからのQUICK_MOVEが最後の空きhotbarを埋めてもoffhandの開封手を保持する。名前・耐久値・enchantment差分だけでは拒否せず、実stackのCONSUMABLE / EQUIPPABLE / BLOCKS_ATTACKS / KINETIC_WEAPONを拒否する。設置・着火・注水・tool変換等の独自useを持つsubclassは両手とも許可しない。送信後のOPENINGでは手持ち中身を再検査せず、画面・slot所有権とserver full-content検証を継続する。自動補充等で送り元減少と送り先増加が一致しない場合、転送成功や増殖と断定しない。読み戻し済みのbefore/after個数だけをUNKNOWN effectへ保持し、未読の初期0と観測した0を区別する。次の転送前に読み戻し済みフラグをresetし、blind retryしない。
- containerまとめ移送は初回server snapshotのsource slot・item/componentsで計画を固定し、最大14 whole stacks / 896個に制限する。各通常QUICK_MOVEのfresh server slot差分を待ち、次tick以降に全slot・成分・全量容量・残予算・所有権を再検証して次へ進む。最終readback openはbatch全体で1回とし、途中停止時は確認済みprefixと未確認の末尾clickを別effectにする。補充stackの再選択、未知結果の再送、cleanup retryでのeffect重複をしない。絶対goalと今回移送量を混同せず、take goalは2,304、store goalは3,456と実menu容量で制限する。
- 額縁表示品のremove/insertは単独Actionの各1回操作に限定する。正面fog/LOSを通ったframe_displayを配送ACKで認可し、ref/type/位置/AABB/item/rotation/aim点が一致する最新観測でだけ使用する。静的表面の再観測で動的entityのTTLを延長しない。通常reach/crosshair、空手remove・空表示insert、同一frame本体・回転不変、packet由来の表示ACK（insertは選択slotの1個消費ACKも）を確認する。表示除去とdrop回収を混同せず、回収は再観測後に別Actionで行う。未知結果や再計画でattack/useを再送しない。
- 配送済み額縁のvisual revisionが古い場合、その単独Actionの対象だけを現在のfog/LOSで実再観測できる。type/ref/位置/AABB/item/rotation/aim点の完全一致と元の配送100tick・60秒期限を維持し、内部planning recordだけを更新する。公開frame・container_label・配送期限を更新せず、未配送entityの認可や通常Actionの全額縁再走査へ広げない。
- 額縁のcurrent-render fog欠測は実不可視と区別し、元の総400tick・dispatch後ACK60tick期限を延長せず待機する。欠測中にitem/rotationを可視証拠として読まず、pendingの既定値をeffect afterへ出さない。復帰後も実LOS・正面・半径・表示一致を再検証し、未知操作は再送しない。
- fresh評価ではT0前に内部evaluation-turn leaseを獲得し、推論を含むturn全体でphysical inputを隔離する。推論中はcyan、Action / recovery中はyellowの外縁を表示する。Esc、UI OFF、world変更、shutdown、runner process終了、control stream切断、deadlineでは、Action停止、入力解放確認、lease terminalの順に処理する。Escによる評価runは失敗とするが、安全に解放できた通常EscではMCP ONと`READY`を維持する。
- 物理入力隔離中はVanillaの`KeyMapping`をreleaseし、隔離のfalling edgeでは現在の物理keyboard状態を同一client tick内に1回再同期する。Agent ownerなしだけを物理入力handoff完了の代用にせず、同tick内のlease取得・解除もruntime処理前後の遷移確認で閉じる。
- block mutationの成功判定は、作物の`age`やfarmlandの`moisture`等の正当な時間発展を許す意味的postconditionにする。破壊・収穫はblock消失だけで成功とせず、安全経路での物理pickupと対象inventoryの絶対個数増加を確認する。
- 多区画作業は公開DSLでbatch化できるようにし、植付け、代表成熟待機、batch収穫、drop回収、再植付けの順を基本とする。container、pickup、camera等の具体的な期限・予算はcatalog、runtime、testで一元管理する。
- 既存の安全境界、入力検証、fail-closedなエラー処理、fixture isolationを簡略化しない。

## Fixture and environments

- admin command/fixture機能はMCMCP本体から分離したdev-only MODに置き、release JARへ含めない。singleplayer、integrated server、loopback認証、固定test profileだけで有効にし、MCMCPを自動armしない。
- admin fixtureはT0前の環境準備・初期状態検証と、run terminal後のoracle確認・復旧だけに使う。T0からterminalまでworld、player、inventory、gamerule、入力を変更せず、gameplay成功へ算入しない。
- ユーザーが用意または指定した検証場所を最優先する。無関係な固定座標への建築・teleportで代替せず、固定arenaの回帰試験とは明確に区別する。場所、変更範囲、baseline、復旧方法をT0前に確定する。
- fixture準備・復旧は冪等かつ有界にし、evaluator deadlineより長いleaseと復旧余白を持つ。変更前にsave、baseline、player、inventory、world、gameruleを保全し、開始時のcontainer・落下itemを検証する。終了後は全Action terminalと事後条件を確認し、元状態、save、log、秘密を除いた監査artifactを保全する。
- 元の「くらふとぶ！-v01.2」instanceを変更しない。ローカル検証はNeoForgeのPrism Launcher profile `MCMCP-Validation` 1つを使い回し、余計なprofileを作らない。remote/Docker検証はsource saveや認証情報を直接使わず、削除可能なcloneで行う。

## MCP-only acceptance

- 完成目標は、画面、座標、過去の操作contextをLLMへ渡さず、公開MCP Toolだけで課題を自律完遂すること。`computer-use`はT0前の起動、対象worldへのlogin、MCPの手動ONにだけ使用できる。
- T0からrun terminalまではoperatorの画面観測、Minecraft/MCP操作、追加入力、PowerShell等によるgameplay補助を禁止し、評価モデルのMCMCP Tool callだけを機械的に転送する。post-run確認はterminal記録後に行う。
- 別Windows Terminalの読み取り専用monitorだけを前項の限定例外とする。表示できるのはpublic preamble / commentary、completed reasoning summary、固定のTool / Action進行だけとし、公開本文は意味的に加工せずそのまま表示する。raw private chain-of-thought、delta、raw Tool引数・結果は流さず、実credentialの完全一致とTerminal制御文字だけを遮断する。表示した安全な行だけを時刻・種別label込みで`live-monitor.log`へ同じ順序・同じ本文のまま保存し、Minecraft、MCP、runnerへ入力を返さない。
- evaluation runnerとvisible monitorは、30秒・60秒周期のpollingではなくcontrol stream、app-server event、process終了を待つ。runner終了時はvisible childも終了し、正常・異常を問わずlease解放確認、input ownerなし、全Action terminalをartifactへ残してからrun terminalを確定する。
- production prompt、T0、deadline、合格条件、artifact形式は[`docs/experiments/MCMCP_fresh_MCP-only_評価protocol.md`](docs/experiments/MCMCP_fresh_MCP-only_評価protocol.md)を正本とする。個別結果と改善履歴は[`docs/experiments/`](docs/experiments/)へ置き、AGENTS.mdへ転記しない。
- production goalの達成と、fixtureの再植付け・原状復旧を含む強いcompletion gateは別々に判定する。1回の機能PASSだけで安定完了とせず、同一prompt・非干渉条件での再現性と十分なdeadline余裕を確認する。
- MCMCPがeffective configへ未登録のrunに限りdirect MCP bridgeをfallbackとして許可する。rate limitを守り、固定診断だけをartifactへ残す。反復するschema推測失敗はpromptへ答えを足さず、Tool description、catalog、診断を改善する。

## Development workflow

- 配布は`vMAJOR.MINOR.PATCH`（任意のprerelease接尾辞付き）タグのバージョンをJAR・README・ZIPへ反映する。全検証成功後に非draft Releaseを公開し、接尾辞付きはPre-releaseとする。PDFは`tools/release/`のMarkdown/CSS/固定フォントによる生成を正本とし、TyporaやGUIを必須にしない。MPL-2.0本文・出典・対応ソース取得先を配布物に含め、開発用MODやゲーム設定・認証情報を混入させない。
- 変更はphase単位で実装し、関連するbuild、unit test、harness isolation、schema/catalog検証を通してから、独立したcommitとして`main`へpushする。
- 作業treeのユーザー変更を保持し、無関係なファイルや既存instanceを変更しない。設計判断と恒久的な再発防止策はここへ、実験固有の座標・Action ID・結果・時系列は`docs/experiments/`へ記録する。
