# PR #86: Windows実機での設定・認証確認（2026-11-15）

## 対象

認証source `0417c9fa27919b540c76c02cf848dd4f71b4fe01` を取り込んだv2統合baseline `1429e73b24f3e12f066d1f9539b0c96dd2b17f61` で確認した。製品SHA-256 `d1f6363597849cd8477f1740f85ac6db2c57cf83049e185ccfd40a58db50f7c4`、harness `5dda44142ff6aed6c7643d5b258a0cf9ea4c33ea4ed610535ca4ab2193fb26b9`。Minecraft26.2 / NeoForge26.2.0.59 / Java25、このPCのPrism検証profileコピーと新しいprivate worldを使い、MCP OFF・Actionなしで行った。

元profileは別保全し、通常profile・通常worldは触っていない。通常Codex/Claude設定は存在状態とファイルhashを事前保存し、試験全体を通して不変だった。書込みと競合注入は検証コピーの分離設定だけに限定した。

## 実機結果

- 通常Codexの確認画面は`~/.codex/config.toml`と`unmanaged_unknown`、通信・認証未確認を表示した。確認画面から戻り、通常登録のOKは押していない。
- 分離設定の確認画面は`minecraft/config/mcmcp/codex-home/config.toml`と`unconfigured`。OK後、分離configと現在profileのヘルパーだけが生成された。configへraw Bearerを保存していない。
- 再度開くと`configured`だが、通信・認証未確認の表示は残る。存在確認とネットワーク成功を区別できた。
- 生成configに登録されたPowerShellヘルパーを、このCodexの実行経路から起動した。出力はメモリ内だけでJSONとして検査し、共通transportで`server/discover`のprotocol/serverInfoと`tools/list`の17ツール名を照合した。誤った試験用tokenはHTTP401となった。ヘルパー出力・token・そのhashは保存/公開していない。
- 検証用分離configだけを、同名・同じ本文のヘルパーを置いた独立した別profileの参照へ変更した。UIは`another_profile`を表示し、OKでも同じcodeで拒否した。分離configと現在profileのヘルパーのhashは変わらなかった。
- Cドライブのnative検証profile junctionと、その実体であるDドライブの作業コピーを使い、同じ物理ヘルパーへの別パスを試した。UIは`configured`となり、別profileと混同せず更新できた。独立コピーの拒否とは別の結果である。
- 別PowerShell processが分離configのsidecarへOSの排他region lockを保持したまま、UIのOKを押した。画面が待機せず戻り、ゲームへ戻ると`config_busy`を表示した。configのhashは不変。lock解放後は再度設定に成功した。
- 最後までMCP OFF・Actionなし、通常設定3ファイルの存在/hash不変を確認した。world保存・Minecraft終了後に次の候補試験へ移行した。元profileの復旧照合は同じ保守作業の最終記録へ残す。

## 修正と限界

実機で分離設定成功の長い案内が856px幅のHUDからはみ出したため、日英の起動案内を短縮した。認証codeとファイル動作は変更していない。この表示修正の最終JARでの再表示は別途確認する。

baselineのJava1,974件、harness16件、admin29件、isolation/buildは成功。参照所有・並行登録・別JVM lockの回帰も含む。今回の実機はUI・生成ヘルパー・実MCP transportの確認であり、通常Codexアプリの再起動や、アプリ自身のCODEX_HOME選択を代行していない。`configured`だけでそのクライアントが正しい設定を読んだと主張しない。
