# ModelForge 3D 1.0.0

Android 上で、文章と参考画像から編集可能な3Dモデルを作る個人用アプリです。
ChatGPTへの指示で設計JSONを生成し、端末内のManifold WASMで立体を計算し、Three.jsで表示します。

## 使い方

1. APKをAndroid端末にインストールします。Android 8.0以降、WebGL 2対応の端末と更新されたAndroid System WebViewが必要です。
2. 「接続する」→「Continue with ChatGPT」を押します。
3. 外部ブラウザーでChatGPTへログインし、アプリによるChatGPTプランの利用を許可します。許可後、ModelForgeへ戻ります。
4. アカウントに提供されているモデルを選び、作りたい形状と寸法を入力します。「＋参考画像」で写真・スケッチを複数枚添付できます。
5. 生成後、「穴の直径を6mmに」「高さを半分に」など追加の指示を出せます。「寸法・パーツ」では数値と色を直接変更できます。
6. 「書き出す」でSTL、GLB、OBJ、編集用JSONを保存します。

サンプルの表示、寸法変更、設計JSONの編集、書き出しはログインなしで使えます。AI生成は通信と対象のChatGPTプランが必要です。独自APIキーや別の3D生成サービスは使いません。既存のChatGPT利用枠を消費します。

## 作れるもの・制約

- 部品、ケース、スタンド、器、幾何形状、曲面を組み合わせたデフォルメ立体など。
- 穴あけ、結合、交差、凸包、ねじれ押し出し、回転体、チューブ、繰り返し配置、閉じたメッシュに対応。
- 参考画像はAIが形状を推定するための入力です。写真測量や精密な3Dスキャンではありません。見えない裏側や寸法は推定になります。
- 写真のように精細な人物、毛並み、複雑な彫刻やテクスチャの自動再現は、この版の機能ではありません。
- 画像入力への対応は選んだモデルによります。画像を処理できない場合は対応モデルを選び直してください。
- プレビューの単位とSTL/OBJの数値はmm。STL自体には単位情報がないため、読み込み先でもmmを選びます。GLBは仕様に合わせメートル・Y-upへ変換します。
- STL書き出し時はパーツをブーリアン結合します。離れたパーツは離れたままです。色はGLBで保持します。
- 印刷前にスライサーで寸法、薄すぎる部分、パーツ接続、サポートを確認してください。構造強度や実機印刷は未検証です。

## 保存と中断

プロジェクトと履歴はアプリ内のファイルに自動保存します。機種変更やアンインストール前に編集用JSONを書き出してください。JSONには参考画像を含めません。

生成通信はAndroidのフォアグラウンドサービスで実行します。別アプリへ移っても通信を続け、完了したAI応答を保存します。立体への変換は画面を開いた際にも行います。OSによる強制終了、利用上限、通信切断の場合は途中の出力を完成扱いせず、最後の完成モデルと依頼を保持します。途中の応答をサーバーから再取得する仕組みではなく、依頼を再実行する方式です。

Androidの省電力設定や強制停止によって処理が止まることはあります。通知の「停止」でも中止できます。AIの設計JSONが不正なら「AIで設計を修正」を利用できます。

## 認証・送信データ

- 公式のSign in with ChatGPTの動的登録、Authorization Code + PKCE、state、nonce、JWKSによる署名検証を実装。
- 認証画面はシステムブラウザーで開きます。パスワードをアプリは受け取りません。
- access/refresh/ID tokenはAndroid KeystoreのAES-GCMで暗号化し、アプリのバックアップ対象外領域へ保存します。画面のJavaScriptへ渡しません。
- ログイン済みアカウントごとのclient IDと登録を保持。再ログインで既存登録を使用します。
- モデル一覧はOAuthトークンで公開APIから取得。推論は公開Responses APIのみ、store:false、stream:true。ChatGPT backend-apiは使いません。
- 「作る」を押すと、指示・現在の設計・最近の編集指示・添付画像をOpenAIへ送信します。画像は長辺1536pxに縮小しJPEGへ変換します。
- 過去のChatGPT会話やメモリーを読み取る機能はありません。
- AI応答は宣言的なJSONのみ。AIが返すJavaScriptやシェル命令は実行しません。
- ログアウト時はrefresh tokenの失効を試み、端末のトークンを消去します。サーバーでの失効が確認できなかった場合は画面に表示します。

個人で端末上に実行する版です。商用・有料・リモートホスティングへ展開する場合は、OpenAIの現行の提供条件と承認要件を確認してください。

## 検証範囲（2026-11-12）

- Javaソースのコンパイル、DEX生成、APKのv2/v3署名検証を実施。
- 立体計算テスト8件。穴あけの体積、真のブーリアン結合、パラメーター、回転、閉じたメッシュ、サンプルの寸法範囲、STL/OBJの構造、不正データ拒否を確認。
- Java通信テスト8項目。完了イベント、途中切断、遅れて届く利用上限エラー、不完全応答、キャンセル、認証切れ案内などを確認。
- APKに同梱する画面をChromiumで操作し、スマホ幅360/412px、折りたたみ相当840px、デスクトップ1280pxを確認。保存・再読み込み・4形式の書き出し・設計JSON再読み込み・不正AI応答時の保持を含む22項目を確認。
- UI上のAI応答テストには模擬応答を使用。実際のChatGPTログイン、実課金枠でのAI推論、Android実機のバックグラウンド動作はこの環境では未検証です。AI生成品質も実アカウントでは未評価です。

## ソースからのビルド

Node.js 20以降、Java 17以降、Android SDK Platform 35、Build Tools 35.0.0が必要です。

```sh
npm ci
npm run build
npm test
```

Android Studioでルートを開き、Gradle Sync後にAPKを作成できます。Gradleプラグイン8.7.3を指定しています。署名なしreleaseまたはdebug APKを作成し、配布時は自分の署名鍵で署名してください。

同梱の `scripts/build-apk.py` はGradle不要の代替ビルドです。ECJ 3.39.0とAndroid SDKツールのパス、署名鍵とパスワードファイルを指定します。

```sh
python3 scripts/build-apk.py \
  --platform /path/to/android-sdk/platforms/android-35 \
  --tools /path/to/android-sdk/build-tools/35.0.0 \
  --ecj /path/to/ecj-3.39.0.jar \
  --keystore /private/path/modelforge-personal.p12 \
  --password-file /private/path/signing-password.txt \
  --out /path/to/modelforge.apk
```

インストール済みAPKへ上書き更新するには同じ署名鍵が必要です。別添の更新用署名鍵バックアップは個人保管用です。ソース公開や一般配布に含めないでください。

UIテストは `playwright-core` とChromiumを使用します。`MF_CHROMIUM` にChromiumの実行ファイルを指定して `node tests/ui.mjs` を実行します。ネイティブ通信テストは `tests/NetTest.java` をJava 17以上とJSON-java 20250517で実行できます。実アカウントへの通信は発生しません。

## 公式資料

- https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/api/docs/guides/images-vision

Three.js 0.187.0、Manifold 3.5.4、jose4j 0.9.6、SLF4J 1.7.36を使用。ライセンスは `licenses` と同梱アセットの `THIRD_PARTY_LICENSES.txt` を参照。
