# ikora-lite

**軽さを最優先にした、10 バンドの Android イコライザ。**
音の処理は Android 内蔵のエフェクト（DynamicsProcessing）が audioserver の中で行い、
このアプリは設定画面と、エフェクトを付け外しするだけ。

- 対応: Android 9 (API 28) 〜 最新 (targetSdk 36 / Android 16)
- 出来上がり: `ikora-lite.apk` — **約 28 KB**
- 権限: **インストール時に自動で許可されるものが 3 つだけ**
  （`MODIFY_AUDIO_SETTINGS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`）。
  確認ダイアログは一度も出ない。通知の権限も求めない
- 依存ライブラリ: **無し**（AndroidX も Kotlin も使っていない）

## 使い方

1. インストールしたら**一度アプリを開く。** 開くまでは Android が「停止状態」として扱い、
   音楽アプリからの知らせが届かない。
2. 画面に「電池の設定を『制限なし』に」と出ていたら、ボタンからアプリ情報 → 電池 →
   「制限なし」にする（Android 12 以降）。
3. あとは音楽アプリで再生を始めるだけ。設定を変えたいときにアプリを開く。

画面の中身:

- **ON / OFF**
- **プリセット**: フラット / 低音 / 高音 / 声
- **10 バンド**: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz。±12 dB、0.5 dB 刻み
- **使うイコライザ**: 端末に入っているイコライザアプリの一覧。ikora 以外を選ぶと
  ikora を OFF にして、そのアプリの画面を開く。Android にはイコライザを一つに決める
  仕組みがなく、ほかのアプリを止めることもできないため

## 仕組み

- 音楽アプリは再生の始めと終わりに `OPEN_AUDIO_EFFECT_CONTROL_SESSION` /
  `CLOSE_…` を送る。それを受けて、そのセッションに DynamicsProcessing の 10 バンドを付ける。
- DynamicsProcessing は Android 9 から全機種にあるので、**どの端末でも同じ 10 バンド**になる。
  作れなかった端末では、端末の Equalizer に同じ曲線を当てはめて代わりにする。
- 常駐はエフェクトが付いている間だけ。セッションが無くなるとサービスは自分で止まる。
- 強制終了されたプレーヤーは CLOSE を送らないので、同じアプリが新しいセッションを開いたら
  古いほうを捨てる。

## 実測

Sony XQ-FS44 / Android 16 (API 36)、YouTube Music で確かめた。

| 項目 | 結果 |
|---|---|
| YT Music の再生開始が届くか | 届く（アプリを一度開いた後）。開く前は届かない |
| エフェクトが付くか | `dumpsys media.audio_flinger` で DynamicsProcessing が Enabled、制御権あり |
| audioserver の CPU（30 秒平均、1 コア比） | ON 1.75 % / OFF 1.62 % → 差 0.13 ポイント（誤差の範囲） |
| 常駐サービスの起動（電池: 最適化あり） | Android が拒否（`ForegroundServiceStartNotAllowedException`）。エフェクトは付く |
| 常駐サービスの起動（電池: 制限なし） | 起動する（`isForeground=true`） |
| プリセット・スライダーの反映 | エラー 0 件 |
| 「使うイコライザ」で他アプリを選ぶ | ikora が外れ、そのアプリの画面が開く |

**確かめていないこと**: Android 9〜15 の実機（lint で API の不足は無いことだけ確認）、
耳で聴いた効き具合、電池「最適化あり」のまま長時間置いたときにプロセスが残るか。

## 注意

- YT Music の設定 → 再生 → イコライザーは、この端末では MusicFX を経由して Sony の
  「オーディオ設定」に固定で飛ぶ。ikora の画面はホームのアイコンから開く。
- ほかのイコライザ（Poweramp Equalizer など）も同じセッションに効果を付けていると、
  重ねがけになる。
