package constants;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 環境設定.
 *
 * @author cyrus
 */
public interface Configurations {

	/**
	 * 出力先ルートディレクトリ.
	 */
	Path OUTPUT_ROOT = Paths.get("downloads");

	/**
	 * ユーザーエージェント.
	 */
	String USER_AGENT = "jHttrack/0.0.1";

	/**
	 * タイムアウト時間（ミリ秒）.
	 */
	int TIMEOUT_MILLIS = 10000;

	/**
	 * クロールの最大深度.
	 */
	int MAX_DEPTH = 3;

	/**
	 * BASIC認証の最大リトライ回数。
	 */
	int MAX_BASIC_AUTH_RETRIES = 3;

	/**
	 * 同一ホストのみクロールするかどうか.
	 */
	boolean SAME_HOST_ONLY = true;

	/**
	 * 既存ファイルを上書きするかどうか.
	 */
	boolean OVERWRITE = true;
}