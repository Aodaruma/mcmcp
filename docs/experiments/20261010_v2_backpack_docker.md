# 2026-10-10 Sophisticated Backpacksの追加検証

後続R18では、並行在庫変化後の終了待ち・診断と取消の拒否を修正した。数量UNKNOWNを保持したcursor回収と、予想外のcursorでは停止を維持したまま診断できることを確認。[終了待ち修正の検証記録](20261010_v2_storage_recovery_docker.md)。以下はR15までの先行記録。

通常ゲームで報告されたネザライトバッグの `storage_layout_unsupported` を、aod-mimoidの既存隔離Dockerで調査した。通常ゲームは地図アート担当が所有し、開発側は操作していない。以下は候補の検証記録であり、通常導入の完了ではない。

## 再現と修正

固定fixture `storage_plain` は120枠の初期化済みネザライトバッグ、白コンクリート64個、黒64個、手元の黒32個を準備する。`storage_upgraded` はTier2×2・Tier1×1・pickup・magnetの5upgradeを入れ、白を2048個にする。`storage_tab` はさらにpickupタブを開いた状態を保存する。試験用world・設定・JARは開始前に退避し、製品JARへfixtureを混入しない。

1. R9ではplain／upgradedのinspectが成功し、tabは65tickで `storage_layout_unsupported`。R10はupgradeタブを操作対象から除いたまま、通常収納のinspectを許可し、tabでも6tickで成功した。全slot構造、正規player枠、MOD版／JAR hash、cursor・同期証拠の確認は維持する。QUICK_MOVEは開いたupgradeへ流入し得るため、引き続き許可しない。
2. R10のupgradedからtake64を行うと、実際には64個をcursorへ取り出す一方、計画は2048個全量を取り出すと予測し、照合に失敗して `release_pending:true` になった。R11は検証済みbackpackのPICKUP上限をitem標準stack数に限定し、その上限を適用してから右クリックの半分を計算する。Vanillaの全量PICKUP契約は別に維持する。誤った残数を受け入れたり、未確認clickを再送したりする変更ではない。

R10失敗時は取消・Escでも自動解放を確定できなかった。隔離fixtureのdimension往復でidentityを破棄し、OFF・jobなしを確認してR11へ切り替えた。この操作は修正版の取消成功の証拠ではない。元worldへの復元は後述のR15終了後に完了した。

## 確認済みの範囲

R11製品SHA-256：`03ef18085d6fb7998297631093ba7b505862b032759b0d58ca34685017b642e9`。

| 構成 | 操作 | 結果 |
| --- | --- | --- |
| 32倍・タブ閉鎖 | 白take64 | succeeded、2048→1984、手元64 |
| 32倍・タブ閉鎖 | 黒store16 | succeeded、64→80、手元32→16 |
| 32倍・タブ閉鎖 | 再open inspect | 白1984／黒80 |
| 32倍・pickupタブ開放 | 白take64 | succeeded、2048→1984、手元64 |
| 32倍・pickupタブ開放 | 黒store16 | succeeded、64→80、手元32→16 |
| 32倍・pickupタブ開放 | 再open inspect | 白1984／黒80 |

上記の最終server側reportは体力20・cursor空。認証復旧後、追加runner `Invoke-McmcpV2BackpackSmoke.ps1` でPlain／Upgraded／Tabのtake24・store16・再open、448個移送中の取消、896個要求のクリック予算超過拒否を確認した。5ケースとも数量保存・upgrade設定・open tab不変・cursor空・体力20が合格。同じ5ケースを統合候補R15でも再実行し、全件成功した。

最初の取消runnerは`result.unconfirmed`を待ったため取消前に448個の移送が完了し、runnerが不合格となった。取消成功とは数えない。`storage_progress.phase=exact_update`を確認して取消するよう修正し、要求直前のjob状態も記録した。R15では4tick時に取消を要求し、confirmed_count=0・cursor回収・再open後の収支を確認した。配送確認前の`unconfirmed`状態も実行前の待機として扱う。

ローカルJava 1,697＋harness 14＋admin bridge 28＝1,739件、build・harness隔離・PowerShell/Python source checksが成功。追加単体試験は64個上限／右クリック32個／保護slotの不変性、およびserver証拠と一致したcursorだけを元slotへ返す取消処理を検証する。公開15ツールのschemaは変更していない。

## 統合候補と復元

最終候補R15の製品SHA-256は`c913077ad3b72a187493a0872b232dd7158541e4fb4b5e01c00114df50642e68`。候補と準備scriptはローカル `artifacts/v2-ladder-far-20261010`、R11追加試験とR15の証拠は`r15-evidence.zip`に回収済み。10:30 JSTに元66ファイルをhash一致で復元し、fixture除去・container停止、10:32 JSTにDocker Desktop停止・維持用SSH終了を確認した。

R15は遠方下階の梯子修正・Waystonesとまとめ、10:43 JSTに差し替え担当が通常profileへ製品JARを導入した。地図アート担当による公開MCPでも2つのバッグのinspectと、9色104個の収納・再open後の合計219個を確認した。[統合検証記録](20261010_v2_waystones_docker.md)。任意のupgrade／MOD収納への一般対応は対象外。
