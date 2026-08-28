# Repository guardrails

- 文書とユーザー向け表示は日本語を基本とする。
- 対象をMinecraft 26.2、NeoForge 26.2.0.59、Java 25へ固定し、互換性確認なしに更新しない。
- `docs/Minecraft_MCP_NeoForge_設計仕様書.md`と`docs/MCMCP_MCP_Tool_Catalog.json`を規範とする。Tool catalogを公開surfaceの正本とし、runtime、`tools/list`、schema test、固定hashを常に同期する。
- Action DSLは任意の複数nodeを組める閉じた文法のまま保ち、`agent_start_action`の公開descriptionだけで必須node `id`、正規opcode・field・capability・座標形式とschema-validな最小例を発見できるようにする。固定action化、非公開alias、評価promptへの文法追記で補わない。
- Tool catalogでrequiredかつnullableなfieldは、値がない場合もHTTP `structuredContent`へ明示的な`null`として残し、transport serializationで省略しない。
- Tool入力schema違反はcatalog validatorが見つけた先頭のpathと固定reasonだけを短く返し、小さなprimitive enumに限り件数・値長・総長を制限したcatalog由来の許可値を示してよい。入力値や未知property名を応答へ反射せず、診断用にAction DSL文法を手書きで二重管理しない。
- MCP serverとgameplay runtimeはMOD内で完結するphysical client専用とし、Minecraft server側MOD、capability handshake、独自payload、server側の許可・capability確認を要求しない。
- MCPはMinecraft JVM内で127.0.0.1だけにbindし、Origin検証とBearer認証を無効化しない。
- LLMへraw key、raw mouse、任意packet、任意command、任意コード実行を公開しない。
- 全周visual、Local Observation Volume、sound clueの許可範囲外にあるhidden world stateをruntimeへ渡さない。
- 全mutationはglobal world revisionと監査ledgerへ残す一方、部分的な360度visual scanの蓄積を破棄する世代はcollision・遮蔽・support・fluid・hazard等のnavigation/visibilityへ影響する変化だけで進める。wheat age等の非衝突tick更新でscanを恒常的に飢餓化させず、surface/boundary recordには各rayの実観測時global revision、frame完成時に採るentity recordには完成時global revisionを付ける。
- Actionは期限・静的budget・停止条件・Esc緊急停止・監査traceを持つ。
- Actionのterminal snapshotをHTTP waiterへ公開する前に、Agent所有のkey・mouse・使用/破壊状態・追跡velocityをすべて解放する。失敗・cancel・world境界を含め、terminal通知後に入力解放する競合窓を作らない。解放成否を無視せず、bounded retry後も未確認ならOFFへlockしてterminal/READYを公開せず、最初のterminal intentを不変に保持して後続ClientTickの先頭で再解放・同一結果の公開を行う。後発のlifecycle/Esc停止理由で上書きしない。
- ユーザーがUIで有効化したMCP操作leaseには自動失効を設けない。通常Actionの成功・失敗・配送未確認・物理Escによる緊急停止では入力を解放して`READY`へ戻し、明示的なUI OFF、world境界、client shutdownだけが`OFF`へ遷移できる。ただしbounded retryを全回失敗した入力解放安全faultは例外であり、後続tickで解放と元terminalの公開に成功しても自動再armせず、手動ONまで`OFF`を保つ。`READY`中のEscはメニューを閉じる通常操作として扱う。
- 公開Action結果schemaはruntimeが生成し得る全terminal failure codeを列挙し、配送・取消・安全回避を含む正規terminal snapshotを`INTERNAL_ERROR`へ潰さない。
- 農地の`moisture`や作物の`age`などtickで正当に進行するblock propertyは、完全state一致ではなくblock種別と許容される単調変化を使う意味的postconditionで照合する。
- 通常Actionの累積camera予算は最大720度、recoveryは別枠最大360度、公開progress/監査上限は合計1,080度とする。角速度、interaction、破壊・設置上限は緩めず、複数nodeの宣言順は意味論なのでruntimeが並べ替えない。
- ActionのHTTP配送確認はresponse受領のackだけを扱い、変動するpose・観測・経路を再admissionしない。安全preflightは予約直前と実行開始直前に維持し、配送失敗とworld変化を混同しない。
- test fixtureはdev-onlyの分離JARとし、command実行能力をsingleplayer、integrated server、loopback認証、固定test profileだけへ限定する。release JARへ含めず、MCMCPを自動armしない。
- fixtureの再準備は冪等にし、前回runのcontainer内容物やworkspace内の落下itemを開始inventoryへ混入させない。block置換は作物等のitem entityを同期生成し得るため、combined wheatはlayout・container設定後にもworkspaceを最終purgeする。評価T0前に空inventory・所定のchest内容・落下itemなしを確認する。
- ユーザーがworld内の検証場所を用意または指定している場合、無関係な固定座標へfixtureを新設・teleportして代替しない。固定arenaを使う回帰試験とユーザー環境での受入試験を明示的に分け、場所・初期化範囲・復旧方法をT0前に確定する。
- fresh評価fixtureのwall-clock leaseは evaluator timeoutにarmからT0までのpreflight・pause・事後確認余白を加えた時間より長くし、timeout変更時はlease定数・境界test・READMEを同期する。有界・非更新と終了・置換時のrestoreは維持する。
- block破壊を伴う受入課題は、破壊数ではなく落下物の物理pickupとinventory増加を成功条件に含める。観測済み落下物だけを既知の安全経路で回収し、未知領域や危険領域へ追跡しない。
- item回収は1 visual scan周期以内のfresh witnessを移動中も再確認し、終点で実player pickup AABBとの交差を必須にする。成功は各node occurrence開始時からの対象item絶対個数増加だけで判定し、replanでbaselineを上書きせず、40 tick pickup delayを包含する有界確認時間を持つ。
- 同じtarget・同じJIT照準/経路failureを無制限に再試行しない。少数回で解析的repositionまたは明示的terminal failureへ移り、Action budgetを空費するspinを禁止する。
- 複数作物の栽培は各区画を逐次「待機→収穫」せず、まとめて植付け、代表作物の成熟待機、batch収穫、落下物回収、再植付けの順に構成できる公開DSL/templateを優先する。
- container primitiveは内部400 active tick上限に対しAction全体で最低25,000 msを確保し、dispatch/JIT用5,000 msのwall-clock余白を含める。catalog、template、validator/testの値を同期する。
- 元の「くらふとぶ！-v01.2」instanceを変更せず、NeoForgeを使うPrism Launcher profile `MCMCP-Validation`の1つだけを永続的に使い回す。
- 実装phaseごとにbuild/testを通し、独立したcommitとしてmainへpushする。
- 既存の安全境界、入力検証、エラー処理、fixture isolationを簡略化しない。

## Production評価

- 完成目標は、画面・座標・過去の操作contextをLLMへ渡さず、公開MCP Toolだけで課題を完遂するMCP-only運用とする。`computer-use`はT0前のMinecraft起動、対象worldへのlogin、MCPの手動ONだけに使用できる。
- fresh評価のproduction promptは「チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか」とbyte-for-byte同一にし、prefix、suffix、追加contextを付けない。
- T0後からrun終了まではoperatorによる画面観測、Minecraft/MCP操作、追加入力を禁止し、モデルのMCMCP Tool callだけを機械的に転送する。
- production promptそのものの達成と、fixtureの再植付け・原状整理を含む強いcompletion gateは別々に判定し、両方の事後条件を実験ノートへ残す。promptにない後片付けを暗黙の不合格理由にせず、反対にcore task成功だけでfixture全完了を主張しない。
- fresh評価が1回成功しても、deadline余裕が60秒未満、recoverable failureからの反復回復、または多数の単品Actionに依存したrunは「機能PASS」であってproduction安定性の完了とは扱わない。同一prompt・同一非干渉条件の連続再現と時間余裕を別途確認する。
- fixture oracle、Minecraft画面、log、artifactの手動事後確認はrunnerがturn terminalを記録した後だけ行い、T0後の評価区間へ遡及する操作と混在させない。
- direct MCP bridgeは、effective configでMCMCPが未登録（`config.mcp_servers`が0件）と確認できた評価runだけのfallbackとし、production runtimeや通常接続方式にしない。
- 評価bridgeはMCMCPのrate limit（20 requests/s、burst 40）を越えないよう全requestを単調時計でpacingし、HTTP 429を一般transport障害と混同しない。失敗artifactには固定分類・診断code・HTTP statusだけを残し、response bodyや例外messageを保存しない。
- fresh評価で同じschema値・入力上限・再取得手順の推測失敗が反復した場合、評価promptへ答えを足さず、公開Tool descriptionとcatalog由来の非反射診断だけで正解を簡潔に発見できるよう改善する。
- fresh MCP-only評価は`docs/experiments/MCMCP_fresh_MCP-only_評価protocol.md`、小麦実験の経緯と判定は`docs/experiments/02_wheet/`を参照し、ディレクトリ名の`wheet`を互換性なく変更しない。
- 実worldを変更する前に対象save、baseline/復元識別子、player・inventory・world・fixture状態を確認して保存する。変更後は全Actionのterminal状態、安全なcontrol/fixture値、world・inventoryの事後条件を確認し、save、log、監査artifactを保全してから終了する。
