# MCMCP fresh MCP-only 評価 protocol

## 目的

選択したprompt profileのproduction prompt **だけ**を新規のephemeral Codex threadへ渡し、MCMCPの公開5 toolsだけで遂行できるかを比較する。runnerは任意文字列を受け取らず、次の厳格な2 profileだけを許可する。

`full-cycle`は製品受入の主profileである。

> チェストに小麦の種と鍬が入っています。これを取り出し、この畑の区画にある耕作可能な土をすべて耕して、すべてに小麦の種を植えてください。成熟後はすべて収穫して植え直す工程を、小麦を1スタック（64個）以上所持するまで繰り返してください。

`short-regression`は、将来この程度に短い依頼が実際に来る可能性を残す文脈推定の回帰profileである。

> チェストに小麦の種と鍬が入っています。これを取り出して、畑から小麦を1スタック作ってもらえませんか

`full-cycle`のproduction goalは、T0前にユーザーまたは評価計画が指定した畑区画について、耕作可能な全土blockの耕耘、全区画への播種、成熟後の全区画収穫、収穫区画への再播種を反復し、player inventoryの小麦絶対個数が64以上になることである。64個へ到達した最後の収穫cycleでも、対象区画の再播種を終えてからproduction goalを完了とする。固定arenaや無関係な座標へのteleport/buildはこの意味を置き換えない。`short-regression`の成功だけを、この明示的completionの代替合格にしてはならない。

対象は `gpt-5.6-sol/high` を先行し、必要に応じて同じ golden baseline を復元して `gpt-5.6-luna/xhigh`、`gpt-5.6-luna/high` を別runで評価する。session、観測、action historyをモデル間で共有しない。

## 評価境界

モデルから見える tool は、MCMCP `tools/list` から得た次の5件だけである。

1. `agent_get_state`
2. `agent_get_observation`
3. `agent_start_action`
4. `agent_get_action`
5. `agent_cancel_action`

container/passage操作は公開Action DSLの`open_known_passage`、`inspect_known_container`、`take_known_container_stack`を`agent_start_action`から使う。test harness、任意command、raw inputは使わない。

runnerは`codex-cli 0.146.1`をscript内定数で固定し、callerによるversion overrideを認めず、`codex app-server --stdio --strict-config`を起動する。0.146.1のapp-server/dynamic toolsはUNSTABLE APIであるため、CLI更新時は生成schemaと監査を先に更新する。永続`~/.codex/config.toml`へMCMCPを登録せず、app-serverのexperimental `dynamicTools`に上記5 schemasを渡し、`item/tool/call`をrunnerがMCP 2026-07-28へ直接forwardする。これはMCP登録が利用できない場合についてユーザーが許可したfallbackであり、モデル自身によるHTTP直叩きではない。

computer-use、shell/PowerShell、browser/web、filesystem変更、sub-agent、skills、apps/plugins、画像生成、環境connectorなどをCodex childから使えないようにする。runnerのPowerShellはlauncher、MCP bridge、artifact collectorであり、モデルの代わりに課題を解かない。

評価中の入力隔離には、公開MCPとは別のBearer認証済みloopback evaluation-turn control planeを使う。この内部endpointと`Mcmcp-Evaluation-Lease` headerはモデルから見えず、MCP method、dynamic Tool、catalog、`tools/list`へ追加しない。公開surfaceは上記5 Toolだけである。

## fixture と T0

golden baselineはevaluator未接続時に作る。少なくともworld save、player state、時刻・天候、entity、作物、chest内容、扉状態、MCMCP JAR/configを復元単位に含め、識別子またはhashを記録する。

各runの前に次を行う。

1. Minecraftと前runのevaluatorが停止していることを確認する。
2. golden baselineを復元し、識別子が一致することを確認する。
3. Minecraftを起動してworldを開き、MCMCPを一度だけarmする。
4. player、inventory、chest、door、cropを変更せずrunnerを開始する。

runnerはclean cwdからfilesystem rootまでの全祖先とisolated `CODEX_HOME`にCodex configがないことを先に証明する。ephemeral login後、artifactへrequest/response本文を記録しない`config/read(includeLayers=true,cwd=clean cwd)`を送り、effective `config.mcp_servers`がobjectかつ0件であることを検証する。bridgeへ残すのはrequest ID、include-layers/cwd/object判定、件数0、raw未記録のBoolean/count proofだけである。その後、T0前に限りMCP 2026-07-28のread-only preflightを直接送る。

1. `server/discover`
2. `tools/list`
3. `tools/call` / `agent_get_state`

`agent_start_action`や`agent_cancel_action`はpreflightで呼ばない。各HTTP requestはliteral `127.0.0.1`だけへ`-NoProxy -MaximumRedirection 0`で送り、UTF-8 JSON Content-Type、JSON-RPC 2.0、request/response IDの型と値、result/errorの排他的存在を検査する。`server/discover`は`resultType=complete`、`supportedVersions=[2026-07-28]`、`capabilities.tools.listChanged=false`、`ttlMs=0`、`cacheScope=private`、serverInfo=`mcmcp/0.1.0`とsemantic exactで一致させる。`tools/list`は`docs/MCMCP_MCP_Tool_Catalog.json`のraw SHA-256 `712b137ef369e40678fdfc81c9ee161800a21f2902a2e4f844de51c8efc7ffb2`とsemantic tool surface SHA-256 `c949e2911271dabfcb4b2c9016fe4ae1fd8fafd7c02c027c6c68fd9194c76c30`をscript内定数へpinし、full resultと固定5件の名前、description、inputSchemaをexact比較してからdynamicToolsへ変換する。

`agent_get_state`は`isError`の存在とBoolean型、`resultType=complete`、serverInfo、TextContent/structuredContent型を検証する。さらに`control.mode=ready`、unpaused、world/observationあり、inventory空、`omnidirectional_rays_per_tick=512`、`observation.record_counts.visible_entity=0`、actionがnullまたはterminalでなければT0へ進まない。このfixtureはmobを生成しないため、visible entityが1件でもあれば作業領域の落下item等による開始条件汚染として扱う。state body、座標、fixture知識はartifactへ保存せず、各判定のBooleanだけを残す。thread作成成功後にも同じreadinessを再取得し、8判定が全てtrueであることを記録する。

preflightとapp-server thread作成後、runnerはpreliminary readinessを確認してevaluation-turn leaseを獲得する。その後、active lease header付き`agent_get_state`でauthoritative T0 readinessを再確認してからT0を記録し、exact promptを含む`turn/start`をstdin JSONLへ1回だけ送る。これによりpreliminary checkからlease取得までのfocus・物理入力変化をT0へ持ち越さない。T0後はモデルのdynamic tool requestに対する機械的な1対1 forward以外、operator/runnerからMinecraftやMCPを操作しない。唯一の例外として、残時間が安全なHTTP完了とturn終端に足りないrequestはMCPへ送信せず、後述の固定deadline拒否をapp-serverへ返す。この拒否はMinecraftを操作せず、引数も変更しない。拒否後もモデル自身が発行した厳密な`agent_cancel_action` 1件だけは、残時間を満たす場合にcleanupとしてforwardできるが、runnerが自動cancelを生成することはない。30分のTurnDeadlineは延長せず、15秒のterminalization reserveをその内側に確保する。30分でturnが完了しなければrunを終了し、一般的なapp-server応答障害ではなく`evaluation deadline expired before turn/completed`として分類する。

## evaluation-turn lease と読み取り専用monitor

runnerはT0前に`/mcp/internal/evaluation-turn`へBearer認証付きPOSTを送り、run固有UUID、実runner process ID、評価上限を覆う有限deadlineを渡す。responseはlease activeのreceiptと、接続断を検出できるevent-driven control streamでなければならない。lease値そのものはメモリだけに保持し、T0以後にforwardする公開5 Toolの各HTTP requestへ`Mcmcp-Evaluation-Lease` headerとして1件だけ付ける。preflight request、app-server、prompt、dynamic schema、monitor、artifactには生のlease値を渡さない。

lease active中は、Action間のモデル推論を含むturn全体でMinecraftのphysical keyboard / mouse入力を隔離する。推論区間はcyan、Action / recovery中はyellowの外縁を表示する。EscとMCMCP状態buttonだけを入力隔離の例外とする。

正常な`turn/completed`後、runnerは内部DELETEでlease releaseを要求する。異常時はEsc、UI OFF、world変更、shutdown、runner process終了、control stream切断、lease deadlineのいずれでも、MCMCPが現在Actionをpriority stopし、入力解放とinput ownerなしを確認してからlease terminalを通知する。これは安全control planeのterminationであり、runnerがモデルの代わりに`agent_cancel_action`を生成したことやproduction goalを達成したことには数えない。Escを含む異常terminalはrun失敗である。Escでは安全に解放できればMCP操作ONの`READY`を維持し、UI OFF、world境界、shutdown、入力解放faultだけが従来どおり`OFF`へ遷移する。runnerはlease release receipt、全Action terminal、inputs released、input ownerなしを確認するまでrun terminalを成功として確定しない。

T0からrun terminalまでは、operatorによるMinecraft画面観測、Minecraft / MCP操作、追加入力、PowerShell等のgameplay補助を引き続き禁止する。別Windows Terminalの読み取り専用monitorだけを限定例外とし、次だけを表示できる。

- `phase=commentary`のcompleted agent message（public preamble / commentary）
- completed reasoning itemから得たreasoning summary本文
- setup、T0、turn、auditと、固定別名に変換したTool / Actionの開始・完了

public commentary / preambleとcompleted reasoning summaryは、座標等を含めCodexが公開した本文を意味的に加工せずそのまま表示する。raw private chain-of-thought、raw / summary delta、raw Tool引数・結果はmonitor eventに選ばない。実credentialとの完全一致は表示前に遮断し、ANSI等のTerminal制御文字だけを固定文へ置換する。monitorからrunner、MCP、Minecraftへ入力を返さず、Minecraft画面や別アプリを観測しない。画面へ表示したprefix除去後の安全な行だけを、時刻・種別labelを含む同じ本文・同じ順序で`live-monitor.log`へ逐次flushする。既存trace / bridge auditで`summary=detailed`、raw / summary deltaのopt-outと不在を証明し、monitor prefix / event allowlist / 制御文字guardと表示・log完全一致をself-testする。

launcher、monitor host、runnerは30秒・60秒周期のpollingを行わない。control stream、app-server stdoutの1行event、実runner child process終了を待ち、visible childは`-NoExit`を使わずrunner終了時に終了する。launcherは実process handleを1回だけ待ち、そのexit codeを伝播する。周期的に許されるのはMCMCP内部control streamのbounded heartbeatだけであり、operator pollingやgameplay観測へ使わない。

## app-server isolation と hardening

runnerはartifactとは別の、ユーザーhome外にある`CommonDocuments/mcmcp-eval-tmp`配下へ、credential/config fileを持たない空の`CODEX_HOME`と空のcwdを毎run作る。cwdからfilesystem rootまで`.codex/config.toml`がないこと、isolated pathと祖先にreparse point/junction/symlinkがないことを確認する。削除時もexact一時rootとreparse point不在を再確認し、child終了をbounded waitで確認できた場合だけ削除する。artifact directoryはrepositoryと重ならないrepo外の新規空directoryに限定する。

app-server childは次で固定する。

- `--stdio --strict-config`
- `cli_auth_credentials_store="ephemeral"`
- `tools.update_plan.enabled=false`
- `tools.experimental_request_user_input.enabled=false`
- `orchestrator.skills.enabled=false`
- `orchestrator.mcp.enabled=false`
- `web_search="disabled"`および`tools.web_search=false`
- `memories.use_memories=false`
- `agents.enabled=false`
- `history.persistence="none"`
- `project_doc_max_bytes=0`

CLIの`--disable`には次を全て指定する。

```text
shell_tool,shell_snapshot,unified_exec,computer_use,
browser_use,browser_use_external,in_app_browser,
apps,plugins,remote_plugin,skill_search,skill_mcp_dependency_install,
tool_suggest,multi_agent,image_generation,workspace_dependencies,goals,
code_mode,code_mode_host,request_permissions_tool,memories,hooks,
auth_elicitation,tool_call_mcp_elicitation
```

`thread/start`のparamsは`model,cwd,approvalPolicy,sandbox,personality,ephemeral,environments,runtimeWorkspaceRoots,dynamicTools,config`だけを許可し、値として`approvalPolicy: "never"`、`sandbox: "read-only"`、`personality: "none"`、`ephemeral: true`、clean cwd、`environments: []`、`runtimeWorkspaceRoots: []`、固定5 `dynamicTools`を明示する。`developerInstructions`、`baseInstructions`、`collaborationMode`など生成schema上の追加入力経路も拒否する。responseのeffective model/cwd/approval、`sandbox.type=readOnly`かつnetwork無効、reasoning effort、`instructionSources=[]`、`runtimeWorkspaceRoots=[]`も検証する。thread configでは上記設定に加え、次のfeaturesを全てfalseにする。

```text
multi_agent,tool_suggest,apps,plugins,image_generation,
standalone_web_search,code_mode,code_mode_only,request_permissions_tool,
deferred_executor,token_budget,current_time_reminder
```

`turn/start`のparamsは`threadId,input,model,effort,summary,cwd,environments`だけを許可し、model、effort、`summary: "detailed"`、同じclean cwd、`environments: []`を明示する。inputは`type: "text"`の1件だけで、選択したprofileのproduction promptとbyte-for-byte同じ文字列にする。prefix、suffix、`additionalContext`を含む追加入力、resumeは禁止する。model/effortは`sol/high`または`luna/high|xhigh`の有効pairだけを許可する。T0、audit、manifestにはprompt本文でなく`prompt_profile`とSHA-256を記録する。

## 認証境界

MCMCP Bearerはrunner親プロセスだけがtoken fileからメモリへ読み、loopback direct HTTP Authorization headerを構成する。app-server childはMCMCPへ直接接続しないため、Bearerを渡さない。

Codex認証はcanonical `~/.codex/auth.json`を親runnerだけが秘密としてparseし、`tokens.access_token`と`tokens.account_id`だけをメモリへ保持する。JWT `exp`がT0から30分の実行上限と5分の安全余裕を満たすことをstartup、login直前、T0直前に検証する。`initialize`/`initialized`後、artifactへ絶対に記録しない`account/login/start`（`type=chatgptAuthTokens`、`chatgptPlanType=null`）でephemeral loginし、安全な`id=login` responseとsecret-freeな成功eventだけを保存する。`account/chatgptAuthTokens/refresh`が来たrunは即無効とし、MCMCPへ転送しない。canonical auth fileは複製/hardlink/更新せず、終了時にhash不変とisolated credential file不在を確認する。

child environmentから全`CODEX_*`/`OPENAI_*`（最後にisolated `CODEX_HOME`だけ再設定）、`MCMCP*`、名前にTOKEN/BEARER/SECRET/API_KEY/ACCESS_KEY/PRIVATE_KEY/PASSWORD/CREDENTIALを含む変数、proxy/CA/TLS log設定、`RUST_LOG`、`OTEL_*`を削除する。残った全environment valueもMCMCP Bearer/access token literalでscanし、該当entryを開始前に削除する。

MCMCP Bearer、Codex access token、account IDをcommand line、app-server config、dynamic schema、prompt、bridge log、manifestへ書かない。artifact生成とauditの後、終了・timeout・例外時を含めて全artifactを3 literal値で再帰scanし、見つけた場合はredact、exit 3、manifestのsecret状態をinvalidにしてrunを不合格にする。終了時は3値の参照をnull化する。

## dynamic MCP bridge

app-server client request IDは`init`、`login`、`config`、`thread`、`turn`という文字列を使い、app-serverが発行するserver request ID（`0`を含む）と名前空間を分ける。artifactに残す`client_send`は`initialize`、`initialized`、`thread_start`、`turn_start`のexact 4件だけである。`initialize`はexact clientInfoと`experimentalApi=true`、notification opt-out配列だけ、`initialized`は空paramsだけを許可する。raw reasoning text deltaとreasoning summary deltaはopt-outし、completed reasoning itemのsummaryだけをmonitor候補にする。setup proofは`launcher → initialize → init response → initialized → login response → login成功 → effective config確認 → preflight → thread/start → thread response → preliminary readiness → evaluation-turn acquire → lease-bound authoritative T0 readiness → T0 → turn/start → turn response`の順に固定し、その後だけdynamic forwardを許可する。

turn中に受理するapp-server requestは`item/tool/call`だけである。requestに`id` propertyが存在するかを確認するため、値`0`も有効である。ID/callId一意性、namespaceが省略またはnull、threadId/turnIdが現在のturnとstrict一致、tool/argumentsをforward**前**に検証する。違反request、未知tool、別methodはMCMCPへ送らずfail closedとする。pacing後に残時間を再計算し、`agent_get_action`は厳密検証済み`wait_timeout_ms`を秒へ切り上げた値+2秒（上限35秒）、他toolは35秒をforward timeoutとする。残時間が`forward timeout + 15秒`以下ならMCP request IDを採番せず、`dynamic_deadline_rejected`を記録して固定の`success=false`結果を返し、terminalizingをlatchする。最初の理由は`insufficient_deadline_headroom`、以後のrequestは`terminalization_latched`として同様にMCPへ送らない。ただしlatch後、exact `{action_id}`かつ正規UUIDのモデル起点`agent_cancel_action`は1件だけcleanup候補にできる。pacing後の残時間が固定5秒timeout+15秒reserveの20秒を**超える**場合だけ、`forward_mode=deadline_cleanup_cancel`と残時間/headroom proofを付け、通常のMCP request ID採番・start/completion/response lifecycleでliteral forwardする。2件目、他tool、不正引数、残時間20秒以下は拒否を継続する。deadline拒否とcleanup startの`remaining_seconds`は、bridgeのT0 UTC+1800秒と各event UTCから再計算したfloor値に対して、event書込遅延分の0..1秒だけを許容して照合する。

MCP成功結果をmodelへ返す`inputText`はtoken節約のため次の優先順で作る。

1. `structuredContent`があれば、その値だけをcompact JSONにする。
2. なければtext contentを使う。
3. どちらもなければresult全体をcompact JSONにする。

正当なdomain resultの`isError: true`はモデルが回復または理由報告に使う有効なtool結果である。ただしoutput textはJSON object `{code,message,recoverable}`のexact 3 member（case-sensitive、重複なし、型はstring/string/bool）で、`structuredContent`が存在しない場合だけ許可する。内容を保持し、`success:false`、`payload_mode=tool_error`として返す。一方、transport error、JSON-RPC error、missing/malformed result、secret filter発火はrun/auditを無効にする。deadline拒否も同じ3 member shapeだが、固定code/message、`recoverable=false`、固定output hashを要求し、MCP domain errorとは別集計にする。bridge logにはcall対応、arguments/outputのSHA-256、成功可否、payload modeに加え、`mcp_is_error`、domain契約valid、structuredContent有無のBoolean proofだけを残し、MCP result本体を重複保存しない。

`agent_get_action`は`wait_timeout_ms: 25000`を指定してterminalまで反復することを推奨する。25秒でactionがterminalにならない場合、同shapeの非terminal snapshotが**成功応答**として返る。これはtimeout errorではないため、状態を確認して同じaction IDを再pollする。固定5 toolsは増やさない。

## 実行

`ArtifactDirectory`にはrepo外の新しい空directoryを指定する。ユーザーが別Windows Terminalで進行を確認するrunは、公開launcherを使う。

```powershell
pwsh -NoProfile -File .\tools\eval\Start-McmcpFreshEvalMonitor.ps1 `
  -Model gpt-5.6-sol `
  -ReasoningEffort high `
  -PromptProfile full-cycle `
  -BaselineId '<復元したbaselineの識別子>' `
  -ArtifactDirectory '<repo外の空directory>\sol-high' `
  -TokenPath '<MCMCP tokenの絶対path>'
```

launcherはvisible `pwsh`を起動し、内部monitor hostが実runnerのstdoutを`ReadLine`で待って公開表示をそのまま転送し、runner processを`WaitForExit`する。`-NoExit`は付けず、終了codeをcallerへ返す。非interactiveな自動検証ではrunnerを直接起動できる。

```powershell
pwsh -NoProfile -File .\tools\eval\Invoke-McmcpFreshEval.ps1 `
  -Model gpt-5.6-sol `
  -ReasoningEffort high `
  -PromptProfile full-cycle `
  -BaselineId '<復元したbaselineの識別子>' `
  -ArtifactDirectory '<repo外の空directory>\sol-high' `
  -TokenPath '<MCMCP tokenの絶対path>'
```

短い依頼の回帰runは同じbaselineを復元して`-PromptProfile short-regression`を明示する。Luna runはbaselineを毎回復元してからmodel/effort、profile、空artifact directoryだけを変える。

```powershell
# baseline復元後
pwsh -NoProfile -File .\tools\eval\Invoke-McmcpFreshEval.ps1 `
  -Model gpt-5.6-luna -ReasoningEffort xhigh `
  -PromptProfile full-cycle `
  -BaselineId '<baseline ID>' `
  -ArtifactDirectory '<repo外>\luna-xhigh' `
  -TokenPath '<token path>'

# 再びbaseline復元後
pwsh -NoProfile -File .\tools\eval\Invoke-McmcpFreshEval.ps1 `
  -Model gpt-5.6-luna -ReasoningEffort high `
  -PromptProfile full-cycle `
  -BaselineId '<baseline ID>' `
  -ArtifactDirectory '<repo外>\luna-high' `
  -TokenPath '<token path>'
```

## artifact と自動監査

各runは次を保存する。

- `app-server-stdout.jsonl`: app-server raw stdout JSONL（secret/private configを含み得る`config/read` exchangeは除外）
- `app-server-stderr.log`: app-server stderr
- `bridge.jsonl`: setup messageとsecret-freeなdynamic forward対応。非公開evaluation-turn controlのbody / header / streamは記録しない
- `preflight.json`: read-only preflight結果とschema hash
- `final-message.txt`: 最終completed agent message
- `audit.json`: fail-closed自動監査結果
- `audit-stderr.log`: audit process stderr
- `manifest.json`: model/effort/baseline、prompt hash、T0、timeout、Codex version、git状態、runner / launcher / monitor script hash、isolation/secret状態、lease ID hash、acquire / terminal時刻、固定terminal reason、inputs released / input owner none / all Action terminalのBoolean proof
- `live-monitor.log`: visible monitorを使ったrunだけに生成し、Terminalへ表示した安全な公開行をprefixなしで同じ順序・同じ本文のまま保存

monitor境界は、raw / summary deltaがopt-outされartifactにも存在しないこと、completed summaryとpublic commentaryだけを採用すること、runner / module / self-test / launcher / hostの固定hash、monitor prefix / event allowlist / 制御文字guard、Terminal表示と`live-monitor.log`の完全一致self-testで証明する。opt-out対象のprivate reasoning notificationが到達した場合はraw writerより前にrunをfail closedさせ、Bearer、access token、account ID、evaluation lease IDの完全一致もraw / bridge / final message / stderr / preflight / manifest / live monitorの各書込み前に拒否する。

自動監査は次を確認する。

- trace監査は、launcherからlease-bound authoritative T0 readiness、T0、turn responseまでの公開setup順序と各response契約を検証する。effective config proofはraw未記録、clean cwd、MCP server 0件である。preliminary readiness → evaluation-turn acquire → authoritative readiness → T0の順序はrunnerのfail-closed制御と専用self-testでも固定する。
- exact prompt、新規ephemeral thread、有効model/effort pair、`summary=detailed`、clean cwd、`environments=[]`、hardening config、固定5 schemasが一致し、setup message/paramsに未知keyや追加入力経路がない。
- raw response IDが`init`、`login`、`thread`、`turn`の各1件かつこの順で、errorがない。secret-bearing login requestとconfig exchange自体はartifactに存在しない。
- server requestは`item/tool/call`だけで、ID `0`を正しく扱う。
- item typeは`userMessage`、`reasoning`、`agentMessage`、`dynamicToolCall`だけで、`item/started|completed`のthreadId/turnIdがactive routeとstrict一致し、startedAtMs/completedAtMsがnumericで、全item lifecycleがstarted/completedになる。
- raw reasoning text deltaとreasoning summary deltaがinitializeでopt-outされ、traceに存在しない。monitor候補のreasoningはcompleted itemのsummaryだけ、agent messageは`phase=commentary`のpublic commentary / preambleだけである。self-testは公開本文が座標、ID、path / URL、JSONを含んでも保持されること、raw Tool引数・結果がevent選択されないこと、private reasoning notificationと実credential完全一致がartifact writer前に拒否されること、Terminal制御文字が表示前に遮断されることを検証する。
- 各dynamic requestは、通常forwardの`start=1/completion=1/response=1/reject=0`、またはdeadline拒否の`start=0/completion=0/reject=1/response=1`のどちらか一方だけに対応する。request ID、call ID、tool、route、argument hash、output hash、success、event順を双方向照合する。通常forwardでは`success=true/status=completed`とexact domain `tool_error`の`success=false/status=failed`をproofまで検証する。deadline拒否では固定error、`success=false/status=failed`、T0/event UTC由来の残時間とheadroom計算を検証し、MCP成功/domain error件数とは分離する。最初の拒否後のforwardは原則禁止し、拒否response後に始まる1件の`agent_cancel_action`だけを、exact引数、5秒timeout、20秒headroom、cleanup mode、UTC proofがすべて一致する場合に許可する。
- evaluation-turn acquireはpreliminary readiness後・authoritative T0 readiness前に1件、terminalはturn terminal後かつrunner終了前に1件とする。runnerは認証済みHTTP receiptとcontrol streamを照合し、acquire / terminal UTC、lease ID hash、runner process束縛、固定terminal reasonをmanifestへ残す。authoritative T0 readiness以後の通常forwardはactive lease header付きであり、生のlease IDをartifact secret scan対象に含める。
- `turn/completed`は`status="completed"`かつ`error=null`で、完了agent messageがある。その後のlease release receiptが`inputs_released=true`、`input_owner_none=true`、`all_actions_terminal=true`を証明するまで成功terminalにしない。
- runner、monitor host、visible childのprocess lifecycleはevent-drivenで、周期poll eventがなく、runner終了後のbounded wait内にvisible childが終了し同じexit codeを伝播する。
- 禁止tool/item/request/notification、未知event/client_send、壊れたJSONL、orphan、重複、turn terminal後のresponse/server request/notificationを含む全messageをfail closedにする。

dynamic requestが0件でも、trace構造とturn正常完了はprotocol上validになり得る。ただし自動監査は理由の意味を保証できないため、能力不足の具体的理由が最終agentMessageにあることを条件付きmanual reviewへ必ず出し、任意の短文を課題成功とは扱わない。監査は0件/成功/domain error/deadline拒否件数をreportし、課題達成可否とは分離する。deadline拒否が1件以上なら、lease terminal proofに加えてMCMCP action auditでも全Actionがterminalであることを確認するmanual reviewを必須にする。runnerはモデルがcancelしないActionを自動cancelしない。evaluation-turn終了時のpriority stopは安全解放であり、production成功へ算入しない。自動監査だけではgame内の達成を証明できないため、MCMCP action auditと突き合わせ、全actionがterminal、小麦64個、fixture外への危険な副作用なしを実験ノートで判定する。失敗runへ追加入力して直さず、artifactを保全してbaselineからやり直す。

## 変更時の検証

```powershell
$files = @(
  '.\tools\eval\Invoke-McmcpFreshEval.ps1',
  '.\tools\eval\Start-McmcpFreshEvalMonitor.ps1',
  '.\tools\eval\Invoke-McmcpFreshEvalMonitorHost.ps1',
  '.\tools\eval\McmcpLiveMonitor.psm1',
  '.\tools\eval\Test-McmcpLiveMonitor.ps1',
  '.\tools\eval\Test-McmcpEvalTrace.ps1'
)
foreach ($file in $files) {
  $tokens = $null
  $errors = $null
  [Management.Automation.Language.Parser]::ParseFile(
    (Resolve-Path $file), [ref]$tokens, [ref]$errors) | Out-Null
  if ($errors.Count -ne 0) { throw "parse failed: $file" }
}

pwsh -NoProfile -File .\tools\eval\Test-McmcpEvalTrace.ps1 -SelfTest
if ($LASTEXITCODE -ne 0) { throw 'audit self-test failed' }
pwsh -NoProfile -File .\tools\eval\Test-McmcpLiveMonitor.ps1
if ($LASTEXITCODE -ne 0) { throw 'monitor self-test failed' }
```

trace self-testはrequest ID `0`を含むsuccess、回復可能domain error、dynamic call 0件、JSON-RPC/protocol failure、route/namespace違反、必須property欠落、禁止item、malformed JSONLに加え、未知client_send、setup順序、terminal後response、domain member欠落/重複/proof不一致、追加instruction/context、無効model/effort、item route/timestamp、notification `emittedAtMs`の欠落・非整数・範囲外・順序違反、readiness proofの欠落・raw混入、failure diagnostic違反、property大小文字違反、正常deadline拒否、`agent_get_action(25000)`のheadroom境界と不正型、latch後の連続拒否、正常cleanup cancel、cleanupのtimeout/headroom/引数/order/回数違反、T0/reject UTC欠落・改変・早すぎる拒否、response欠落、重複拒否、reject/forward混在、hash/success/headroom/reason/identity不一致を検証する。monitor / runner self-testはevaluation-turnのacquire / terminal順、active header付与、release proof、raw / summary delta opt-out、公開本文の無加工転送、実credential / Terminal制御文字guard、Terminal表示と`live-monitor.log`の行単位完全一致、周期polling不在、visible childの終了連動を固定する。endpoint認証、lease状態遷移、Esc / UI / lifecycle / deadline時のAction停止と入力解放はJava契約テストで検証する。Codex CLIを更新する場合はversion pinを先に緩めず、generated experimental schema、external auth、dynamic lifecycle、hardening key、synthetic auditを再確認する。

参考: [Codex app server](https://developers.openai.com/codex/app-server/)、[Codex MCP](https://developers.openai.com/codex/mcp/)
