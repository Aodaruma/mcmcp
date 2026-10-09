# 2026-10-10 Sophisticated Backpacksの追加検証

通常ゲームで報告されたネザライトバッグの `storage_layout_unsupported` を、aod-mimoidの既存隔離Dockerで調査した。通常ゲームは地図アート担当が所有し、開発側は操作していない。以下は候補の検証記録であり、通常導入の完了ではない。

## 再現と修正

固定fixture `storage_plain` は120枠の初期化済みネザライトバッグ、白コンクリート64個、黒64個、手元の黒32個を準備する。`storage_upgraded` はTier2×2・Tier1×1・pickup・magnetの5upgradeを入れ、白を2048個にする。`storage_tab` はさらにpickupタブを開いた状態を保存する。試験用world・設定・JARは開始前に退避し、製品JARへfixtureを混入しない。

1. R9ではplain／upgradedのinspectが成功し、tabは65tickで `storage_layout_unsupported`。R10はupgradeタブを操作対象から除いたまま、通常収納のinspectを許可し、tabでも6tickで成功した。全slot構造、正規player枠、MOD版／JAR hash、cursor・同期証拠の確認は維持する。QUICK_MOVEは開いたupgradeへ流入し得るため、引き続き許可しない。
2. R10のupgradedからtake64を行うと、実際には64個をcursorへ取り出す一方、計画は2048個全量を取り出すと予測し、照合に失敗して `release_pending:true` になった。R11は検証済みbackpackのPICKUP上限をitem標準stack数に限定し、その上限を適用してから右クリックの半分を計算する。Vanillaの全量PICKUP契約は別に維持する。誤った残数を受け入れたり、未確認clickを再送したりする変更ではない。

R10失敗時は取消・Escでも自動解放を確定できなかった。隔離fixtureのdimension往復でidentityを破棄し、OFF・jobなしを確認してR11へ切り替えた。この操作は修正版の取消成功の証拠ではない。元worldへの復元は下記のとおり未完了。

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

上記の最終server側reportは体力20・cursor空。upgrade設定の比較、端数24個、移送中取消、14clickを超える数量の拒否を確認する追加runner `Invoke-McmcpV2BackpackSmoke.ps1` を用意したが、実行前のSSH接続でCloudflare再認証が必要になり、まだ実施していない。

ローカルJava 1,697＋harness 14＋admin bridge 28＝1,739件、build・harness隔離・PowerShell/Python source checksが成功。追加単体試験は64個上限／右クリック32個／保護slotの不変性、およびserver証拠と一致したcursorだけを元slotへ返す取消処理を検証する。公開15ツールのschemaは変更していない。

## 残りの検証と復元

候補と準備scriptはローカル `artifacts/v2-ladder-far-20261010`、実機結果はリモートの同名評価ディレクトリに保存。R11の残試験・証拠回収・元66ファイルのhash照合復元・containerとDocker Desktopの停止は、SSH再認証待ちで未完了。先行R7検証の復旧receiptを今回の復旧証拠として扱わない。

通常profileへの導入は未実施。検証を完了してから、利用者指定の差し替え担当へ製品JARだけを引き継ぐ。Waystonesの行き先一覧・選択、任意のupgrade／MOD収納への一般対応はこの修正に含まない。
