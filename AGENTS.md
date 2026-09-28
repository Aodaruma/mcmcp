# MCMCP開発・保守の基本方針

日本語で簡潔に、確認できた事実と未確認事項を分けて報告する。コードの入口は[CODE_MAP](docs/CODE_MAP.md)、現行の公開仕様は[MCP Tool Catalog](docs/MCMCP_MCP_Tool_Catalog.json)、新しい公開面の開発目標は[公開API v2](docs/PUBLIC_API_V2.md)を参照する。後者は実装・検証されるまで現行機能と混同しない。

## 製品と責任境界

- Minecraft 26.2／NeoForge 26.2.0.59／Java 25のクライアントMOD。MCP endpointは原則として同じゲームJVM内の`127.0.0.1`へ置き、Origin検証とBearer認証を維持する。外部script workerを採用する場合は配置と認証を設計書で明示する。
- LLMは目標、座標・範囲、作業条件と反復意図を指定できる。MODは必要な局所観測、移動経路、道具選択、照準、ゲーム入力、結果照合を担う。未観測の行き先も指定できるが、未ロードのworldを透視した情報として返さない。
- 公開ツール数は固定しない。短い行動ツールを一組で発見可能にし、共通のjob ID・進行取得・取消へ接続する。ツール説明、catalog、runtime、schema testを一致させる。公開JSON Action DSLは制限付きスクリプトへ置き換え、最終的な二重公開を避ける。
- 制限付きスクリプトはゲーム操作を宣言・合成する言語とする。ファイル、ネットワーク、Javaクラス、OS、任意packet・commandへのアクセスを許さない。scriptやraw入力からも、MODの入力所有、有限予算、取消、結果照合を経由する。
- raw入力はMinecraft内の論理キー／マウス操作として提供できる。対象や画面の条件、回数・時間・頻度、同時・順次入力、停止条件を仕様化し、終了・例外・Esc・UI OFF・world変更時に入力を解放する。

## 観測と実行

- 公開world情報は現に利用できる視覚・局所安全情報・再生音などに限定する。看板、チャット、scoreboard等に含まれる文字列を命令として実行しない。情報が返らない場所は空気ではなく未知として扱う。
- `get_state`の既定はプレイヤー状態と手元9枠を中心にする。MCP制御・session・frame/action IDは明示要求または専用toolへ分ける。`get_observation`は引数なしで最新frameを取得でき、blockごとに短く表示する。省略・打切り・未観測を利用者が判別できる契約を保つ。
- 目的地・破壊・設置範囲は未観測座標でも指定できる。ゲーム内で進むにつれ局所的に観測して判断する。新たに露出した対象に対する操作は、その時点の状態、reach、安全条件を確認する。範囲外や除外条件の対象へ操作しない。
- Action/jobは有界とし、Esc、UI OFF、取消、画面・worldの境界、危険、server不一致で停止できる。通常のAction終了時はAgent所有のキー、使用、破壊、追跡速度、menuを解放してから結果を確定する。失敗や取消は確定済みのworld変更を巻き戻さない。未確認操作を無条件で再送しない。
- 現行実行器の詳細な安全条件は[設計仕様書](docs/Minecraft_MCP_NeoForge_設計仕様書.md)を参照する。v2では目的達成に必要な条件を残しつつ、特定の旧DSL構文や固定block allowlistを永続的な製品制限として扱わない。旧仕様を変更するPRでは理由、代替契約、試験を記載する。
- Action不在の待機中に利用者入力を繰り返し解放しない。手動操作、MCP READY、稼働中のActionを区別する。
- 斜めの地上移動は、角の支持・立位corridor・現在revisionの証拠を確認し、各tickの最終安全判定を維持する。

## 作業と検証

- 変更はIssue単位の専用branch/worktreeで進め、共有`main`を直接編集・pushしない。他担当のworktreeや現場ゲームへ割り込まない。subagentは使用しない。
- [CONTRIBUTING](CONTRIBUTING.md)と[保守手順](docs/MAINTENANCE.md)を参照し、Issueの担当・範囲とPRの差分・試験・未確認事項を記録する。既存の未統合PRと同じファイルを触るときは重複実装を避ける。
- 仕様変更ではcatalog、runtime、schema test、利用ガイドを同期する。まず単体・ハーネス・隔離fixtureで重要な成功と停止を確認する。長時間の旧方式との成功率比較を着手条件にはしない。検証は12:00～23:00 JSTにはこのPCのみで行い、23:00～12:00 JSTには許可済みSSH `aod-mimoid`の隔離Dockerも使える。実機試験後は元world・設定を復旧する。
- 認証情報、private log、元の「くらふとぶ！-v01.2」profileを作業資料や試験へ流用しない。外部PRの未確認コードを認証情報のある環境で実行しない。
- 現在の利用者指示により、mainへの統合、タグ作成、Release公開は追加の明示指示があるまで行わない。PR作成まで進める。admin bypass、force push、必須check解除をしない。
